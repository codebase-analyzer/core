package dev.codeanalyzer.core.autofix.fixers;

import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import dev.codeanalyzer.core.autofix.AutoFixer;
import dev.codeanalyzer.core.autofix.Patch;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a MODERATE patch that flips {@code FetchType.EAGER} to
 * {@code FetchType.LAZY} on a JPA collection annotation.
 *
 * <p>Targets {@code hibernate.eager-collection}. EAGER on collections is the
 * classic perf footgun — fetched on every load whether needed or not, triggers
 * N+1 in list queries, OOM on batch jobs. LAZY + {@code @BatchSize} is the
 * idiomatic fix; this patch covers the first half.
 *
 * <p>MODERATE confidence: rare legitimate uses exist (small immutable lookup
 * tables, {@code @ElementCollection} of 2-3 enum values). User should confirm.
 *
 * <p>Only handles the EXPLICIT form ({@code fetch = FetchType.EAGER} or
 * {@code fetch = EAGER}). The implicit case (no fetch attribute on an
 * association that defaults to EAGER) requires ADDING a parameter to the
 * annotation rather than replacing text — deferred to a later PR.
 */
public class EagerToLazyFixer implements AutoFixer {

    private static final String RULE_ID = "hibernate.eager-collection";

    /** Matches {@code FetchType.EAGER} or bare {@code EAGER} as a whole word. */
    private static final Pattern EAGER_PATTERN = Pattern.compile("\\b(FetchType\\.)?EAGER\\b");

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public Optional<Patch> generateFix(Finding finding, ParsedSource source) {
        if (finding == null || source == null || finding.getLocation() == null) {
            return Optional.empty();
        }
        int startLine = finding.getLocation().getStartLine();
        if (startLine < 1) return Optional.empty();

        CompilationUnit cu = source.getCompilationUnit();
        if (cu == null) return Optional.empty();

        FieldDeclaration field = findFieldSpanning(cu, startLine);
        if (field == null) return Optional.empty();

        List<String> lines;
        try {
            lines = Files.readAllLines(source.getFilePath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }

        Range r = field.getRange().orElse(null);
        if (r == null) return Optional.empty();
        int scanFrom = r.begin.line;
        int scanTo = Math.min(lines.size(), r.end.line);

        for (int i = scanFrom; i <= scanTo; i++) {
            int idx = i - 1;
            if (idx < 0 || idx >= lines.size()) continue;
            String line = lines.get(idx);
            Matcher m = EAGER_PATTERN.matcher(line);
            if (m.find()) {
                String replacement = m.group(1) != null ? "FetchType.LAZY" : "LAZY";
                String replaced = m.replaceFirst(Matcher.quoteReplacement(replacement));
                return Optional.of(new Patch(
                        source.getFilePath().toString(),
                        source.getRelativePathString(),
                        i, i,
                        line,
                        replaced,
                        RULE_ID,
                        Patch.Confidence.MODERATE,
                        "Switch collection fetch from EAGER to LAZY. Consider adding " +
                                "@BatchSize(size = 20) on the field and using @EntityGraph / " +
                                "JOIN FETCH in the specific queries that need the data."
                ));
            }
        }
        return Optional.empty();
    }

    private FieldDeclaration findFieldSpanning(CompilationUnit cu, int line) {
        for (FieldDeclaration f : cu.findAll(FieldDeclaration.class)) {
            Range r = f.getRange().orElse(null);
            if (r == null) continue;
            if (r.begin.line <= line && r.end.line >= line) return f;
        }
        return null;
    }
}
