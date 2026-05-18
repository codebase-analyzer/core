package dev.codeanalyzer.core.suppression;

import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inline suppression via source-code comments.
 *
 * <p>Supported forms (anywhere on the line at or just before the finding):
 * <pre>
 * // @analyzer-ignore: rule-id [optional reason]
 * /* @analyzer-ignore: rule-id, other-rule-id reason *&#47;
 * </pre>
 *
 * <p>{@code rule-id} matches {@link Finding#getRuleId()}. Multiple rule IDs may
 * be comma-separated. Use the wildcard {@code *} to ignore <em>any</em> rule on
 * that line.
 *
 * <p>The check looks at the finding's line and the line immediately above it
 * (so a developer can place the comment on the preceding line without altering
 * the offending line itself).
 */
public final class InlineSuppression {

    private static final Logger log = LoggerFactory.getLogger(InlineSuppression.class);

    private static final Pattern IGNORE_PATTERN = Pattern.compile(
            "@analyzer-ignore\\s*:\\s*([^*\\n\\r]+?)(?:\\s+(.*))?$");

    /** Project root for resolving relative paths in finding locations. */
    private final Path projectRoot;

    /** filePath → cached source lines (for files we've already touched). */
    private final Map<String, List<String>> fileCache = new HashMap<>();

    public InlineSuppression(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /** True if the finding's line (or the line above) carries a matching @analyzer-ignore comment. */
    public boolean isSuppressed(Finding f) {
        SourceLocation loc = f.getLocation();
        if (loc == null || loc.getFilePath() == null || loc.getStartLine() <= 0) return false;
        if (f.getRuleId() == null || f.getRuleId().isEmpty()) return false;

        List<String> lines = loadLines(loc.getFilePath());
        if (lines == null) return false;

        int idx = loc.getStartLine() - 1; // 0-based
        if (idx < 0 || idx >= lines.size()) return false;

        // Check the finding line and the preceding line.
        if (lineIgnoresRule(lines.get(idx), f.getRuleId())) return true;
        if (idx > 0 && lineIgnoresRule(lines.get(idx - 1), f.getRuleId())) return true;
        return false;
    }

    private boolean lineIgnoresRule(String line, String ruleId) {
        if (line == null || !line.contains("@analyzer-ignore")) return false;
        Matcher m = IGNORE_PATTERN.matcher(line);
        if (!m.find()) return false;
        String rulePart = m.group(1).trim();
        Set<String> rules = new HashSet<>();
        for (String r : rulePart.split(",")) {
            String token = r.trim();
            // Stop at trailing comment terminator characters if present.
            int cut = token.indexOf(' ');
            if (cut > 0) token = token.substring(0, cut);
            if (!token.isEmpty()) rules.add(token);
        }
        return rules.contains("*") || rules.contains(ruleId);
    }

    private List<String> loadLines(String filePath) {
        return fileCache.computeIfAbsent(filePath, p -> {
            try {
                Path resolved = Paths.get(p);
                if (!resolved.isAbsolute()) {
                    resolved = projectRoot.resolve(p);
                }
                if (!Files.isRegularFile(resolved)) return null;
                return Files.readAllLines(resolved, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.debug("Could not read for inline suppression: {}", p);
                return null;
            }
        });
    }
}
