package dev.codeanalyzer.core.suppression;

import dev.codeanalyzer.core.analyzer.migration.MigrationScore;
import dev.codeanalyzer.core.analyzer.prioritization.TechDebt;
import dev.codeanalyzer.core.model.Finding;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregate stats captured at baseline-write time, used to show trend deltas
 * on subsequent scans ("since baseline: -42 critical, +12 medium, -€8k debt").
 *
 * <p>This is the OSS-tier trend feature — a single point-in-time snapshot
 * embedded inside the baseline JSON. Multi-run history with time-series
 * graphs is reserved for the Team SaaS tier.
 *
 * <p>Backwards compatible: old baselines without snapshot data load as
 * {@link #empty()} and the comparison panel simply isn't rendered.
 */
public final class BaselineSnapshot {

    private final String generatedAt;          // ISO-8601 instant
    private final int totalFindings;
    private final Map<String, Integer> bySeverity;   // CRITICAL → 5060, etc.
    private final Map<String, Integer> byCategory;   // ARCHITECTURE_CYCLE → 17, etc.
    private final int cveCount;
    private final long techDebtHours;
    private final double techDebtCost;
    private final String currencyCode;
    private final int migrationScore;          // -1 if not computed
    private final int migrationBlockers;

    private BaselineSnapshot(Builder b) {
        this.generatedAt = b.generatedAt;
        this.totalFindings = b.totalFindings;
        this.bySeverity = Collections.unmodifiableMap(new LinkedHashMap<>(b.bySeverity));
        this.byCategory = Collections.unmodifiableMap(new LinkedHashMap<>(b.byCategory));
        this.cveCount = b.cveCount;
        this.techDebtHours = b.techDebtHours;
        this.techDebtCost = b.techDebtCost;
        this.currencyCode = b.currencyCode;
        this.migrationScore = b.migrationScore;
        this.migrationBlockers = b.migrationBlockers;
    }

    public static BaselineSnapshot empty() {
        return new Builder().build();
    }

    /**
     * Factory: aggregate a list of findings + computed metrics into a snapshot.
     * Used both by the baseline writer (capturing point-in-time state) and by the
     * engine to compute the "current raw" snapshot for trend comparison.
     *
     * <p>{@code debt} and {@code migration} may be null/empty.
     */
    public static BaselineSnapshot fromComponents(List<Finding> findings, TechDebt debt, MigrationScore migration) {
        Builder b = new Builder()
                .generatedAt(Instant.now().toString())
                .totalFindings(findings != null ? findings.size() : 0);

        Map<String, Integer> bySev = new LinkedHashMap<>();
        for (Finding.Severity sev : Finding.Severity.values()) bySev.put(sev.name(), 0);
        Map<String, Integer> byCat = new LinkedHashMap<>();
        int cveCount = 0;
        if (findings != null) {
            for (Finding f : findings) {
                String s = f.getSeverity() != null ? f.getSeverity().name() : null;
                if (s != null) bySev.put(s, bySev.getOrDefault(s, 0) + 1);
                String c = f.getCategory() != null ? f.getCategory().name() : null;
                if (c != null) byCat.put(c, byCat.getOrDefault(c, 0) + 1);
                if (f.getCategory() == Finding.Category.DEPENDENCY_VULNERABILITY) cveCount++;
            }
        }
        b.bySeverity(bySev).byCategory(byCat).cveCount(cveCount);

        if (debt != null && !debt.isEmpty()) {
            b.techDebtHours(debt.getTotalHours())
             .techDebtCost(debt.getTotalCost())
             .currencyCode(debt.getCurrencyCode());
        }
        if (migration != null && !migration.isEmpty()) {
            b.migrationScore(migration.getOverallScore())
             .migrationBlockers(migration.getTotalBlockers());
        }
        return b.build();
    }

    public boolean isEmpty() { return totalFindings == 0 && bySeverity.isEmpty(); }

    public String getGeneratedAt() { return generatedAt; }
    public int getTotalFindings() { return totalFindings; }
    public Map<String, Integer> getBySeverity() { return bySeverity; }
    public Map<String, Integer> getByCategory() { return byCategory; }
    public int getCveCount() { return cveCount; }
    public long getTechDebtHours() { return techDebtHours; }
    public double getTechDebtCost() { return techDebtCost; }
    public String getCurrencyCode() { return currencyCode; }
    public int getMigrationScore() { return migrationScore; }
    public int getMigrationBlockers() { return migrationBlockers; }

    public int countSeverity(Finding.Severity sev) {
        Integer v = bySeverity.get(sev.name());
        return v != null ? v : 0;
    }

    public int countCategory(Finding.Category cat) {
        Integer v = byCategory.get(cat.name());
        return v != null ? v : 0;
    }

    public static class Builder {
        private String generatedAt = "";
        private int totalFindings = 0;
        private final Map<String, Integer> bySeverity = new LinkedHashMap<>();
        private final Map<String, Integer> byCategory = new LinkedHashMap<>();
        private int cveCount = 0;
        private long techDebtHours = 0;
        private double techDebtCost = 0.0;
        private String currencyCode = "EUR";
        private int migrationScore = -1;
        private int migrationBlockers = 0;

        public Builder generatedAt(String s) { this.generatedAt = s; return this; }
        public Builder totalFindings(int n) { this.totalFindings = n; return this; }
        public Builder bySeverity(Map<String, Integer> m) {
            this.bySeverity.clear(); if (m != null) this.bySeverity.putAll(m); return this;
        }
        public Builder byCategory(Map<String, Integer> m) {
            this.byCategory.clear(); if (m != null) this.byCategory.putAll(m); return this;
        }
        public Builder cveCount(int n) { this.cveCount = n; return this; }
        public Builder techDebtHours(long n) { this.techDebtHours = n; return this; }
        public Builder techDebtCost(double v) { this.techDebtCost = v; return this; }
        public Builder currencyCode(String s) { if (s != null) this.currencyCode = s; return this; }
        public Builder migrationScore(int n) { this.migrationScore = n; return this; }
        public Builder migrationBlockers(int n) { this.migrationBlockers = n; return this; }

        public BaselineSnapshot build() { return new BaselineSnapshot(this); }
    }
}
