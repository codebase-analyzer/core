package dev.codeanalyzer.core.analyzer.prioritization;

import dev.codeanalyzer.core.model.Finding;

/**
 * A {@link Finding} wrapped with a computed business-impact score and the
 * reasoning behind it. Produced by {@link ImpactScorer} and ranked into the
 * "Top 10 priority fixes" list shown on the dashboard.
 */
public final class PrioritizedFinding {

    public enum Effort { LOW, MEDIUM, HIGH }

    private final Finding finding;
    private final int impactScore;       // 0-100, higher = fix first
    private final String impactReason;   // human-readable explanation
    private final Effort effort;
    private final int estimatedHours;    // for tech-debt totals
    private final int gitChurn;          // commits in window

    public PrioritizedFinding(Finding finding, int impactScore, String impactReason,
                              Effort effort, int estimatedHours, int gitChurn) {
        this.finding = finding;
        this.impactScore = impactScore;
        this.impactReason = impactReason;
        this.effort = effort;
        this.estimatedHours = estimatedHours;
        this.gitChurn = gitChurn;
    }

    public Finding getFinding() { return finding; }
    public int getImpactScore() { return impactScore; }
    public String getImpactReason() { return impactReason; }
    public Effort getEffort() { return effort; }
    public int getEstimatedHours() { return estimatedHours; }
    public int getGitChurn() { return gitChurn; }
}
