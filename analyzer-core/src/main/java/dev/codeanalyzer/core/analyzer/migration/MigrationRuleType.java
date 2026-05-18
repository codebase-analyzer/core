package dev.codeanalyzer.core.analyzer.migration;

/**
 * How a {@link MigrationRule} matches the codebase.
 *
 * <ul>
 *   <li>{@link #IMPORT_PATTERN} — matches a Java import statement (prefix or exact).</li>
 *   <li>{@link #API_USAGE} — matches a method call or static-field reference.</li>
 *   <li>{@link #CLASS_REFERENCE} — matches any reference to a type (extends, instanceof, field type, etc.).</li>
 *   <li>{@link #ANNOTATION} — matches an annotation usage anywhere in the source.</li>
 *   <li>{@link #CONFIG_PROPERTY} — matches a key in {@code .properties} / {@code .yml} / {@code application*.properties}.</li>
 * </ul>
 */
public enum MigrationRuleType {
    IMPORT_PATTERN,
    API_USAGE,
    CLASS_REFERENCE,
    ANNOTATION,
    CONFIG_PROPERTY
}
