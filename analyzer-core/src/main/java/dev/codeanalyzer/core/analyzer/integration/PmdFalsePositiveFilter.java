package dev.codeanalyzer.core.analyzer.integration;

import net.sourceforge.pmd.reporting.RuleViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and filters PMD false positives by reading the source file
 * and analyzing the context around the flagged line.
 */
final class PmdFalsePositiveFilter {

    private static final Logger log = LoggerFactory.getLogger(PmdFalsePositiveFilter.class);

    private static final Pattern DUPLICATE_LITERAL_PATTERN = Pattern.compile(
        "The String literal \"(.+?)\" appears \\d+ times");

    private static final Pattern SPRING_ANNOTATION_PATTERN = Pattern.compile(
        "@(?:Bean|Qualifier|Component|Service|Repository|DependsOn|Resource|Named)\\s*\\(");

    private static final Pattern FOR_EACH_PATTERN = Pattern.compile(
        "for\\s*\\(\\s*\\S+\\s+(\\w+)\\s*:");

    private static final Pattern FOR_CLASSIC_PATTERN = Pattern.compile(
        "for\\s*\\(\\s*\\S+\\s+(\\w+)\\s*=");

    private static final Pattern WHILE_ITERATOR_PATTERN = Pattern.compile(
        "while\\s*\\(\\s*(\\w+)\\.hasNext\\(\\)");

    private static final Pattern DO_WHILE_PATTERN = Pattern.compile("^do\\s*\\{?\\s*$");

    private static final Pattern VARIABLE_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?:^|\\s)(\\w+)\\s*=\\s*new\\s+\\w+");

    private PmdFalsePositiveFilter() {}

    static boolean isFalsePositive(RuleViolation v) {
        String rule = v.getRule().getName();

        if ("AvoidDuplicateLiterals".equals(rule)) {
            return isSpringLiteralFalsePositive(v);
        }

        if ("AvoidInstantiatingObjectsInLoops".equals(rule)) {
            return isLoopInstantiationWithLoopVariable(v);
        }

        return false;
    }

    /**
     * AvoidDuplicateLiterals: skip when the literal is used as a Spring bean/qualifier name.
     */
    private static boolean isSpringLiteralFalsePositive(RuleViolation v) {
        Matcher m = DUPLICATE_LITERAL_PATTERN.matcher(v.getDescription());
        if (!m.find()) {
            return false;
        }
        String literal = m.group(1);

        try {
            String content = readFile(v);
            if (content == null) return false;

            if (SPRING_ANNOTATION_PATTERN.matcher(content).find()) {
                Pattern inAnnotation = Pattern.compile(
                    "@(?:Bean|Qualifier|Component|Service|Repository|DependsOn|Resource|Named)" +
                    "\\s*\\(\\s*(?:value\\s*=\\s*)?\"" + Pattern.quote(literal) + "\"");
                if (inAnnotation.matcher(content).find()) {
                    return true;
                }
            }
        } catch (IOException e) {
            log.debug("Could not read file for false-positive check: {}", v.getFileId().getAbsolutePath());
        }

        return false;
    }

    /**
     * AvoidInstantiatingObjectsInLoops: skip when the object genuinely needs
     * to be created per iteration. Checks:
     * 1. Constructor uses the loop variable as argument
     * 2. Object is called on the loop variable (loopVar.setX(new Obj()))
     * 3. Object is configured via setters with values that change per iteration
     *    (common in do-while pagination patterns)
     */
    private static boolean isLoopInstantiationWithLoopVariable(RuleViolation v) {
        try {
            List<String> lines = Files.readAllLines(
                Paths.get(v.getFileId().getAbsolutePath()), StandardCharsets.UTF_8);

            int violationLine = v.getBeginLine() - 1;
            if (violationLine < 0 || violationLine >= lines.size()) return false;

            String instantiationLine = lines.get(violationLine).trim();

            // Find the enclosing loop and its variable (if any)
            LoopInfo loopInfo = findEnclosingLoop(lines, violationLine);
            if (loopInfo == null) return false;

            if (loopInfo.variable != null) {
                // Check if the constructor call uses the loop variable
                Pattern usesLoopVar = Pattern.compile(
                    "new\\s+\\w+\\s*\\([^)]*\\b" + Pattern.quote(loopInfo.variable) + "\\b");
                if (usesLoopVar.matcher(instantiationLine).find()) {
                    return true;
                }

                // Check if it's assigned to something derived from the loop var
                Pattern loopVarMethodCall = Pattern.compile(
                    "\\b" + Pattern.quote(loopInfo.variable) + "\\s*\\.");
                if (loopVarMethodCall.matcher(instantiationLine).find()) {
                    return true;
                }
            }

            // Check if the created object is configured via setters with
            // values that change per iteration (variables assigned inside the loop)
            if (isObjectConfiguredPerIteration(lines, violationLine, instantiationLine, loopInfo)) {
                return true;
            }

        } catch (IOException e) {
            log.debug("Could not read file for loop analysis: {}", v.getFileId().getAbsolutePath());
        }

        return false;
    }

    /**
     * Check if the instantiated object is configured via setters that use
     * variables modified within the loop body, making each instance unique.
     */
    private static boolean isObjectConfiguredPerIteration(
            List<String> lines, int violationLine, String instantiationLine, LoopInfo loopInfo) {

        // Extract the variable name the new object is assigned to
        Matcher assignMatch = VARIABLE_ASSIGNMENT_PATTERN.matcher(instantiationLine);
        if (!assignMatch.find()) return false;
        String objVarName = assignMatch.group(1);

        // Scan lines after the instantiation (within the loop body) for setter calls
        // that use variables other than constants or method parameters
        int loopEnd = findLoopEnd(lines, loopInfo.headerLine);
        if (loopEnd < 0) loopEnd = lines.size() - 1;

        Pattern setterPattern = Pattern.compile(
            "\\b" + Pattern.quote(objVarName) + "\\.(set|add|put)\\w*\\s*\\(");
        Pattern setterWithNewObj = Pattern.compile(
            "\\b" + Pattern.quote(objVarName) + "\\.\\w+\\s*\\(\\s*new\\s+");

        int setterCount = 0;
        boolean usesChangingValue = false;

        for (int i = violationLine + 1; i <= loopEnd && i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (setterPattern.matcher(line).find()) {
                setterCount++;
                // If setter uses a new object or a variable that's likely loop-scoped
                if (setterWithNewObj.matcher(line).find()) {
                    usesChangingValue = true;
                }
                // Check if setter uses variables that are reassigned inside the loop
                if (loopInfo.variable != null) {
                    if (line.contains(loopInfo.variable)) {
                        usesChangingValue = true;
                    }
                }
                // Check for variables that look like they change per iteration
                // (e.g., currentIssueDateFrom, currentPage, offset, etc.)
                if (line.matches(".*\\.(set|add)\\w+\\s*\\([^)]*\\b(current|next|prev|offset|page|index|batch)\\w*\\b.*")) {
                    usesChangingValue = true;
                }
            }
        }

        // If the object has setters called AND at least one uses changing values,
        // it's a per-iteration configuration pattern
        if (setterCount >= 2 && usesChangingValue) {
            return true;
        }

        // Broader heuristic: if object has 3+ setter calls right after creation,
        // it's very likely being configured per iteration (why else create it in a loop?)
        if (setterCount >= 3) {
            return true;
        }

        return false;
    }

    private static int findLoopEnd(List<String> lines, int loopHeaderLine) {
        int braceDepth = 0;
        boolean foundOpenBrace = false;
        for (int i = loopHeaderLine; i < lines.size(); i++) {
            String line = lines.get(i);
            for (int c = 0; c < line.length(); c++) {
                if (line.charAt(c) == '{') {
                    braceDepth++;
                    foundOpenBrace = true;
                } else if (line.charAt(c) == '}') {
                    braceDepth--;
                    if (foundOpenBrace && braceDepth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private static class LoopInfo {
        final String variable;  // may be null for do-while or generic while
        final int headerLine;   // 0-indexed line of the loop header (do/for/while)
        final String type;      // "for-each", "for", "while", "do-while"

        LoopInfo(String variable, int headerLine, String type) {
            this.variable = variable;
            this.headerLine = headerLine;
            this.type = type;
        }
    }

    /**
     * Scan backwards from the violation line to find the nearest enclosing
     * loop and extract the loop variable name (if determinable).
     */
    private static LoopInfo findEnclosingLoop(List<String> lines, int fromLine) {
        int braceDepth = 0;

        for (int i = fromLine; i >= 0; i--) {
            String line = lines.get(i).trim();

            // Track brace depth to find the enclosing scope
            for (int c = line.length() - 1; c >= 0; c--) {
                if (line.charAt(c) == '}') braceDepth++;
                else if (line.charAt(c) == '{') braceDepth--;
            }

            // When we've exited the current block, check if this line is a loop
            if (braceDepth < 0) {
                // for-each: for (Type var : collection)
                Matcher forEach = FOR_EACH_PATTERN.matcher(line);
                if (forEach.find()) {
                    return new LoopInfo(forEach.group(1), i, "for-each");
                }

                // classic for: for (int i = ...)
                Matcher forClassic = FOR_CLASSIC_PATTERN.matcher(line);
                if (forClassic.find()) {
                    return new LoopInfo(forClassic.group(1), i, "for");
                }

                // while with iterator: while (iter.hasNext())
                Matcher whileIter = WHILE_ITERATOR_PATTERN.matcher(line);
                if (whileIter.find()) {
                    return new LoopInfo(whileIter.group(1), i, "while");
                }

                // Generic while — no determinable loop variable
                if (line.startsWith("while")) {
                    return new LoopInfo(null, i, "while");
                }

                // do-while: do {
                if (DO_WHILE_PATTERN.matcher(line).find() || line.equals("do")) {
                    return new LoopInfo(null, i, "do-while");
                }

                // Not a loop — might be an if/else/try, keep scanning
                braceDepth = 0;
            }
        }

        return null;
    }

    private static String readFile(RuleViolation v) throws IOException {
        Path sourceFile = Paths.get(v.getFileId().getAbsolutePath());
        if (!Files.isRegularFile(sourceFile)) return null;
        return new String(Files.readAllBytes(sourceFile), StandardCharsets.UTF_8);
    }
}
