package dev.codeanalyzer.core;

import dev.codeanalyzer.core.model.AnalysisResult;
import dev.codeanalyzer.core.model.AnalysisStats;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;
import dev.codeanalyzer.core.model.ProjectMetadata;
import dev.codeanalyzer.core.analyzer.Analyzer;
import dev.codeanalyzer.core.analyzer.architecture.ArchitectureAnalyzer;
import dev.codeanalyzer.core.analyzer.architecture.ArchitectureReport;
import dev.codeanalyzer.core.analyzer.migration.MigrationScore;
import dev.codeanalyzer.core.analyzer.migration.MigrationScoreCalculator;
import dev.codeanalyzer.core.analyzer.prioritization.GitHistory;
import dev.codeanalyzer.core.analyzer.prioritization.GitHistoryCollector;
import dev.codeanalyzer.core.analyzer.prioritization.ImpactScorer;
import dev.codeanalyzer.core.analyzer.prioritization.PrioritizedFinding;
import dev.codeanalyzer.core.analyzer.prioritization.TechDebt;
import dev.codeanalyzer.core.analyzer.prioritization.TechDebtCalculator;
import dev.codeanalyzer.core.autofix.AutoFixEngine;
import dev.codeanalyzer.core.autofix.Patch;
import dev.codeanalyzer.core.parser.ProjectMetadataDetector;
import dev.codeanalyzer.core.parser.ProjectScanner;
import dev.codeanalyzer.core.registry.AnalyzerRegistry;
import dev.codeanalyzer.core.suppression.Baseline;
import dev.codeanalyzer.core.suppression.BaselineSnapshot;
import dev.codeanalyzer.core.suppression.FindingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Main entry point for the analysis engine.
 * Orchestrates: scan → parse → analyze → produce result.
 */
public class AnalysisEngine {

    private static final Logger log = LoggerFactory.getLogger(AnalysisEngine.class);

    private final AnalyzerRegistry registry;
    private Baseline baseline = Baseline.empty();
    private double hourlyRate = 100.0;
    private String currencyCode = "EUR";   // EUR is our default; configurable via --currency
    private boolean useGit = true;
    private int gitWindowMonths = 12;

    public AnalysisEngine(AnalyzerRegistry registry) {
        this.registry = registry;
    }

    /** Optional baseline — findings already present here are filtered out. */
    public AnalysisEngine withBaseline(Baseline baseline) {
        this.baseline = baseline != null ? baseline : Baseline.empty();
        return this;
    }

    /** Hourly rate used by tech-debt projection (in the configured currency). Default: 100. */
    public AnalysisEngine withHourlyRate(double rate) { this.hourlyRate = rate; return this; }
    /** ISO-4217 currency code for tech-debt cost projection. Default: EUR. */
    public AnalysisEngine withCurrency(String code) {
        if (code != null && !code.trim().isEmpty()) this.currencyCode = code.trim().toUpperCase();
        return this;
    }
    /** Disable git history collection entirely (e.g. CI without full git history). */
    public AnalysisEngine withoutGit() { this.useGit = false; return this; }
    /** How many months of git log to consider for churn (default 12). */
    public AnalysisEngine withGitWindowMonths(int months) {
        this.gitWindowMonths = Math.max(1, months); return this;
    }

    public AnalysisResult analyze(Path projectPath) throws IOException {
        long start = System.currentTimeMillis();

        // 1. Detect project metadata (Java version, frameworks)
        ProjectMetadataDetector metadataDetector = new ProjectMetadataDetector();
        ProjectMetadata metadata = metadataDetector.detect(projectPath);

        // 2. Scan and parse
        ProjectScanner scanner = new ProjectScanner(projectPath);
        ProjectScanner.ScanResult scanResult = scanner.scan();
        List<ParsedSource> sources = scanResult.getSources();

        // 3. Collect codebase stats
        int entityCount = countAnnotated(sources, "Entity");
        int repositoryCount = countAnnotated(sources, "Repository");
        int serviceCount = countAnnotated(sources, "Service");
        int controllerCount = countAnnotated(sources, "Controller", "RestController");

        // 4. Run all analyzers
        List<Finding> rawFindings = registry.runAll(sources, projectPath, metadata);

        // 5. Apply suppression: .codebase-analyzer.yml + inline comments + baseline
        FindingFilter filter = new FindingFilter(projectPath, baseline);
        List<Finding> findings = filter.apply(rawFindings);

        long duration = System.currentTimeMillis() - start;

        // 5. Build result
        AnalysisStats stats = AnalysisStats.builder()
                .totalFiles(scanResult.getTotalFiles())
                .parsedFiles(sources.size())
                .parseErrors(scanResult.getParseErrors())
                .entityCount(entityCount)
                .repositoryCount(repositoryCount)
                .serviceCount(serviceCount)
                .controllerCount(controllerCount)
                .durationMs(duration)
                .build();

        AnalysisResult result = new AnalysisResult(
                projectPath.toAbsolutePath().toString(), findings, stats, metadata);

        // Trend tracking: lift the baseline snapshot (if any) into the result so the
        // HTML report can compute and render deltas. Empty for fresh / old-format baselines.
        result.setBaselineSnapshot(baseline.getSnapshot());

        // Migration score is derived from MIGRATION_BLOCKER findings produced by
        // the MigrationReadinessAnalyzer; computed against the FULL finding list
        // (before suppression) so baselining doesn't artificially inflate the score.
        MigrationScore migration = MigrationScoreCalculator.calculate(rawFindings);
        if (!migration.isEmpty()) {
            log.info("Migration readiness: overall {}/100 ({} verdict), {} blockers, ~{}h effort",
                    migration.getOverallScore(), migration.getVerdict(),
                    migration.getTotalBlockers(), migration.getEstimatedEffortHours());
        }
        result.setMigrationScore(migration);

        // When a non-empty baseline snapshot is loaded, also compute a snapshot of
        // the CURRENT raw state (pre-suppression) — so the trend grid compares
        // apples to apples: "you had X before, you have Y now" instead of "you had X
        // before, you have 0 net-new now" (which would always show massive improvement).
        if (!baseline.getSnapshot().isEmpty()) {
            TechDebt rawDebt = TechDebtCalculator.calculate(rawFindings, hourlyRate, currencyCode);
            BaselineSnapshot currentSnap = BaselineSnapshot.fromComponents(rawFindings, rawDebt, migration);
            result.setCurrentSnapshot(currentSnap);
        }

        // Architecture report (B3) — lift cached report from the registered
        // ArchitectureAnalyzer. We don't recompute: the analyzer already built
        // the package graph + cycles + violations + god classes during runAll().
        for (Analyzer a : registry.getAnalyzers()) {
            if (a instanceof ArchitectureAnalyzer) {
                ArchitectureReport arch = ((ArchitectureAnalyzer) a).getLastReport();
                result.setArchitectureReport(arch);
                if (arch != null && !arch.isEmpty()) {
                    log.info("Architecture report: {} packages, {} cycles, {} layering violations, {} god classes",
                            arch.getGraph().size(), arch.getCycles().size(),
                            arch.getViolations().size(), arch.getGodClasses().size());
                }
                break;
            }
        }

        // Manager Dashboard (B4 + B5) — git churn → impact scoring → top-N priority list,
        // and tech-debt projection in hours + $$. Run on POST-suppression findings so
        // suppression/baseline correctly reduces what counts as "outstanding debt".
        GitHistory git = useGit ? GitHistoryCollector.collect(projectPath, gitWindowMonths)
                                : GitHistory.empty();
        result.setGitHistory(git);

        List<PrioritizedFinding> prioritized = ImpactScorer.score(findings, git);
        result.setPrioritizedFindings(prioritized);

        TechDebt debt = TechDebtCalculator.calculate(findings, hourlyRate, currencyCode);
        result.setTechDebt(debt);
        if (!debt.isEmpty()) {
            log.info("Tech debt: {} hours / {}{} at {}{}/hr ({} categories)",
                    debt.getTotalHours(), debt.getCurrencySymbol(),
                    formatMoney(debt.getTotalCost()), debt.getCurrencySymbol(),
                    (int) hourlyRate, debt.getByCategory().size());
        }
        if (!prioritized.isEmpty()) {
            PrioritizedFinding top = prioritized.get(0);
            log.info("Top priority fix: impact={}, {}: {}",
                    top.getImpactScore(), top.getFinding().getCategory(), top.getFinding().getTitle());
        }

        // Auto-fix generation (B6) — produce unified-diff patches for findings
        // whose ruleId matches a registered fixer. Generation is always-on
        // (cheap: no work for ruleIds without a fixer). Applying patches to
        // disk is opt-in via a future --fix CLI flag.
        try {
            AutoFixEngine fixEngine = new AutoFixEngine();
            Map<Finding, Patch> fixes = fixEngine.generatePatches(findings, sources);
            result.setAvailableFixes(fixes);
        } catch (RuntimeException e) {
            log.warn("Auto-fix generation failed: {}", e.getMessage());
        }

        log.info("Analysis complete in {}ms: {} findings ({} critical, {} high)",
                duration, findings.size(),
                result.countBySeverity(Finding.Severity.CRITICAL),
                result.countBySeverity(Finding.Severity.HIGH));

        return result;
    }

    private static String formatMoney(double v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000.0);
        if (v >= 1_000)     return String.format("%.0fk", v / 1_000.0);
        return String.format("%.0f", v);
    }

    private int countAnnotated(List<ParsedSource> sources, String... annotationNames) {
        int count = 0;
        for (ParsedSource source : sources) {
            List<com.github.javaparser.ast.body.ClassOrInterfaceDeclaration> classes =
                    source.getCompilationUnit().findAll(
                            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
            for (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration c : classes) {
                boolean matched = false;
                for (String ann : annotationNames) {
                    if (c.getAnnotationByName(ann).isPresent()) {
                        matched = true;
                        break;
                    }
                }
                if (matched) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }
}
