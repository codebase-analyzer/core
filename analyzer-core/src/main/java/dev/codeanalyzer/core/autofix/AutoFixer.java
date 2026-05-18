package dev.codeanalyzer.core.autofix;

import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;

import java.util.Optional;

/**
 * Contract for code-fix generators.
 *
 * <p>Each {@code AutoFixer} targets one specific finding {@link Finding#getRuleId()
 * ruleId} (or a narrow family of related rules) and produces a {@link Patch}
 * that, when applied, makes the finding disappear without breaking the code.
 *
 * <p>Fixers must NOT modify files. They only produce {@link Patch} objects
 * representing the proposed change. Applying patches to disk is handled
 * separately by the CLI {@code --fix} flag.
 *
 * <p>Implementations should be stateless and deterministic — given the same
 * {@link Finding} and {@link ParsedSource}, they must always produce the same
 * {@link Patch} (or empty Optional).
 */
public interface AutoFixer {

    /**
     * The {@code Finding.ruleId} this fixer handles, e.g.
     * {@code "pmd.MissingOverride"}.
     */
    String getRuleId();

    /**
     * Cheap pre-check. Default: matches any finding whose ruleId equals
     * {@link #getRuleId()}. Override when a fixer wants to look at additional
     * metadata (severity, evidence, message text) before committing.
     */
    default boolean canFix(Finding finding, ParsedSource source) {
        return finding != null && getRuleId().equals(finding.getRuleId());
    }

    /**
     * Attempt to generate a patch. Returns {@link Optional#empty()} when the
     * fix cannot be confidently produced (AST shape unexpected, ambiguity,
     * edge case, etc.). Returning empty is preferred over throwing.
     */
    Optional<Patch> generateFix(Finding finding, ParsedSource source);
}
