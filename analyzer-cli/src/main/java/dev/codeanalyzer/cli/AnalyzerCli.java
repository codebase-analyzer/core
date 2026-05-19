package dev.codeanalyzer.cli;

import dev.codeanalyzer.core.AnalysisEngine;
import dev.codeanalyzer.core.autofix.ApplyResult;
import dev.codeanalyzer.core.autofix.AutoFixApplier;
import dev.codeanalyzer.core.suppression.Baseline;
import dev.codeanalyzer.core.analyzer.DeadBeanAnalyzer;
import dev.codeanalyzer.core.analyzer.HibernateEntityAnalyzer;
import dev.codeanalyzer.core.analyzer.architecture.ArchitectureAnalyzer;
import dev.codeanalyzer.core.analyzer.bestpractices.HibernateBestPracticesAnalyzer;
import dev.codeanalyzer.core.analyzer.bestpractices.SpringBestPracticesAnalyzer;
import dev.codeanalyzer.core.analyzer.integration.PmdAnalyzer;
import dev.codeanalyzer.core.analyzer.migration.MigrationReadinessAnalyzer;
import dev.codeanalyzer.core.analyzer.runtime.SqlQueryAnalyzer;
import dev.codeanalyzer.core.analyzer.runtime.TransactionGraphAnalyzer;
import dev.codeanalyzer.core.analyzer.security.DependencySecurityAnalyzer;
import dev.codeanalyzer.core.model.AnalysisResult;
import dev.codeanalyzer.core.registry.AnalyzerRegistry;
import dev.codeanalyzer.report.HtmlReportGenerator;
import dev.codeanalyzer.report.MarkdownSummaryGenerator;
import dev.codeanalyzer.report.ReportGenerator;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(
        name = "codebase-analyzer",
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        description = "Analyzes legacy Java/Spring/Hibernate codebases for common issues."
)
public class AnalyzerCli implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the project root directory")
    private String projectPath;

    @Option(names = {"-o", "--output"}, description = "Output directory for reports (default: ./analyzer-output)")
    private String outputPath = "analyzer-output";

    @Option(names = {"--json"}, description = "Generate JSON report")
    private boolean jsonReport = false;

    @Option(names = {"--no-html"}, description = "Skip HTML report generation")
    private boolean noHtml = false;

    @Option(names = {"-v", "--verbose"}, description = "Print findings to console")
    private boolean verbose = false;

    @Option(names = {"--baseline"}, description = "Filter out findings already present in this baseline JSON file")
    private String baselinePath;

    @Option(names = {"--write-baseline"}, description = "Write all current findings as a baseline JSON file at this path and exit")
    private String writeBaselinePath;

    @Option(names = {"--rate-per-hour"}, description = "Hourly rate used by tech-debt cost projection (default: 100)")
    private double ratePerHour = 100.0;

    @Option(names = {"--currency"}, description = "ISO-4217 currency code for cost output: EUR, USD, GBP, ... (default: EUR)")
    private String currency = "EUR";

    @Option(names = {"--no-git"}, description = "Skip git churn collection (use severity-only impact scoring)")
    private boolean noGit = false;

    @Option(names = {"--git-window-months"}, description = "How many months of git history to consider for churn (default: 12)")
    private int gitWindowMonths = 12;

    @Option(names = {"--mode"}, description = "Initial report mode in the HTML view: dev | lead | exec (default: dev). Users can switch modes in the sidebar dropdown.")
    private String mode = "dev";

    @Option(names = {"--markdown-summary"}, description = "Write a compact Markdown summary at the given path (for CI/PR-comment integration).")
    private String markdownSummaryPath;

    @Option(names = {"--fix"},
            description = "Apply auto-fix patches to disk. Creates .bak backups alongside each modified file. "
                    + "By default only SAFE patches are applied; use --fix-confidence to widen.")
    private boolean applyFixes = false;

    @Option(names = {"--fix-preview"},
            description = "Print fix patches as unified diffs to stdout without modifying any files. "
                    + "Useful for reviewing what --fix would do.")
    private boolean previewFixes = false;

    @Option(names = {"--fix-confidence"},
            description = "Minimum confidence threshold for --fix / --fix-preview: safe | moderate | all (default: safe). "
                    + "'safe' = mechanical refactors only. 'moderate' = also include patches that may alter semantics. "
                    + "'all' = include REVIEW_REQUIRED patches that should always be reviewed first.")
    private String fixConfidence = "safe";

    @Override
    public Integer call() {
        try {
            Path project = Paths.get(projectPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(project)) {
                System.err.println("Error: Not a directory: " + project);
                return 1;
            }

            // Wire up analyzers
            AnalyzerRegistry registry = new AnalyzerRegistry()
                    .register(new HibernateEntityAnalyzer())
                    .register(new HibernateBestPracticesAnalyzer())
                    .register(new SpringBestPracticesAnalyzer())
                    .register(new DeadBeanAnalyzer())
                    .register(new PmdAnalyzer())
                    .register(new DependencySecurityAnalyzer())
                    .register(new MigrationReadinessAnalyzer())
                    .register(new SqlQueryAnalyzer())
                    .register(new TransactionGraphAnalyzer())
                    .register(new ArchitectureAnalyzer());

            // Engine config: baseline + manager-dashboard knobs
            AnalysisEngine engine = new AnalysisEngine(registry)
                    .withHourlyRate(ratePerHour)
                    .withCurrency(currency)
                    .withGitWindowMonths(gitWindowMonths);
            if (noGit) engine.withoutGit();
            if (baselinePath != null && !baselinePath.isEmpty()) {
                Path bp = Paths.get(baselinePath).toAbsolutePath();
                engine.withBaseline(Baseline.load(bp));
                System.out.println("Using baseline: " + bp);
            }
            AnalysisResult result = engine.analyze(project);

            // --write-baseline: persist current findings + trend snapshot, exit early.
            // We use the AnalysisResult overload so the baseline embeds severity/category
            // counts, tech-debt total and migration score — fueling the trend deltas
            // on subsequent runs.
            if (writeBaselinePath != null && !writeBaselinePath.isEmpty()) {
                Path bp = Paths.get(writeBaselinePath).toAbsolutePath();
                Baseline.write(result, bp);
                System.out.println("Wrote baseline (" + result.getFindings().size()
                        + " findings + trend snapshot) to: " + bp);
                return 0;
            }

            // Output
            ReportGenerator reporter = new ReportGenerator();
            Path output = Paths.get(outputPath);

            if (verbose) {
                reporter.printConsoleReport(result);
            }

            if (!noHtml) {
                HtmlReportGenerator htmlReporter = new HtmlReportGenerator()
                        .withInitialMode(mode);
                Path htmlFile = htmlReporter.generate(result, output);
                System.out.println("HTML report: " + htmlFile.toAbsolutePath());
            }

            if (jsonReport) {
                Path reportFile = reporter.writeJsonReport(result, output);
                System.out.println("JSON report: " + reportFile.toAbsolutePath());
            }

            if (markdownSummaryPath != null && !markdownSummaryPath.isEmpty()) {
                Path mdFile = Paths.get(markdownSummaryPath).toAbsolutePath();
                new MarkdownSummaryGenerator().generate(result, mdFile);
                System.out.println("Markdown summary: " + mdFile);
            }

            // Auto-fix: --fix-preview (print diffs, no IO) or --fix (apply to disk).
            // The two are mutually exclusive — preview is precisely the read-only
            // version of apply.
            if (applyFixes && previewFixes) {
                System.err.println("Error: --fix and --fix-preview are mutually exclusive. "
                        + "Use --fix-preview to inspect, --fix to apply.");
                return 1;
            }
            if (applyFixes || previewFixes) {
                handleAutoFix(project, result);
            }

            // Exit code based on severity
            if (result.countBySeverity(dev.codeanalyzer.core.model.Finding.Severity.CRITICAL) > 0) {
                return 2;
            }
            return 0;

        } catch (Exception e) {
            System.err.println("Analysis failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    /**
     * Orchestrates --fix and --fix-preview. The analyser's {@link AnalysisResult}
     * already contains {@code availableFixes} (populated by {@code AutoFixEngine});
     * here we apply or preview them under the user's confidence filter.
     */
    private void handleAutoFix(Path project, AnalysisResult result) {
        AutoFixApplier.ConfidenceFilter filter;
        try {
            filter = AutoFixApplier.ConfidenceFilter.parse(fixConfidence);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return;
        }

        AutoFixApplier applier = new AutoFixApplier();
        ApplyResult ar = previewFixes
                ? applier.preview(result.getAvailableFixes(), filter, project, System.out)
                : applier.apply(result.getAvailableFixes(), filter, project);

        printFixSummary(ar, filter);
    }

    private void printFixSummary(ApplyResult ar, AutoFixApplier.ConfidenceFilter filter) {
        String mode = ar.isPreviewOnly() ? "preview" : "apply";
        String filterLabel;
        switch (filter) {
            case SAFE_ONLY:         filterLabel = "SAFE only";         break;
            case SAFE_AND_MODERATE: filterLabel = "SAFE + MODERATE";   break;
            case ALL:               filterLabel = "ALL confidences";   break;
            default:                filterLabel = filter.name();
        }
        System.out.println();
        System.out.println("Auto-fix " + mode + " (" + filterLabel + "):");
        if (ar.isPreviewOnly()) {
            System.out.println("  " + ar.getAppliedPatches() + " patches would be applied");
        } else {
            System.out.println("  " + ar.getAppliedPatches() + " patches applied across "
                    + ar.getAppliedFiles() + " files (.bak backups created)");
        }
        if (ar.getSkippedByConfidence() > 0) {
            System.out.println("  " + ar.getSkippedByConfidence()
                    + " patches skipped: above confidence threshold (raise with --fix-confidence)");
        }
        if (ar.getSkippedByConflict() > 0) {
            System.out.println("  " + ar.getSkippedByConflict()
                    + " patches skipped: source file changed since scan");
        }
        if (ar.getFailedToWrite() > 0) {
            System.out.println("  " + ar.getFailedToWrite() + " patches FAILED to write (see warnings)");
        }
        for (String w : ar.getWarnings()) {
            System.out.println("  ! " + w);
        }
        if (!ar.isPreviewOnly() && ar.getAppliedPatches() > 0) {
            System.out.println("  Tip: re-run without --fix to see remaining findings.");
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AnalyzerCli()).execute(args);
        System.exit(exitCode);
    }
}
