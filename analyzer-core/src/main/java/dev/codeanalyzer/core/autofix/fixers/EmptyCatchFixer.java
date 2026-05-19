package dev.codeanalyzer.core.autofix.fixers;

import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.CatchClause;
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
 * Generates a MODERATE patch that converts an empty {@code catch} block into
 * a visible-but-non-fatal handler: prints the swallowed exception to
 * {@code stderr} and marks the spot with a TODO comment.
 *
 * <p>Targets {@code pmd.EmptyCatchBlock}.
 *
 * <p><strong>Why MODERATE, not SAFE.</strong> Earlier versions of this fixer
 * inserted only a {@code // TODO} comment. That looked like a SAFE fix on
 * paper — zero runtime change — but in practice PMD's {@code EmptyCatchBlock}
 * rule checks for <em>statements</em>, not text. A comment doesn't satisfy
 * the rule, so the warning kept firing on the next scan and the fixer
 * effectively no-op'd. This version inserts a real statement
 * ({@code e.printStackTrace();}) that both:
 * <ul>
 *   <li>actually clears the PMD warning, and</li>
 *   <li>makes the previously-silent swallow visible at runtime.</li>
 * </ul>
 * That visibility is a runtime behaviour change (stderr writes), so the fix
 * is classified MODERATE and is excluded from the default {@code --fix} run.
 * Users opt in with {@code --fix-confidence moderate}.
 *
 * <p>{@code printStackTrace} is the least-surprising bridge: every Java
 * developer recognises it as "I haven't decided how to handle this yet";
 * it's grep-able for later replacement with proper logging; and it
 * preserves the original control flow (no rethrow).
 *
 * <p>If the catch parameter has no usable name we fall back to {@code "e"} —
 * which still compiles because we don't actually reference the parameter
 * directly outside the inserted statement.
 */
public class EmptyCatchFixer implements AutoFixer {

    private static final String RULE_ID = "pmd.EmptyCatchBlock";

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

        // Locate an empty CatchClause whose range contains the reported line.
        CatchClause empty = null;
        for (CatchClause c : cu.findAll(CatchClause.class)) {
            if (!c.getBody().isEmpty()) continue;
            Range r = c.getRange().orElse(null);
            if (r == null) continue;
            if (r.begin.line <= startLine && r.end.line >= startLine) {
                empty = c;
                break;
            }
        }
        if (empty == null) return Optional.empty();

        Range bodyRange = empty.getBody().getRange().orElse(null);
        if (bodyRange == null) return Optional.empty();

        // Pull the catch parameter name so the inserted printStackTrace() call
        // references the actual variable: 'e.printStackTrace()' or
        // 'ex.printStackTrace()' depending on how the user named it.
        String exVar = "e";
        try {
            String n = empty.getParameter().getNameAsString();
            if (n != null && !n.isEmpty()) exVar = n;
        } catch (RuntimeException ignored) { /* fall back to "e" */ }

        List<String> lines;
        try {
            lines = Files.readAllLines(source.getFilePath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }

        int openLine = bodyRange.begin.line;
        int closeLine = bodyRange.end.line;

        if (openLine == closeLine) {
            // Single-line form:  } catch (X e) { }
            // Expand to multi-line and inject TODO + printStackTrace.
            int idx = openLine - 1;
            if (idx < 0 || idx >= lines.size()) return Optional.empty();
            String orig = lines.get(idx);
            int openBrace = orig.lastIndexOf('{');
            int closeBrace = openBrace >= 0 ? orig.indexOf('}', openBrace) : -1;
            if (openBrace < 0 || closeBrace < 0) return Optional.empty();
            String indent = leadingWhitespace(orig);
            String before = orig.substring(0, openBrace + 1);
            String after  = orig.substring(closeBrace);
            String replaced = before
                    + "\n" + indent + "    // TODO: replace with proper handling"
                    + "\n" + indent + "    " + exVar + ".printStackTrace();"
                    + "\n" + indent + after;
            return Optional.of(buildPatch(source, openLine, openLine, orig, replaced));
        }

        // Multi-line form:
        //     } catch (X e) {
        //     }
        // Insert TODO + printStackTrace immediately before the closing brace.
        int closeIdx = closeLine - 1;
        if (closeIdx < 0 || closeIdx >= lines.size()) return Optional.empty();
        String closeLineText = lines.get(closeIdx);
        String indent = leadingWhitespace(closeLineText);
        String replaced = indent + "    // TODO: replace with proper handling\n"
                + indent + "    " + exVar + ".printStackTrace();\n"
                + closeLineText;
        return Optional.of(buildPatch(source, closeLine, closeLine, closeLineText, replaced));
    }

    private Patch buildPatch(ParsedSource source, int startLine, int endLine,
                              String original, String replacement) {
        return new Patch(
                source.getFilePath().toString(),
                source.getRelativePathString(),
                startLine, endLine,
                original,
                replacement,
                RULE_ID,
                Patch.Confidence.MODERATE,
                "Surface the silently-swallowed exception via printStackTrace() and " +
                        "mark a TODO. Replace with proper logging or rethrow before merging."
        );
    }

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
