package dev.codeanalyzer.core.analyzer;

import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;

import java.util.List;

/**
 * Contract for all code analyzers.
 * Each analyzer inspects parsed sources and produces findings.
 *
 * Analyzers that only need Java ASTs override analyze(List<ParsedSource>).
 * Analyzers that also need non-Java files (XML, properties) override
 * analyze(AnalysisContext) to access the project root.
 */
public interface Analyzer {

    String getId();

    String getName();

    /**
     * Run analysis with full context (parsed sources + project root).
     * Default implementation delegates to the sources-only method.
     */
    default List<Finding> analyze(AnalysisContext context) {
        return analyze(context.getSources());
    }

    /**
     * Run analysis on parsed Java sources only.
     * Override this for analyzers that don't need filesystem access.
     */
    List<Finding> analyze(List<ParsedSource> sources);
}
