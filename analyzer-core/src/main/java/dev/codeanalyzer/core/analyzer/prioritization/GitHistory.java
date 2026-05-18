package dev.codeanalyzer.core.analyzer.prioritization;

import java.util.Collections;
import java.util.Map;

/**
 * File churn statistics derived from {@code git log}. Keyed by repo-relative
 * file path (forward-slash normalized).
 *
 * <p>{@link #available} is false when git wasn't usable on the project
 * (no .git, git binary missing, or {@code --no-git} flag). The
 * {@link PrioritizedFinding} pipeline then degrades gracefully to a
 * severity-only score.
 */
public final class GitHistory {

    private final boolean available;
    private final Map<String, Integer> commitCountByFile;
    private final int windowMonths;

    public GitHistory(boolean available, Map<String, Integer> commitCountByFile, int windowMonths) {
        this.available = available;
        this.commitCountByFile = Collections.unmodifiableMap(commitCountByFile);
        this.windowMonths = windowMonths;
    }

    public static GitHistory empty() {
        return new GitHistory(false, Collections.<String, Integer>emptyMap(), 0);
    }

    public boolean isAvailable() { return available; }
    public int getWindowMonths() { return windowMonths; }
    public Map<String, Integer> getCommitCountByFile() { return commitCountByFile; }

    /** Commit count for the given relative path; 0 if unknown. */
    public int getCommitCount(String relativeFilePath) {
        if (relativeFilePath == null) return 0;
        Integer n = commitCountByFile.get(relativeFilePath.replace('\\', '/'));
        return n != null ? n : 0;
    }
}
