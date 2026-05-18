package dev.codeanalyzer.core.analyzer.prioritization;

import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.SourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks findings by business impact instead of raw severity.
 *
 * <p>The score is a deliberately simple, defensible formula:
 * <pre>
 *   impact = severityWeight × confidenceWeight × (1 + log2(1 + churn))
 *          + categoryBonus
 * </pre>
 * <ul>
 *   <li>Severity weight: CRITICAL=80, HIGH=60, MEDIUM=40, LOW=20, INFO=5</li>
 *   <li>Confidence weight: CERTAIN=1.0, HIGH=0.9, MEDIUM=0.7, LOW=0.4</li>
 *   <li>Churn multiplier: file with 0 commits → ×1.0; 1 commit → ×2.0;
 *       8 commits → ×4.16; 32 commits → ×6.04. Log-scaled so frequently-
 *       changed files dominate without blowing up.</li>
 *   <li>Category bonus: CVEs and EAGER cycles get +20 (especially load-bearing).</li>
 * </ul>
 *
 * <p>Final score is clamped to [0, 100]. When git history is unavailable
 * ({@link GitHistory#isAvailable()} is false), the churn multiplier defaults
 * to 1.0 — the result reduces to severity-confidence-weighted ranking.
 */
public final class ImpactScorer {

    private ImpactScorer() {}

    /** Produces a sorted list of {@link PrioritizedFinding}, highest impact first. */
    public static List<PrioritizedFinding> score(List<Finding> findings, GitHistory git) {
        List<PrioritizedFinding> out = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            int churn = git != null ? git.getCommitCount(filePath(f)) : 0;
            int minutes = EffortTable.minutesFor(f.getCategory());
            int score = computeScore(f, churn);
            String reason = buildReason(f, churn, git != null && git.isAvailable());
            out.add(new PrioritizedFinding(
                    f, score, reason,
                    EffortTable.bandFromMinutes(minutes),
                    Math.max(1, (int) Math.round(minutes / 60.0)),
                    churn));
        }
        out.sort(Comparator.comparingInt(PrioritizedFinding::getImpactScore).reversed());
        return out;
    }

    private static int computeScore(Finding f, int churn) {
        double sev = severityWeight(f.getSeverity());
        double conf = confidenceWeight(f.getConfidence());
        double churnMult = 1.0 + (Math.log(1 + Math.max(0, churn)) / Math.log(2));
        double base = sev * conf * churnMult;
        base += categoryBonus(f.getCategory());
        if (base < 0) base = 0;
        if (base > 100) base = 100;
        return (int) Math.round(base);
    }

    private static double severityWeight(Finding.Severity s) {
        if (s == null) return 0;
        switch (s) {
            case CRITICAL: return 80;
            case HIGH:     return 60;
            case MEDIUM:   return 40;
            case LOW:      return 20;
            case INFO:     return 5;
            default:       return 0;
        }
    }

    private static double confidenceWeight(Finding.Confidence c) {
        if (c == null) return 0.9;
        switch (c) {
            case CERTAIN: return 1.00;
            case HIGH:    return 0.90;
            case MEDIUM:  return 0.70;
            case LOW:     return 0.40;
            default:      return 0.80;
        }
    }

    private static int categoryBonus(Finding.Category c) {
        if (c == null) return 0;
        switch (c) {
            case DEPENDENCY_VULNERABILITY: return 20; // active CVE — fix immediately
            case HIBERNATE_EAGER_CYCLE:    return 15; // production-impacting perf
            case SPRING_TRANSACTIONAL_MISUSE: return 10; // silent data-integrity bugs
            case HIBERNATE_N_PLUS_ONE:     return 10;
            case ARCHITECTURE_CYCLE:       return 10; // structural debt, slows everything
            case ARCHITECTURE_GOD_CLASS:   return 5;  // bottleneck for change
            default: return 0;
        }
    }

    private static String filePath(Finding f) {
        if (f == null) return null;
        SourceLocation loc = f.getLocation();
        return loc != null ? loc.getFilePath() : null;
    }

    private static String buildReason(Finding f, int churn, boolean gitAvailable) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.getSeverity()).append('/').append(f.getConfidence());
        if (gitAvailable) {
            if (churn >= 10) {
                sb.append(" · hot file (").append(churn).append(" commits)");
            } else if (churn > 0) {
                sb.append(" · ").append(churn).append(" recent commits");
            } else {
                sb.append(" · cold file");
            }
        }
        switch (f.getCategory()) {
            case DEPENDENCY_VULNERABILITY:    sb.append(" · known CVE"); break;
            case HIBERNATE_EAGER_CYCLE:       sb.append(" · perf cycle"); break;
            case SPRING_TRANSACTIONAL_MISUSE: sb.append(" · silent TX bug"); break;
            case ARCHITECTURE_CYCLE:          sb.append(" · package cycle"); break;
            case ARCHITECTURE_GOD_CLASS:      sb.append(" · god class"); break;
            default: break;
        }
        return sb.toString();
    }
}
