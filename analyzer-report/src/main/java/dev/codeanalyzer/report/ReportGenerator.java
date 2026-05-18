package dev.codeanalyzer.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.codeanalyzer.core.model.AnalysisResult;
import dev.codeanalyzer.core.model.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Generates analysis reports in multiple formats.
 */
public class ReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerator.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Print a summary to console.
     */
    public void printConsoleReport(AnalysisResult result) {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  CODEBASE ANALYZER - Analysis Report");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.printf("  Project:      %s%n", result.getProjectPath());
        System.out.printf("  Timestamp:    %s%n", result.getTimestamp());
        System.out.printf("  Duration:     %dms%n", result.getStats().getDurationMs());
        System.out.println();
        System.out.println("  ── Codebase Stats ──────────────────────────────────────────");
        System.out.printf("  Files scanned:  %d (%d parsed, %d errors)%n",
                result.getStats().getTotalFiles(),
                result.getStats().getParsedFiles(),
                result.getStats().getParseErrors());
        System.out.printf("  Entities:       %d%n", result.getStats().getEntityCount());
        System.out.printf("  Repositories:   %d%n", result.getStats().getRepositoryCount());
        System.out.printf("  Services:       %d%n", result.getStats().getServiceCount());
        System.out.printf("  Controllers:    %d%n", result.getStats().getControllerCount());
        System.out.println();

        // Summary by severity
        System.out.println("  ── Findings Summary ────────────────────────────────────────");
        Map<Finding.Severity, List<Finding>> bySeverity = result.findingsBySeverity();
        for (Finding.Severity sev : Finding.Severity.values()) {
            List<Finding> list = bySeverity.get(sev);
            int count = list != null ? list.size() : 0;
            if (count > 0) {
                System.out.printf("  %-10s %d%n", sev, count);
            }
        }
        System.out.printf("  %-10s %d%n", "TOTAL", result.getFindings().size());
        System.out.println();

        // Detail per finding
        if (!result.getFindings().isEmpty()) {
            System.out.println("  ── Findings Detail ─────────────────────────────────────────");
            System.out.println();

            Map<Finding.Category, List<Finding>> byCategory = result.findingsByCategory();
            for (Map.Entry<Finding.Category, List<Finding>> entry : byCategory.entrySet()) {
                System.out.printf("  [%s]%n", entry.getKey());
                for (Finding f : entry.getValue()) {
                    System.out.printf("    %s %s%n", severityIcon(f.getSeverity()), f.getTitle());
                    System.out.printf("      %s%n", f.getLocation());
                    System.out.printf("      %s%n", f.getDescription());
                    System.out.printf("      -> %s%n", f.getSuggestion());
                    System.out.println();
                }
            }
        }

        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    /**
     * Write JSON report to file.
     */
    public Path writeJsonReport(AnalysisResult result, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path reportFile = outputDir.resolve("analysis-report.json");
        String json = GSON.toJson(result);
        Files.write(reportFile, json.getBytes(StandardCharsets.UTF_8));
        log.info("JSON report written to: {}", reportFile);
        return reportFile;
    }

    private String severityIcon(Finding.Severity severity) {
        switch (severity) {
            case CRITICAL: return "[CRIT]";
            case HIGH:     return "[HIGH]";
            case MEDIUM:   return "[MED] ";
            case LOW:      return "[LOW] ";
            case INFO:     return "[INFO]";
            default:       return "      ";
        }
    }
}
