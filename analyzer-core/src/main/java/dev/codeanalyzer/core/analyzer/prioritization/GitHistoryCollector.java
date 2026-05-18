package dev.codeanalyzer.core.analyzer.prioritization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Collects per-file commit counts via {@code git log} run as an external process.
 *
 * <p>No JGit dependency on purpose — keeping the runtime footprint tiny matters
 * more than the speed/fidelity boost JGit would give. {@code git log} is the
 * universal lowest-common-denominator and works on any machine that already
 * has the project cloned.
 *
 * <p>Output format used: {@code git log --since="<N> months ago" --pretty=format:"" --name-only}.
 * Each line is a file path; blank lines separate commits. We just bucket-count
 * per file. Renames are tracked by git automatically so the latest path wins,
 * which is exactly what we want for matching against the current analysis.
 */
public final class GitHistoryCollector {

    private static final Logger log = LoggerFactory.getLogger(GitHistoryCollector.class);

    /** How many seconds we wait for git before giving up. */
    private static final long GIT_TIMEOUT_SECONDS = 30;

    private GitHistoryCollector() {}

    /**
     * Collects churn over the last {@code months} months for the project at
     * {@code projectRoot}. Returns an empty/unavailable history on any failure
     * (no git binary, not a git repo, timeout, etc.) — never throws.
     */
    public static GitHistory collect(Path projectRoot, int months) {
        if (projectRoot == null || !Files.isDirectory(projectRoot.resolve(".git"))) {
            log.debug("No .git directory at {} — skipping git history collection", projectRoot);
            return GitHistory.empty();
        }

        long start = System.currentTimeMillis();
        Map<String, Integer> counts = new HashMap<>();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "log",
                    "--since=" + months + " months ago",
                    "--pretty=format:",
                    "--name-only",
                    "--no-renames"   // simpler — we just want raw filenames
            );
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    String key = line.replace('\\', '/');
                    counts.merge(key, 1, Integer::sum);
                }
            }

            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("git log timed out after {}s — using partial history ({} files)",
                        GIT_TIMEOUT_SECONDS, counts.size());
            }

            int exit = p.exitValue();
            if (exit != 0 && counts.isEmpty()) {
                log.warn("git log exited {} on {} — git history unavailable", exit, projectRoot);
                return GitHistory.empty();
            }

            long dur = System.currentTimeMillis() - start;
            log.info("Git history: {} files with commits in last {} months ({} ms)",
                    counts.size(), months, dur);
            return new GitHistory(true, counts, months);

        } catch (IOException e) {
            log.info("git binary not available ({}) — degrading to severity-only prioritization", e.getMessage());
            return GitHistory.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GitHistory.empty();
        } catch (Exception e) {
            log.warn("git history collection failed: {}", e.toString());
            return GitHistory.empty();
        }
    }
}
