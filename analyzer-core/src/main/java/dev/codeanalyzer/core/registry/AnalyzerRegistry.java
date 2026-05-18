package dev.codeanalyzer.core.registry;

import dev.codeanalyzer.core.analyzer.AnalysisContext;
import dev.codeanalyzer.core.analyzer.Analyzer;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;
import dev.codeanalyzer.core.model.ProjectMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registry of all analyzers. Add new analyzers here.
 * Runs them in order and collects all findings.
 */
public class AnalyzerRegistry {

    private static final Logger log = LoggerFactory.getLogger(AnalyzerRegistry.class);

    private final List<Analyzer> analyzers = new ArrayList<>();

    public AnalyzerRegistry register(Analyzer analyzer) {
        analyzers.add(analyzer);
        log.info("Registered analyzer: {} ({})", analyzer.getName(), analyzer.getId());
        return this;
    }

    public List<Analyzer> getAnalyzers() {
        return Collections.unmodifiableList(analyzers);
    }

    /**
     * Run all registered analyzers with full context and return aggregated findings.
     */
    public List<Finding> runAll(List<ParsedSource> sources, Path projectRoot, ProjectMetadata metadata) {
        AnalysisContext context = new AnalysisContext(sources, projectRoot, metadata);
        List<Finding> allFindings = new ArrayList<>();

        for (Analyzer analyzer : analyzers) {
            log.info("Running analyzer: {}", analyzer.getName());
            try {
                List<Finding> findings = analyzer.analyze(context);
                log.info("  -> {} findings", findings.size());
                allFindings.addAll(findings);
            } catch (Exception e) {
                log.error("Analyzer {} failed: {}", analyzer.getId(), e.getMessage(), e);
            }
        }

        return allFindings;
    }
}
