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
 * Generates a SAFE patch that inserts a {@code // TODO: handle exception}
 * comment inside an empty {@code catch} block.
 *
 * <p>Targets {@code pmd.EmptyCatchBlock}.
 *
 * <p>SAFE because the inserted text is a comment — zero runtime effect. The
 * value is to flip the silent-swallow smell from invisible to flagged in code
 * review, and to leave the developer a hook to write real handling.
 *
 * <p>A more thorough fix would insert a {@code log.warn("...", e)} call, but
 * that requires knowing whether the class has a logger field and which logging
 * framework is in use. Deferred to a later PR.
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
            // Expand to multi-line and inject the TODO.
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
                    + "\n" + indent + "    // TODO: handle exception\n"
                    + indent + after;
            return Optional.of(buildPatch(source, openLine, openLine, orig, replaced));
        }

        // Multi-line form:
        //     } catch (X e) {
        //     }
        // Insert the comment immediately before the closing brace.
        int closeIdx = closeLine - 1;
        if (closeIdx < 0 || closeIdx >= lines.size()) return Optional.empty();
        String closeLineText = lines.get(closeIdx);
        String indent = leadingWhitespace(closeLineText);
        String replaced = indent + "    // TODO: handle exception\n" + closeLineText;
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
                Patch.Confidence.SAFE,
                "Insert TODO comment inside empty catch block. " +
                        "Add proper exception handling or logging before merging."
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
