package dev.codeanalyzer.core.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;
import dev.codeanalyzer.core.model.ProjectMetadata;
import dev.codeanalyzer.core.model.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Detects Spring-managed beans that appear to be unused.
 *
 * <p><b>Approach (A3 — AST-based reference graph):</b>
 * <ol>
 *   <li>Collect bean candidates from {@code @Component / @Service / @Repository / @Named}
 *       classes.</li>
 *   <li>Build a project-wide index of <em>real</em> identifier references by walking the
 *       parsed AST of every Java source (not text grep):
 *       {@code ClassOrInterfaceType}, {@code NameExpr}, {@code MethodReferenceExpr},
 *       and {@code StringLiteralExpr} (for {@code getBean("name")} patterns).</li>
 *   <li>For each bean candidate, look up its simple name, its camelCase bean name, and
 *       its implemented interfaces in the index. A reference outside the bean's own file
 *       means the bean is alive.</li>
 *   <li>If no AST reference exists, fall back to text-grep over non-Java config files
 *       (XML, YAML, properties, JSPs, .factories, .imports). Catches XML-wired beans and
 *       Spring SPI registrations.</li>
 *   <li>Only if neither the AST nor the config files reference the bean is it flagged.</li>
 * </ol>
 *
 * <p>The AST-first approach eliminates the false positives that text grep produces from
 * comments, javadoc, identical local-variable names, and accidental substring matches.
 * Findings produced here are {@link Finding.Confidence#HIGH} (deterministic AST + config
 * scan) when no {@code @Conditional*} is present, and {@link Finding.Confidence#MEDIUM}
 * when conditionally registered (since the wiring may depend on profile).
 *
 * <p>Skipped: {@code @Controller}/{@code @RestController}/HTTP entry points,
 * {@code @Configuration}, framework callback interfaces (Spring Batch tasklets, runners,
 * listeners, etc.), {@code @Scheduled}/{@code @EventListener}/{@code @PostConstruct} side-effect beans.
 */
public class DeadBeanAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(DeadBeanAnalyzer.class);

    private static final Set<String> BEAN_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "Component", "Service", "Repository", "Named"
    ));

    private static final Set<String> ENTRY_POINT_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "Controller", "RestController", "Configuration",
            "ControllerAdvice", "RestControllerAdvice",
            "Path", "RequestMapping"
    ));

    private static final Set<String> HTTP_METHOD_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping",
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"
    ));

    private static final Set<String> SIDE_EFFECT_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "Scheduled", "EventListener", "PostConstruct",
            "TransactionalEventListener"
    ));

    private static final Set<String> CONDITIONAL_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "ConditionalOnProperty", "ConditionalOnBean",
            "ConditionalOnMissingBean", "ConditionalOnClass", "Profile"
    ));

    private static final Set<String> FRAMEWORK_INTERFACES = new HashSet<>(Arrays.asList(
            "ApplicationListener", "ApplicationRunner", "CommandLineRunner",
            "InitializingBean", "DisposableBean", "BeanPostProcessor",
            "BeanFactoryPostProcessor", "HandlerInterceptor",
            "WebMvcConfigurer", "Filter", "HealthIndicator",
            "Converter", "Formatter", "PropertyEditor",
            "Tasklet", "ItemReader", "ItemWriter", "ItemProcessor",
            "StepExecutionListener", "JobExecutionListener",
            "ChunkListener", "SkipListener", "ItemReadListener",
            "ItemWriteListener", "ItemProcessListener",
            "CompletionPolicy", "Partitioner", "LineMapper",
            "FieldSetMapper", "LineTokenizer", "RowMapper"
    ));

    /** Non-Java config file extensions worth grepping (for XML/SPI/profile wiring). */
    private static final Set<String> CONFIG_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".xml", ".properties", ".yml", ".yaml",
            ".json", ".groovy", ".cfg", ".conf",
            ".xhtml", ".jsf", ".jspx", ".jsp", ".tld",
            ".factories", ".imports"
    ));

    @Override
    public String getId() { return "dead-bean"; }

    @Override
    public String getName() { return "Dead Bean Analyzer"; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<ParsedSource> sources = context.getSources();
        Path projectRoot = context.getProjectRoot();

        // Phase 1: Collect bean candidates from Java ASTs.
        Map<String, BeanInfo> declaredBeans = new LinkedHashMap<>();
        for (ParsedSource source : sources) {
            for (ClassOrInterfaceDeclaration clazz : source.getCompilationUnit()
                    .findAll(ClassOrInterfaceDeclaration.class)) {
                if (isBeanCandidate(clazz)) {
                    String name = clazz.getNameAsString();
                    Set<String> interfaceNames = extractImplementedTypes(clazz);
                    declaredBeans.put(name, new BeanInfo(name, interfaceNames, clazz, source));
                }
            }
        }
        log.info("Found {} bean candidates", declaredBeans.size());
        if (declaredBeans.isEmpty()) {
            return Collections.emptyList();
        }

        // Phase 2: Build the AST reference index (replaces the old text-grep pass).
        ReferenceIndex astIndex = buildAstReferenceIndex(sources);
        log.info("AST reference index: {} distinct identifiers, {} bean-name string literals",
                astIndex.identifierRefs.size(), astIndex.stringRefs.size());

        // Phase 3: Index non-Java config files (XML / properties / YAML / SPI .imports).
        // These are still grepped because they're not Java AST.
        Map<Path, String> configFiles = indexConfigFiles(projectRoot);
        log.info("Indexed {} non-Java config files", configFiles.size());

        // Phase 4: Decide for each bean.
        List<Finding> findings = new ArrayList<>();
        for (BeanInfo bean : declaredBeans.values()) {
            if (isFrameworkBean(bean) || hasSideEffects(bean.declaration)) {
                continue;
            }

            Path ownFile = bean.source.getFilePath().toAbsolutePath().normalize();
            String className = bean.name;
            String camelCaseBeanName = camelCase(className);

            // Build all aliases this bean could be referenced under.
            List<String> aliases = new ArrayList<>();
            aliases.add(className);
            aliases.addAll(bean.implementedTypes);

            // 4a. AST identifier reference outside the bean's own file?
            String astHit = findAstReference(aliases, astIndex.identifierRefs, ownFile);
            if (astHit != null) continue;

            // 4b. getBean("camelCaseName") / Class.forName("FQN") string-literal reference?
            String stringHit = findStringReference(className, camelCaseBeanName, astIndex.stringRefs, ownFile);
            if (stringHit != null) continue;

            // 4c. Non-Java config (XML/YAML/properties/SPI) reference?
            String configHit = findConfigReference(className, camelCaseBeanName, bean.implementedTypes,
                    configFiles, ownFile);
            if (configHit != null) continue;

            // No reference found anywhere. Emit a finding.
            boolean conditional = hasConditional(bean.declaration);
            List<String> evidence = new ArrayList<>();
            evidence.add("Scanned " + sources.size() + " Java source files for references to '"
                    + className + "', camelCase bean name '" + camelCaseBeanName + "', and interfaces "
                    + (bean.implementedTypes.isEmpty() ? "[]" : bean.implementedTypes)
                    + " — none found.");
            evidence.add("Scanned " + configFiles.size() + " non-Java config files (XML/YAML/properties/SPI"
                    + " .imports/.factories) for the bean name — none found.");
            if (conditional) {
                evidence.add("Bean has a @Conditional* or @Profile annotation; wiring may be profile-dependent.");
            }

            if (conditional) {
                findings.add(new Finding(
                        Finding.Category.ORPHAN_BEAN,
                        Finding.Severity.LOW,
                        Finding.Confidence.MEDIUM,
                        "orphan-bean.conditional",
                        "Conditional bean with no detected usage",
                        String.format("%s is annotated with a conditional and no reference to it " +
                                        "(class name, interface, camelCase bean name, or string literal) " +
                                        "was found anywhere in the project.", bean.name),
                        location(bean),
                        String.format(
                                "### Why this was flagged\n" +
                                "%s is annotated with @Conditional* / @Profile. A full AST + config scan " +
                                "found no reference to its class name, implemented interfaces, " +
                                "camelCase bean name, or string-literal lookups (getBean / Class.forName).\n" +
                                "### Possible explanations\n" +
                                "- The condition is never met in any active profile (effectively dead code)\n" +
                                "- Wired via a name not detectable by static analysis (e.g. dynamic property)\n" +
                                "### What to do\n" +
                                "- Check if the condition/profile is still active in any deployment\n" +
                                "- If unused, remove the class and its tests\n" +
                                "- If used dynamically, document the wiring mechanism in a comment",
                                bean.name),
                        evidence
                ));
            } else {
                findings.add(new Finding(
                        Finding.Category.ORPHAN_BEAN,
                        Finding.Severity.MEDIUM,
                        Finding.Confidence.HIGH,
                        "orphan-bean.unreferenced",
                        "Unused Spring bean",
                        String.format("%s is a Spring-managed bean. AST scan of all Java sources and a " +
                                        "config-file scan of XML/YAML/properties/SPI files found no " +
                                        "reference to it. Implemented interfaces: %s.",
                                bean.name, bean.implementedTypes.isEmpty() ? "none" : bean.implementedTypes),
                        location(bean),
                        String.format(
                                "### What was checked\n" +
                                "- Java AST: every `ClassOrInterfaceType`, `NameExpr`, `MethodReferenceExpr`, " +
                                "and `StringLiteralExpr` across the project was indexed and queried for " +
                                "the class name '%s', its interfaces %s, and the camelCase bean name '%s'.\n" +
                                "- Config files: %s — grepped for the same identifiers.\n" +
                                "### Recommended action\n" +
                                "```java\n" +
                                "// Remove the bean annotation and the class if no longer needed\n" +
                                "@Service  // <-- delete the annotation\n" +
                                "public class %s { ... }\n" +
                                "```\n" +
                                "### Impact of removal\n" +
                                "- Reduces Spring context startup time (fewer beans to instantiate)\n" +
                                "- Reduces cognitive overhead for developers navigating the codebase\n" +
                                "- Eliminates accidental dependency injection conflicts\n" +
                                "### Before removing, verify\n" +
                                "- Not used via reflection (`Class.forName(...)` with a dynamic FQN string)\n" +
                                "- Not loaded via Spring AOP, BeanPostProcessor, or auto-configuration imports\n" +
                                "- Not referenced in external configuration (deployment scripts, K8s manifests, etc.)",
                                bean.name,
                                bean.implementedTypes.isEmpty() ? "[]" : bean.implementedTypes,
                                camelCaseBeanName,
                                "XML, YAML, properties, JSP/JSF, Spring SPI",
                                bean.name),
                        evidence
                ));
            }
        }

        log.info("Dead bean analysis complete: {} unused beans found (AST-based)", findings.size());
        return findings;
    }

    @Override
    public List<Finding> analyze(List<ParsedSource> sources) {
        log.warn("DeadBeanAnalyzer called without AnalysisContext - config-file fallback disabled");
        return analyze(new AnalysisContext(sources, Paths.get("."), ProjectMetadata.empty()));
    }

    // -- AST reference index -------------------------------------------------

    /**
     * Walks every parsed source and indexes:
     * <ul>
     *   <li>{@code identifierRefs}: simple name → set of files where that identifier appears
     *       as a type, name expression, or method reference qualifier.</li>
     *   <li>{@code stringRefs}: string-literal value → set of files (for {@code getBean("x")},
     *       {@code Class.forName("a.b.Foo")}).</li>
     * </ul>
     */
    private ReferenceIndex buildAstReferenceIndex(List<ParsedSource> sources) {
        ReferenceIndex idx = new ReferenceIndex();
        for (ParsedSource src : sources) {
            Path file = src.getFilePath().toAbsolutePath().normalize();
            CompilationUnit cu = src.getCompilationUnit();

            for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
                idx.addIdentifier(t.getNameAsString(), file);
            }
            for (NameExpr n : cu.findAll(NameExpr.class)) {
                idx.addIdentifier(n.getNameAsString(), file);
            }
            for (MethodReferenceExpr mr : cu.findAll(MethodReferenceExpr.class)) {
                // Scope is an Expression; toString() is good enough to capture "Foo::bar".
                String scope = mr.getScope().toString();
                idx.addIdentifier(scope, file);
            }
            for (StringLiteralExpr s : cu.findAll(StringLiteralExpr.class)) {
                String v = s.getValue();
                // Cheap filter — bean names / FQNs only.
                if (v.length() > 0 && v.length() < 200
                        && (Character.isJavaIdentifierStart(v.charAt(0))
                            || v.indexOf('.') > 0)) {
                    idx.addString(v, file);
                }
            }
        }
        return idx;
    }

    private String findAstReference(List<String> aliases, Map<String, Set<Path>> refs, Path ownFile) {
        for (String alias : aliases) {
            Set<Path> hits = refs.get(alias);
            if (hits == null) continue;
            for (Path p : hits) {
                if (!p.equals(ownFile)) return alias + " referenced in " + p.getFileName();
            }
        }
        return null;
    }

    private String findStringReference(String className, String beanName,
                                       Map<String, Set<Path>> stringRefs, Path ownFile) {
        // Direct hits on the camelCase bean name or class name.
        for (String key : new String[]{className, beanName}) {
            Set<Path> hits = stringRefs.get(key);
            if (hits != null) {
                for (Path p : hits) {
                    if (!p.equals(ownFile)) return "string literal \"" + key + "\" in " + p.getFileName();
                }
            }
        }
        // FQN-style hits (anything ending in ".ClassName").
        String fqnSuffix = "." + className;
        for (Map.Entry<String, Set<Path>> e : stringRefs.entrySet()) {
            if (e.getKey().endsWith(fqnSuffix)) {
                for (Path p : e.getValue()) {
                    if (!p.equals(ownFile)) return "FQN string \"" + e.getKey() + "\" in " + p.getFileName();
                }
            }
        }
        return null;
    }

    // -- Config-file fallback ------------------------------------------------

    private Map<Path, String> indexConfigFiles(Path projectRoot) {
        Map<Path, String> index = new HashMap<>();
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (dirName.equals("target") || dirName.equals("build")
                            || dirName.equals("node_modules") || dirName.startsWith(".")
                            || dirName.equals("generated-sources") || dirName.equals("test-classes")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    for (String ext : CONFIG_EXTENSIONS) {
                        if (fileName.endsWith(ext)) {
                            try {
                                if (attrs.size() < 1_048_576) {
                                    index.put(file.toAbsolutePath().normalize(),
                                            new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                                }
                            } catch (IOException e) {
                                // skip
                            }
                            break;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error indexing config files: {}", e.getMessage());
        }
        return index;
    }

    private String findConfigReference(String className, String beanName, Set<String> interfaces,
                                       Map<Path, String> configs, Path ownFile) {
        List<String> terms = new ArrayList<>();
        terms.add(className);
        terms.add(beanName);
        terms.addAll(interfaces);
        for (Map.Entry<Path, String> e : configs.entrySet()) {
            if (e.getKey().equals(ownFile)) continue;
            String content = e.getValue();
            for (String term : terms) {
                if (term != null && !term.isEmpty() && content.contains(term)) {
                    return term + " in " + e.getKey().getFileName();
                }
            }
        }
        return null;
    }

    // -- Bean candidate detection --------------------------------------------

    private boolean isBeanCandidate(ClassOrInterfaceDeclaration clazz) {
        if (clazz.isInterface() || clazz.isAbstract()) return false;
        for (String ann : ENTRY_POINT_ANNOTATIONS) {
            if (clazz.getAnnotationByName(ann).isPresent()) return false;
        }
        boolean hasHttpMethods = clazz.getMethods().stream().anyMatch(m ->
                HTTP_METHOD_ANNOTATIONS.stream().anyMatch(a -> m.getAnnotationByName(a).isPresent()));
        if (hasHttpMethods) return false;
        for (String ann : BEAN_ANNOTATIONS) {
            if (clazz.getAnnotationByName(ann).isPresent()) return true;
        }
        return false;
    }

    private boolean isFrameworkBean(BeanInfo bean) {
        for (String iface : bean.implementedTypes) {
            if (FRAMEWORK_INTERFACES.contains(iface)) return true;
        }
        return false;
    }

    private Set<String> extractImplementedTypes(ClassOrInterfaceDeclaration clazz) {
        Set<String> types = new HashSet<>();
        for (ClassOrInterfaceType iface : clazz.getImplementedTypes()) types.add(iface.getNameAsString());
        for (ClassOrInterfaceType extended : clazz.getExtendedTypes()) types.add(extended.getNameAsString());
        return types;
    }

    private boolean hasSideEffects(ClassOrInterfaceDeclaration clazz) {
        return clazz.getMethods().stream().anyMatch(m ->
                SIDE_EFFECT_ANNOTATIONS.stream().anyMatch(a -> m.getAnnotationByName(a).isPresent())
        );
    }

    private boolean hasConditional(ClassOrInterfaceDeclaration clazz) {
        return CONDITIONAL_ANNOTATIONS.stream()
                .anyMatch(a -> clazz.getAnnotationByName(a).isPresent());
    }

    private static String camelCase(String name) {
        if (name == null || name.isEmpty()) return "";
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private SourceLocation location(BeanInfo bean) {
        int line = bean.declaration.getBegin().map(p -> p.line).orElse(0);
        int endLine = bean.declaration.getEnd().map(p -> p.line).orElse(line);
        return SourceLocation.of(
                bean.source.getRelativePathString(), line, endLine,
                bean.name, null);
    }

    // -- Inner types ---------------------------------------------------------

    private static class ReferenceIndex {
        final Map<String, Set<Path>> identifierRefs = new HashMap<>();
        final Map<String, Set<Path>> stringRefs = new HashMap<>();

        void addIdentifier(String name, Path file) {
            if (name == null || name.isEmpty()) return;
            identifierRefs.computeIfAbsent(name, k -> new HashSet<>()).add(file);
        }

        void addString(String value, Path file) {
            stringRefs.computeIfAbsent(value, k -> new HashSet<>()).add(file);
        }
    }

    private static class BeanInfo {
        final String name;
        final Set<String> implementedTypes;
        final ClassOrInterfaceDeclaration declaration;
        final ParsedSource source;

        BeanInfo(String name, Set<String> implementedTypes,
                 ClassOrInterfaceDeclaration declaration, ParsedSource source) {
            this.name = name;
            this.implementedTypes = implementedTypes;
            this.declaration = declaration;
            this.source = source;
        }
    }
}
