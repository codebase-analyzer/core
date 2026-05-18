package dev.codeanalyzer.core.analyzer.migration;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import dev.codeanalyzer.core.analyzer.AnalysisContext;
import dev.codeanalyzer.core.analyzer.Analyzer;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;
import dev.codeanalyzer.core.model.ProjectMetadata;
import dev.codeanalyzer.core.model.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Migration Readiness analyzer (Phase B1) — scans for known incompatibilities
 * between current and target versions of Java, Spring Boot, and Hibernate,
 * using {@link MigrationRuleRegistry}.
 *
 * <p>Auto-detects which migration paths are relevant by inspecting
 * {@link ProjectMetadata} (Java version + dependency coordinates):
 * <ul>
 *   <li>Java &lt; 17 → run Java 8→17 rules.</li>
 *   <li>{@code spring-boot:2.x} → run Spring Boot 2→3 rules.</li>
 *   <li>{@code hibernate-core:5.x} → run Hibernate 5→6 rules.</li>
 * </ul>
 *
 * <p>Findings are tagged with {@link Finding.Category#MIGRATION_BLOCKER} and
 * carry the migration {@link MigrationRule#getId()} as the {@code ruleId} so they
 * can be suppressed via {@code .codebase-analyzer.yml}.
 */
public class MigrationReadinessAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(MigrationReadinessAnalyzer.class);

    @Override
    public String getId() { return "migration-readiness"; }

    @Override
    public String getName() { return "Migration Readiness Analyzer"; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        ProjectMetadata meta = context.getMetadata();
        List<String> activeAreas = detectActiveAreas(meta);
        if (activeAreas.isEmpty()) {
            log.info("Migration scan: no upgrade paths applicable for this project");
            return Collections.emptyList();
        }
        log.info("Migration scan: active areas = {}", activeAreas);

        List<Finding> findings = new ArrayList<>();
        scanImports(context.getSources(), activeAreas, findings);
        scanClassReferences(context.getSources(), activeAreas, findings);
        scanAnnotations(context.getSources(), activeAreas, findings);
        scanConfigProperties(context.getProjectRoot(), activeAreas, findings);

        log.info("Migration scan complete: {} blockers found across {} areas",
                findings.size(), activeAreas.size());
        return findings;
    }

    @Override
    public List<Finding> analyze(List<ParsedSource> sources) {
        // Without metadata we can't auto-detect target versions; skip.
        return Collections.emptyList();
    }

    // ─── Active-area detection ───────────────────────────────────────────────

    /**
     * Decides which migration rule areas apply based on the project's current
     * Java version, dependency coordinates, AND root-pom version properties.
     *
     * <p>Properties are used because in multi-module Maven projects the actual
     * {@code <dependency>} entries usually live in submodules — but the root
     * pom always declares {@code <spring.version>}, {@code <hibernate.version>},
     * etc. in {@code <properties>}. Detecting via property keys catches projects
     * we'd otherwise mis-classify as having no frameworks.
     */
    private List<String> detectActiveAreas(ProjectMetadata meta) {
        List<String> areas = new ArrayList<>();
        if (meta == null) return areas;

        // Java: anything older than 17 is a candidate for the Java 8→17 path
        String jv = meta.getJavaVersion();
        if (jv != null && isOlderThanJava17(jv)) {
            areas.add(MigrationRuleRegistry.AREA_JAVA);
        }

        // Spring Boot 2.x — either via dependency coord or via property
        if (hasDependencyVersionStartingWith(meta, "org.springframework.boot", "2.")
                || propertyVersionStartsWith(meta, SPRING_BOOT_PROP_KEYS, "2.")) {
            areas.add(MigrationRuleRegistry.AREA_SPRING_BOOT);
        }
        // Raw Spring Framework (no Boot) 4.x/5.x — same Jakarta concerns apply
        else if (hasDependencyMatching(meta, RAW_SPRING_COORDS)
                || hasAnyProperty(meta, SPRING_FRAMEWORK_PROP_KEYS)) {
            // Spring Framework 4/5 → 6 maps to the same Spring Boot 2→3 rule set
            // (Jakarta namespace migration applies the same way)
            areas.add(MigrationRuleRegistry.AREA_SPRING_BOOT);
        }

        // Hibernate 5.x — coord OR property signal
        if (hasDependencyVersionStartingWith(meta, "org.hibernate", "5.")
                || hasDependencyVersionStartingWith(meta, "org.hibernate", "4.")
                || propertyVersionStartsWith(meta, HIBERNATE_PROP_KEYS, "5.")
                || propertyVersionStartsWith(meta, HIBERNATE_PROP_KEYS, "4.")) {
            areas.add(MigrationRuleRegistry.AREA_HIBERNATE);
        }

        return areas;
    }

    // Property keys commonly used in Spring Boot projects.
    private static final List<String> SPRING_BOOT_PROP_KEYS = Arrays.asList(
            "spring-boot.version", "spring.boot.version", "springBoot.version",
            "spring-boot-dependencies.version"
    );
    // Property keys for raw Spring Framework / Spring Web MVC / WebFlow projects.
    private static final List<String> SPRING_FRAMEWORK_PROP_KEYS = Arrays.asList(
            "spring.version", "spring-framework.version", "spring-core.version",
            "spring-context.version", "spring-web.version", "spring-webmvc.version",
            "spring-webflow.version", "spring-security.version", "spring-batch.version",
            "spring-data.version", "spring-orm.version"
    );
    // Raw Spring Framework dependency coordinates (not Boot).
    private static final List<String> RAW_SPRING_COORDS = Arrays.asList(
            "org.springframework:spring-core", "org.springframework:spring-context",
            "org.springframework:spring-web", "org.springframework:spring-webmvc",
            "org.springframework.webflow:spring-webflow",
            "org.springframework.security:spring-security-core",
            "org.springframework.batch:spring-batch-core"
    );
    // Property keys for Hibernate ORM.
    private static final List<String> HIBERNATE_PROP_KEYS = Arrays.asList(
            "hibernate.version", "hibernate-core.version", "hibernate.core.version",
            "hibernate-orm.version", "hibernate.orm.version", "hibernate-entitymanager.version"
    );

    /** True if any of the given property keys exists in metadata (regardless of value). */
    private boolean hasAnyProperty(ProjectMetadata meta, List<String> keys) {
        if (meta.getProperties() == null) return false;
        for (String k : keys) {
            if (meta.getProperties().containsKey(k)) return true;
        }
        return false;
    }

    /** True if any of the given property keys exists AND its resolved value starts with the version prefix. */
    private boolean propertyVersionStartsWith(ProjectMetadata meta, List<String> keys, String versionPrefix) {
        if (meta.getProperties() == null) return false;
        for (String k : keys) {
            String v = meta.getProperties().get(k);
            if (v != null && v.startsWith(versionPrefix)) return true;
        }
        return false;
    }

    /** True if the dependency map contains any of the given groupId:artifactId coords. */
    private boolean hasDependencyMatching(ProjectMetadata meta, List<String> coords) {
        if (meta.getDependencies() == null) return false;
        for (String c : coords) {
            if (meta.getDependencies().containsKey(c)) return true;
        }
        return false;
    }

    private boolean isOlderThanJava17(String version) {
        String v = version.trim();
        // Normalize "1.8" → "8"
        if (v.startsWith("1.")) v = v.substring(2);
        // Take leading digits
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < v.length() && Character.isDigit(v.charAt(i)); i++) {
            digits.append(v.charAt(i));
        }
        if (digits.length() == 0) return false;
        try { return Integer.parseInt(digits.toString()) < 17; }
        catch (NumberFormatException e) { return false; }
    }

    private boolean hasDependencyVersionStartingWith(ProjectMetadata meta, String groupPrefix, String versionPrefix) {
        if (meta == null || meta.getDependencies() == null) return false;
        for (Map.Entry<String, String> e : meta.getDependencies().entrySet()) {
            String key = e.getKey();
            String ver = e.getValue();
            if (key == null || !key.startsWith(groupPrefix)) continue;
            if (ver == null) continue;
            if (ver.startsWith(versionPrefix)) return true;
        }
        return false;
    }

    // ─── Scanners ────────────────────────────────────────────────────────────

    private void scanImports(List<ParsedSource> sources, List<String> areas, List<Finding> out) {
        List<MigrationRule> rules = MigrationRuleRegistry.getRulesByType(MigrationRuleType.IMPORT_PATTERN);
        for (ParsedSource src : sources) {
            CompilationUnit cu = src.getCompilationUnit();
            for (ImportDeclaration imp : cu.getImports()) {
                String name = imp.getNameAsString();
                if (imp.isAsterisk()) name = name + ".";
                for (MigrationRule rule : rules) {
                    if (!areas.contains(rule.getArea())) continue;
                    if (matchesImport(name, rule.getPattern())) {
                        out.add(buildFinding(rule, src.getRelativePathString(),
                                imp.getBegin().map(p -> p.line).orElse(0),
                                "import " + imp.getNameAsString()));
                    }
                }
            }
        }
    }

    private boolean matchesImport(String importName, String pattern) {
        // Pattern can be a prefix ("javax.persistence.") or an FQN ("javax.persistence.Entity").
        if (pattern.endsWith(".")) return importName.startsWith(pattern) || (importName + ".").startsWith(pattern);
        return importName.equals(pattern) || importName.startsWith(pattern + ".");
    }

    private void scanClassReferences(List<ParsedSource> sources, List<String> areas, List<Finding> out) {
        List<MigrationRule> rules = MigrationRuleRegistry.getRulesByType(MigrationRuleType.CLASS_REFERENCE);
        if (rules.isEmpty()) return;

        for (ParsedSource src : sources) {
            CompilationUnit cu = src.getCompilationUnit();

            // Type references — covers extends, implements, fields, params, returns, generics
            for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
                String simple = t.getNameAsString();
                int line = t.getBegin().map(p -> p.line).orElse(0);
                emitMatchesForName(simple, line, src, rules, areas, out);
            }
            // Name expressions — `Unsafe.getUnsafe()`, etc.
            for (NameExpr n : cu.findAll(NameExpr.class)) {
                String simple = n.getNameAsString();
                int line = n.getBegin().map(p -> p.line).orElse(0);
                emitMatchesForName(simple, line, src, rules, areas, out);
            }
        }
    }

    private void emitMatchesForName(String simple, int line, ParsedSource src,
                                     List<MigrationRule> rules, List<String> areas, List<Finding> out) {
        for (MigrationRule rule : rules) {
            if (!areas.contains(rule.getArea())) continue;
            if (simple.equals(rule.getPattern())) {
                out.add(buildFinding(rule, src.getRelativePathString(), line, simple));
            }
        }
    }

    private void scanAnnotations(List<ParsedSource> sources, List<String> areas, List<Finding> out) {
        List<MigrationRule> rules = MigrationRuleRegistry.getRulesByType(MigrationRuleType.ANNOTATION);
        if (rules.isEmpty()) return;

        for (ParsedSource src : sources) {
            CompilationUnit cu = src.getCompilationUnit();
            for (AnnotationExpr a : cu.findAll(AnnotationExpr.class)) {
                String name = a.getNameAsString();
                int line = a.getBegin().map(p -> p.line).orElse(0);
                for (MigrationRule rule : rules) {
                    if (!areas.contains(rule.getArea())) continue;
                    if (name.equals(rule.getPattern())) {
                        // Extra check for @Type(type="...") legacy form
                        if ("Type".equals(rule.getPattern()) && !looksLikeLegacyTypeAnnotation(a)) continue;
                        out.add(buildFinding(rule, src.getRelativePathString(), line, "@" + name));
                    }
                }
            }
        }
    }

    private boolean looksLikeLegacyTypeAnnotation(AnnotationExpr a) {
        // Only flag the string-based @Type(type="...") form, not the new @Type(MyClass.class)
        String s = a.toString();
        return s.contains("type") && s.contains("=") && s.contains("\"");
    }

    private void scanConfigProperties(Path projectRoot, List<String> areas, List<Finding> out) {
        List<MigrationRule> rules = MigrationRuleRegistry.getRulesByType(MigrationRuleType.CONFIG_PROPERTY);
        if (rules.isEmpty() || projectRoot == null) return;

        final List<String> activeAreas = areas;
        final List<MigrationRule> activeRules = rules;
        final List<Finding> findings = out;

        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dn = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (dn.equals("target") || dn.equals("build") || dn.startsWith(".")
                            || dn.equals("node_modules") || dn.equals("generated-sources")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    boolean isConfig = (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml")
                            || name.equals("spring.factories")
                            || name.equals("AutoConfiguration.imports"));
                    boolean isMeta = file.toString().replace('\\', '/').contains("META-INF/spring");
                    if (!isConfig && !isMeta) return FileVisitResult.CONTINUE;
                    if (attrs.size() > 524_288) return FileVisitResult.CONTINUE; // skip > 512KB

                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i);
                            for (MigrationRule rule : activeRules) {
                                if (!activeAreas.contains(rule.getArea())) continue;
                                if (matchesConfigLine(name, line, rule)) {
                                    String rel = projectRoot.relativize(file).toString().replace('\\', '/');
                                    findings.add(buildFinding(rule, rel, i + 1, line.trim()));
                                }
                            }
                        }
                    } catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("Config scan walk failed: {}", e.getMessage());
        }
    }

    private boolean matchesConfigLine(String fileName, String line, MigrationRule rule) {
        String pat = rule.getPattern();
        if ("spring.factories".equals(pat)) {
            return fileName.equals("spring.factories");
        }
        if (pat.endsWith(".")) {
            return line.contains(pat);
        }
        // Look for "key=" or "key:" prefixed by start-of-line or whitespace
        String trimmed = line.trim();
        return trimmed.startsWith(pat + "=") || trimmed.startsWith(pat + ":")
                || trimmed.startsWith(pat + " =") || trimmed.startsWith(pat + " :");
    }

    // ─── Finding builder ─────────────────────────────────────────────────────

    private Finding buildFinding(MigrationRule rule, String filePath, int line, String evidenceSnippet) {
        Finding.Confidence conf = (rule.getType() == MigrationRuleType.IMPORT_PATTERN
                || rule.getType() == MigrationRuleType.CONFIG_PROPERTY)
                ? Finding.Confidence.CERTAIN
                : Finding.Confidence.HIGH;

        SourceLocation loc = SourceLocation.of(filePath, line, line, null, null);

        String title = "[" + rule.getArea() + " " + rule.getSourceVersion() + "→" + rule.getTargetVersion() + "] "
                + rule.getTitle();

        String description = "Migration blocker for " + rule.getArea() + " " + rule.getSourceVersion()
                + " → " + rule.getTargetVersion() + ". " + rule.getReplacement();

        return new Finding(
                Finding.Category.MIGRATION_BLOCKER,
                rule.getSeverity(),
                conf,
                "migration." + rule.getId(),
                title,
                description,
                loc,
                rule.getSuggestion(),
                Arrays.asList(
                        "Rule: " + rule.getId() + " (" + rule.getType() + ")",
                        "Matched: " + evidenceSnippet,
                        "Estimated effort: ~" + rule.getEstimatedMinutes() + " minutes per occurrence"
                )
        );
    }
}
