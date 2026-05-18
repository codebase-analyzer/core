package dev.codeanalyzer.core.suppression;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.codeanalyzer.core.model.AnalysisResult;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Baseline mode — only report findings new since a previously written snapshot.
 *
 * <p>This is the killer feature for adopting static analysis on legacy code:
 * accept everything that exists today, then enforce a "no new findings" gate
 * on future commits.
 *
 * <p>A baseline is a JSON file of stable finding fingerprints. We deliberately
 * avoid line numbers in the fingerprint (those churn with unrelated edits) and
 * key on {@code ruleId + filePath + className + memberName + title}.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code --write-baseline baseline.json}: persist current findings as the baseline.</li>
 *   <li>{@code --baseline baseline.json}: filter out any finding that matches an entry.</li>
 * </ul>
 */
public final class Baseline {

    private static final Logger log = LoggerFactory.getLogger(Baseline.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Set<String> fingerprints;
    private final BaselineSnapshot snapshot;

    private Baseline(Set<String> fingerprints, BaselineSnapshot snapshot) {
        this.fingerprints = fingerprints;
        this.snapshot = snapshot != null ? snapshot : BaselineSnapshot.empty();
    }

    /** Empty baseline — matches no findings. */
    public static Baseline empty() {
        return new Baseline(Collections.emptySet(), BaselineSnapshot.empty());
    }

    /** Aggregate stats captured at write time (point-in-time snapshot). Empty for old baselines. */
    public BaselineSnapshot getSnapshot() { return snapshot; }

    /** Loads a baseline JSON file written by {@link #write}. Reads embedded snapshot if present. */
    public static Baseline load(Path file) {
        if (!Files.isRegularFile(file)) {
            log.warn("Baseline file not found: {} — treating as empty baseline", file);
            return empty();
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            Set<String> fps = new HashSet<>();
            if (root != null && root.has("findings")) {
                for (JsonElement el : root.getAsJsonArray("findings")) {
                    String fp = el.getAsJsonObject().has("fingerprint")
                            ? el.getAsJsonObject().get("fingerprint").getAsString() : null;
                    if (fp != null) fps.add(fp);
                }
            }
            BaselineSnapshot snap = readSnapshot(root);
            log.info("Loaded baseline with {} fingerprints from {}{}", fps.size(), file.getFileName(),
                    snap.isEmpty() ? "" : " (with trend snapshot from " + snap.getGeneratedAt() + ")");
            return new Baseline(fps, snap);
        } catch (Exception e) {
            log.warn("Failed to read baseline {}: {}", file, e.getMessage());
            return empty();
        }
    }

    /**
     * Pulls the snapshot block out of a baseline JSON. Missing or malformed
     * snapshot returns {@link BaselineSnapshot#empty()} — old baselines load fine.
     */
    private static BaselineSnapshot readSnapshot(JsonObject root) {
        if (root == null || !root.has("snapshot")) return BaselineSnapshot.empty();
        JsonObject s = root.getAsJsonObject("snapshot");
        BaselineSnapshot.Builder b = new BaselineSnapshot.Builder();
        if (s.has("generatedAt")) b.generatedAt(s.get("generatedAt").getAsString());
        if (s.has("totalFindings")) b.totalFindings(s.get("totalFindings").getAsInt());
        if (s.has("bySeverity"))   b.bySeverity(asIntMap(s.getAsJsonObject("bySeverity")));
        if (s.has("byCategory"))   b.byCategory(asIntMap(s.getAsJsonObject("byCategory")));
        if (s.has("cveCount"))     b.cveCount(s.get("cveCount").getAsInt());
        if (s.has("techDebtHours")) b.techDebtHours(s.get("techDebtHours").getAsLong());
        if (s.has("techDebtCost"))  b.techDebtCost(s.get("techDebtCost").getAsDouble());
        if (s.has("currencyCode"))  b.currencyCode(s.get("currencyCode").getAsString());
        if (s.has("migrationScore")) b.migrationScore(s.get("migrationScore").getAsInt());
        if (s.has("migrationBlockers")) b.migrationBlockers(s.get("migrationBlockers").getAsInt());
        return b.build();
    }

    private static Map<String, Integer> asIntMap(JsonObject obj) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (obj == null) return out;
        for (String k : obj.keySet()) out.put(k, obj.get(k).getAsInt());
        return out;
    }

    /** True if the finding is already in the baseline. */
    public boolean contains(Finding f) {
        return fingerprints.contains(fingerprint(f));
    }

    /**
     * Legacy writer — writes fingerprints only, no trend snapshot. Old callers
     * keep working but lose the trend-tracking capability on subsequent runs.
     * Prefer {@link #write(AnalysisResult, Path)} for new code.
     */
    public static void write(List<Finding> findings, Path file) throws IOException {
        writeImpl(findings, file, null);
    }

    /**
     * Writes a baseline including a {@link BaselineSnapshot} so subsequent scans
     * can show trend deltas (KPI cards + Executive Summary).
     */
    public static void write(AnalysisResult result, Path file) throws IOException {
        writeImpl(result.getFindings(), file, buildSnapshot(result));
    }

    private static void writeImpl(List<Finding> findings, Path file, BaselineSnapshot snapshot)
            throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("count", findings.size());

        // Embed snapshot if available — old loaders ignore unknown keys gracefully.
        if (snapshot != null && !snapshot.isEmpty()) {
            JsonObject s = new JsonObject();
            s.addProperty("generatedAt", snapshot.getGeneratedAt());
            s.addProperty("totalFindings", snapshot.getTotalFindings());
            JsonObject bs = new JsonObject();
            for (Map.Entry<String, Integer> e : snapshot.getBySeverity().entrySet()) {
                bs.addProperty(e.getKey(), e.getValue());
            }
            s.add("bySeverity", bs);
            JsonObject bc = new JsonObject();
            for (Map.Entry<String, Integer> e : snapshot.getByCategory().entrySet()) {
                bc.addProperty(e.getKey(), e.getValue());
            }
            s.add("byCategory", bc);
            s.addProperty("cveCount", snapshot.getCveCount());
            s.addProperty("techDebtHours", snapshot.getTechDebtHours());
            s.addProperty("techDebtCost", snapshot.getTechDebtCost());
            s.addProperty("currencyCode", snapshot.getCurrencyCode());
            s.addProperty("migrationScore", snapshot.getMigrationScore());
            s.addProperty("migrationBlockers", snapshot.getMigrationBlockers());
            root.add("snapshot", s);
        }

        List<JsonObject> entries = new ArrayList<>();
        for (Finding f : findings) {
            JsonObject e = new JsonObject();
            e.addProperty("fingerprint", fingerprint(f));
            e.addProperty("ruleId", f.getRuleId());
            e.addProperty("title", f.getTitle());
            if (f.getLocation() != null) {
                e.addProperty("file", f.getLocation().getFilePath());
                if (f.getLocation().getClassName() != null) {
                    e.addProperty("class", f.getLocation().getClassName());
                }
                if (f.getLocation().getMemberName() != null) {
                    e.addProperty("member", f.getLocation().getMemberName());
                }
            }
            entries.add(e);
        }
        root.add("findings", GSON.toJsonTree(entries));

        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        log.info("Wrote baseline with {} findings{} to {}",
                findings.size(),
                snapshot != null && !snapshot.isEmpty() ? " (with trend snapshot)" : "",
                file);
    }

    /** Aggregates an AnalysisResult into a BaselineSnapshot for trend tracking. */
    private static BaselineSnapshot buildSnapshot(AnalysisResult result) {
        return BaselineSnapshot.fromComponents(
                result.getFindings(), result.getTechDebt(), result.getMigrationScore());
    }

    /**
     * Builds a stable fingerprint for a finding. Deliberately excludes line numbers
     * (those churn on unrelated edits); includes ruleId, file, class, member, and title.
     */
    static String fingerprint(Finding f) {
        StringBuilder sb = new StringBuilder();
        sb.append(safe(f.getRuleId())).append('|');
        if (f.getLocation() != null) {
            SourceLocation loc = f.getLocation();
            sb.append(safe(loc.getFilePath())).append('|');
            sb.append(safe(loc.getClassName())).append('|');
            sb.append(safe(loc.getMemberName())).append('|');
        } else {
            sb.append("||||");
        }
        sb.append(safe(f.getTitle()));
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static String safe(String s) { return s == null ? "" : s; }

    public int size() { return fingerprints.size(); }
    public boolean isEmpty() { return fingerprints.isEmpty(); }
}
