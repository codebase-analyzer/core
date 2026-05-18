package dev.codeanalyzer.core.suppression;

import dev.codeanalyzer.core.model.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads and applies suppression rules from {@code .codebase-analyzer.yml} at
 * the project root.
 *
 * <p>Supported format (a flat YAML subset):
 * <pre>
 * ignore-rules:
 *   - pmd.UnusedFormalParameter
 *   - orphan-bean.conditional
 *
 * ignore-paths:
 *   - "src/generated/**"
 *   - "**&#47;legacy/**"
 * </pre>
 *
 * <p>Use {@link #shouldSuppress(Finding)} to decide whether a given finding
 * is filtered out. Pair with {@link InlineSuppression} for {@code // @analyzer-ignore: rule-id}
 * comment support.
 */
public final class SuppressionConfig {

    private static final Logger log = LoggerFactory.getLogger(SuppressionConfig.class);

    public static final String CONFIG_FILENAME = ".codebase-analyzer.yml";

    private final Set<String> ignoredRules;
    private final List<PathMatcher> ignoredPaths;

    private SuppressionConfig(Set<String> ignoredRules, List<PathMatcher> ignoredPaths) {
        this.ignoredRules = ignoredRules;
        this.ignoredPaths = ignoredPaths;
    }

    /** An empty config that suppresses nothing. */
    public static SuppressionConfig empty() {
        return new SuppressionConfig(Collections.emptySet(), Collections.emptyList());
    }

    /** Loads the config from {@code projectRoot/.codebase-analyzer.yml}, or returns an empty one. */
    public static SuppressionConfig load(Path projectRoot) {
        Path configFile = projectRoot.resolve(CONFIG_FILENAME);
        if (!Files.isRegularFile(configFile)) {
            return empty();
        }
        try {
            String yaml = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            Map<String, List<String>> parsed = MiniYaml.parseListsOfStrings(yaml);

            Set<String> rules = new HashSet<>(
                    parsed.getOrDefault("ignore-rules", Collections.emptyList()));
            List<PathMatcher> matchers = new ArrayList<>();
            for (String glob : parsed.getOrDefault("ignore-paths", Collections.emptyList())) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
            }
            log.info("Loaded {} (rules={}, paths={})", CONFIG_FILENAME, rules.size(), matchers.size());
            return new SuppressionConfig(rules, matchers);
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", CONFIG_FILENAME, e.getMessage());
            return empty();
        } catch (RuntimeException e) {
            log.warn("Failed to parse {}: {}", CONFIG_FILENAME, e.getMessage());
            return empty();
        }
    }

    /** True if the finding's rule ID is globally suppressed or its file matches an ignore-path glob. */
    public boolean shouldSuppress(Finding f) {
        if (f.getRuleId() != null && ignoredRules.contains(f.getRuleId())) return true;
        if (f.getLocation() != null && f.getLocation().getFilePath() != null) {
            Path filePath = Paths.get(f.getLocation().getFilePath().replace('\\', '/'));
            for (PathMatcher m : ignoredPaths) {
                if (m.matches(filePath)) return true;
            }
        }
        return false;
    }

    public Set<String> getIgnoredRules() { return ignoredRules; }
    public int getIgnoredPathCount() { return ignoredPaths.size(); }
    public boolean isEmpty() { return ignoredRules.isEmpty() && ignoredPaths.isEmpty(); }
}
