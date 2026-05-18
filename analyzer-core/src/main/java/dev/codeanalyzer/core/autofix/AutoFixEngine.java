package dev.codeanalyzer.core.autofix;

import dev.codeanalyzer.core.autofix.fixers.MissingOverrideFixer;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates fix generation.
 *
 * <p>Holds a registry of {@link AutoFixer}s keyed by {@code Finding.ruleId}
 * and, given a list of findings and the corresponding parsed sources,
 * returns the patches each fixer can produce.
 *
 * <p>Generation is intended to be always-on (cheap — fixers only run for
 * findings whose ruleId matches a registered fixer). Application to disk
 * is opt-in via the CLI {@code --fix} flag (handled outside this class,
 * planned in a future PR).
 *
 * <p>Per-fixer exceptions are caught and logged — same defensive pattern
 * as {@code AnalyzerRegistry} — so one bad fixer cannot poison the run.
 */
public class AutoFixEngine {

    private static final Logger log = LoggerFactory.getLogger(AutoFixEngine.class);

    private final Map<String, AutoFixer> fixers = new HashMap<String, AutoFixer>();

    public AutoFixEngine() {
        // Built-in fixers registered by default. Add more in future PRs.
        register(new MissingOverrideFixer());
    }

    /** Registers a fixer by its {@code ruleId}. Overrides any prior entry. */
    public AutoFixEngine register(AutoFixer fixer) {
        if (fixer != null && fixer.getRuleId() != null) {
            fixers.put(fixer.getRuleId(), fixer);
        }
        return this;
    }

    /**
     * Run all registered fixers against the given findings. Returns a map from
     * finding to its generated patch — only findings with a successful fix
     * appear in the result.
     *
     * <p>Insertion order is preserved (we use {@link LinkedHashMap}) so the
     * map iteration order matches the input findings order, which makes
     * report rendering deterministic.
     */
    public Map<Finding, Patch> generatePatches(List<Finding> findings, List<ParsedSource> sources) {
        if (findings == null || findings.isEmpty()
                || sources == null || sources.isEmpty()
                || fixers.isEmpty()) {
            return Collections.emptyMap();
        }

        // Index sources by relative path for O(1) lookup. Paths in Finding.location
        // are already forward-slash normalized; ParsedSource.getRelativePathString()
        // applies the same normalization.
        Map<String, ParsedSource> sourcesByPath = new HashMap<String, ParsedSource>(sources.size());
        for (ParsedSource s : sources) {
            sourcesByPath.put(s.getRelativePathString(), s);
        }

        Map<Finding, Patch> patches = new LinkedHashMap<Finding, Patch>();
        int attempted = 0;

        for (Finding f : findings) {
            if (f == null || f.getRuleId() == null) continue;
            AutoFixer fixer = fixers.get(f.getRuleId());
            if (fixer == null) continue;

            ParsedSource source = lookupSource(sourcesByPath, f);
            if (source == null) continue;

            attempted++;
            try {
                Optional<Patch> patch = fixer.generateFix(f, source);
                if (patch.isPresent()) {
                    patches.put(f, patch.get());
                }
            } catch (RuntimeException e) {
                log.warn("Auto-fixer {} threw on finding at {}: {}",
                        fixer.getClass().getSimpleName(),
                        f.getLocation() != null ? f.getLocation().toString() : "<unknown>",
                        e.getMessage());
            }
        }

        if (attempted > 0) {
            log.info("Auto-fix generation: {} patches produced ({} attempted, {} total findings, {} registered fixers)",
                    patches.size(), attempted, findings.size(), fixers.size());
        }

        return patches;
    }

    /** Get the registered fixer for a ruleId, or null if none. */
    public AutoFixer getFixer(String ruleId) {
        return fixers.get(ruleId);
    }

    /** Number of registered fixers. */
    public int size() {
        return fixers.size();
    }

    private ParsedSource lookupSource(Map<String, ParsedSource> byPath, Finding f) {
        if (f.getLocation() == null) return null;
        String filePath = f.getLocation().getFilePath();
        if (filePath == null) return null;
        ParsedSource s = byPath.get(filePath);
        if (s != null) return s;
        // Fallback: try the slash-normalized form
        return byPath.get(filePath.replace('\\', '/'));
    }
}
