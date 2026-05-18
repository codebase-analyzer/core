package dev.codeanalyzer.core.analyzer.security;

import dev.codeanalyzer.core.analyzer.Analyzer;
import dev.codeanalyzer.core.analyzer.AnalysisContext;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;
import dev.codeanalyzer.core.model.ProjectMetadata;
import dev.codeanalyzer.core.model.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class DependencySecurityAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(DependencySecurityAnalyzer.class);

    private final SecurityAdvisoryDatabase database = new SecurityAdvisoryDatabase();

    @Override
    public String getId() { return "dependency-security"; }

    @Override
    public String getName() { return "Dependency Security Scanner"; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        ProjectMetadata metadata = context.getMetadata();
        if (metadata == null || metadata.getDependencies().isEmpty()) {
            return Collections.emptyList();
        }

        List<Finding> findings = new ArrayList<>();

        for (Map.Entry<String, String> dep : metadata.getDependencies().entrySet()) {
            String coordinates = dep.getKey();
            String version = dep.getValue();

            List<SecurityAdvisory> advisories = database.findAdvisories(coordinates, version);
            for (SecurityAdvisory advisory : advisories) {
                findings.add(createFinding(advisory, coordinates, version));
            }
        }

        log.info("Security scan: checked {} dependencies, found {} vulnerabilities",
            metadata.getDependencies().size(), findings.size());
        return findings;
    }

    @Override
    public List<Finding> analyze(List<ParsedSource> sources) {
        return Collections.emptyList();
    }

    private Finding createFinding(SecurityAdvisory advisory, String coordinates, String currentVersion) {
        Finding.Severity severity = mapSeverity(advisory.getCvssScore());

        String title = advisory.getCveId() + " — " + extractArtifactName(coordinates);

        String description = advisory.getSummary()
            + " Affected versions: " + advisory.getAffectedRange()
            + ". Your version: " + currentVersion + ".";

        String suggestion = buildSuggestion(advisory, coordinates, currentVersion);

        return new Finding(
            Finding.Category.DEPENDENCY_VULNERABILITY,
            severity,
            title,
            description,
            SourceLocation.of("pom.xml", 0, coordinates),
            suggestion
        );
    }

    private String buildSuggestion(SecurityAdvisory advisory, String coordinates, String currentVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Vulnerability: ").append(advisory.getCveId()).append("\n");
        sb.append("CVSS Score: ").append(advisory.getCvssScore())
          .append(" (").append(advisory.getSeverity()).append(")\n");
        sb.append("\n");
        sb.append("### Impact\n");
        sb.append(advisory.getSummary()).append("\n");
        sb.append("\n");
        sb.append("### Fix\n");
        sb.append("Upgrade ").append(coordinates)
          .append(" from ").append(currentVersion)
          .append(" to ").append(advisory.getFixedVersion())
          .append(" or later.\n");
        sb.append("\n");
        sb.append("### Bad\n");
        sb.append("```\n");
        sb.append("<dependency>\n");
        sb.append("    <groupId>").append(advisory.getGroupId()).append("</groupId>\n");
        sb.append("    <artifactId>").append(advisory.getArtifactId()).append("</artifactId>\n");
        sb.append("    <version>").append(currentVersion).append("</version>\n");
        sb.append("</dependency>\n");
        sb.append("```\n");
        sb.append("### Good\n");
        sb.append("```\n");
        sb.append("<dependency>\n");
        sb.append("    <groupId>").append(advisory.getGroupId()).append("</groupId>\n");
        sb.append("    <artifactId>").append(advisory.getArtifactId()).append("</artifactId>\n");
        sb.append("    <version>").append(advisory.getFixedVersion()).append("</version>\n");
        sb.append("</dependency>\n");
        sb.append("```\n");
        return sb.toString();
    }

    private Finding.Severity mapSeverity(double cvssScore) {
        if (cvssScore >= 9.0) return Finding.Severity.CRITICAL;
        if (cvssScore >= 7.0) return Finding.Severity.HIGH;
        if (cvssScore >= 4.0) return Finding.Severity.MEDIUM;
        return Finding.Severity.LOW;
    }

    private String extractArtifactName(String coordinates) {
        int colonIdx = coordinates.lastIndexOf(':');
        return colonIdx >= 0 ? coordinates.substring(colonIdx + 1) : coordinates;
    }
}
