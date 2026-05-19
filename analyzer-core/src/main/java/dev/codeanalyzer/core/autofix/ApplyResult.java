package dev.codeanalyzer.core.autofix;

import java.util.Collections;
import java.util.List;

/**
 * Summary of an {@link AutoFixApplier} run. Immutable.
 *
 * <p>Distinguishes the four reasons a patch may not be applied so the CLI can
 * tell the user exactly what happened (and what knob to turn if they want
 * different behaviour):
 *
 * <ul>
 *   <li>{@link #getAppliedPatches()} — patches successfully written to disk.</li>
 *   <li>{@link #getSkippedByConfidence()} — patches whose confidence tier was
 *       above the {@code --fix-confidence} threshold the user picked.</li>
 *   <li>{@link #getSkippedByConflict()} — patches whose source file changed
 *       since the analyser ran (line content no longer matches the patch's
 *       original-text), so applying could destroy user work.</li>
 *   <li>{@link #getFailedToWrite()} — I/O failures while writing the file
 *       back (permission, disk full, etc.). Backup will have been restored.</li>
 * </ul>
 */
public final class ApplyResult {

    private final boolean previewOnly;
    private final int appliedPatches;
    private final int appliedFiles;
    private final int skippedByConfidence;
    private final int skippedByConflict;
    private final int failedToWrite;
    private final List<String> warnings;

    public ApplyResult(boolean previewOnly,
                       int appliedPatches, int appliedFiles,
                       int skippedByConfidence, int skippedByConflict,
                       int failedToWrite, List<String> warnings) {
        this.previewOnly = previewOnly;
        this.appliedPatches = appliedPatches;
        this.appliedFiles = appliedFiles;
        this.skippedByConfidence = skippedByConfidence;
        this.skippedByConflict = skippedByConflict;
        this.failedToWrite = failedToWrite;
        this.warnings = warnings != null
                ? Collections.unmodifiableList(warnings)
                : Collections.<String>emptyList();
    }

    public static ApplyResult empty(boolean previewOnly) {
        return new ApplyResult(previewOnly, 0, 0, 0, 0, 0, Collections.<String>emptyList());
    }

    public boolean isPreviewOnly()       { return previewOnly; }
    public int getAppliedPatches()       { return appliedPatches; }
    public int getAppliedFiles()         { return appliedFiles; }
    public int getSkippedByConfidence()  { return skippedByConfidence; }
    public int getSkippedByConflict()    { return skippedByConflict; }
    public int getFailedToWrite()        { return failedToWrite; }
    public List<String> getWarnings()    { return warnings; }

    /** True if anything at all was applied (or would be applied, in preview mode). */
    public boolean isEmpty() {
        return appliedPatches == 0;
    }
}
