package dev.codeanalyzer.core.analyzer.prioritization;

import dev.codeanalyzer.core.model.Finding;

import java.util.EnumMap;
import java.util.Map;

/**
 * Single source of truth for "how long does it take to fix one finding of category X?"
 * Used by both {@link ImpactScorer} (effort grading) and {@link TechDebtCalculator}
 * (debt-hour totals).
 *
 * <p>Numbers are calibrated estimates — opinionated but consistent. The point is
 * not millimetric accuracy; it's giving managers a defensible ballpark instead of
 * "we have 10,000 findings, please fund the refactor."
 */
public final class EffortTable {

    /** Minutes per single finding, by category. */
    private static final Map<Finding.Category, Integer> MINUTES_PER_FINDING =
            new EnumMap<>(Finding.Category.class);

    static {
        // Hibernate — investigation + entity remap + tests
        MINUTES_PER_FINDING.put(Finding.Category.HIBERNATE_N_PLUS_ONE,       60);
        MINUTES_PER_FINDING.put(Finding.Category.HIBERNATE_FETCH_STRATEGY,   45);
        MINUTES_PER_FINDING.put(Finding.Category.HIBERNATE_EAGER_CYCLE,    120);
        MINUTES_PER_FINDING.put(Finding.Category.HIBERNATE_MISSING_BATCH_SIZE, 15);
        MINUTES_PER_FINDING.put(Finding.Category.HIBERNATE_EQUALS_HASHCODE,  30);

        // Dead code / orphan beans — find usages + delete + retest
        MINUTES_PER_FINDING.put(Finding.Category.DEAD_CODE,                  20);
        MINUTES_PER_FINDING.put(Finding.Category.ORPHAN_BEAN,                25);

        // Spring — usually small AOP/wiring tweak
        MINUTES_PER_FINDING.put(Finding.Category.SPRING_TRANSACTIONAL_MISUSE, 30);
        MINUTES_PER_FINDING.put(Finding.Category.SPRING_ASYNC_MISUSE,        45);
        MINUTES_PER_FINDING.put(Finding.Category.SPRING_CONFIG_SMELL,        20);

        // Migration — overridden per-rule at finding level when possible
        MINUTES_PER_FINDING.put(Finding.Category.MIGRATION_BLOCKER,          15);

        // SQL / perf
        MINUTES_PER_FINDING.put(Finding.Category.SQL_PERFORMANCE,            45);

        // PMD — most are 5-minute fixes
        MINUTES_PER_FINDING.put(Finding.Category.PMD_BEST_PRACTICES,         10);
        MINUTES_PER_FINDING.put(Finding.Category.PMD_ERROR_PRONE,            15);
        MINUTES_PER_FINDING.put(Finding.Category.PMD_PERFORMANCE,            10);

        // Dependency CVE — upgrade + regression test
        MINUTES_PER_FINDING.put(Finding.Category.DEPENDENCY_VULNERABILITY,   90);

        // Architecture — fixes range from "extract one class" to "redesign package boundaries"
        MINUTES_PER_FINDING.put(Finding.Category.ARCHITECTURE_CYCLE,        240);
        MINUTES_PER_FINDING.put(Finding.Category.ARCHITECTURE_LAYERING,      60);
        MINUTES_PER_FINDING.put(Finding.Category.ARCHITECTURE_GOD_CLASS,    480);
    }

    private EffortTable() {}

    /** Estimated minutes to fix a single finding of the given category. */
    public static int minutesFor(Finding.Category category) {
        Integer m = MINUTES_PER_FINDING.get(category);
        return m != null ? m : 20;
    }

    /** Bands one finding's estimated minutes into a coarse effort label. */
    public static PrioritizedFinding.Effort bandFromMinutes(int minutes) {
        if (minutes <= 15) return PrioritizedFinding.Effort.LOW;
        if (minutes <= 60) return PrioritizedFinding.Effort.MEDIUM;
        return PrioritizedFinding.Effort.HIGH;
    }
}
