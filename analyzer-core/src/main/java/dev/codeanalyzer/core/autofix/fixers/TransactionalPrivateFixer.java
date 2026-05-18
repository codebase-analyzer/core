package dev.codeanalyzer.core.autofix.fixers;

import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
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
 * Generates a REVIEW_REQUIRED patch that changes a {@code @Transactional}
 * private method's visibility to public, so Spring proxies can actually
 * intercept it.
 *
 * <p>Targets {@code spring.transactional-private}. The annotation is silently
 * ignored on private methods (the proxy can't intercept them), so the typical
 * intent is "I wanted this transactional" — making the method public restores
 * that behaviour.
 *
 * <p>Marked REVIEW_REQUIRED because changing visibility affects API surface:
 * the user should confirm the method belongs in the public API. A common
 * alternative is to extract the method into a separate {@code @Service} bean
 * and inject it — a decision the fixer cannot make automatically.
 */
public class TransactionalPrivateFixer implements AutoFixer {

    private static final String RULE_ID = "spring.transactional-private";

    /** Matches a standalone {@code private} keyword (not inside another identifier). */
    private static final Pattern PRIVATE_MODIFIER = Pattern.compile("\\bprivate\\b");

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

        MethodDeclaration method = findMethodSpanning(cu, startLine);
        if (method == null || !method.isPrivate()) {
            return Optional.empty();
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(source.getFilePath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }

        // Scan the method's full source range (annotations + signature) for the
        // 'private' modifier. JavaParser doesn't expose modifier source positions
        // directly, so we scan textually within the bounds it does give us.
        Range r = method.getRange().orElse(null);
        if (r == null) return Optional.empty();
        int scanFrom = r.begin.line;
        int scanTo = Math.min(lines.size(), r.end.line);

        for (int i = scanFrom; i <= scanTo; i++) {
            int idx = i - 1;
            if (idx < 0 || idx >= lines.size()) continue;
            String line = lines.get(idx);
            Matcher m = PRIVATE_MODIFIER.matcher(line);
            if (m.find()) {
                String replaced = m.replaceFirst("public");
                return Optional.of(new Patch(
                        source.getFilePath().toString(),
                        source.getRelativePathString(),
                        i, i,
                        line,
                        replaced,
                        RULE_ID,
                        Patch.Confidence.REVIEW_REQUIRED,
                        "Change 'private' to 'public' so @Transactional can be proxied. " +
                                "Verify this method belongs in the bean's public API; " +
                                "an alternative is to extract it into a separate @Service bean."
                ));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the {@link MethodDeclaration} whose source range contains the
     * given line. We use a containment check (not begin-line equality) because
     * JavaParser's {@code getBegin().line} for a method with annotations is the
     * first annotation's line, not the modifier line.
     */
    private MethodDeclaration findMethodSpanning(CompilationUnit cu, int line) {
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            Range r = m.getRange().orElse(null);
            if (r == null) continue;
            if (r.begin.line <= line && r.end.line >= line) return m;
        }
        return null;
    }
}
