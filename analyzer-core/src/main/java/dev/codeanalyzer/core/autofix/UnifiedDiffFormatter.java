package dev.codeanalyzer.core.autofix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Renders a {@link Patch} as a standard unified diff with 3 lines of context.
 *
 * <p>Output is compatible with {@code git apply}, {@code patch -p0}, and any
 * tooling that consumes unified-diff format.
 *
 * <p>We deliberately keep this dependency-free instead of pulling in
 * {@code java-diff-utils}: the patches we produce are always contiguous
 * line-range replacements, which makes the diff arithmetic trivial.
 *
 * <p>Header format follows the conventional shape:
 * <pre>
 * --- a/path/to/File.java
 * +++ b/path/to/File.java
 * &#64;&#64; -startOld,countOld +startNew,countNew &#64;&#64;
 * </pre>
 */
public final class UnifiedDiffFormatter {

    private static final int CONTEXT_LINES = 3;

    private UnifiedDiffFormatter() {}

    public static String format(Patch patch) {
        if (patch == null) return "";

        List<String> fileLines = readFileLines(patch.getAbsolutePath());
        if (fileLines == null) {
            return formatInline(patch);
        }

        int start = patch.getStartLine();
        int end = patch.getEndLine();
        if (start < 1 || end > fileLines.size() || start > end) {
            return formatInline(patch);
        }

        // Original line range to be replaced (1-indexed, inclusive)
        List<String> originalRange = fileLines.subList(start - 1, end);

        // Pre/post context windows clamped to the file's actual size
        int preStart = Math.max(1, start - CONTEXT_LINES);
        List<String> preContext = fileLines.subList(preStart - 1, start - 1);

        int postEnd = Math.min(fileLines.size(), end + CONTEXT_LINES);
        List<String> postContext = fileLines.subList(end, postEnd);

        List<String> replacementLines = splitLines(patch.getReplacementText());

        // Hunk header counts include context + changed lines
        int oldLineStart = preStart;
        int oldLineCount = preContext.size() + originalRange.size() + postContext.size();
        int newLineStart = preStart;
        int newLineCount = preContext.size() + replacementLines.size() + postContext.size();

        StringBuilder sb = new StringBuilder(256);
        sb.append("--- a/").append(patch.getDisplayPath()).append('\n');
        sb.append("+++ b/").append(patch.getDisplayPath()).append('\n');
        sb.append("@@ -").append(oldLineStart).append(',').append(oldLineCount)
                .append(" +").append(newLineStart).append(',').append(newLineCount)
                .append(" @@\n");

        for (String line : preContext)     sb.append(' ').append(line).append('\n');
        for (String line : originalRange)  sb.append('-').append(line).append('\n');
        for (String line : replacementLines) sb.append('+').append(line).append('\n');
        for (String line : postContext)    sb.append(' ').append(line).append('\n');

        return sb.toString();
    }

    /**
     * Fallback when the source file can't be read (moved, deleted, permission
     * issue). Emits a minimal diff with just the old/new text, no surrounding
     * context. Still valid unified-diff syntax.
     */
    private static String formatInline(Patch patch) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("--- a/").append(patch.getDisplayPath()).append('\n');
        sb.append("+++ b/").append(patch.getDisplayPath()).append('\n');
        sb.append("@@ lines ").append(patch.getStartLine()).append('-')
                .append(patch.getEndLine()).append(" @@\n");
        for (String line : splitLines(patch.getOriginalText()))    sb.append('-').append(line).append('\n');
        for (String line : splitLines(patch.getReplacementText())) sb.append('+').append(line).append('\n');
        return sb.toString();
    }

    private static List<String> readFileLines(String filePath) {
        try {
            return Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Splits text into lines. Normalizes CRLF -> LF first, then drops a single
     * trailing empty line if the input ended with a newline (so we don't emit
     * a spurious blank line in the diff).
     */
    static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<String>();
        String[] parts = text.replace("\r\n", "\n").split("\n", -1);
        List<String> lines = new ArrayList<String>(Arrays.asList(parts));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
