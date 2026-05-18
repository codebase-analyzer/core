package dev.codeanalyzer.core.model;

import java.util.Collections;
import java.util.List;

/**
 * A single finding produced by an analyzer.
 *
 * <p>Findings carry both a {@link Severity} (how bad if true) and a
 * {@link Confidence} (how sure we are). The HTML report sorts and filters
 * on confidence to suppress speculative findings by default.
 *
 * <p>{@code ruleId} is a stable identifier (e.g. {@code "hibernate.eager-cycle.bidirectional"})
 * usable for suppression in {@code .codebase-analyzer.yml} or inline
 * {@code // @analyzer-ignore: <ruleId>} comments.
 *
 * <p>{@code evidence} captures concrete proof points the analyzer used to reach
 * its conclusion (line refs, matched patterns, AST node summaries) so users can
 * audit why a finding was raised.
 */
public class Finding {

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW, INFO
    }

    /**
     * How confident the analyzer is that this finding is a true positive.
     * <ul>
     *   <li>{@link #CERTAIN} — deterministic match (e.g. CVE coords + version range).</li>
     *   <li>{@link #HIGH}    — strong AST-resolved signal with low false-positive risk.</li>
     *   <li>{@link #MEDIUM}  — heuristic with known edge cases.</li>
     *   <li>{@link #LOW}     — speculative; suppressed from the default report view.</li>
     * </ul>
     */
    public enum Confidence {
        CERTAIN, HIGH, MEDIUM, LOW
    }

    public enum Category {
        HIBERNATE_N_PLUS_ONE,
        HIBERNATE_FETCH_STRATEGY,
        HIBERNATE_EAGER_CYCLE,
        HIBERNATE_MISSING_BATCH_SIZE,
        HIBERNATE_EQUALS_HASHCODE,
        DEAD_CODE,
        ORPHAN_BEAN,
        SPRING_TRANSACTIONAL_MISUSE,
        SPRING_ASYNC_MISUSE,
        SPRING_CONFIG_SMELL,
        MIGRATION_BLOCKER,
        SQL_PERFORMANCE,
        PMD_BEST_PRACTICES,
        PMD_ERROR_PRONE,
        PMD_PERFORMANCE,
        DEPENDENCY_VULNERABILITY,
        ARCHITECTURE_CYCLE,
        ARCHITECTURE_LAYERING,
        ARCHITECTURE_GOD_CLASS
    }

    private final Category category;
    private final Severity severity;
    private final Confidence confidence;
    private final String ruleId;
    private final String title;
    private final String description;
    private final SourceLocation location;
    private final String suggestion;
    private final List<String> evidence;

    /**
     * Backward-compatible constructor. Assigns:
     * <ul>
     *   <li>{@code confidence = HIGH} (analyzers should opt-in to a more accurate value)</li>
     *   <li>{@code ruleId} derived from {@code category.name()} lowercased</li>
     *   <li>{@code evidence = empty}</li>
     * </ul>
     * Prefer the full constructor for new analyzers.
     */
    public Finding(Category category, Severity severity, String title,
                   String description, SourceLocation location, String suggestion) {
        this(category, severity, Confidence.HIGH, defaultRuleId(category),
             title, description, location, suggestion, Collections.<String>emptyList());
    }

    /**
     * Full constructor — analyzers should use this and supply a stable
     * {@code ruleId}, an honest {@code confidence}, and concrete {@code evidence}.
     */
    public Finding(Category category, Severity severity, Confidence confidence, String ruleId,
                   String title, String description, SourceLocation location, String suggestion,
                   List<String> evidence) {
        this.category = category;
        this.severity = severity;
        this.confidence = confidence != null ? confidence : Confidence.HIGH;
        this.ruleId = ruleId != null ? ruleId : defaultRuleId(category);
        this.title = title;
        this.description = description;
        this.location = location;
        this.suggestion = suggestion;
        this.evidence = evidence != null
                ? Collections.unmodifiableList(evidence)
                : Collections.<String>emptyList();
    }

    private static String defaultRuleId(Category category) {
        return category != null ? category.name().toLowerCase() : "unknown";
    }

    public Category getCategory() { return category; }
    public Severity getSeverity() { return severity; }
    public Confidence getConfidence() { return confidence; }
    public String getRuleId() { return ruleId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public SourceLocation getLocation() { return location; }
    public String getSuggestion() { return suggestion; }
    public List<String> getEvidence() { return evidence; }

    @Override
    public String toString() {
        return String.format("[%s/%s] %s - %s (%s)",
                severity, confidence, category, title, location);
    }
}
