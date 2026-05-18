package dev.codeanalyzer.report;

import dev.codeanalyzer.core.analyzer.architecture.ArchitectureReport;
import dev.codeanalyzer.core.analyzer.architecture.ClassMetric;
import dev.codeanalyzer.core.analyzer.architecture.LayerInference;
import dev.codeanalyzer.core.analyzer.architecture.PackageGraph;
import dev.codeanalyzer.core.analyzer.architecture.PackageNode;
import dev.codeanalyzer.core.analyzer.dependencies.FrameworkRegistry;
import dev.codeanalyzer.core.analyzer.migration.MigrationScore;
import dev.codeanalyzer.core.analyzer.prioritization.PrioritizedFinding;
import dev.codeanalyzer.core.analyzer.prioritization.TechDebt;
import dev.codeanalyzer.core.autofix.Patch;
import dev.codeanalyzer.core.suppression.BaselineSnapshot;
import dev.codeanalyzer.core.model.AnalysisResult;
import dev.codeanalyzer.core.model.AnalysisStats;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ProjectMetadata;
import dev.codeanalyzer.core.model.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates a self-contained HTML report with interactive filtering,
 * severity breakdown, and collapsible category sections.
 * Zero external dependencies — everything is inlined.
 */
public class HtmlReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(HtmlReportGenerator.class);

    /** Initial report-mode setting on body load. User can switch via the sidebar. */
    private String initialMode = "dev";

    /** Sets the initial mode rendered on first load. Accepts dev|lead|exec, default dev. */
    public HtmlReportGenerator withInitialMode(String mode) {
        if (mode != null) {
            String m = mode.trim().toLowerCase();
            if (m.equals("dev") || m.equals("lead") || m.equals("exec")) {
                this.initialMode = m;
            }
        }
        return this;
    }

    public Path generate(AnalysisResult result, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path reportFile = outputDir.resolve("analysis-report.html");

        StringBuilder html = new StringBuilder(32_000);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n");
        appendHead(html);
        // Body carries the active mode; CSS + JS use it as the source of truth.
        // Initial mode comes from the --mode CLI flag; the sidebar selector overrides at runtime.
        html.append("<body data-mode=\"").append(initialMode).append("\">\n");

        // ── Dashboard layout: sidebar + main content area ─────────────────
        html.append("<div class=\"app-layout\">\n");
        appendSidebar(html, result);

        html.append("<main class=\"app-content\">\n");
        appendTopBar(html, result);

        // Overview is the landing page (active by default).
        html.append("<section class=\"page active\" data-page=\"overview\">\n");
        // Executive Summary panel — only visible in exec mode (CSS-driven).
        appendExecutiveSummary(html, result);
        appendOverview(html, result);
        html.append("</section>\n");

        html.append("<section class=\"page\" data-page=\"priority\">\n");
        html.append("  <h2 class=\"page-title\">Priority &amp; Tech Debt</h2>\n");
        appendPrioritization(html, result);
        html.append("</section>\n");

        // Findings page hidden in exec mode (managers don't want the firehose).
        html.append("<section class=\"page\" data-page=\"findings\" data-modes=\"dev lead\">\n");
        html.append("  <h2 class=\"page-title\">Findings</h2>\n");
        appendFilters(html, result);
        appendFindings(html, result);
        html.append("</section>\n");

        html.append("<section class=\"page\" data-page=\"migration\">\n");
        html.append("  <h2 class=\"page-title\">Migration Readiness</h2>\n");
        appendMigrationReadiness(html, result);
        html.append("</section>\n");

        // Architecture page hidden in exec mode — counts already show on Overview.
        html.append("<section class=\"page\" data-page=\"architecture\" data-modes=\"dev lead\">\n");
        html.append("  <h2 class=\"page-title\">Architecture &amp; Modularity</h2>\n");
        appendArchitecture(html, result);
        html.append("</section>\n");

        html.append("<section class=\"page\" data-page=\"security\">\n");
        html.append("  <h2 class=\"page-title\">Security &amp; Dependencies CVEs</h2>\n");
        appendSecuritySummary(html, result);
        html.append("</section>\n");

        // Dependencies + Stats: dev-mode only (engineer-focused).
        html.append("<section class=\"page\" data-page=\"dependencies\" data-modes=\"dev\">\n");
        html.append("  <h2 class=\"page-title\">Project Dependencies</h2>\n");
        appendTechnologies(html, result.getMetadata());
        html.append("</section>\n");

        html.append("<section class=\"page\" data-page=\"stats\" data-modes=\"dev\">\n");
        html.append("  <h2 class=\"page-title\">Codebase Statistics</h2>\n");
        appendStatsBar(html, result.getStats());
        appendSeveritySummary(html, result);
        html.append("</section>\n");

        appendFooter(html, result);
        html.append("</main>\n");
        html.append("</div>\n"); // app-layout

        html.append("\n</body>\n</html>");

        Files.write(reportFile, html.toString().getBytes(StandardCharsets.UTF_8));
        log.info("HTML report written to: {}", reportFile);
        return reportFile;
    }

    // ─── Sidebar + Top Bar ───────────────────────────────────────────────────

    /**
     * Left-rail navigation. Each menu item drives a page section via the
     * {@code data-page} attribute and the URL hash. Counts are pre-computed
     * server-side and shown as small badges so users can spot loaded sections
     * at a glance.
     */
    private void appendSidebar(StringBuilder html, AnalysisResult result) {
        long criticalCount = result.countBySeverity(Finding.Severity.CRITICAL);
        long highCount = result.countBySeverity(Finding.Severity.HIGH);
        int totalFindings = result.getFindings().size();
        long vulnCount = result.getFindings().stream()
                .filter(f -> f.getCategory() == Finding.Category.DEPENDENCY_VULNERABILITY).count();
        int migrationBlockers = result.getMigrationScore() != null
                ? result.getMigrationScore().getTotalBlockers() : 0;
        ArchitectureReport arch = result.getArchitectureReport();
        int archIssueCount = arch != null
                ? arch.getCycles().size() + arch.getViolations().size() + arch.getGodClasses().size() : 0;

        html.append("<nav class=\"sidebar\">\n");
        html.append("  <div class=\"sidebar-brand\">\n");
        html.append("    <div class=\"sidebar-brand-icon\"><img src=\"")
            .append(LOGO_ICON_DATA_URI)
            .append("\" alt=\"Code Owl\" width=\"40\" height=\"40\"/></div>\n");
        html.append("    <div class=\"sidebar-brand-text\">\n");
        html.append("      <div class=\"sidebar-brand-title\">Code Owl</div>\n");
        html.append("      <div class=\"sidebar-brand-sub\">").append(escHtml(CODE_OWL_VERSION)).append("</div>\n");
        html.append("    </div>\n");
        html.append("  </div>\n");

        // Mode selector — driven by JS, updates body[data-mode] which CSS uses
        // to show/hide everything tagged with data-modes.
        html.append("  <div class=\"sidebar-mode\">\n");
        html.append("    <label for=\"modeSelect\" class=\"sidebar-mode-label\">Audience view</label>\n");
        html.append("    <select id=\"modeSelect\" class=\"sidebar-mode-select\">\n");
        html.append("      <option value=\"dev\">Developer (full detail)</option>\n");
        html.append("      <option value=\"lead\">Tech lead (priority + summary)</option>\n");
        html.append("      <option value=\"exec\">Executive (KPIs + verdict)</option>\n");
        html.append("    </select>\n");
        html.append("  </div>\n");

        html.append("  <ul class=\"nav-menu\">\n");
        // The fifth arg below is `modes` — a space-separated list of modes in which
        // this nav item is visible. Items always visible omit it (default: all modes).
        appendNavLink(html, "overview",     "Overview",      "&#x1F3E0;", null, null,        true);
        appendNavLink(html, "priority",     "Priority",      "&#x1F3AF;",
                result.getPrioritizedFindings() != null && !result.getPrioritizedFindings().isEmpty()
                        ? String.valueOf(Math.min(10, result.getPrioritizedFindings().size())) : null,
                null, false);
        appendNavLink(html, "findings",     "Findings",      "&#x1F50D;", String.valueOf(totalFindings),
                "dev lead", false);
        appendNavLink(html, "migration",    "Migration",     "&#x1F680;",
                migrationBlockers > 0 ? String.valueOf(migrationBlockers) : null, null, false);
        appendNavLink(html, "architecture", "Architecture",  "&#x1F3D7;&#xFE0F;",
                archIssueCount > 0 ? String.valueOf(archIssueCount) : null, "dev lead", false);
        appendNavLink(html, "security",     "Security",      "&#x1F512;",
                vulnCount > 0 ? String.valueOf(vulnCount) : null, null, false);
        appendNavLink(html, "dependencies", "Dependencies",  "&#x1F4DA;", null, "dev",       false);
        appendNavLink(html, "stats",        "Project Stats", "&#x1F4CA;", null, "dev",       false);
        html.append("  </ul>\n");

        html.append("  <div class=\"sidebar-summary\">\n");
        if (criticalCount > 0) {
            html.append("    <div class=\"sidebar-pill sidebar-pill-critical\">")
                .append(criticalCount).append(" critical</div>\n");
        }
        if (highCount > 0) {
            html.append("    <div class=\"sidebar-pill sidebar-pill-high\">")
                .append(highCount).append(" high</div>\n");
        }
        html.append("  </div>\n");

        html.append("</nav>\n");
    }

    private void appendNavLink(StringBuilder html, String page, String label, String icon,
                                String badge, String modes, boolean active) {
        html.append("    <li").append(modes != null ? " data-modes=\"" + modes + "\"" : "").append(">\n");
        html.append("      <a href=\"#").append(page).append("\" class=\"nav-link")
            .append(active ? " active" : "").append("\" data-page=\"").append(page).append("\">\n");
        html.append("        <span class=\"nav-icon\">").append(icon).append("</span>\n");
        html.append("        <span class=\"nav-label\">").append(label).append("</span>\n");
        if (badge != null) {
            html.append("        <span class=\"nav-badge\">").append(badge).append("</span>\n");
        }
        html.append("      </a>\n");
        html.append("    </li>\n");
    }

    // ─── Executive Summary panel (B7) ────────────────────────────────────────

    /**
     * One-paragraph verdict + top risks + "if you fix N this quarter, debt drops by €X".
     * Only visible in exec mode (CSS hides it for dev/lead). Sits at the very top of
     * the Overview page so an exec opening the report sees this first.
     */
    private void appendExecutiveSummary(StringBuilder html, AnalysisResult result) {
        long critical = result.countBySeverity(Finding.Severity.CRITICAL);
        long high = result.countBySeverity(Finding.Severity.HIGH);
        long total = result.getFindings().size();
        long cveCount = result.getFindings().stream()
                .filter(f -> f.getCategory() == Finding.Category.DEPENDENCY_VULNERABILITY).count();
        TechDebt debt = result.getTechDebt();
        MigrationScore migration = result.getMigrationScore();
        ArchitectureReport arch = result.getArchitectureReport();
        List<PrioritizedFinding> top = result.getPrioritizedFindings();

        // ─── Verdict paragraph ───────────────────────────────────────────────
        String healthVerdict;
        String healthClass;
        if (critical == 0 && cveCount == 0 && (debt == null || debt.getTotalHours() < 200)) {
            healthVerdict = "Healthy";
            healthClass = "exec-verdict-good";
        } else if (critical < 50 && cveCount < 3
                && (debt == null || debt.getTotalHours() < 1500)) {
            healthVerdict = "Manageable";
            healthClass = "exec-verdict-warn";
        } else if (critical < 500 && cveCount < 10) {
            healthVerdict = "Concerning";
            healthClass = "exec-verdict-bad";
        } else {
            healthVerdict = "Critical";
            healthClass = "exec-verdict-critical";
        }

        html.append("  <section class=\"exec-summary\" data-modes=\"exec\">\n");
        html.append("    <div class=\"exec-header\">\n");
        html.append("      <h2 class=\"exec-title\">Executive Summary</h2>\n");
        html.append("      <span class=\"exec-verdict ").append(healthClass).append("\">")
            .append(healthVerdict).append("</span>\n");
        html.append("    </div>\n");
        html.append("    <p class=\"exec-project\">Codebase: <strong>")
            .append(escHtml(shortenPath(result.getProjectPath()))).append("</strong> &middot; ")
            .append("scanned ").append(escHtml(result.getTimestamp())).append("</p>\n");

        // ─── 1-paragraph narrative ───────────────────────────────────────────
        StringBuilder narrative = new StringBuilder();
        narrative.append("The scan surfaced <strong>").append(total).append("</strong> issues across ")
                .append(result.getStats().getTotalFiles()).append(" files, of which <strong>")
                .append(critical).append("</strong> are critical and <strong>")
                .append(high).append("</strong> are high severity. ");
        if (cveCount > 0) {
            narrative.append("There ").append(cveCount == 1 ? "is" : "are").append(" <strong>")
                    .append(cveCount).append("</strong> known dependency ")
                    .append(cveCount == 1 ? "CVE" : "CVEs").append(" — patch immediately. ");
        } else {
            narrative.append("No known dependency CVEs were detected. ");
        }
        if (debt != null && !debt.isEmpty()) {
            narrative.append("Estimated remediation cost: <strong>").append(debt.getCurrencySymbol())
                    .append(formatMoneyCompact(debt.getTotalCost())).append("</strong> (~")
                    .append(debt.getTotalHours()).append(" engineering hours at ")
                    .append(debt.getCurrencySymbol()).append((int) debt.getHourlyRate()).append("/hr). ");
        }
        if (migration != null && !migration.isEmpty()) {
            narrative.append("Migration readiness: <strong>").append(migration.getOverallScore())
                    .append("/100</strong> (").append(escHtml(migration.getVerdict()))
                    .append(", ~").append(migration.getEstimatedEffortHours()).append("h of work). ");
        }
        html.append("    <p class=\"exec-narrative\">").append(narrative).append("</p>\n");

        // ─── Top risks (3 bullets) ───────────────────────────────────────────
        List<String> risks = new ArrayList<String>();
        if (cveCount > 0) {
            risks.add("<strong>" + cveCount + " known dependency " + (cveCount == 1 ? "CVE" : "CVEs")
                    + "</strong> in third-party libraries — security exposure pending patch.");
        }
        if (arch != null && !arch.getCycles().isEmpty()) {
            risks.add("<strong>" + arch.getCycles().size() + " package dependency cycle"
                    + (arch.getCycles().size() == 1 ? "" : "s") + "</strong> "
                    + "block modularization and slow down independent team delivery.");
        }
        if (migration != null && migration.getOverallScore() < 70 && migration.getTotalBlockers() > 0) {
            risks.add("<strong>" + migration.getTotalBlockers() + " migration blocker"
                    + (migration.getTotalBlockers() == 1 ? "" : "s") + "</strong> stand between "
                    + "the codebase and the next LTS — " + escHtml(migration.getVerdict()) + ".");
        }
        long txCount = result.getFindings().stream()
                .filter(f -> f.getCategory() == Finding.Category.SPRING_TRANSACTIONAL_MISUSE).count();
        if (txCount >= 5) {
            risks.add("<strong>" + txCount + " transaction-management issues</strong> "
                    + "(silent data-integrity risk under failure or load).");
        }
        long eagerCount = result.getFindings().stream()
                .filter(f -> f.getCategory() == Finding.Category.HIBERNATE_EAGER_CYCLE
                          || f.getCategory() == Finding.Category.HIBERNATE_N_PLUS_ONE
                          || f.getCategory() == Finding.Category.HIBERNATE_FETCH_STRATEGY).count();
        if (eagerCount >= 20) {
            risks.add("<strong>" + eagerCount + " Hibernate fetch-strategy issues</strong> "
                    + "— production query performance trap, especially under list-page traffic.");
        }
        if (arch != null && !arch.getGodClasses().isEmpty()) {
            risks.add("<strong>" + arch.getGodClasses().size() + " god classes</strong> "
                    + "concentrate complexity and slow every change to those areas.");
        }

        if (!risks.isEmpty()) {
            html.append("    <div class=\"exec-risks\">\n");
            html.append("      <div class=\"exec-section-title\">Top risks</div>\n");
            html.append("      <ul class=\"exec-risk-list\">\n");
            int shown = 0;
            for (String r : risks) {
                if (shown++ >= 3) break;
                html.append("        <li>").append(r).append("</li>\n");
            }
            html.append("      </ul>\n");
            html.append("    </div>\n");
        }

        // ─── ROI bullet: "fix the top N this quarter and debt drops by €X" ──
        if (top != null && !top.isEmpty() && debt != null && !debt.isEmpty()) {
            int planSize = Math.min(5, top.size());
            int planHours = 0;
            double planCost = 0;
            for (int i = 0; i < planSize; i++) {
                planHours += top.get(i).getEstimatedHours();
                planCost += top.get(i).getEstimatedHours() * debt.getHourlyRate();
            }
            html.append("    <div class=\"exec-roi\">\n");
            html.append("      <div class=\"exec-section-title\">Quick-win plan</div>\n");
            html.append("      <p>Fixing the top ").append(planSize)
                .append(" priority items would take roughly <strong>")
                .append(planHours).append(" engineering hours</strong> (")
                .append(debt.getCurrencySymbol()).append(formatMoneyCompact(planCost))
                .append(") and tackles the highest-impact issues by churn-weighted risk score.</p>\n");
            html.append("    </div>\n");
        }

        html.append("    <p class=\"exec-footer-note\">Switch to <em>Developer</em> view in the sidebar "
                + "for full per-finding detail, file locations and remediation code.</p>\n");
        html.append("  </section>\n");
    }

    /**
     * Trend deltas vs the baseline snapshot. Surfaces "improvement vs regression"
     * narrative so execs/leads can see if their effort is moving the needle.
     * Rendered inside the Executive Summary panel; also visible in dev/lead modes
     * via the dedicated Overview KPI badges (separate render path).
     */
    private void appendTrendBlock(StringBuilder html, AnalysisResult result, BaselineSnapshot snap) {
        // Use the CURRENT raw snapshot (computed by the engine when a baseline is present)
        // so the comparison is raw-vs-raw, not post-suppression-vs-raw. Falls back to
        // post-suppression counts if for some reason currentSnapshot wasn't populated.
        BaselineSnapshot current = result.getCurrentSnapshot();
        int currentTotal;
        int currentCritical;
        int currentHigh;
        int currentCve;
        long currentDebtHours;
        double currentDebtCost;
        if (current != null && !current.isEmpty()) {
            currentTotal    = current.getTotalFindings();
            currentCritical = current.countSeverity(Finding.Severity.CRITICAL);
            currentHigh     = current.countSeverity(Finding.Severity.HIGH);
            currentCve      = current.getCveCount();
            currentDebtHours = current.getTechDebtHours();
            currentDebtCost  = current.getTechDebtCost();
        } else {
            // Fallback — old behaviour, post-suppression counts.
            currentTotal    = result.getFindings().size();
            currentCritical = (int) result.countBySeverity(Finding.Severity.CRITICAL);
            currentHigh     = (int) result.countBySeverity(Finding.Severity.HIGH);
            currentCve = 0;
            for (Finding f : result.getFindings()) {
                if (f.getCategory() == Finding.Category.DEPENDENCY_VULNERABILITY) currentCve++;
            }
            currentDebtHours = result.getTechDebt() != null ? result.getTechDebt().getTotalHours() : 0;
            currentDebtCost  = result.getTechDebt() != null ? result.getTechDebt().getTotalCost() : 0;
        }
        String sym = result.getTechDebt() != null ? result.getTechDebt().getCurrencySymbol() : "€";

        int deltaTotal    = currentTotal - snap.getTotalFindings();
        int deltaCritical = currentCritical - snap.countSeverity(Finding.Severity.CRITICAL);
        int deltaHigh     = currentHigh - snap.countSeverity(Finding.Severity.HIGH);
        int deltaCve      = currentCve - snap.getCveCount();
        long deltaHours   = currentDebtHours - snap.getTechDebtHours();
        double deltaCost  = currentDebtCost - snap.getTechDebtCost();

        // Overall direction — for the headline.
        boolean improved = deltaCritical < 0 || deltaCve < 0 || deltaCost < 0;
        boolean regressed = deltaCritical > 0 || deltaCve > 0 || deltaCost > 1000;
        String headline;
        String headlineClass;
        if (deltaTotal == 0 && deltaCritical == 0 && deltaCost == 0) {
            headline = "No change since baseline";
            headlineClass = "trend-neutral";
        } else if (improved && !regressed) {
            headline = "Improved since baseline";
            headlineClass = "trend-improved";
        } else if (regressed && !improved) {
            headline = "Regressed since baseline";
            headlineClass = "trend-regressed";
        } else {
            headline = "Mixed since baseline";
            headlineClass = "trend-mixed";
        }

        html.append("    <div class=\"exec-trend\">\n");
        html.append("      <div class=\"exec-section-title\">Trend ");
        html.append("<span class=\"trend-headline ").append(headlineClass).append("\">")
            .append(headline).append("</span>");
        if (snap.getGeneratedAt() != null && !snap.getGeneratedAt().isEmpty()) {
            html.append(" <span class=\"trend-baseline-date\">vs ")
                .append(escHtml(snap.getGeneratedAt().substring(0, Math.min(10, snap.getGeneratedAt().length()))))
                .append("</span>");
        }
        html.append("</div>\n");
        html.append("      <div class=\"trend-grid\">\n");
        appendTrendCell(html, "Total findings", currentTotal, deltaTotal, false, sym);
        appendTrendCell(html, "Critical",       currentCritical, deltaCritical, false, sym);
        appendTrendCell(html, "High",           currentHigh,     deltaHigh,     false, sym);
        appendTrendCell(html, "CVEs",           currentCve,      deltaCve,      false, sym);
        appendTrendCell(html, "Tech-debt hours", currentDebtHours, (int) deltaHours, false, sym);
        appendTrendCell(html, "Tech-debt cost", currentDebtCost, deltaCost, true, sym);
        html.append("      </div>\n");
        html.append("    </div>\n");
    }

    /**
     * Single cell in the trend grid. The delta is colored green (improvement = lower)
     * or red (regression = higher); zero shows as neutral.
     */
    private void appendTrendCell(StringBuilder html, String label, Number currentValue,
                                 Number delta, boolean isMoney, String sym) {
        String deltaClass;
        String arrow;
        double d = delta.doubleValue();
        if (d < 0) { deltaClass = "trend-down"; arrow = "&darr;"; }       // fewer = better
        else if (d > 0) { deltaClass = "trend-up"; arrow = "&uarr;"; }    // more = worse
        else { deltaClass = "trend-flat"; arrow = "&middot;"; }

        String formattedCurrent = isMoney
                ? sym + formatMoneyCompact(currentValue.doubleValue())
                : formatNumber(currentValue.doubleValue());
        String formattedDelta = isMoney
                ? sym + formatMoneyCompact(Math.abs(d))
                : formatNumber(Math.abs(d));

        html.append("        <div class=\"trend-cell\">\n");
        html.append("          <div class=\"trend-cell-label\">").append(escHtml(label)).append("</div>\n");
        html.append("          <div class=\"trend-cell-value\">").append(formattedCurrent).append("</div>\n");
        html.append("          <div class=\"trend-cell-delta ").append(deltaClass).append("\">")
            .append(arrow).append(' ').append(d == 0 ? "no change" : formattedDelta).append("</div>\n");
        html.append("        </div>\n");
    }

    /** Compact integer formatter for trend cells (5,060 not 5060, 1.2k for big numbers). */
    private static String formatNumber(double v) {
        if (v >= 100_000) return String.format("%.1fk", v / 1000.0);
        if (v >= 10_000)  return String.format("%,d", (long) v);
        return String.format("%,d", (long) v);
    }

    // ─── Overview page (landing) ─────────────────────────────────────────────

    /**
     * Landing page — high-density at-a-glance dashboard. Renders 4 KPI cards
     * (findings, critical issues, migration verdict, security CVE count) and a
     * "Top critical issues" teaser. Each KPI card is a clickable shortcut to
     * its detail page.
     */
    private void appendOverview(StringBuilder html, AnalysisResult result) {
        long totalFindings = result.getFindings().size();
        long critical = result.countBySeverity(Finding.Severity.CRITICAL);
        long high = result.countBySeverity(Finding.Severity.HIGH);
        long medium = result.countBySeverity(Finding.Severity.MEDIUM);
        long low = result.countBySeverity(Finding.Severity.LOW);
        long info = result.countBySeverity(Finding.Severity.INFO);
        long vulnCount = result.getFindings().stream()
                .filter(f -> f.getCategory() == Finding.Category.DEPENDENCY_VULNERABILITY).count();

        html.append("  <h2 class=\"page-title\">Overview</h2>\n");
        html.append("  <p class=\"page-subtitle\">High-level snapshot of <strong>")
            .append(escHtml(shortenPath(result.getProjectPath()))).append("</strong></p>\n");

        // ── Trend strip (only when a baseline snapshot is present) ───────────
        // Visible in all modes — devs care "did my PR add findings", leads care
        // "is the sprint moving the needle", execs care "are we improving".
        BaselineSnapshot snap = result.getBaselineSnapshot();
        if (snap != null && !snap.isEmpty()) {
            appendTrendBlock(html, result, snap);
        }

        // ── 4 KPI cards (Findings / Critical / Migration / Security) ─────────
        html.append("  <div class=\"kpi-grid\">\n");

        // 1) Total findings
        html.append("    <a class=\"kpi-card kpi-findings\" href=\"#findings\" data-page-link=\"findings\">\n");
        html.append("      <div class=\"kpi-icon\">&#x1F50D;</div>\n");
        html.append("      <div class=\"kpi-value\">").append(totalFindings).append("</div>\n");
        html.append("      <div class=\"kpi-label\">Total findings</div>\n");
        html.append("      <div class=\"kpi-sub\">across all analyzers</div>\n");
        html.append("    </a>\n");

        // 2) Critical issues
        String criticalClass = critical > 0 ? "kpi-critical kpi-alert" : "kpi-critical kpi-clean";
        html.append("    <a class=\"kpi-card ").append(criticalClass).append("\" href=\"#findings\" data-page-link=\"findings\" data-prefilter-severity=\"CRITICAL\">\n");
        html.append("      <div class=\"kpi-icon\">&#x26A0;&#xFE0F;</div>\n");
        html.append("      <div class=\"kpi-value\">").append(critical).append("</div>\n");
        html.append("      <div class=\"kpi-label\">Critical issues</div>\n");
        html.append("      <div class=\"kpi-sub\">").append(high).append(" high &middot; ")
            .append(medium).append(" medium</div>\n");
        html.append("    </a>\n");

        // 3) Migration readiness
        if (result.getMigrationScore() != null && !result.getMigrationScore().isEmpty()) {
            MigrationScore ms = result.getMigrationScore();
            String mvClass = verdictClass(ms.getOverallScore());
            html.append("    <a class=\"kpi-card kpi-migration ").append(mvClass).append("\" href=\"#migration\" data-page-link=\"migration\">\n");
            html.append("      <div class=\"kpi-icon\">&#x1F680;</div>\n");
            html.append("      <div class=\"kpi-value\">").append(ms.getOverallScore()).append("<span class=\"kpi-of\">/100</span></div>\n");
            html.append("      <div class=\"kpi-label\">Migration readiness</div>\n");
            html.append("      <div class=\"kpi-sub\">").append(escHtml(ms.getVerdict())).append(" &middot; ~")
                .append(ms.getEstimatedEffortHours()).append("h</div>\n");
            html.append("    </a>\n");
        } else {
            html.append("    <a class=\"kpi-card kpi-migration kpi-clean\" href=\"#migration\" data-page-link=\"migration\">\n");
            html.append("      <div class=\"kpi-icon\">&#x1F680;</div>\n");
            html.append("      <div class=\"kpi-value\">&#x2014;</div>\n");
            html.append("      <div class=\"kpi-label\">Migration readiness</div>\n");
            html.append("      <div class=\"kpi-sub\">No active migration path detected</div>\n");
            html.append("    </a>\n");
        }

        // 4) Security
        String secClass = vulnCount > 0 ? "kpi-security kpi-alert" : "kpi-security kpi-clean";
        html.append("    <a class=\"kpi-card ").append(secClass).append("\" href=\"#security\" data-page-link=\"security\">\n");
        html.append("      <div class=\"kpi-icon\">&#x1F512;</div>\n");
        html.append("      <div class=\"kpi-value\">").append(vulnCount).append("</div>\n");
        html.append("      <div class=\"kpi-label\">Dependency CVEs</div>\n");
        html.append("      <div class=\"kpi-sub\">").append(vulnCount == 0 ? "No known vulnerabilities" : "review immediately").append("</div>\n");
        html.append("    </a>\n");

        // 5) Tech debt (cost in configured currency)
        TechDebt debt = result.getTechDebt();
        if (debt != null && !debt.isEmpty()) {
            String sym = debt.getCurrencySymbol();
            html.append("    <a class=\"kpi-card kpi-debt\" href=\"#priority\" data-page-link=\"priority\">\n");
            html.append("      <div class=\"kpi-icon\">&#x1F4B0;</div>\n");
            html.append("      <div class=\"kpi-value\">").append(sym).append(formatMoneyCompact(debt.getTotalCost())).append("</div>\n");
            html.append("      <div class=\"kpi-label\">Tech debt</div>\n");
            html.append("      <div class=\"kpi-sub\">~").append(debt.getTotalHours()).append("h at ").append(sym)
                .append((int) debt.getHourlyRate()).append("/hr</div>\n");
            html.append("    </a>\n");
        }

        // 6) Top priority fix
        if (result.getPrioritizedFindings() != null && !result.getPrioritizedFindings().isEmpty()) {
            PrioritizedFinding top = result.getPrioritizedFindings().get(0);
            html.append("    <a class=\"kpi-card kpi-priority\" href=\"#priority\" data-page-link=\"priority\">\n");
            html.append("      <div class=\"kpi-icon\">&#x1F3AF;</div>\n");
            html.append("      <div class=\"kpi-value\">").append(top.getImpactScore()).append("<span class=\"kpi-of\">/100</span></div>\n");
            html.append("      <div class=\"kpi-label\">Top priority fix</div>\n");
            html.append("      <div class=\"kpi-sub\">~").append(top.getEstimatedHours()).append("h &middot; ")
                .append(top.getEffort()).append(" effort</div>\n");
            html.append("    </a>\n");
        }

        html.append("  </div>\n");

        // ── Secondary row: codebase stats + severity breakdown bar ───────────
        // dev + lead see this; exec sees the verdict panel instead.
        html.append("  <div class=\"overview-grid\" data-modes=\"dev lead\">\n");

        // Codebase composition
        AnalysisStats st = result.getStats();
        html.append("    <div class=\"overview-card\">\n");
        html.append("      <div class=\"overview-card-title\">Codebase</div>\n");
        html.append("      <div class=\"overview-stat-row\"><span>Java files scanned</span><strong>")
            .append(st.getTotalFiles()).append("</strong></div>\n");
        if (st.getParseErrors() > 0) {
            html.append("      <div class=\"overview-stat-row warn\"><span>Parse errors</span><strong>")
                .append(st.getParseErrors()).append("</strong></div>\n");
        }
        html.append("      <div class=\"overview-stat-row\"><span>JPA entities</span><strong>")
            .append(st.getEntityCount()).append("</strong></div>\n");
        html.append("      <div class=\"overview-stat-row\"><span>Spring repositories</span><strong>")
            .append(st.getRepositoryCount()).append("</strong></div>\n");
        html.append("      <div class=\"overview-stat-row\"><span>Spring services</span><strong>")
            .append(st.getServiceCount()).append("</strong></div>\n");
        html.append("      <div class=\"overview-stat-row\"><span>REST controllers</span><strong>")
            .append(st.getControllerCount()).append("</strong></div>\n");
        html.append("    </div>\n");

        // Severity distribution
        html.append("    <div class=\"overview-card\">\n");
        html.append("      <div class=\"overview-card-title\">Severity distribution</div>\n");
        appendOverviewSeverityBar(html, "Critical", critical, totalFindings, "sev-critical");
        appendOverviewSeverityBar(html, "High",     high,     totalFindings, "sev-high");
        appendOverviewSeverityBar(html, "Medium",   medium,   totalFindings, "sev-medium");
        appendOverviewSeverityBar(html, "Low",      low,      totalFindings, "sev-low");
        appendOverviewSeverityBar(html, "Info",     info,     totalFindings, "sev-info");
        html.append("    </div>\n");
        html.append("  </div>\n");

        // ── Top critical issue TYPES (deduped by title) ──────────────────────
        // Group critical findings by title so we show ONE example per issue type
        // (e.g. "Bidirectional EAGER cycle" appears once with count=4, not 4 rows).
        // Pick the first occurrence as the representative example.
        Map<String, List<Finding>> criticalByTitle = result.getFindings().stream()
                .filter(f -> f.getSeverity() == Finding.Severity.CRITICAL)
                .collect(Collectors.groupingBy(Finding::getTitle, LinkedHashMap::new, Collectors.toList()));

        if (!criticalByTitle.isEmpty()) {
            // Detail card — engineers care, exec sees a higher-level verdict instead.
            html.append("  <div class=\"overview-card overview-top\" data-modes=\"dev lead\">\n");
            html.append("    <div class=\"overview-card-title\">Top critical issue types &mdash; ")
                .append(criticalByTitle.size()).append(" distinct, ").append(critical).append(" total occurrences</div>\n");
            html.append("    <ul class=\"overview-top-list\">\n");

            int rendered = 0;
            for (Map.Entry<String, List<Finding>> entry : criticalByTitle.entrySet()) {
                if (rendered++ >= 5) break;
                Finding example = entry.getValue().get(0);
                int count = entry.getValue().size();
                html.append("      <li class=\"overview-top-item\">\n");
                html.append("        <span class=\"sev-badge sev-critical\">CRITICAL</span>\n");
                html.append("        <span class=\"overview-top-title\">").append(escHtml(entry.getKey())).append("</span>\n");
                if (count > 1) {
                    html.append("        <span class=\"overview-top-count\">").append(count)
                        .append(" occurrences</span>\n");
                }
                if (example.getLocation() != null && example.getLocation().getFilePath() != null) {
                    html.append("        <span class=\"overview-top-loc\"><code>")
                        .append(escHtml(example.getLocation().getFilePath()))
                        .append("</code>:").append(example.getLocation().getStartLine()).append("</span>\n");
                }
                html.append("      </li>\n");
            }
            html.append("    </ul>\n");
            html.append("    <a href=\"#findings\" class=\"overview-cta\" data-page-link=\"findings\" data-prefilter-severity=\"CRITICAL\">See all critical findings &rarr;</a>\n");
            html.append("  </div>\n");
        }
    }

    private void appendOverviewSeverityBar(StringBuilder html, String label, long count, long total, String sevClass) {
        if (total == 0) total = 1;
        int pct = (int) Math.round((count * 100.0) / total);
        html.append("      <div class=\"overview-sev-row\">\n");
        html.append("        <span class=\"overview-sev-label\">").append(label).append("</span>\n");
        html.append("        <div class=\"overview-sev-bar\"><div class=\"overview-sev-bar-fill ").append(sevClass)
            .append("\" style=\"width:").append(pct).append("%\"></div></div>\n");
        html.append("        <span class=\"overview-sev-count\">").append(count).append("</span>\n");
        html.append("      </div>\n");
    }

    // ─── Priority &amp; Tech Debt page (B4 + B5) ──────────────────────────────

    /**
     * Manager-dashboard page: top-N priority fixes (ranked by impact score)
     * plus a tech-debt breakdown table (hours and $ per category).
     * Hidden when no findings at all.
     */
    private void appendPrioritization(StringBuilder html, AnalysisResult result) {
        List<PrioritizedFinding> pf = result.getPrioritizedFindings();
        TechDebt debt = result.getTechDebt();
        boolean gitOn = result.getGitHistory() != null && result.getGitHistory().isAvailable();

        if ((pf == null || pf.isEmpty()) && (debt == null || debt.isEmpty())) {
            html.append("  <p>No findings to prioritize.</p>\n");
            return;
        }

        // Headline: total debt + git status
        html.append("  <div class=\"priority-headline\">\n");
        if (debt != null && !debt.isEmpty()) {
            String sym = debt.getCurrencySymbol();
            html.append("    <div class=\"priority-debt\">\n");
            html.append("      <div class=\"priority-debt-value\">").append(sym).append(formatMoneyCompact(debt.getTotalCost())).append("</div>\n");
            html.append("      <div class=\"priority-debt-label\">estimated tech debt &middot; ")
                .append(debt.getTotalHours()).append(" hours at ").append(sym)
                .append((int) debt.getHourlyRate()).append("/hr &middot; ")
                .append(escHtml(debt.getCurrencyCode())).append("</div>\n");
            html.append("    </div>\n");
        }
        html.append("    <div class=\"priority-git\">\n");
        if (gitOn) {
            html.append("      <span class=\"git-pill git-pill-on\">&#x2714; Git-aware</span>\n");
            html.append("      <span class=\"git-info\">").append(result.getGitHistory().getCommitCountByFile().size())
                .append(" files with commits in last ")
                .append(result.getGitHistory().getWindowMonths()).append(" months</span>\n");
        } else {
            html.append("      <span class=\"git-pill git-pill-off\">Git unavailable</span>\n");
            html.append("      <span class=\"git-info\">Impact scores use severity + confidence only. Run from a git repo to weight by file churn.</span>\n");
        }
        html.append("    </div>\n");
        html.append("  </div>\n");

        // Top N priority fixes
        if (pf != null && !pf.isEmpty()) {
            int topN = Math.min(20, pf.size());
            html.append("  <div class=\"overview-card priority-top\">\n");
            html.append("    <div class=\"overview-card-title\">Top ").append(topN).append(" priority fixes (ranked by impact)</div>\n");
            html.append("    <table class=\"priority-table\">\n");
            html.append("      <thead><tr><th>#</th><th>Impact</th><th>Severity</th><th>Issue</th><th>File</th><th>Effort</th><th>Why</th></tr></thead>\n");
            html.append("      <tbody>\n");
            int rank = 1;
            for (PrioritizedFinding p : pf.subList(0, topN)) {
                Finding f = p.getFinding();
                String sevClass = f.getSeverity().name().toLowerCase();
                String filePath = f.getLocation() != null ? f.getLocation().getFilePath() : "";
                int line = f.getLocation() != null ? f.getLocation().getStartLine() : 0;
                html.append("        <tr>\n");
                html.append("          <td class=\"pr-rank\">").append(rank++).append("</td>\n");
                html.append("          <td class=\"pr-impact\"><div class=\"pr-impact-bar\"><div class=\"pr-impact-fill\" style=\"width:")
                    .append(p.getImpactScore()).append("%\"></div></div><span>")
                    .append(p.getImpactScore()).append("</span></td>\n");
                html.append("          <td><span class=\"sev-badge sev-").append(sevClass).append("\">")
                    .append(f.getSeverity().name()).append("</span></td>\n");
                html.append("          <td class=\"pr-title\">").append(escHtml(f.getTitle())).append("</td>\n");
                html.append("          <td class=\"pr-file\" title=\"").append(escAttr(filePath)).append("\"><code>")
                    .append(escHtml(filePath)).append("</code>:").append(line).append("</td>\n");
                html.append("          <td><span class=\"pr-effort pr-effort-").append(p.getEffort().name().toLowerCase())
                    .append("\">").append(p.getEffort()).append(" &middot; ~").append(p.getEstimatedHours()).append("h</span></td>\n");
                html.append("          <td class=\"pr-reason\">").append(escHtml(p.getImpactReason())).append("</td>\n");
                html.append("        </tr>\n");
            }
            html.append("      </tbody>\n");
            html.append("    </table>\n");
            html.append("  </div>\n");
        }

        // Debt breakdown by category
        if (debt != null && !debt.isEmpty()) {
            String sym = debt.getCurrencySymbol();
            html.append("  <div class=\"overview-card\">\n");
            html.append("    <div class=\"overview-card-title\">Tech debt by category</div>\n");
            html.append("    <table class=\"priority-table\">\n");
            html.append("      <thead><tr><th>Category</th><th>Findings</th><th>Hours</th><th>Cost (")
                .append(escHtml(debt.getCurrencyCode())).append(")</th><th>% of debt</th></tr></thead>\n");
            html.append("      <tbody>\n");
            int totalH = Math.max(1, debt.getTotalHours());
            for (TechDebt.CategoryDebt cd : debt.getByCategory().values()) {
                int pct = (int) Math.round((cd.hours * 100.0) / totalH);
                html.append("        <tr>\n");
                html.append("          <td>").append(escHtml(formatCategory(cd.category))).append("</td>\n");
                html.append("          <td class=\"pr-num\">").append(cd.findings).append("</td>\n");
                html.append("          <td class=\"pr-num\">").append(cd.hours).append("</td>\n");
                html.append("          <td class=\"pr-num\">").append(sym).append(formatMoneyCompact(cd.cost)).append("</td>\n");
                html.append("          <td><div class=\"pr-impact-bar\"><div class=\"pr-impact-fill\" style=\"width:")
                    .append(pct).append("%\"></div></div><span>").append(pct).append("%</span></td>\n");
                html.append("        </tr>\n");
            }
            html.append("      </tbody>\n");
            html.append("    </table>\n");
            html.append("  </div>\n");
        }
    }

    /** Compact money formatter (currency-agnostic): 1234567 → "1.2M", 12345 → "12.3k". */
    private static String formatMoneyCompact(double v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000.0);
        if (v >= 1_000)     return String.format("%.1fk", v / 1_000.0);
        return String.format("%.0f", v);
    }

    /** Top bar shown above every page — project name + timestamp + duration. */
    private void appendTopBar(StringBuilder html, AnalysisResult result) {
        html.append("<div class=\"top-bar\">\n");
        html.append("  <div class=\"top-bar-project\">").append(escHtml(shortenPath(result.getProjectPath()))).append("</div>\n");
        html.append("  <div class=\"top-bar-meta\">\n");
        html.append("    <span class=\"top-bar-time\">").append(escHtml(result.getTimestamp())).append("</span>\n");
        html.append("    <span class=\"top-bar-duration\">analysis ").append(result.getStats().getDurationMs()).append(" ms</span>\n");
        html.append("  </div>\n");
        html.append("</div>\n");
    }

    private String shortenPath(String path) {
        if (path == null) return "";
        int len = path.length();
        if (len <= 70) return path;
        return "..." + path.substring(len - 67);
    }

    private void appendHead(StringBuilder html) {
        html.append("<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>Code Owl Report</title>\n");
        // Inline favicon — same 64×64 base64 PNG used for the sidebar brand.
        // Keeps the report self-contained (no external requests).
        html.append("<link rel=\"icon\" type=\"image/png\" href=\"").append(LOGO_ICON_DATA_URI).append("\">\n");
        html.append("<style>\n");
        html.append(CSS);
        html.append("</style>\n");
        html.append("</head>\n");
    }

    private void appendHeader(StringBuilder html, AnalysisResult result) {
        html.append("<div class=\"header\">\n");
        html.append("  <div class=\"header-content\">\n");
        html.append("    <div class=\"header-left\">\n");
        html.append("      <h1>Code Owl</h1>\n");
        html.append("      <p class=\"project-path\">").append(escHtml(result.getProjectPath())).append("</p>\n");
        html.append("    </div>\n");
        html.append("    <div class=\"header-right\">\n");
        html.append("      <span class=\"timestamp\">").append(escHtml(result.getTimestamp())).append("</span>\n");
        html.append("      <span class=\"duration\">").append(result.getStats().getDurationMs()).append("ms</span>\n");
        html.append("    </div>\n");
        html.append("  </div>\n");
        html.append("</div>\n");
    }

    private void appendStatsBar(StringBuilder html, AnalysisStats stats) {
        html.append("<div class=\"stats-bar\">\n");
        appendStatCard(html, "Files Scanned", String.valueOf(stats.getTotalFiles()),
                stats.getParseErrors() > 0 ? stats.getParseErrors() + " errors" : "0 errors");
        appendStatCard(html, "Entities", String.valueOf(stats.getEntityCount()), null);
        appendStatCard(html, "Repositories", String.valueOf(stats.getRepositoryCount()), null);
        appendStatCard(html, "Services", String.valueOf(stats.getServiceCount()), null);
        appendStatCard(html, "Controllers", String.valueOf(stats.getControllerCount()), null);
        html.append("</div>\n");
    }

    private void appendStatCard(StringBuilder html, String label, String value, String sub) {
        html.append("<div class=\"stat-card\">\n");
        html.append("  <div class=\"stat-value\">").append(value).append("</div>\n");
        html.append("  <div class=\"stat-label\">").append(label).append("</div>\n");
        if (sub != null) {
            html.append("  <div class=\"stat-sub\">").append(escHtml(sub)).append("</div>\n");
        }
        html.append("</div>\n");
    }

    /**
     * Renders the Dependencies page using {@link FrameworkRegistry} to detect
     * frameworks via BOTH {@code <properties>} keys and {@code groupId:artifactId}
     * coords. This catches Spring/Hibernate/JSF/CXF/etc. on multi-module Maven
     * projects where the root pom only declares versions as properties.
     */
    private void appendTechnologies(StringBuilder html, ProjectMetadata metadata) {
        if (metadata == null || metadata.getBuildTool() == null) return;

        html.append("<div class=\"tech-section\">\n");

        // Build / runtime header — always shown
        html.append("  <h3>Build &amp; Runtime</h3>\n");
        html.append("  <div class=\"tech-grid\">\n");
        appendTechCard(html, "Build tool", metadata.getBuildTool(), null, "build");
        appendTechCard(html, "Java version",
                metadata.getJavaVersion() != null ? metadata.getJavaVersion() : "Unknown",
                null, "runtime");
        html.append("  </div>\n");

        // Detect everything via the registry, then group by category in registry order.
        List<FrameworkRegistry.Detection> detected = FrameworkRegistry.detect(metadata);
        Map<FrameworkRegistry.Category, List<FrameworkRegistry.Detection>> byCategory =
                new LinkedHashMap<>();
        for (FrameworkRegistry.Category c : FrameworkRegistry.Category.values()) {
            byCategory.put(c, new ArrayList<>());
        }
        for (FrameworkRegistry.Detection d : detected) {
            byCategory.get(d.category).add(d);
        }

        boolean any = false;
        for (Map.Entry<FrameworkRegistry.Category, List<FrameworkRegistry.Detection>> e : byCategory.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            any = true;
            html.append("  <h3>").append(escHtml(e.getKey().label())).append("</h3>\n");
            html.append("  <div class=\"tech-grid\">\n");
            for (FrameworkRegistry.Detection d : e.getValue()) {
                appendTechCard(html, d.name, d.version, d.source, categoryClass(d.category));
            }
            html.append("  </div>\n");
        }

        if (!any) {
            html.append("  <p class=\"tech-total\">No known frameworks detected. " +
                    "Check that the project's <code>pom.xml</code> declares standard dependency coordinates " +
                    "or version properties (e.g. <code>spring.version</code>, <code>hibernate.version</code>).</p>\n");
        }

        html.append("  <div class=\"tech-total\">Detected <strong>").append(detected.size())
            .append("</strong> known frameworks/libraries from <strong>")
            .append(metadata.getDependencies().size()).append("</strong> direct dependencies and <strong>")
            .append(metadata.getProperties() != null ? metadata.getProperties().size() : 0)
            .append("</strong> pom properties.</div>\n");

        html.append("</div>\n");
    }

    private static String categoryClass(FrameworkRegistry.Category c) {
        switch (c) {
            case FRAMEWORK:   return "framework";
            case PERSISTENCE: return "framework";
            case WEB_UI:      return "framework";
            case MESSAGING:   return "framework";
            case LIBRARY:     return "library";
            case LOGGING:     return "logging";
            case JSON:        return "library";
            case TESTING:     return "testing";
            case DATABASE:    return "database";
            case BUILD:       return "build";
            default:          return "library";
        }
    }

    private void appendTechCard(StringBuilder html, String label, String value, String source, String type) {
        String typeClass = type != null ? " tech-" + type : "";
        html.append("    <div class=\"tech-card").append(typeClass).append("\">\n");
        html.append("      <div class=\"tech-label\">").append(escHtml(label)).append("</div>\n");
        html.append("      <div class=\"tech-value\">").append(escHtml(value != null ? value : "N/A")).append("</div>\n");
        if (source != null && !source.isEmpty()) {
            html.append("      <div class=\"tech-source\">").append(escHtml(source)).append("</div>\n");
        }
        html.append("    </div>\n");
    }

    private void appendSecuritySummary(StringBuilder html, AnalysisResult result) {
        List<Finding> vulns = new ArrayList<>();
        for (Finding f : result.getFindings()) {
            if (f.getCategory() == Finding.Category.DEPENDENCY_VULNERABILITY) {
                vulns.add(f);
            }
        }
        if (vulns.isEmpty()) return;

        // Count by severity
        int critical = 0, high = 0, medium = 0, low = 0;
        for (Finding f : vulns) {
            switch (f.getSeverity()) {
                case CRITICAL: critical++; break;
                case HIGH: high++; break;
                case MEDIUM: medium++; break;
                default: low++; break;
            }
        }

        html.append("<div class=\"security-section\">\n");
        html.append("  <div class=\"security-header\">\n");
        html.append("    <h2>Security Vulnerabilities</h2>\n");
        html.append("    <span class=\"security-total\">").append(vulns.size())
                .append(vulns.size() == 1 ? " vulnerability" : " vulnerabilities").append(" found</span>\n");
        html.append("  </div>\n");

        // Severity breakdown badges
        html.append("  <div class=\"security-badges\">\n");
        if (critical > 0) {
            html.append("    <span class=\"sec-badge sec-critical\">").append(critical).append(" CRITICAL</span>\n");
        }
        if (high > 0) {
            html.append("    <span class=\"sec-badge sec-high\">").append(high).append(" HIGH</span>\n");
        }
        if (medium > 0) {
            html.append("    <span class=\"sec-badge sec-medium\">").append(medium).append(" MEDIUM</span>\n");
        }
        if (low > 0) {
            html.append("    <span class=\"sec-badge sec-low\">").append(low).append(" LOW</span>\n");
        }
        html.append("  </div>\n");

        // Top vulnerability cards (show up to 5 most severe)
        vulns.sort(Comparator.comparingInt(f -> f.getSeverity().ordinal()));
        int shown = Math.min(vulns.size(), 5);
        html.append("  <div class=\"security-cards\">\n");
        for (int i = 0; i < shown; i++) {
            Finding v = vulns.get(i);
            String sevClass = v.getSeverity().name().toLowerCase();
            html.append("    <div class=\"security-card security-card-").append(sevClass).append("\">\n");
            html.append("      <div class=\"security-card-header\">\n");
            html.append("        <span class=\"sev-badge sev-").append(sevClass).append("\">")
                    .append(v.getSeverity().name()).append("</span>\n");
            html.append("        <span class=\"security-cve\">").append(escHtml(v.getTitle())).append("</span>\n");
            html.append("      </div>\n");
            html.append("      <p class=\"security-desc\">").append(escHtml(v.getDescription())).append("</p>\n");
            html.append("    </div>\n");
        }
        if (vulns.size() > 5) {
            html.append("    <div class=\"security-more\">... and ")
                    .append(vulns.size() - 5).append(" more (see Dependency Vulnerability section below)</div>\n");
        }
        html.append("  </div>\n");

        html.append("</div>\n");
    }

    /**
     * Renders the Migration Readiness section: overall score, verdict, per-area
     * gauges (Java / Spring Boot / Hibernate), blocker counts, and effort estimate.
     * Hidden entirely when no migration paths apply to the project.
     */
    private void appendMigrationReadiness(StringBuilder html, AnalysisResult result) {
        MigrationScore score = result.getMigrationScore();
        if (score == null || score.isEmpty()) return;

        String verdict = score.getVerdict();
        String verdictClass = verdictClass(score.getOverallScore());

        html.append("<div class=\"migration-section\">\n");
        html.append("  <div class=\"migration-header\">\n");
        html.append("    <h2>Migration Readiness</h2>\n");
        html.append("    <div class=\"migration-headline\">\n");
        html.append("      <div class=\"migration-gauge migration-gauge-overall ").append(verdictClass)
                .append("\" title=\"").append(escAttr(score.getScoreExplanation())).append("\">\n");
        html.append("        <div class=\"migration-gauge-score\">").append(score.getOverallScore()).append("</div>\n");
        html.append("        <div class=\"migration-gauge-label\">of 100</div>\n");
        html.append("      </div>\n");
        html.append("      <div class=\"migration-verdict\">\n");
        html.append("        <div class=\"migration-verdict-text ").append(verdictClass).append("\">")
                .append(escHtml(verdict)).append("</div>\n");
        html.append("        <div class=\"migration-meta\">")
                .append(score.getTotalBlockers()).append(" blockers &middot; ~")
                .append(score.getEstimatedEffortHours()).append("h estimated effort (~")
                .append(Math.max(1, score.getEstimatedEffortHours() / 8)).append(" person-days)")
                .append("</div>\n");
        html.append("      </div>\n");
        html.append("    </div>\n");
        html.append("    <div class=\"migration-explainer\">\n");
        html.append("      <strong>What the score means:</strong> ").append(escHtml(score.getScoreExplanation())).append("\n");
        html.append("    </div>\n");
        html.append("  </div>\n");

        html.append("  <div class=\"migration-areas\">\n");
        for (MigrationScore.AreaScore area : score.getAreas().values()) {
            String areaClass = verdictClass(area.getScore());
            html.append("    <div class=\"migration-area ").append(areaClass).append("\">\n");
            html.append("      <div class=\"migration-area-head\">\n");
            html.append("        <span class=\"migration-area-name\">").append(escHtml(area.getArea())).append("</span>\n");
            html.append("        <span class=\"migration-area-version\">").append(escHtml(area.getSourceVersion()))
                    .append(" &rarr; ").append(escHtml(area.getTargetVersion())).append("</span>\n");
            html.append("      </div>\n");
            html.append("      <div class=\"migration-area-bar\">\n");
            html.append("        <div class=\"migration-area-bar-fill\" style=\"width:")
                    .append(area.getScore()).append("%\"></div>\n");
            html.append("      </div>\n");
            html.append("      <div class=\"migration-area-stats\">\n");
            html.append("        <span class=\"migration-area-score\">").append(area.getScore()).append("/100</span>\n");
            if (area.getCritical() > 0)
                html.append("        <span class=\"migration-pill mp-critical\">").append(area.getCritical()).append(" critical</span>\n");
            if (area.getHigh() > 0)
                html.append("        <span class=\"migration-pill mp-high\">").append(area.getHigh()).append(" high</span>\n");
            if (area.getMedium() > 0)
                html.append("        <span class=\"migration-pill mp-medium\">").append(area.getMedium()).append(" medium</span>\n");
            if (area.getLow() > 0)
                html.append("        <span class=\"migration-pill mp-low\">").append(area.getLow()).append(" low</span>\n");
            html.append("        <span class=\"migration-effort\">~").append(area.getEffortHours()).append("h</span>\n");
            html.append("      </div>\n");
            html.append("    </div>\n");
        }
        html.append("  </div>\n");
        html.append("  <button class=\"migration-cta\" onclick=\"focusMigrationBlockers()\">\n");
        html.append("    See every blocker &mdash; file locations + recommended fixes &rarr;\n");
        html.append("  </button>\n");
        html.append("</div>\n");
    }

    private String verdictClass(int score) {
        if (score >= 90) return "verdict-ready";
        if (score >= 70) return "verdict-mostly";
        if (score >= 40) return "verdict-work";
        return "verdict-rewrite";
    }

    // ─── Architecture page (B3) ─────────────────────────────────────────────

    /**
     * Renders the dedicated Architecture page: package-graph summary,
     * dependency cycles, layering violations, and god classes. Each section
     * degrades gracefully when there's nothing to show.
     */
    private void appendArchitecture(StringBuilder html, AnalysisResult result) {
        ArchitectureReport report = result.getArchitectureReport();
        if (report == null || report.isEmpty()) {
            html.append("  <div class=\"empty-state\">\n");
            html.append("    <p>No architectural issues detected. ");
            html.append("Either the codebase has clean modular structure, or it doesn't follow ");
            html.append("conventional layer naming (controller/service/repository).</p>\n");
            html.append("  </div>\n");
            return;
        }

        PackageGraph graph = report.getGraph();
        int totalEdges = 0;
        for (PackageNode n : graph.getNodes()) totalEdges += n.getEdges().size();

        // ── Summary strip: 4 KPIs at a glance ────────────────────────────
        html.append("  <p class=\"page-subtitle\">Package coupling, layering integrity and concentration of complexity.</p>\n");
        html.append("  <div class=\"arch-kpis\">\n");
        appendArchKpi(html, String.valueOf(graph.size()), "internal packages", "neutral");
        appendArchKpi(html, String.valueOf(totalEdges), "internal edges", "neutral");
        appendArchKpi(html, String.valueOf(report.getCycles().size()), "dependency cycles",
                report.getCycles().isEmpty() ? "clean" : "alert");
        appendArchKpi(html, String.valueOf(report.getViolations().size()), "layering violations",
                report.getViolations().isEmpty() ? "clean" : "warn");
        appendArchKpi(html, String.valueOf(report.getGodClasses().size()), "god classes",
                report.getGodClasses().isEmpty() ? "clean" : "warn");
        html.append("  </div>\n");

        // ── Cycles ────────────────────────────────────────────────────────
        html.append("  <section class=\"arch-section\">\n");
        html.append("    <h3>&#x1F300; Package Dependency Cycles</h3>\n");
        if (report.getCycles().isEmpty()) {
            html.append("    <p class=\"arch-empty\">No cycles found — the package graph is acyclic. ");
            html.append("Good architectural hygiene.</p>\n");
        } else {
            html.append("    <p class=\"arch-help\">Each block below is a strongly-connected component: ");
            html.append("every package in the block transitively depends on the others. Cycles prevent ");
            html.append("independent compilation, reuse and testing.</p>\n");
            int idx = 1;
            int shown = 0;
            for (List<String> scc : report.getCycles()) {
                if (shown >= 10) {
                    html.append("    <p class=\"arch-more\">+ ").append(report.getCycles().size() - shown)
                        .append(" more cycle(s) — see Findings page (category ARCHITECTURE_CYCLE)</p>\n");
                    break;
                }
                html.append("    <div class=\"arch-cycle\">\n");
                html.append("      <div class=\"arch-cycle-header\">Cycle #").append(idx++)
                    .append(" &middot; <strong>").append(scc.size()).append("</strong> packages</div>\n");
                html.append("      <ul class=\"arch-cycle-list\">\n");
                for (String pkg : scc) {
                    html.append("        <li><code>").append(escHtml(pkg)).append("</code></li>\n");
                }
                html.append("      </ul>\n");
                // Show evidence — concrete imports that close the loop
                List<String> evidence = collectCycleEvidence(graph, scc, 4);
                if (!evidence.isEmpty()) {
                    html.append("      <details class=\"arch-evidence\">\n");
                    html.append("        <summary>Closing imports (sample)</summary>\n");
                    html.append("        <ul class=\"arch-imports\">\n");
                    for (String ev : evidence) {
                        html.append("          <li><code>").append(escHtml(ev)).append("</code></li>\n");
                    }
                    html.append("        </ul>\n");
                    html.append("      </details>\n");
                }
                html.append("    </div>\n");
                shown++;
            }
        }
        html.append("  </section>\n");

        // ── Layering violations ───────────────────────────────────────────
        html.append("  <section class=\"arch-section\">\n");
        html.append("    <h3>&#x1F3DB;&#xFE0F; Layering Violations</h3>\n");
        if (report.getViolations().isEmpty()) {
            html.append("    <p class=\"arch-empty\">No conventional layering violations detected ");
            html.append("(controller &rarr; service &rarr; repository &rarr; entity).</p>\n");
        } else {
            html.append("    <p class=\"arch-help\">Calls that invert or skip the standard onion layering. ");
            html.append("Only packages with recognizable layer names are checked.</p>\n");
            html.append("    <table class=\"arch-violations\">\n");
            html.append("      <thead><tr><th>From</th><th>To</th><th>Layers</th><th>Edges</th><th>Reason</th></tr></thead>\n");
            html.append("      <tbody>\n");
            int shown = 0;
            for (ArchitectureReport.LayeringViolation v : report.getViolations()) {
                if (shown >= 25) {
                    html.append("        <tr><td colspan=\"5\" class=\"arch-more\">+ ")
                        .append(report.getViolations().size() - shown)
                        .append(" more — see Findings page (category ARCHITECTURE_LAYERING)</td></tr>\n");
                    break;
                }
                html.append("        <tr>\n");
                html.append("          <td><code>").append(escHtml(v.getFromPackage())).append("</code></td>\n");
                html.append("          <td><code>").append(escHtml(v.getToPackage())).append("</code></td>\n");
                html.append("          <td><span class=\"layer-badge layer-").append(v.getFromLayer().name().toLowerCase())
                    .append("\">").append(v.getFromLayer()).append("</span> &rarr; ")
                    .append("<span class=\"layer-badge layer-").append(v.getToLayer().name().toLowerCase())
                    .append("\">").append(v.getToLayer()).append("</span></td>\n");
                html.append("          <td class=\"arch-numeric\">").append(v.getEdgeWeight()).append("</td>\n");
                html.append("          <td>").append(escHtml(v.getReason())).append("</td>\n");
                html.append("        </tr>\n");
                shown++;
            }
            html.append("      </tbody>\n");
            html.append("    </table>\n");
        }
        html.append("  </section>\n");

        // ── God classes ───────────────────────────────────────────────────
        html.append("  <section class=\"arch-section\">\n");
        html.append("    <h3>&#x1F47E; God Classes</h3>\n");
        if (report.getGodClasses().isEmpty()) {
            html.append("    <p class=\"arch-empty\">No classes exceed the complexity thresholds ");
            html.append("(&ge; 40 methods, &ge; 800 LOC, or &ge; 25 outgoing dependencies).</p>\n");
        } else {
            html.append("    <p class=\"arch-help\">Classes that concentrate too much responsibility. ");
            html.append("Ranked by composite weight (methods + LOC + fan-out + Spring stereotype).</p>\n");
            html.append("    <table class=\"arch-god\">\n");
            html.append("      <thead><tr><th>Class</th><th>Package</th><th>Methods</th><th>LOC</th>")
                .append("<th>Fan-out</th><th>Fields</th><th>Stereotype</th></tr></thead>\n");
            html.append("      <tbody>\n");
            for (ClassMetric m : report.getGodClasses()) {
                html.append("        <tr>\n");
                html.append("          <td><code>").append(escHtml(m.getClassName())).append("</code>");
                html.append(" <span class=\"arch-file\">").append(escHtml(m.getFilePath()))
                    .append(":").append(m.getBeginLine()).append("</span></td>\n");
                html.append("          <td><code>").append(escHtml(m.getPackageName())).append("</code></td>\n");
                html.append("          <td class=\"arch-numeric ").append(thresholdClass(m.getMethodCount(), 40))
                    .append("\">").append(m.getMethodCount()).append("</td>\n");
                html.append("          <td class=\"arch-numeric ").append(thresholdClass(m.getLinesOfCode(), 800))
                    .append("\">").append(m.getLinesOfCode()).append("</td>\n");
                html.append("          <td class=\"arch-numeric ").append(thresholdClass(m.getOutgoingDependencies(), 25))
                    .append("\">").append(m.getOutgoingDependencies()).append("</td>\n");
                html.append("          <td class=\"arch-numeric\">").append(m.getFieldCount()).append("</td>\n");
                html.append("          <td>");
                if (m.getStereotype() != null) {
                    html.append("<span class=\"arch-stereotype\">@").append(escHtml(m.getStereotype())).append("</span>");
                } else {
                    html.append("&mdash;");
                }
                html.append("</td>\n        </tr>\n");
            }
            html.append("      </tbody>\n");
            html.append("    </table>\n");
        }
        html.append("  </section>\n");

        // ── Top fan-out packages (lightweight) ───────────────────────────
        html.append("  <section class=\"arch-section\">\n");
        html.append("    <h3>&#x1F517; Top Fan-Out Packages</h3>\n");
        List<PackageNode> ranked = new ArrayList<PackageNode>(graph.getNodes());
        ranked.sort(new Comparator<PackageNode>() {
            @Override public int compare(PackageNode a, PackageNode b) {
                return Integer.compare(b.getEdges().size(), a.getEdges().size());
            }
        });
        html.append("    <p class=\"arch-help\">Packages depending on the largest number of other internal packages. ");
        html.append("High fan-out concentrates change risk.</p>\n");
        html.append("    <table class=\"arch-fanout\">\n");
        html.append("      <thead><tr><th>Package</th><th>Layer</th><th>Files</th><th>Fan-out</th></tr></thead>\n");
        html.append("      <tbody>\n");
        int shown = 0;
        for (PackageNode n : ranked) {
            if (shown >= 15) break;
            if (n.getEdges().isEmpty()) break;
            LayerInference.Layer layer = LayerInference.of(n.getName());
            html.append("        <tr>\n");
            html.append("          <td><code>").append(escHtml(n.getName())).append("</code></td>\n");
            html.append("          <td>");
            if (layer != LayerInference.Layer.UNKNOWN) {
                html.append("<span class=\"layer-badge layer-").append(layer.name().toLowerCase())
                    .append("\">").append(layer).append("</span>");
            } else {
                html.append("&mdash;");
            }
            html.append("</td>\n");
            html.append("          <td class=\"arch-numeric\">").append(n.getFiles().size()).append("</td>\n");
            html.append("          <td class=\"arch-numeric\">").append(n.getEdges().size()).append("</td>\n");
            html.append("        </tr>\n");
            shown++;
        }
        html.append("      </tbody>\n");
        html.append("    </table>\n");
        html.append("  </section>\n");
    }

    private void appendArchKpi(StringBuilder html, String value, String label, String tone) {
        html.append("    <div class=\"arch-kpi arch-kpi-").append(tone).append("\">\n");
        html.append("      <div class=\"arch-kpi-value\">").append(escHtml(value)).append("</div>\n");
        html.append("      <div class=\"arch-kpi-label\">").append(escHtml(label)).append("</div>\n");
        html.append("    </div>\n");
    }

    private static String thresholdClass(int value, int threshold) {
        if (value >= threshold * 2) return "arch-numeric-hot";
        if (value >= threshold) return "arch-numeric-warm";
        return "";
    }

    /** Walk the SCC and collect at most {@code max} import lines that participate in closing the cycle. */
    private static List<String> collectCycleEvidence(PackageGraph graph, List<String> scc, int max) {
        List<String> out = new ArrayList<String>();
        Set<String> sccSet = new HashSet<String>(scc);
        for (String pkg : scc) {
            PackageNode n = graph.get(pkg);
            if (n == null) continue;
            for (Map.Entry<String, List<String>> e : n.getEdgeEvidence().entrySet()) {
                if (!sccSet.contains(e.getKey())) continue;
                for (String s : e.getValue()) {
                    if (out.size() >= max) return out;
                    out.add(s);
                }
            }
        }
        return out;
    }

    private void appendSeveritySummary(StringBuilder html, AnalysisResult result) {
        Map<Finding.Severity, List<Finding>> bySev = result.findingsBySeverity();
        int total = result.getFindings().size();

        html.append("<div class=\"severity-summary\">\n");
        html.append("  <h2>Findings Overview</h2>\n");
        html.append("  <div class=\"severity-bars\">\n");

        for (Finding.Severity sev : Finding.Severity.values()) {
            int count = bySev.containsKey(sev) ? bySev.get(sev).size() : 0;
            if (count == 0) continue;
            double pct = total > 0 ? (count * 100.0 / total) : 0;
            html.append("    <div class=\"severity-row\">\n");
            html.append("      <span class=\"sev-label sev-").append(sev.name().toLowerCase())
                    .append("\">").append(sev.name()).append("</span>\n");
            html.append("      <div class=\"sev-bar-track\">\n");
            html.append("        <div class=\"sev-bar-fill sev-bg-").append(sev.name().toLowerCase())
                    .append("\" style=\"width:").append(String.format("%.1f", pct)).append("%\"></div>\n");
            html.append("      </div>\n");
            html.append("      <span class=\"sev-count\">").append(count).append("</span>\n");
            html.append("    </div>\n");
        }

        html.append("  </div>\n");
        html.append("  <div class=\"total-findings\">Total: <strong>").append(total).append("</strong> findings</div>\n");
        html.append("</div>\n");
    }

    private void appendFilters(StringBuilder html, AnalysisResult result) {
        Set<Finding.Severity> severities = result.getFindings().stream()
                .map(Finding::getSeverity).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Finding.Category> categories = result.getFindings().stream()
                .map(Finding::getCategory).collect(Collectors.toCollection(LinkedHashSet::new));

        html.append("<div class=\"filters\">\n");
        html.append("  <div class=\"filter-group\">\n");
        html.append("    <label>Severity:</label>\n");
        html.append("    <button class=\"filter-btn active\" data-filter-type=\"severity\" data-value=\"all\">All</button>\n");
        for (Finding.Severity sev : Finding.Severity.values()) {
            if (!severities.contains(sev)) continue;
            html.append("    <button class=\"filter-btn sev-btn-").append(sev.name().toLowerCase())
                    .append("\" data-filter-type=\"severity\" data-value=\"")
                    .append(sev.name()).append("\">").append(sev.name()).append("</button>\n");
        }
        html.append("  </div>\n");

        html.append("  <div class=\"filter-group\">\n");
        html.append("    <label>Category:</label>\n");
        html.append("    <button class=\"filter-btn active\" data-filter-type=\"category\" data-value=\"all\">All</button>\n");
        for (Finding.Category cat : categories) {
            html.append("    <button class=\"filter-btn\" data-filter-type=\"category\" data-value=\"")
                    .append(cat.name()).append("\">").append(formatCategory(cat)).append("</button>\n");
        }
        html.append("  </div>\n");

        // Confidence filter — defaults to hiding LOW (speculative) findings.
        // The "≥ Medium" button is a meta-filter that includes CERTAIN, HIGH, and MEDIUM
        // — it's not a separate confidence level. The individual level buttons
        // (Certain / High / Medium / Low) match a single confidence value exactly.
        html.append("  <div class=\"filter-group\">\n");
        html.append("    <label>Confidence:</label>\n");
        html.append("    <button class=\"filter-btn\" data-filter-type=\"confidence\" data-value=\"all\" title=\"Show every finding regardless of confidence\">All levels</button>\n");
        html.append("    <button class=\"filter-btn active\" data-filter-type=\"confidence\" data-value=\"trusted\" title=\"Combined view: shows Certain + High + Medium. Hides only Low (speculative) findings.\">&#8805; Medium</button>\n");
        html.append("    <button class=\"filter-btn\" data-filter-type=\"confidence\" data-value=\"CERTAIN\" title=\"Only findings with CERTAIN confidence\">Certain only</button>\n");
        html.append("    <button class=\"filter-btn\" data-filter-type=\"confidence\" data-value=\"HIGH\" title=\"Only findings with HIGH confidence\">High only</button>\n");
        html.append("    <button class=\"filter-btn\" data-filter-type=\"confidence\" data-value=\"MEDIUM\" title=\"Only findings with MEDIUM confidence\">Medium only</button>\n");
        html.append("    <button class=\"filter-btn\" data-filter-type=\"confidence\" data-value=\"LOW\" title=\"Only findings with LOW (speculative) confidence\">Low only</button>\n");
        html.append("  </div>\n");

        html.append("  <div class=\"filter-group\">\n");
        html.append("    <label>Search:</label>\n");
        html.append("    <input type=\"text\" id=\"searchInput\" placeholder=\"Filter by file, class, or field...\" />\n");
        html.append("  </div>\n");

        html.append("</div>\n");
    }

    private void appendFindings(StringBuilder html, AnalysisResult result) {
        html.append("<div class=\"findings\" id=\"findingsContainer\">\n");

        Map<Finding.Category, List<Finding>> byCategory = result.findingsByCategory();

        for (Map.Entry<Finding.Category, List<Finding>> entry : byCategory.entrySet()) {
            Finding.Category category = entry.getKey();
            List<Finding> findings = entry.getValue();

            // Sort by severity (CRITICAL first)
            findings.sort(Comparator.comparingInt(f -> f.getSeverity().ordinal()));

            html.append("<div class=\"category-section\" data-category=\"").append(category.name()).append("\">\n");
            html.append("  <div class=\"category-header\" onclick=\"toggleCategory(this)\">\n");
            html.append("    <span class=\"collapse-icon\">&#9660;</span>\n");
            html.append("    <h3>").append(formatCategory(category)).append("</h3>\n");
            html.append("    <span class=\"category-count\">").append(findings.size()).append(" findings</span>\n");
            html.append("  </div>\n");

            // Per-category issue type dropdown
            Set<String> issueTypes = new LinkedHashSet<>();
            for (Finding f : findings) {
                issueTypes.add(f.getTitle());
            }
            if (issueTypes.size() > 1) {
                html.append("  <div class=\"category-filter\">\n");
                html.append("    <label>Issue type:</label>\n");
                html.append("    <select onchange=\"filterByIssueType(this)\">\n");
                html.append("      <option value=\"all\">All (").append(findings.size()).append(")</option>\n");
                for (String type : issueTypes) {
                    long count = findings.stream().filter(f -> f.getTitle().equals(type)).count();
                    html.append("      <option value=\"").append(escAttr(type)).append("\">")
                            .append(escHtml(type)).append(" (").append(count).append(")</option>\n");
                }
                html.append("    </select>\n");
                html.append("  </div>\n");
            }

            html.append("  <div class=\"category-body\">\n");

            // Group findings by title (issue type)
            Map<String, List<Finding>> byTitle = new LinkedHashMap<>();
            for (Finding f : findings) {
                byTitle.computeIfAbsent(f.getTitle(), k -> new ArrayList<>()).add(f);
            }

            Map<Finding, Patch> availableFixes = result.getAvailableFixes();
            for (Map.Entry<String, List<Finding>> group : byTitle.entrySet()) {
                appendFindingGroup(html, group.getKey(), group.getValue(), availableFixes);
            }

            html.append("  </div>\n");
            html.append("</div>\n");
        }

        html.append("</div>\n");

        // Pagination controls
        html.append("<div class=\"pagination\" id=\"paginationControls\">\n");
        html.append("  <div class=\"page-size-selector\">\n");
        html.append("    <label>Per page:</label>\n");
        html.append("    <select id=\"pageSizeSelect\">\n");
        html.append("      <option value=\"25\">25</option>\n");
        html.append("      <option value=\"50\" selected>50</option>\n");
        html.append("      <option value=\"100\">100</option>\n");
        html.append("      <option value=\"0\">All</option>\n");
        html.append("    </select>\n");
        html.append("  </div>\n");
        html.append("  <button id=\"prevPage\" onclick=\"changePage(-1)\">&laquo; Prev</button>\n");
        html.append("  <span class=\"page-info\" id=\"pageInfo\"></span>\n");
        html.append("  <button id=\"nextPage\" onclick=\"changePage(1)\">Next &raquo;</button>\n");
        html.append("</div>\n");

        // No results message
        html.append("<div id=\"noResults\" class=\"no-results\" style=\"display:none\">\n");
        html.append("  <p>No findings match the current filters.</p>\n");
        html.append("</div>\n");
    }

    private void appendFindingGroup(StringBuilder html, String title, List<Finding> findings,
                                    Map<Finding, Patch> availableFixes) {
        Finding first = findings.get(0);
        String sevClass = first.getSeverity().name().toLowerCase();

        // Count how many findings in this group have an auto-fix patch.
        int fixCount = 0;
        if (availableFixes != null && !availableFixes.isEmpty()) {
            for (Finding f : findings) {
                if (availableFixes.containsKey(f)) fixCount++;
            }
        }

        // Build searchable text from all locations in the group
        StringBuilder searchText = new StringBuilder();
        searchText.append(title).append(' ').append(first.getDescription()).append(' ');
        for (Finding f : findings) {
            searchText.append(f.getLocation().getFilePath()).append(' ');
            if (f.getLocation().getClassName() != null) searchText.append(f.getLocation().getClassName()).append(' ');
            if (f.getLocation().getMemberName() != null) searchText.append(f.getLocation().getMemberName()).append(' ');
        }

        // Group confidence = highest confidence in the group (any CERTAIN > HIGH > MEDIUM > LOW)
        Finding.Confidence groupConfidence = highestConfidence(findings);
        String confClass = groupConfidence.name().toLowerCase();

        html.append("    <div class=\"finding-card\" data-severity=\"").append(first.getSeverity().name())
                .append("\" data-confidence=\"").append(groupConfidence.name())
                .append("\" data-category=\"").append(first.getCategory().name())
                .append("\" data-title=\"").append(escAttr(title))
                .append("\" data-search=\"").append(escAttr(searchText.toString().toLowerCase())).append("\">\n");

        // Header with severity + confidence badges, title, and occurrence count
        html.append("      <div class=\"finding-header\">\n");
        html.append("        <span class=\"sev-badge sev-").append(sevClass).append("\">")
                .append(first.getSeverity().name()).append("</span>\n");
        html.append("        <span class=\"conf-badge conf-").append(confClass)
                .append("\" title=\"Analyzer confidence this finding is real\">")
                .append(groupConfidence.name()).append("</span>\n");
        if (fixCount > 0) {
            // Pick the highest confidence among the patches for the badge tier.
            Patch.Confidence patchConf = highestPatchConfidence(findings, availableFixes);
            String patchConfClass = patchConf.name().toLowerCase().replace('_', '-');
            html.append("        <span class=\"fix-badge fix-").append(patchConfClass)
                    .append("\" title=\"Auto-fix patch available for ").append(fixCount)
                    .append(" of ").append(findings.size()).append(" occurrence(s)\">")
                    .append("&#x1F527; FIX AVAILABLE")
                    .append(fixCount < findings.size() ? " (" + fixCount + ")" : "")
                    .append("</span>\n");
        }
        html.append("        <span class=\"finding-title\">").append(escHtml(title)).append("</span>\n");
        html.append("        <span class=\"finding-count\">").append(findings.size())
                .append(findings.size() == 1 ? " occurrence" : " occurrences").append("</span>\n");
        html.append("      </div>\n");

        // Pick one representative finding for the example description + suggestion.
        // Prefer the one with the richest suggestion (longest text) so users get the
        // most useful example. The example is clearly labeled so it's understood as
        // illustrative — not literal for every occurrence.
        Finding example = pickRepresentative(findings);
        String exampleLoc = example.getLocation() != null
                ? (example.getLocation().getClassName() != null ? example.getLocation().getClassName() : example.getLocation().getFilePath())
                : null;

        // Single description paragraph using the representative finding.
        if (example.getDescription() != null && !example.getDescription().isEmpty()) {
            html.append("      <div class=\"finding-description\">\n");
            html.append("        <div class=\"example-tag\">Example");
            if (exampleLoc != null) html.append(" — <code>").append(escHtml(exampleLoc)).append("</code>");
            html.append("</div>\n");
            html.append("        <p>").append(escHtml(example.getDescription())).append("</p>\n");
            html.append("      </div>\n");
        }

        // Single suggestion block, labeled as an example fix.
        // Suggestion contains code blocks + deep mechanics — dev mode only.
        if (example.getSuggestion() != null && !example.getSuggestion().isEmpty()) {
            html.append("      <div class=\"finding-suggestion\" data-modes=\"dev\">\n");
            html.append("        <div class=\"example-tag suggestion-tag\">Example fix — apply the same pattern to all impacted classes below</div>\n");
            html.append("        ").append(formatSuggestion(example.getSuggestion())).append("\n");
            html.append("      </div>\n");
        }

        // Auto-fix patches (dev mode only). Shows the unified diff for each
        // finding in the group that has an available patch. Collapsed by default
        // so the per-finding details only render when a developer wants them.
        if (fixCount > 0) {
            html.append("      <div class=\"finding-fixes\" data-modes=\"dev\">\n");
            html.append("        <div class=\"fixes-header\" onclick=\"toggleFixes(this)\">\n");
            html.append("          <span class=\"fixes-toggle\">&#9654;</span>\n");
            html.append("          &#x1F527; Auto-fix patches (").append(fixCount)
                    .append(") &mdash; click to show diffs\n");
            html.append("        </div>\n");
            html.append("        <div class=\"fixes-content\" style=\"display:none\">\n");
            int rendered = 0;
            for (Finding f : findings) {
                Patch p = availableFixes != null ? availableFixes.get(f) : null;
                if (p == null) continue;
                rendered++;
                String patchConfClass = p.getConfidence().name().toLowerCase().replace('_', '-');
                html.append("          <div class=\"fix-patch\">\n");
                html.append("            <div class=\"fix-patch-header\">\n");
                html.append("              <span class=\"fix-conf fix-").append(patchConfClass).append("\">")
                        .append(p.getConfidence().name().replace('_', ' ')).append("</span>\n");
                html.append("              <span class=\"fix-desc\">").append(escHtml(p.getDescription())).append("</span>\n");
                html.append("              <span class=\"fix-loc\"><code>").append(escHtml(p.getDisplayPath()))
                        .append(":").append(p.getStartLine()).append("</code></span>\n");
                html.append("            </div>\n");
                html.append("            <pre class=\"diff\"><code>");
                html.append(renderDiff(p.toUnifiedDiff()));
                html.append("</code></pre>\n");
                html.append("          </div>\n");
                // Cap to keep page size bounded on huge groups; the cap is generous.
                if (rendered >= 50) {
                    html.append("          <div class=\"fix-more-note\">+").append(fixCount - rendered)
                            .append(" more patches not shown to keep the report compact.</div>\n");
                    break;
                }
            }
            html.append("        </div>\n");
            html.append("      </div>\n");
        }

        // Impacted locations — plain list, no click-to-expand. Keeps DOM small and
        // the page snappy on large reports.
        // Per-file location detail is dev-only; lead sees only the aggregated card.
        html.append("      <div class=\"finding-locations\" data-modes=\"dev\">\n");
        html.append("        <div class=\"locations-header\" onclick=\"toggleLocations(this)\">\n");
        html.append("          <span class=\"locations-toggle\">&#9654;</span>\n");
        html.append("          Impacted classes (").append(findings.size()).append(") &mdash; click to show\n");
        html.append("        </div>\n");
        html.append("        <table class=\"locations-table\" style=\"display:none\">\n");
        html.append("          <thead><tr><th>File</th><th>Line</th><th>Class</th><th>Member</th></tr></thead>\n");
        html.append("          <tbody>\n");

        for (Finding f : findings) {
            SourceLocation loc = f.getLocation();
            String filePath = escHtml(loc.getFilePath());
            String className = escHtml(loc.getClassName() != null ? loc.getClassName() : "");
            String memberName = escHtml(loc.getMemberName() != null ? loc.getMemberName() : "");

            html.append("          <tr>\n");
            html.append("            <td title=\"").append(filePath).append("\"><code>").append(filePath).append("</code></td>\n");
            html.append("            <td>").append(loc.getStartLine()).append("</td>\n");
            html.append("            <td title=\"").append(className).append("\">").append(className).append("</td>\n");
            html.append("            <td title=\"").append(memberName).append("\">").append(memberName).append("</td>\n");
            html.append("          </tr>\n");
        }

        html.append("          </tbody>\n");
        html.append("        </table>\n");
        html.append("      </div>\n");

        html.append("    </div>\n");
    }

    /** Picks one finding to use as the example — the one with the richest suggestion. */
    private Finding pickRepresentative(List<Finding> findings) {
        Finding best = findings.get(0);
        int bestLen = best.getSuggestion() != null ? best.getSuggestion().length() : 0;
        for (Finding f : findings) {
            int len = f.getSuggestion() != null ? f.getSuggestion().length() : 0;
            if (len > bestLen) {
                best = f;
                bestLen = len;
            }
        }
        return best;
    }

    private void appendFooter(StringBuilder html, AnalysisResult result) {
        String project = result.getProjectPath();
        if (project != null) {
            int sep = Math.max(project.lastIndexOf('/'), project.lastIndexOf('\\'));
            if (sep > 0 && sep < project.length() - 1) project = project.substring(sep + 1);
        } else {
            project = "";
        }
        String timestamp = result.getTimestamp() != null ? result.getTimestamp().replace('T', ' ') : "";
        // Trim sub-second precision: "2026-05-18 23:08:06.7482565" -> "2026-05-18 23:08:06"
        int dot = timestamp.indexOf('.');
        if (dot > 0) timestamp = timestamp.substring(0, dot);

        // Footer — keeps the original "Generated by Code Owl vX" phrasing but
        // adds scan metadata so the reader can see exactly when this report was
        // produced and against which project.
        html.append("<div class=\"footer\">\n");
        html.append("  <p>Generated by <strong>Code Owl ").append(escHtml(CODE_OWL_VERSION)).append("</strong>");
        if (!timestamp.isEmpty()) {
            html.append(" &middot; scanned ").append(escHtml(timestamp));
        }
        if (!project.isEmpty()) {
            html.append(" &middot; <strong>").append(escHtml(project)).append("</strong>");
        }
        html.append("</p>\n");
        html.append("</div>\n");

        // JavaScript for filtering
        html.append("<script>\n");
        html.append(JAVASCRIPT);
        html.append("</script>\n");
    }

    private String searchableText(Finding f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.getTitle()).append(' ');
        sb.append(f.getDescription()).append(' ');
        sb.append(f.getLocation().getFilePath()).append(' ');
        if (f.getLocation().getClassName() != null) sb.append(f.getLocation().getClassName()).append(' ');
        if (f.getLocation().getMemberName() != null) sb.append(f.getLocation().getMemberName());
        return sb.toString().toLowerCase();
    }

    /** Returns the highest confidence in the group (CERTAIN beats HIGH beats MEDIUM beats LOW). */
    private Finding.Confidence highestConfidence(List<Finding> findings) {
        Finding.Confidence best = Finding.Confidence.LOW;
        for (Finding f : findings) {
            Finding.Confidence c = f.getConfidence();
            if (c == null) continue;
            if (c.ordinal() < best.ordinal()) best = c;
        }
        return best;
    }

    /**
     * Returns the highest patch confidence (lowest ordinal: SAFE &lt; MODERATE
     * &lt; REVIEW_REQUIRED) across all patches associated with the given
     * findings. Used to colour the FIX AVAILABLE badge.
     */
    private Patch.Confidence highestPatchConfidence(List<Finding> findings, Map<Finding, Patch> patches) {
        Patch.Confidence best = Patch.Confidence.REVIEW_REQUIRED;
        if (patches == null) return best;
        for (Finding f : findings) {
            Patch p = patches.get(f);
            if (p == null || p.getConfidence() == null) continue;
            if (p.getConfidence().ordinal() < best.ordinal()) best = p.getConfidence();
        }
        return best;
    }

    /**
     * Renders a unified-diff string as HTML-escaped &lt;span&gt;-wrapped lines so
     * that CSS can colour additions green and removals red. The +++/--- header
     * lines and @@-hunk marker get a "diff-header" class for muted styling.
     */
    private String renderDiff(String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isEmpty()) return "";
        StringBuilder out = new StringBuilder(unifiedDiff.length() + 256);
        for (String line : unifiedDiff.split("\n", -1)) {
            String cls;
            if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@")) {
                cls = "diff-header";
            } else if (line.startsWith("+")) {
                cls = "diff-add";
            } else if (line.startsWith("-")) {
                cls = "diff-del";
            } else {
                cls = "diff-ctx";
            }
            out.append("<span class=\"").append(cls).append("\">").append(escHtml(line)).append("</span>\n");
        }
        return out.toString();
    }

    private String formatCategory(Finding.Category cat) {
        String raw = cat.name().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        boolean capitalize = true;
        for (char c : raw.toCharArray()) {
            if (c == ' ') {
                sb.append(' ');
                capitalize = true;
            } else if (capitalize) {
                sb.append(Character.toUpperCase(c));
                capitalize = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * Renders suggestion text as HTML with support for:
     * - Lines starting with "###" become section headers
     * - Lines wrapped in ``` become code blocks
     * - Lines starting with "- " become list items
     * - "\n" becomes line breaks
     */
    private String formatSuggestion(String suggestion) {
        if (suggestion == null) return "";

        StringBuilder html = new StringBuilder();
        String[] lines = suggestion.split("\n");
        boolean inCodeBlock = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    html.append("</code></pre>\n");
                    inCodeBlock = false;
                } else {
                    html.append("<pre class=\"suggestion-code\"><code>");
                    inCodeBlock = true;
                }
                continue;
            }

            if (inCodeBlock) {
                html.append(escHtml(line)).append("\n");
                continue;
            }

            if (trimmed.startsWith("### ")) {
                html.append("<div class=\"suggestion-heading\">")
                        .append(escHtml(trimmed.substring(4))).append("</div>\n");
            } else if (trimmed.startsWith("- ")) {
                html.append("<div class=\"suggestion-item\">")
                        .append(escHtml(trimmed.substring(2))).append("</div>\n");
            } else if (!trimmed.isEmpty()) {
                html.append("<div class=\"suggestion-text\">")
                        .append(escHtml(trimmed)).append("</div>\n");
            }
        }

        if (inCodeBlock) {
            html.append("</code></pre>\n");
        }

        return html.toString();
    }

    private String escAttr(String s) {
        return escHtml(s);
    }


    // ─── Inlined static resources (loaded from classpath at startup) ──────
    // CSS lives in src/main/resources/report.css — edit there, no Java recompile
    // needed for style tweaks. Same for JS at src/main/resources/report.js.

    private static final String CSS = loadResource("/report.css");
    private static final String JAVASCRIPT = loadResource("/report.js");
    /**
     * 64×64 Code Owl icon, base64-encoded. ~6 KB inline — used both for the
     * sidebar brand spot and the document favicon. The full-size logo lives in
     * the repo's /assets/ folder (used by README, not bundled in the report).
     */
    private static final String LOGO_ICON_DATA_URI = "data:image/png;base64," + loadResourceAsBase64("/logo-icon-64.png");

    /**
     * Code Owl runtime version. First tries reading from a filtered properties
     * file written at build time (most reliable across the maven-shade-plugin
     * boundary). Falls back to the JAR manifest's Implementation-Version,
     * then to "dev" when neither is available (typical when running from
     * class files in an IDE).
     */
    private static final String CODE_OWL_VERSION = readVersion();

    private static String readVersion() {
        // 1) Filtered properties file (preferred — survives shading)
        try (java.io.InputStream in = HtmlReportGenerator.class.getResourceAsStream("/code-owl.properties")) {
            if (in != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.isEmpty() && !v.startsWith("${")) {
                    return formatVersion(v);
                }
            }
        } catch (java.io.IOException ignored) { /* fall through */ }

        // 2) JAR manifest
        String v = HtmlReportGenerator.class.getPackage().getImplementationVersion();
        if (v != null && !v.isEmpty()) return formatVersion(v);

        // 3) Last resort
        return "dev";
    }

    private static String formatVersion(String v) {
        // "0.1.1-SNAPSHOT" -> "v0.1.1-dev" ; "0.1.1" -> "v0.1.1"
        if (v.endsWith("-SNAPSHOT")) {
            v = v.substring(0, v.length() - "-SNAPSHOT".length()) + "-dev";
        }
        return "v" + v;
    }

    private static String loadResource(String name) {
        try (java.io.InputStream in = HtmlReportGenerator.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Required resource not found on classpath: " + name);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read resource: " + name, e);
        }
    }

    /** Loads a binary classpath resource and returns it as a base64-encoded string. */
    private static String loadResourceAsBase64(String name) {
        try (java.io.InputStream in = HtmlReportGenerator.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Required resource not found on classpath: " + name);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read resource: " + name, e);
        }
    }
}
