package dev.codeanalyzer.core.autofix.fixers;

import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import dev.codeanalyzer.core.autofix.AutoFixer;
import dev.codeanalyzer.core.autofix.Patch;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

/**
 * Generates a MODERATE patch that rewrites string reference comparison
 * ({@code obj == "literal"}) as a null-safe value-based call
 * ({@code "literal".equals(obj)}).
 *
 * <p>Targets {@code pmd.UseEqualsToCompareStrings}.
 *
 * <p>MODERATE confidence: the change is semantically equivalent in 99% of
 * cases (and actively corrects a real bug in the 1%). Calling
 * {@code .equals()} on the literal — not the variable — is null-safe:
 * {@code obj == null} returns false instead of NPE.
 *
 * <p>Only handles the case where the whole binary expression sits on a single
 * line (so a column-based source substring is reliable). Multi-line
 * expressions and chained comparisons are deferred to a later PR.
 *
 * <p>Limitation: the variable side is re-printed via JavaParser's canonical
 * form, which may differ in whitespace or parentheses from the original. The
 * semantics are preserved; the visual style may not be exactly preserved.
 */
public class StringComparisonFixer implements AutoFixer {

    private static final String RULE_ID = "pmd.UseEqualsToCompareStrings";

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public Optional<Patch> generateFix(Finding finding, ParsedSource source) {
        if (finding == null || source == null || finding.getLocation() == null) {
            return Optional.empty();
        }
        int targetLine = finding.getLocation().getStartLine();
        if (targetLine < 1) return Optional.empty();

        CompilationUnit cu = source.getCompilationUnit();
        if (cu == null) return Optional.empty();

        // Pick the BinaryExpr on the target line that:
        //   - uses == or !=
        //   - has at least one StringLiteralExpr operand
        BinaryExpr target = null;
        for (BinaryExpr be : cu.findAll(BinaryExpr.class)) {
            Range r = be.getRange().orElse(null);
            if (r == null || r.begin.line != targetLine) continue;
            BinaryExpr.Operator op = be.getOperator();
            if (op != BinaryExpr.Operator.EQUALS && op != BinaryExpr.Operator.NOT_EQUALS) continue;
            if (be.getLeft() instanceof StringLiteralExpr || be.getRight() instanceof StringLiteralExpr) {
                target = be;
                break;
            }
        }
        if (target == null) return Optional.empty();

        // Use the literal as the receiver — null-safe.
        StringLiteralExpr literal;
        Expression other;
        if (target.getLeft() instanceof StringLiteralExpr) {
            literal = (StringLiteralExpr) target.getLeft();
            other = target.getRight();
        } else {
            literal = (StringLiteralExpr) target.getRight();
            other = target.getLeft();
        }
        boolean isNegated = target.getOperator() == BinaryExpr.Operator.NOT_EQUALS;

        Range r = target.getRange().orElse(null);
        if (r == null) return Optional.empty();
        // Only handle single-line expressions for now.
        if (r.begin.line != r.end.line) return Optional.empty();

        List<String> lines;
        try {
            lines = Files.readAllLines(source.getFilePath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }

        int idx = r.begin.line - 1;
        if (idx < 0 || idx >= lines.size()) return Optional.empty();
        String line = lines.get(idx);

        int beginCol = r.begin.column - 1;            // JavaParser columns are 1-indexed
        int endColExclusive = r.end.column;           // r.end.column is the last char, so +1 for exclusive
        if (beginCol < 0 || endColExclusive > line.length() || beginCol >= endColExclusive) {
            return Optional.empty();
        }

        // Build the replacement expression text.
        String literalToken = "\"" + escapeStringLiteral(literal.getValue()) + "\"";
        String otherToken = other.toString();
        String replacementExpr = (isNegated ? "!" : "") + literalToken + ".equals(" + otherToken + ")";

        String replacedLine = line.substring(0, beginCol) + replacementExpr + line.substring(endColExclusive);

        String description = (isNegated
                ? "Rewrite '!= literal' as !\"literal\".equals(...)"
                : "Rewrite '== literal' as \"literal\".equals(...)")
                + " — null-safe value comparison.";

        return Optional.of(new Patch(
                source.getFilePath().toString(),
                source.getRelativePathString(),
                r.begin.line, r.begin.line,
                line,
                replacedLine,
                RULE_ID,
                Patch.Confidence.MODERATE,
                description
        ));
    }

    /** Escapes characters that have special meaning inside a Java string literal. */
    private String escapeStringLiteral(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
