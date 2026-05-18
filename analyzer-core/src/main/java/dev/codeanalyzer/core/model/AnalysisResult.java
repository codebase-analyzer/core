package dev.codeanalyzer.core.model;

import dev.codeanalyzer.core.analyzer.architecture.ArchitectureReport;
import dev.codeanalyzer.core.analyzer.migration.MigrationScore;
import dev.codeanalyzer.core.analyzer.prioritization.GitHistory;
import dev.codeanalyzer.core.analyzer.prioritization.PrioritizedFinding;
import dev.codeanalyzer.core.analyzer.prioritization.TechDebt;
import dev.codeanalyzer.core.suppression.BaselineSnapshot;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregated result of running all analyzers on a project.
 */
public class AnalysisResult {

    private final String projectPath;
    private final String timestamp;
    private final List<Finding> findings;
    private final AnalysisStats stats;
    private final ProjectMetadata metadata;
    private MigrationScore migrationScore;
    private List<PrioritizedFinding> prioritizedFindings = Collections.emptyList();
    private TechDebt techDebt = TechDebt.empty();
    private GitHistory gitHistory = GitHistory.empty();
    private ArchitectureReport architectureReport = ArchitectureReport.empty();
    /** Optional baseline snapshot for trend tracking (null/empty if no baseline supplied). */
    private BaselineSnapshot baselineSnapshot = BaselineSnapshot.empty();
    /** Snapshot of the CURRENT raw state (pre-suppression). Filled in when a baseline is present, for trend comparison. */
    private BaselineSnapshot currentSnapshot = BaselineSnapshot.empty();

    public AnalysisResult(String projectPath, List<Finding> findings, AnalysisStats stats, ProjectMetadata metadata) {
        this.projectPath = projectPath;
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
        this.stats = stats;
        this.metadata = metadata;
    }

    public String getProjectPath() { return projectPath; }
    public String getTimestamp() { return timestamp; }
    public List<Finding> getFindings() { return findings; }
    public AnalysisStats getStats() { return stats; }
    public ProjectMetadata getMetadata() { return metadata; }
    public MigrationScore getMigrationScore() { return migrationScore; }
    public void setMigrationScore(MigrationScore migrationScore) { this.migrationScore = migrationScore; }

    public List<PrioritizedFinding> getPrioritizedFindings() { return prioritizedFindings; }
    public void setPrioritizedFindings(List<PrioritizedFinding> prioritized) {
        this.prioritizedFindings = prioritized != null
                ? Collections.unmodifiableList(prioritized)
                : Collections.<PrioritizedFinding>emptyList();
    }

    public TechDebt getTechDebt() { return techDebt; }
    public void setTechDebt(TechDebt techDebt) { this.techDebt = techDebt != null ? techDebt : TechDebt.empty(); }

    public GitHistory getGitHistory() { return gitHistory; }
    public void setGitHistory(GitHistory g) { this.gitHistory = g != null ? g : GitHistory.empty(); }

    public ArchitectureReport getArchitectureReport() { return architectureReport; }
    public void setArchitectureReport(ArchitectureReport r) {
        this.architectureReport = r != null ? r : ArchitectureReport.empty();
    }

    /** Snapshot from the loaded baseline; empty if no baseline or old-format baseline. */
    public BaselineSnapshot getBaselineSnapshot() { return baselineSnapshot; }
    public void setBaselineSnapshot(BaselineSnapshot s) {
        this.baselineSnapshot = s != null ? s : BaselineSnapshot.empty();
    }

    /** Snapshot of the CURRENT raw state — used for trend deltas vs baseline. */
    public BaselineSnapshot getCurrentSnapshot() { return currentSnapshot; }
    public void setCurrentSnapshot(BaselineSnapshot s) {
        this.currentSnapshot = s != null ? s : BaselineSnapshot.empty();
    }

    public Map<Finding.Severity, List<Finding>> findingsBySeverity() {
        return Collections.unmodifiableMap(
                findings.stream().collect(Collectors.groupingBy(Finding::getSeverity)));
    }

    public Map<Finding.Category, List<Finding>> findingsByCategory() {
        return Collections.unmodifiableMap(
                findings.stream().collect(Collectors.groupingBy(Finding::getCategory)));
    }

    public long countBySeverity(Finding.Severity severity) {
        return findings.stream().filter(f -> f.getSeverity() == severity).count();
    }
}
