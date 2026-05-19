package dev.codeanalyzer.core.autofix;

import dev.codeanalyzer.core.model.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies {@link Patch}es to disk (or just prints them, in preview mode).
 *
 * <p>Safety mechanisms:
 * <ul>
 *   <li><strong>Backups.</strong> Before any file is modified, a {@code .bak}
 *       sibling is written. If the modification fails midway the backup is
 *       restored.</li>
 *   <li><strong>Conflict detection.</strong> Each patch carries the exact
 *       original-text line(s) it targets; if the file's current content at
 *       that line doesn't match, the file has been edited since the analyser
 *       ran and we skip the patch rather than risk corruption.</li>
 *   <li><strong>Bottom-up application.</strong> When several patches modify
 *       the same file, they are applied in descending {@code startLine}
 *       order so that earlier line numbers stay valid throughout.</li>
 *   <li><strong>Line-ending preservation.</strong> Files are read and
 *       rewritten using the line separator they already used (CRLF on
 *       Windows-edited files stays CRLF), so the diff for the user's next
 *       commit shows only meaningful changes.</li>
 * </ul>
 *
 * <p>This class does no I/O beyond the file being patched. It does not
 * read git state, does not call out to other tools. Deterministic.
 */
public class AutoFixApplier {

    private static final Logger log = LoggerFactory.getLogger(AutoFixApplier.class);

    /** Confidence threshold for which patches the user opts in to applying. */
    public enum ConfidenceFilter {
        /** Only {@link Patch.Confidence#SAFE} patches. Default. */
        SAFE_ONLY,
        /** SAFE and MODERATE patches. Excludes REVIEW_REQUIRED. */
        SAFE_AND_MODERATE,
        /** Every patch the engine produced, regardless of confidence. */
        ALL;

        public boolean accepts(Patch.Confidence c) {
            if (c == null) return false;
            switch (this) {
                case SAFE_ONLY:         return c == Patch.Confidence.SAFE;
                case SAFE_AND_MODERATE: return c == Patch.Confidence.SAFE || c == Patch.Confidence.MODERATE;
                case ALL:               return true;
                default:                return false;
            }
        }

        /** Parses the value of the {@code --fix-confidence} CLI flag. */
        public static ConfidenceFilter parse(String s) {
            if (s == null) return SAFE_ONLY;
            String norm = s.trim().toLowerCase();
            switch (norm) {
                case "":
                case "safe":
                    return SAFE_ONLY;
                case "moderate":
                    return SAFE_AND_MODERATE;
                case "all":
                case "review":
                case "review_required":
                    return ALL;
                default:
                    throw new IllegalArgumentException(
                            "Unknown --fix-confidence value: '" + s + "' (expected safe|moderate|all)");
            }
        }
    }

    /**
     * Apply patches to disk. Returns a summary of what happened.
     *
     * @param patches    finding-to-patch map (as produced by {@link AutoFixEngine}).
     * @param filter     which confidence tiers to apply.
     * @param projectRoot project root used to resolve relative paths to absolute.
     */
    public ApplyResult apply(Map<Finding, Patch> patches, ConfidenceFilter filter, Path projectRoot) {
        return run(patches, filter, projectRoot, /* preview */ false, /* out */ null);
    }

    /**
     * Print patches as unified diffs to {@code out} without modifying any files.
     * Returns a summary using the same shape as {@link #apply}, but with
     * {@link ApplyResult#isPreviewOnly()} {@code == true}.
     */
    public ApplyResult preview(Map<Finding, Patch> patches, ConfidenceFilter filter,
                                Path projectRoot, PrintStream out) {
        return run(patches, filter, projectRoot, /* preview */ true, out);
    }

    // -- internal --------------------------------------------------------

    private ApplyResult run(Map<Finding, Patch> patches, ConfidenceFilter filter,
                             Path projectRoot, boolean preview, PrintStream out) {
        if (patches == null || patches.isEmpty()) {
            return ApplyResult.empty(preview);
        }
        if (filter == null) filter = ConfidenceFilter.SAFE_ONLY;

        // First pass: filter by confidence + group by absolute file path.
        Map<Path, List<Patch>> byFile = new LinkedHashMap<Path, List<Patch>>();
        int skippedByConfidence = 0;

        for (Patch p : patches.values()) {
            if (p == null) continue;
            if (!filter.accepts(p.getConfidence())) {
                skippedByConfidence++;
                continue;
            }
            Path file = resolveFile(p, projectRoot);
            if (file == null) continue;
            List<Patch> bucket = byFile.get(file);
            if (bucket == null) {
                bucket = new ArrayList<Patch>();
                byFile.put(file, bucket);
            }
            bucket.add(p);
        }

        int appliedPatches = 0;
        int appliedFiles = 0;
        int skippedByConflict = 0;
        int failedToWrite = 0;
        List<String> warnings = new ArrayList<String>();

        for (Map.Entry<Path, List<Patch>> entry : byFile.entrySet()) {
            Path file = entry.getKey();
            List<Patch> filePatches = entry.getValue();

            FileApplication result;
            try {
                result = applyToFile(file, filePatches, preview, out);
            } catch (IOException e) {
                failedToWrite += filePatches.size();
                warnings.add("I/O error processing " + file + ": " + e.getMessage());
                continue;
            }
            appliedPatches += result.applied;
            skippedByConflict += result.conflicts;
            warnings.addAll(result.warnings);
            if (!preview && result.applied > 0) appliedFiles++;
        }

        ApplyResult ar = new ApplyResult(preview, appliedPatches, appliedFiles,
                skippedByConfidence, skippedByConflict, failedToWrite, warnings);

        if (!preview) {
            log.info("Auto-fix apply: {} patches applied across {} files (skipped: {} confidence, {} conflict, {} failed)",
                    appliedPatches, appliedFiles, skippedByConfidence, skippedByConflict, failedToWrite);
        }
        return ar;
    }

    private FileApplication applyToFile(Path file, List<Patch> filePatches,
                                          boolean preview, PrintStream out) throws IOException {
        if (!Files.isRegularFile(file)) {
            FileApplication fa = new FileApplication();
            fa.conflicts = filePatches.size();
            fa.warnings.add("File no longer exists or is not regular: " + file);
            return fa;
        }

        // Read once. Preserve original line separator.
        byte[] originalBytes = Files.readAllBytes(file);
        String content = new String(originalBytes, StandardCharsets.UTF_8);
        String sep = content.contains("\r\n") ? "\r\n" : "\n";
        boolean trailingNewline = content.endsWith(sep);

        // -1 keeps trailing empties so we can preserve trailing newline on write.
        List<String> lines = new ArrayList<String>(Arrays.asList(content.split("\\r?\\n", -1)));

        // Apply bottom-up so earlier line numbers stay valid.
        List<Patch> sorted = new ArrayList<Patch>(filePatches);
        sorted.sort(new Comparator<Patch>() {
            @Override public int compare(Patch a, Patch b) {
                return Integer.compare(b.getStartLine(), a.getStartLine());
            }
        });

        FileApplication fa = new FileApplication();

        for (Patch p : sorted) {
            int idx = p.getStartLine() - 1;
            if (idx < 0 || idx >= lines.size()) {
                fa.conflicts++;
                fa.warnings.add("Out-of-range patch: " + p.getDisplayPath()
                        + ":" + p.getStartLine() + " (file has " + lines.size() + " lines)");
                continue;
            }
            String currentLine = lines.get(idx);
            if (!currentLine.equals(p.getOriginalText())) {
                // File has been modified since the analyser ran.
                fa.conflicts++;
                fa.warnings.add("Skipped " + p.getDisplayPath() + ":" + p.getStartLine()
                        + " — file changed since scan");
                continue;
            }

            // In preview mode, just emit the diff. No mutation.
            if (preview) {
                if (out != null) {
                    out.println(p.toUnifiedDiff());
                }
                fa.applied++;
                continue;
            }

            // Apply: remove the original line range, insert the replacement lines.
            int startIdx = p.getStartLine() - 1;
            int endIdx = p.getEndLine() - 1;
            for (int i = endIdx; i >= startIdx; i--) {
                lines.remove(i);
            }
            String[] replacementLines = p.getReplacementText().split("\\r?\\n", -1);
            // Drop a trailing empty line that comes from replacements ending with "\n".
            int insertCount = replacementLines.length;
            if (insertCount > 0 && replacementLines[insertCount - 1].isEmpty()) {
                insertCount--;
            }
            for (int i = 0; i < insertCount; i++) {
                lines.add(startIdx + i, replacementLines[i]);
            }
            fa.applied++;
        }

        if (preview || fa.applied == 0) {
            return fa;
        }

        // Rebuild content using the original line separator + trailing-newline policy.
        StringBuilder sb = new StringBuilder(content.length() + 256);
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1) sb.append(sep);
        }
        if (trailingNewline) sb.append(sep);

        // Backup + atomic-ish write.
        Path backup = file.resolveSibling(file.getFileName().toString() + ".bak");
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Best-effort rollback: if the backup made it, restore the original.
            if (Files.exists(backup)) {
                try {
                    Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException restoreError) {
                    fa.warnings.add("CRITICAL: could not restore backup for " + file
                            + ": " + restoreError.getMessage());
                }
            }
            throw e;
        }
        return fa;
    }

    /** Resolves the patch's stored path to an absolute path under the project root. */
    private Path resolveFile(Patch p, Path projectRoot) {
        String abs = p.getAbsolutePath();
        if (abs != null && !abs.isEmpty()) {
            try {
                return Paths.get(abs);
            } catch (RuntimeException ignored) { /* fall through to relative */ }
        }
        if (projectRoot == null) return null;
        String display = p.getDisplayPath();
        if (display == null || display.isEmpty()) return null;
        try {
            return projectRoot.resolve(display);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Per-file accumulator used while iterating patches. */
    private static final class FileApplication {
        int applied = 0;
        int conflicts = 0;
        final List<String> warnings = new ArrayList<String>();
    }
}
