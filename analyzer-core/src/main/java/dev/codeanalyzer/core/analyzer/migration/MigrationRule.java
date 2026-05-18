package dev.codeanalyzer.core.analyzer.migration;

import dev.codeanalyzer.core.model.Finding;

/**
 * A single migration rule. Each rule represents one known incompatibility or
 * breaking change between {@code sourceVersion} and {@code targetVersion}
 * within an {@link #area} (e.g. {@code "Java"}, {@code "Spring Boot"}, {@code "Hibernate"}).
 *
 * <p>Rules are looked up by {@link MigrationRuleType} bucket in
 * {@link MigrationRuleRegistry} so the analyzer only walks the AST patterns
 * relevant to a given rule type — keeping the scan cheap on large codebases.
 */
public final class MigrationRule {

    private final String id;
    private final String area;
    private final String sourceVersion;
    private final String targetVersion;
    private final MigrationRuleType type;
    private final String pattern;
    private final String replacement;
    private final Finding.Severity severity;
    private final String title;
    private final String suggestion;
    private final int estimatedMinutes;

    public MigrationRule(String id, String area, String sourceVersion, String targetVersion,
                         MigrationRuleType type, String pattern, String replacement,
                         Finding.Severity severity, String title, String suggestion,
                         int estimatedMinutes) {
        this.id = id;
        this.area = area;
        this.sourceVersion = sourceVersion;
        this.targetVersion = targetVersion;
        this.type = type;
        this.pattern = pattern;
        this.replacement = replacement;
        this.severity = severity;
        this.title = title;
        this.suggestion = suggestion;
        this.estimatedMinutes = estimatedMinutes;
    }

    public String getId() { return id; }
    public String getArea() { return area; }
    public String getSourceVersion() { return sourceVersion; }
    public String getTargetVersion() { return targetVersion; }
    public MigrationRuleType getType() { return type; }
    public String getPattern() { return pattern; }
    public String getReplacement() { return replacement; }
    public Finding.Severity getSeverity() { return severity; }
    public String getTitle() { return title; }
    public String getSuggestion() { return suggestion; }
    public int getEstimatedMinutes() { return estimatedMinutes; }

    @Override
    public String toString() {
        return id + " [" + area + " " + sourceVersion + "->" + targetVersion + "] " + title;
    }
}
