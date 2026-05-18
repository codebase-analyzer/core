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

/**
 * Generates a SAFE patch that adds a missing {@code @Override} annotation
 * above a method declaration.
 *
 * <p>Targets PMD's {@code MissingOverride} rule, which Code Owl surfaces
 * as findings with {@code ruleId == "pmd.MissingOverride"}.
 *
 * <p>The fix is semantically neutral — {@code @Override} is a compile-time
 * contract checker, not a runtime change. Confidence is therefore
 * {@link Patch.Confidence#SAFE}.
 *
 * <p>Indentation: we lift the leading whitespace from the method-signature
 * line and reuse it for the inserted annotation, so the patch matches the
 * surrounding code style (tabs vs. spaces, 2 vs. 4 spaces, etc.).
 */
public class MissingOverrideFixer implements AutoFixer {

    private static final String RULE_ID = "pmd.MissingOverride";

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

        // Locate the method whose signature begins at the reported line.
        MethodDeclaration method = findMethodAtLine(cu, targetLine);
        if (method == null) return Optional.empty();

        // Defensive: if @Override is somehow already there, don't double-add.
        // PMD shouldn't flag in that case, but a wrong patch is worse than
        // a missing one.
        if (method.getAnnotationByName("Override").isPresent()) {
            return Optional.empty();
        }

        // Read the file from disk to preserve exact original indentation.
        // The AST has positions but doesn't always round-trip raw whitespace.
        List<String> lines;
        try {
            lines = Files.readAllLines(source.getFilePath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }

        int origLineIdx = targetLine - 1;
        if (origLineIdx < 0 || origLineIdx >= lines.size()) {
            return Optional.empty();
        }
        String originalLine = lines.get(origLineIdx);
        String indent = leadingWhitespace(originalLine);

        // Replacement: insert "@Override" line above, preserving indentation.
        // We replace exactly one line (the signature line) with two lines
        // (annotation + original signature), so line numbering downstream
        // shifts by +1 — that's expected.
        String originalText = originalLine;
        String replacementText = indent + "@Override\n" + originalLine;

        Patch patch = new Patch(
                source.getFilePath().toString(),
                source.getRelativePathString(),
                targetLine,
                targetLine,
                originalText,
                replacementText,
                RULE_ID,
                Patch.Confidence.SAFE,
                "Add missing @Override annotation"
        );
        return Optional.of(patch);
    }

    /**
     * Finds the {@link MethodDeclaration} whose declaration begins on the
     * given line. JavaParser's {@code getBegin()} for a method without
     * preceding annotations points at the first modifier or return type —
     * which is what PMD reports for {@code MissingOverride}.
     *
     * <p>Falls back to ±1 line to tolerate small reporting drift between
     * PMD's reported line and JavaParser's parsed line (rare but observed
     * with some formatter styles).
     */
    private MethodDeclaration findMethodAtLine(CompilationUnit cu, int line) {
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            Optional<Range> r = m.getRange();
            if (!r.isPresent()) continue;
            if (r.get().begin.line == line) return m;
        }
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            Optional<Range> r = m.getRange();
            if (!r.isPresent()) continue;
            int bl = r.get().begin.line;
            if (bl == line - 1 || bl == line + 1) return m;
        }
        return null;
    }

    /** Returns the leading whitespace (spaces and tabs) of a line. */
    private String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') break;
            i++;
        }
        return line.substring(0, i);
    }
}
