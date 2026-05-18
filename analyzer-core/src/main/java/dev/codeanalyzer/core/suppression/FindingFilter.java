package dev.codeanalyzer.core.suppression;

import dev.codeanalyzer.core.model.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Combined post-analysis filter: config-file suppression, inline-comment
 * suppression, and baseline-mode filtering. All three are independent and
 * compose via OR — a finding is dropped if <em>any</em> of them suppress it.
 *
 * <p>The filter is applied by {@code AnalysisEngine} after analyzers run, so
 * analyzers don't need to know about suppression at all.
 */
public final class FindingFilter {

    private static final Logger log = LoggerFactory.getLogger(FindingFilter.class);

    private final SuppressionConfig config;
    private final InlineSuppression inline;
    private final Baseline baseline;

    private int suppressedByConfig;
    private int suppressedByInline;
    private int suppressedByBaseline;

    public FindingFilter(Path projectRoot, Baseline baseline) {
        this.config = SuppressionConfig.load(projectRoot);
        this.inline = new InlineSuppression(projectRoot);
        this.baseline = baseline != null ? baseline : Baseline.empty();
    }

    public List<Finding> apply(List<Finding> findings) {
        List<Finding> kept = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            if (config.shouldSuppress(f)) { suppressedByConfig++; continue; }
            if (inline.isSuppressed(f))   { suppressedByInline++; continue; }
            if (baseline.contains(f))     { suppressedByBaseline++; continue; }
            kept.add(f);
        }
        int total = suppressedByConfig + suppressedByInline + suppressedByBaseline;
        if (total > 0) {
            log.info("Suppression: {} findings filtered ({} by config, {} inline, {} baseline)",
                    total, suppressedByConfig, suppressedByInline, suppressedByBaseline);
        }
        return kept;
    }

    public int getSuppressedByConfig()   { return suppressedByConfig; }
    public int getSuppressedByInline()   { return suppressedByInline; }
    public int getSuppressedByBaseline() { return suppressedByBaseline; }

    public boolean hasAnyRules() {
        return !config.isEmpty() || !baseline.isEmpty();
    }
}
