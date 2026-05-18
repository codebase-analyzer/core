package dev.codeanalyzer.core.autofix;

import java.util.Objects;

/**
 * A single suggested code change produced by an {@link AutoFixer}.
 *
 * <p>A {@code Patch} is purely data — generating it does not touch the
 * filesystem, and rendering it as a unified diff is delegated to
 * {@link UnifiedDiffFormatter}. Applying patches to disk is a separate
 * concern handled by the CLI's {@code --fix} flag (planned).
 *
 * <p>The patch carries two paths:
 * <ul>
 *   <li>{@code absolutePath} — the actual on-disk location, used to re-read
 *       the file when generating the diff (so we can include surrounding
 *       context lines).</li>
 *   <li>{@code displayPath} — the project-relative path shown in the diff
 *       header ({@code --- a/<displayPath>}). Stable across machines.</li>
 * </ul>
 *
 * <p>{@link Confidence} indicates how safe it is to apply the fix without
 * human review:
 * <ul>
 *   <li>{@link Confidence#SAFE} — pure mechanical refactoring, semantically
 *       neutral (e.g. adding a missing {@code @Override} annotation).</li>
 *   <li>{@link Confidence#MODERATE} — likely correct but has rare edge cases
 *       where intent matters (e.g. {@code EAGER -> LAZY}).</li>
 *   <li>{@link Confidence#REVIEW_REQUIRED} — substantive change that may
 *       alter behavior. Always preview before applying.</li>
 * </ul>
 */
public class Patch {

    public enum Confidence { SAFE, MODERATE, REVIEW_REQUIRED }

    private final String absolutePath;
    private final String displayPath;
    private final int startLine;
    private final int endLine;
    private final String originalText;
    private final String replacementText;
    private final String ruleId;
    private final Confidence confidence;
    private final String description;

    public Patch(String absolutePath, String displayPath,
                 int startLine, int endLine,
                 String originalText, String replacementText,
                 String ruleId, Confidence confidence, String description) {
        this.absolutePath = Objects.requireNonNull(absolutePath, "absolutePath");
        this.displayPath = displayPath != null ? displayPath : absolutePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.originalText = originalText != null ? originalText : "";
        this.replacementText = replacementText != null ? replacementText : "";
        this.ruleId = ruleId != null ? ruleId : "unknown";
        this.confidence = confidence != null ? confidence : Confidence.MODERATE;
        this.description = description != null ? description : "";
    }

    public String getAbsolutePath() { return absolutePath; }
    public String getDisplayPath() { return displayPath; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public String getOriginalText() { return originalText; }
    public String getReplacementText() { return replacementText; }
    public String getRuleId() { return ruleId; }
    public Confidence getConfidence() { return confidence; }
    public String getDescription() { return description; }

    /** Standard unified-diff format compatible with {@code git apply}. */
    public String toUnifiedDiff() {
        return UnifiedDiffFormatter.format(this);
    }

    @Override
    public String toString() {
        return String.format("Patch[%s, lines %d-%d, ruleId=%s, %s]",
                displayPath, startLine, endLine, ruleId, confidence);
    }
}
