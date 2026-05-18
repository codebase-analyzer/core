package dev.codeanalyzer.report;

import dev.codeanalyzer.core.analyzer.architecture.ArchitectureReport;
import dev.codeanalyzer.core.analyzer.migration.MigrationScore;
import dev.codeanalyzer.core.analyzer.prioritization.PrioritizedFinding;
import dev.codeanalyzer.core.analyzer.prioritization.TechDebt;
import dev.codeanalyzer.core.model.AnalysisResult;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.suppression.BaselineSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Emits a compact Markdown summary of the analysis result — designed to be
 * pasted into a GitHub / GitLab PR comment by CI workflows.
 *
 * <p>Layout: headline → trend (when a baseline is present) → KPI table → top 5
 * findings → footer link to the full HTML artifact. Stays under ~3 KB so it
 * doesn't blow past GitHub's 65 KB comment limit even on huge codebases.
 *
 * <p>This generator is intentionally separate from {@link HtmlReportGenerator}
 * and {@link ReportGenerator} so the CI integration can be evolved without
 * touching the rich HTML output. Triggered by the {@code --markdown-summary}
 * CLI flag.
 */
public class MarkdownSummaryGenerator {

    private static final Logger log = LoggerFactory.getLogger(MarkdownSummaryGenerator.class);

    public Path generate(AnalysisResult result, Path outputFile) throws IOException {
        StringBuilder md = new StringBuilder(2048);

        TechDebt debt = result.getTechDebt();
        MigrationScore migration = result.getMigrationScore();
        ArchitectureReport arch = result.getArchitectureReport();
        BaselineSnapshot snap = result.getBaselineSnapshot();
        BaselineSnapshot current = result.getCurrentSnapshot();

        // For the headline emoji, prefer the raw current snapshot (when baseline is
        // present) so the emoji is consistent with the trend table. Otherwise fall
        // back to post-suppression counts.
        long critical, high, total, cveCount;
        if (current != null && !current.isEmpty()) {
            critical = current.countSeverity(Finding.Severity.CRITICAL);
            high     = current.countSeverity(Finding.Severity.HIGH);
            total    = current.getTotalFindings();
            cveCount = current.getCveCount();
        } else {
            critical = result.countBySeverity(Finding.Severity.CRITICAL);
            high     = result.countBySeverity(Finding.Severity.HIGH);
            total    = result.getFindings().size();
            cveCount = result.getFindings().stream()
                    .filter(f -> f.getCategory() == Finding.Category.DEPENDENCY_VULNERABILITY).count();
        }

        // ── Headline ─────────────────────────────────────────────────────
        String emoji;
        if (critical >= 100 || cveCount >= 5) emoji = "🔴";
        else if (critical >= 10 || cveCount > 0) emoji = "🟠";
        else if (high >= 10) emoji = "🟡";
        else emoji = "🟢";

        md.append("## ").append(emoji).append(" 🦉 Code Owl report\n\n");

        // ── Trend banner (only when baseline + current snapshots are present) ─
        if (snap != null && !snap.isEmpty() && current != null && !current.isEmpty()) {
            int dTotal    = current.getTotalFindings() - snap.getTotalFindings();
            int dCritical = current.countSeverity(Finding.Severity.CRITICAL)
                          - snap.countSeverity(Finding.Severity.CRITICAL);
            int dHigh     = current.countSeverity(Finding.Severity.HIGH)
                          - snap.countSeverity(Finding.Severity.HIGH);
            int dCve      = current.getCveCount() - snap.getCveCount();
            long dHours   = current.getTechDebtHours() - snap.getTechDebtHours();
            double dCost  = current.getTechDebtCost() - snap.getTechDebtCost();

            boolean improved = dCritical < 0 || dCve < 0 || dCost < 0;
            boolean regressed = dCritical > 0 || dCve > 0 || dCost > 1000;

            String trendEmoji;
            String trendVerdict;
            if (dTotal == 0 && dCritical == 0 && dCost == 0) {
                trendEmoji = "➖"; trendVerdict = "No change since baseline";
            } else if (improved && !regressed) {
                trendEmoji = "✅"; trendVerdict = "Improved since baseline";
            } else if (regressed && !improved) {
                trendEmoji = "❌"; trendVerdict = "Regressed since baseline";
            } else {
                trendEmoji = "⚠️"; trendVerdict = "Mixed change since baseline";
            }

            md.append("### ").append(trendEmoji).append(" Trend: ").append(trendVerdict).append("\n\n");
            md.append("| Metric | Current | Δ vs baseline |\n");
            md.append("|---|---:|---:|\n");
            md.append("| Total findings | ").append(current.getTotalFindings())
                .append(" | ").append(formatDelta(dTotal, false, null)).append(" |\n");
            md.append("| Critical | ").append(current.countSeverity(Finding.Severity.CRITICAL))
                .append(" | ").append(formatDelta(dCritical, false, null)).append(" |\n");
            md.append("| High | ").append(current.countSeverity(Finding.Severity.HIGH))
                .append(" | ").append(formatDelta(dHigh, false, null)).append(" |\n");
            md.append("| CVEs | ").append(current.getCveCount())
                .append(" | ").append(formatDelta(dCve, false, null)).append(" |\n");
            String sym = debt != null ? debt.getCurrencySymbol() : "€";
            md.append("| Tech-debt hours | ").append(current.getTechDebtHours())
                .append(" | ").append(formatDelta((int) dHours, false, null)).append(" |\n");
            md.append("| Tech-debt cost | ").append(sym).append(formatMoney(current.getTechDebtCost()))
                .append(" | ").append(formatDelta((int) dCost, true, sym)).append(" |\n\n");
        } else {
            // No baseline — flat KPI table
            md.append("### Snapshot\n\n");
            md.append("| Metric | Value |\n|---|---:|\n");
            md.append("| Total findings | ").append(total).append(" |\n");
            md.append("| Critical | ").append(critical).append(" |\n");
            md.append("| High | ").append(high).append(" |\n");
            md.append("| Dependency CVEs | ").append(cveCount).append(" |\n");
            if (debt != null && !debt.isEmpty()) {
                md.append("| Tech debt | ").append(debt.getCurrencySymbol())
                    .append(formatMoney(debt.getTotalCost())).append(" (~")
                    .append(debt.getTotalHours()).append(" h) |\n");
            }
            if (migration != null && !migration.isEmpty()) {
                md.append("| Migration readiness | ").append(migration.getOverallScore())
                    .append("/100 (").append(migration.getVerdict()).append(") |\n");
            }
            if (arch != null && !arch.isEmpty()) {
                md.append("| Package cycles | ").append(arch.getCycles().size()).append(" |\n");
                md.append("| Layering violations | ").append(arch.getViolations().size()).append(" |\n");
                md.append("| God classes | ").append(arch.getGodClasses().size()).append(" |\n");
            }
            md.append("\n");
        }

        // ── Top 5 priority fixes ─────────────────────────────────────────
        if (result.getPrioritizedFindings() != null && !result.getPrioritizedFindings().isEmpty()) {
            md.append("### Top priority fixes\n\n");
            md.append("| # | Impact | Severity | Effort | Finding |\n");
            md.append("|---:|---:|---|---|---|\n");
            int n = Math.min(5, result.getPrioritizedFindings().size());
            for (int i = 0; i < n; i++) {
                PrioritizedFinding pf = result.getPrioritizedFindings().get(i);
                Finding f = pf.getFinding();
                md.append("| ").append(i + 1)
                  .append(" | **").append(pf.getImpactScore()).append("/100**")
                  .append(" | ").append(f.getSeverity())
                  .append(" | ").append(pf.getEffort()).append(" (~").append(pf.getEstimatedHours()).append("h)")
                  .append(" | ").append(escapePipe(f.getTitle())).append(" |\n");
            }
            md.append('\n');
        }

        // ── Footer ───────────────────────────────────────────────────────
        md.append("---\n");
        md.append("<sub>Full HTML report available as a workflow artifact. ");
        md.append("Generated by [Code Owl](https://github.com/codebase-analyzer/core) at ");
        md.append(result.getTimestamp()).append("</sub>\n");

        Files.createDirectories(outputFile.toAbsolutePath().getParent());
        Files.write(outputFile, md.toString().getBytes(StandardCharsets.UTF_8));
        log.info("Markdown summary written to: {} ({} bytes)", outputFile, md.length());
        return outputFile;
    }

    /** Δ display: green ↓ for improvement (negative), red ↑ for regression (positive). */
    private static String formatDelta(int delta, boolean isMoney, String currencySymbol) {
        if (delta == 0) return "—";
        String sym = currencySymbol != null ? currencySymbol : "";
        String abs = isMoney ? sym + formatMoney(Math.abs(delta)) : String.valueOf(Math.abs(delta));
        if (delta < 0) return "🟢 ↓ " + abs;
        return "🔴 ↑ " + abs;
    }

    private static String formatMoney(double v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000.0);
        if (v >= 1_000)     return String.format("%.0fk", v / 1_000.0);
        return String.format("%.0f", v);
    }

    /** Escape pipe characters so the title doesn't break the Markdown table. */
    private static String escapePipe(String s) {
        return s == null ? "" : s.replace("|", "\\|");
    }
}
