package dev.codeanalyzer.core.analyzer.integration;

import dev.codeanalyzer.core.analyzer.Analyzer;
import dev.codeanalyzer.core.analyzer.AnalysisContext;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;
import dev.codeanalyzer.core.model.SourceLocation;
import dev.codeanalyzer.core.model.ProjectMetadata;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PmdAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(PmdAnalyzer.class);

    @Override
    public String getId() { return "pmd"; }

    @Override
    public String getName() { return "PMD"; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();

        try {
            PMDConfiguration config = new PMDConfiguration();
            Language javaLang = LanguageRegistry.PMD.getLanguageById("java");
            LanguageVersion langVersion = resolveJavaVersion(javaLang, context.getMetadata());
            config.setDefaultLanguageVersion(langVersion);
            log.info("PMD using Java language version: {}", langVersion.getName());
            config.addInputPath(context.getProjectRoot());
            config.setAnalysisCacheLocation(
                context.getProjectRoot().resolve("target").resolve("pmd-cache").toString());

            config.addRuleSet("pmd-ruleset.xml");

            try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
                Report report = pmd.performAnalysisAndCollectReport();

                for (RuleViolation v : report.getViolations()) {
                    Path filePath = context.getProjectRoot().relativize(
                        java.nio.file.Paths.get(v.getFileId().getAbsolutePath()));

                    String pathStr = filePath.toString().replace('\\', '/');
                    if (pathStr.contains("/test/") || pathStr.contains("/tests/")) {
                        continue;
                    }

                    if (PmdFalsePositiveFilter.isFalsePositive(v)) {
                        continue;
                    }

                    String ruleName = v.getRule().getName();
                    findings.add(new Finding(
                        mapCategory(v.getRule().getRuleSetName()),
                        mapPriority(v.getRule().getPriority()),
                        confidenceForRule(ruleName),
                        "pmd." + ruleName,
                        ruleName,
                        v.getDescription(),
                        SourceLocation.of(
                            filePath.toString(),
                            v.getBeginLine(),
                            v.getEndLine(),
                            "",
                            ""),
                        PmdSuggestions.getSuggestion(ruleName, v.getRule().getDescription()),
                        Collections.singletonList(
                            "PMD rule " + ruleName + " at " + filePath + ":" + v.getBeginLine())
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("PMD analysis failed: {}", e.getMessage());
        }

        return findings;
    }

    @Override
    public List<Finding> analyze(List<ParsedSource> sources) {
        return Collections.emptyList();
    }

    /**
     * Honest confidence per PMD rule. Rules that match deterministic AST
     * patterns are HIGH; rules that depend on intent or context are MEDIUM;
     * rules with known false-positive risk on legacy code are LOW.
     */
    private Finding.Confidence confidenceForRule(String ruleName) {
        if (ruleName == null) return Finding.Confidence.MEDIUM;
        switch (ruleName) {
            // Deterministic — these are virtually always real
            case "MissingOverride":
            case "EmptyCatchBlock":
            case "EqualsNull":
            case "BrokenNullCheck":
            case "JumbledIncrementer":
            case "UnconditionalIfStatement":
            case "ClassCastExceptionWithToArray":
            case "UseEqualsToCompareStrings":
            case "CompareObjectsWithEquals":
            case "AvoidDecimalLiteralsInBigDecimalConstructor":
            case "BigIntegerInstantiation":
            case "StringInstantiation":
            case "StringToString":
            case "UseIndexOfChar":
            case "UseStringBufferLength":
            case "UselessStringValueOf":
                return Finding.Confidence.HIGH;

            // Heuristic — usually right but can have legitimate exceptions
            case "AvoidInstantiatingObjectsInLoops":
            case "ConstructorCallsOverridableMethod":
            case "AvoidLiteralsInIfCondition":
            case "GuardLogStatement":
            case "AvoidReassigningParameters":
                return Finding.Confidence.MEDIUM;

            // Unused-* rules — strong but symbol-resolver-dependent
            case "UnusedPrivateField":
            case "UnusedPrivateMethod":
            case "UnusedLocalVariable":
            case "UnusedFormalParameter":
            case "UnusedAssignment":
                return Finding.Confidence.HIGH;

            default:
                return Finding.Confidence.MEDIUM;
        }
    }

    private Finding.Severity mapPriority(RulePriority priority) {
        switch (priority) {
            case HIGH: return Finding.Severity.HIGH;
            case MEDIUM_HIGH:
            case MEDIUM: return Finding.Severity.MEDIUM;
            default: return Finding.Severity.LOW;
        }
    }

    private Finding.Category mapCategory(String ruleSet) {
        if (ruleSet == null) return Finding.Category.PMD_BEST_PRACTICES;
        String lower = ruleSet.toLowerCase();
        if (lower.contains("errorprone")) return Finding.Category.PMD_ERROR_PRONE;
        if (lower.contains("performance")) return Finding.Category.PMD_PERFORMANCE;
        return Finding.Category.PMD_BEST_PRACTICES;
    }

    private LanguageVersion resolveJavaVersion(Language java, ProjectMetadata metadata) {
        if (metadata == null || metadata.getJavaVersion() == null) {
            return java.getLatestVersion();
        }
        String ver = metadata.getJavaVersion().trim();
        // Normalize "1.8" to "8", "1.7" to "7", etc.
        if (ver.startsWith("1.")) {
            ver = ver.substring(2);
        }
        LanguageVersion resolved = java.getVersion(ver);
        if (resolved != null) {
            return resolved;
        }
        log.warn("PMD does not support Java version '{}', falling back to latest", metadata.getJavaVersion());
        return java.getLatestVersion();
    }
}
