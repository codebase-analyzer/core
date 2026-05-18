package dev.codeanalyzer.core.analyzer.migration;

import dev.codeanalyzer.core.model.Finding;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a list of migration findings into a {@link MigrationScore}.
 *
 * <p><b>Effort-based scoring</b> (replaces the old count×weight formula which
 * scored 0 for any project with more than ~7 critical findings, even when the
 * actual effort was just a few days):
 * <pre>
 *   ≤ 1 hour    → 100  (Already Ready)
 *   ≤ 8 hours   → 95   (Trivial — 1 day)
 *   ≤ 40 hours  → 80   (Sprint-sized — 1 week)
 *   ≤ 100 hours → 65   (Multi-sprint — 2–3 weeks)
 *   ≤ 250 hours → 50   (Quarter-sized — 1–2 months)
 *   ≤ 500 hours → 35   (Multi-quarter — 2–3 months)
 *   ≤ 1500 hours → 20  (Major project — 3–9 months)
 *   &gt; 1500 hours → 5   (Strategic rewrite — 9+ months)
 * </pre>
 *
 * <p>Effort itself is summed from each rule's {@link MigrationRule#getEstimatedMinutes()}
 * multiplied by the matched occurrence count.
 *
 * <p>Overall score = min of all area scores (a project is only as ready as its
 * worst-prepared area). If no migration findings exist, returns an empty score.
 */
public final class MigrationScoreCalculator {

    private MigrationScoreCalculator() {}

    public static MigrationScore calculate(List<Finding> findings) {
        // Bucket findings by area, parsed from the ruleId prefix.
        Map<String, AreaAccumulator> byArea = new LinkedHashMap<>();
        Map<String, MigrationRule> ruleIndex = indexRulesById();

        for (Finding f : findings) {
            if (f.getCategory() != Finding.Category.MIGRATION_BLOCKER) continue;
            if (f.getRuleId() == null || !f.getRuleId().startsWith("migration.")) continue;

            String ruleId = f.getRuleId().substring("migration.".length());
            MigrationRule rule = ruleIndex.get(ruleId);
            if (rule == null) continue;

            AreaAccumulator acc = byArea.computeIfAbsent(rule.getArea(),
                    k -> new AreaAccumulator(rule.getArea(), rule.getSourceVersion(), rule.getTargetVersion()));
            acc.add(f.getSeverity(), rule.getEstimatedMinutes());
        }

        if (byArea.isEmpty()) {
            return new MigrationScore(100, new LinkedHashMap<>(), 0, 0);
        }

        Map<String, MigrationScore.AreaScore> areaScores = new LinkedHashMap<>();
        int totalEffortMinutes = 0;
        int totalBlockers = 0;

        for (AreaAccumulator acc : byArea.values()) {
            int effortHours = Math.max(1, Math.round(acc.effortMinutes / 60f));
            int score = scoreFromHours(effortHours);
            areaScores.put(acc.area, new MigrationScore.AreaScore(
                    acc.area, acc.sourceVersion, acc.targetVersion, score,
                    acc.critical, acc.high, acc.medium, acc.low, effortHours));
            totalEffortMinutes += acc.effortMinutes;
            totalBlockers += acc.critical + acc.high + acc.medium + acc.low;
        }

        int totalEffortHours = Math.max(1, Math.round(totalEffortMinutes / 60f));
        // Overall score is recomputed from the total effort hours, not from the worst-area
        // score — this avoids penalizing a small area with a high critical density.
        int overall = scoreFromHours(totalEffortHours);
        return new MigrationScore(overall, areaScores, totalEffortHours, totalBlockers);
    }

    /** Effort-band mapping: see class javadoc. */
    private static int scoreFromHours(int hours) {
        if (hours <= 1)    return 100;
        if (hours <= 8)    return 95;
        if (hours <= 40)   return 80;
        if (hours <= 100)  return 65;
        if (hours <= 250)  return 50;
        if (hours <= 500)  return 35;
        if (hours <= 1500) return 20;
        return 5;
    }

    private static Map<String, MigrationRule> indexRulesById() {
        Map<String, MigrationRule> idx = new HashMap<>();
        for (MigrationRule r : MigrationRuleRegistry.getAllRules()) idx.put(r.getId(), r);
        return idx;
    }

    private static final class AreaAccumulator {
        final String area;
        final String sourceVersion;
        final String targetVersion;
        int critical, high, medium, low;
        int effortMinutes;

        AreaAccumulator(String area, String sourceVersion, String targetVersion) {
            this.area = area;
            this.sourceVersion = sourceVersion;
            this.targetVersion = targetVersion;
        }

        void add(Finding.Severity sev, int minutes) {
            switch (sev) {
                case CRITICAL: critical++; break;
                case HIGH:     high++;     break;
                case MEDIUM:   medium++;   break;
                case LOW:      low++;      break;
                default:                   break;
            }
            effortMinutes += minutes;
        }
    }
}
