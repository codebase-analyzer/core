package dev.codeanalyzer.core.analyzer.migration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The output of {@link MigrationScoreCalculator}: an overall readiness score
 * (0–100, higher is closer to ready) plus a per-area breakdown.
 *
 * <p>Also carries an {@code estimatedEffortHours} projection — sum of each
 * rule's {@link MigrationRule#getEstimatedMinutes()} across all matched findings,
 * divided by 60.
 */
public final class MigrationScore {

    private final int overallScore;
    private final Map<String, AreaScore> areas;
    private final int estimatedEffortHours;
    private final int totalBlockers;

    public MigrationScore(int overallScore, Map<String, AreaScore> areas,
                          int estimatedEffortHours, int totalBlockers) {
        this.overallScore = overallScore;
        this.areas = Collections.unmodifiableMap(new LinkedHashMap<>(areas));
        this.estimatedEffortHours = estimatedEffortHours;
        this.totalBlockers = totalBlockers;
    }

    public int getOverallScore() { return overallScore; }
    public Map<String, AreaScore> getAreas() { return areas; }
    public int getEstimatedEffortHours() { return estimatedEffortHours; }
    public int getTotalBlockers() { return totalBlockers; }
    public boolean isEmpty() { return areas.isEmpty(); }

    /**
     * Human-friendly verdict label calibrated against actual effort hours.
     * The score alone is misleading — "0/100" doesn't mean "impossible", it
     * means "well above 1500 hours". The verdict translates effort into
     * project-management terms an engineering manager can plan around.
     */
    public String getVerdict() {
        int h = estimatedEffortHours;
        if (h <= 1)    return "Already Ready";
        if (h <= 8)    return "Trivial (< 1 day)";
        if (h <= 40)   return "Sprint-sized (~1 week)";
        if (h <= 100)  return "Multi-sprint (2–3 weeks)";
        if (h <= 250)  return "Quarter-sized (1–2 months)";
        if (h <= 500)  return "Multi-quarter (2–3 months)";
        if (h <= 1500) return "Major project (3–9 months)";
        return "Strategic rewrite (9+ months)";
    }

    /**
     * A plain-English explanation of what the score means, suitable for a tooltip.
     * Avoids the misleading "0 = impossible" framing.
     */
    public String getScoreExplanation() {
        return "Score is based on estimated effort to migrate, not on whether migration is " +
               "possible. Every migration is possible — the question is how much engineering " +
               "time it takes. Higher score = less work. A score of 0 means more than 1500 " +
               "engineering hours; it does NOT mean blocked. The verdict label translates " +
               "the effort into sprint / quarter / multi-quarter terms.";
    }

    /** Per-area score block: score, blocker counts, source/target versions, effort. */
    public static final class AreaScore {
        private final String area;
        private final String sourceVersion;
        private final String targetVersion;
        private final int score;
        private final int critical;
        private final int high;
        private final int medium;
        private final int low;
        private final int effortHours;

        public AreaScore(String area, String sourceVersion, String targetVersion, int score,
                         int critical, int high, int medium, int low, int effortHours) {
            this.area = area;
            this.sourceVersion = sourceVersion;
            this.targetVersion = targetVersion;
            this.score = score;
            this.critical = critical;
            this.high = high;
            this.medium = medium;
            this.low = low;
            this.effortHours = effortHours;
        }

        public String getArea() { return area; }
        public String getSourceVersion() { return sourceVersion; }
        public String getTargetVersion() { return targetVersion; }
        public int getScore() { return score; }
        public int getCritical() { return critical; }
        public int getHigh() { return high; }
        public int getMedium() { return medium; }
        public int getLow() { return low; }
        public int getEffortHours() { return effortHours; }
        public int getTotalBlockers() { return critical + high + medium + low; }
    }
}
