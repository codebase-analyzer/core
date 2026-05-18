package dev.codeanalyzer.core.analyzer;

import dev.codeanalyzer.core.model.ParsedSource;
import dev.codeanalyzer.core.model.ProjectMetadata;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Context passed to analyzers. Carries parsed Java sources
 * and the project root path for analyzers that need to scan
 * non-Java files (XML configs, properties, etc.).
 */
public class AnalysisContext {

    private final List<ParsedSource> sources;
    private final Path projectRoot;
    private final ProjectMetadata metadata;

    public AnalysisContext(List<ParsedSource> sources, Path projectRoot, ProjectMetadata metadata) {
        this.sources = Collections.unmodifiableList(sources);
        this.projectRoot = projectRoot;
        this.metadata = metadata;
    }

    public List<ParsedSource> getSources() { return sources; }
    public Path getProjectRoot() { return projectRoot; }
    public ProjectMetadata getMetadata() { return metadata; }
}
