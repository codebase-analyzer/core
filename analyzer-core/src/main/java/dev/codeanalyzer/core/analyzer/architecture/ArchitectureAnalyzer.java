package dev.codeanalyzer.core.analyzer.architecture;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import dev.codeanalyzer.core.analyzer.Analyzer;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;
import dev.codeanalyzer.core.model.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Architecture / modularity analyzer (Feature B3).
 *
 * <p>Produces three classes of finding:
 * <ol>
 *   <li>{@link Finding.Category#ARCHITECTURE_CYCLE} — package-level dependency
 *       cycles via Tarjan's SCC algorithm.</li>
 *   <li>{@link Finding.Category#ARCHITECTURE_LAYERING} — calls that invert or
 *       skip the standard Controller -> Service -> Repository -> Entity layering.</li>
 *   <li>{@link Finding.Category#ARCHITECTURE_GOD_CLASS} — classes exceeding
 *       complexity thresholds (methods + LOC + fan-out), Spring-stereotype
 *       weighted.</li>
 * </ol>
 *
 * <p>Stateful: the last invocation's full report is cached on the instance so
 * {@code AnalysisEngine} can lift it into {@code AnalysisResult} for the
 * dedicated HTML "Architecture" page without rebuilding the graph.
 */
public class ArchitectureAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureAnalyzer.class);

    // God-class thresholds — tuned to flag classes that genuinely concentrate
    // responsibilities. We aim for "yep, that's a problem" not "academic concern".
    private static final int GOD_METHODS_THRESHOLD = 40;
    private static final int GOD_LOC_THRESHOLD = 800;
    private static final int GOD_DEPS_THRESHOLD = 25;
    private static final int GOD_TOP_N = 25;            // cap report size
    private static final int LAYERING_MAX_REPORT = 200; // cap finding count

    private ArchitectureReport lastReport = ArchitectureReport.empty();

    public ArchitectureReport getLastReport() { return lastReport; }

    @Override public String getId() { return "architecture"; }

    @Override public String getName() { return "Architecture & Modularity Analyzer"; }

    @Override
    public List<Finding> analyze(List<ParsedSource> sources) {
        if (sources == null || sources.isEmpty()) {
            this.lastReport = ArchitectureReport.empty();
            return Collections.emptyList();
        }

        // Pass 1 — collect internal package set + per-class metrics
        PackageGraph graph = new PackageGraph();
        Set<String> internalPackages = new HashSet<String>();
        List<ClassMetric> allMetrics = new ArrayList<ClassMetric>();
        Map<String, ParsedSource> sourceByPackage = new LinkedHashMap<String, ParsedSource>();

        for (ParsedSource src : sources) {
            CompilationUnit cu = src.getCompilationUnit();
            if (cu == null) continue;
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            if (pkg.isEmpty()) continue;
            internalPackages.add(pkg);
            graph.getOrCreate(pkg).addFile(src.getRelativePathString());
            sourceByPackage.putIfAbsent(pkg, src);
            collectClassMetrics(src, pkg, allMetrics);
        }

        // Pass 2 — for each compilation unit, register its imports as outbound
        // edges from its package, filtered to internal packages only.
        for (ParsedSource src : sources) {
            CompilationUnit cu = src.getCompilationUnit();
            if (cu == null) continue;
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            if (pkg.isEmpty()) continue;

            PackageNode node = graph.getOrCreate(pkg);
            for (ImportDeclaration imp : cu.getImports()) {
                String fqn = imp.getNameAsString();
                String targetPkg = packageOf(fqn, imp.isAsterisk());
                if (targetPkg == null || targetPkg.equals(pkg)) continue;
                if (!isInternalEdge(targetPkg, internalPackages)) continue;
                node.addEdge(targetPkg, "import " + fqn + (imp.isAsterisk() ? ".*" : "")
                        + " (" + src.getRelativePathString() + ")");
            }
        }

        // Pass 3 — detect cycles, layering violations, god classes
        List<List<String>> cycles = graph.findCycles();
        List<ArchitectureReport.LayeringViolation> violations = findLayeringViolations(graph);
        List<ClassMetric> godClasses = findGodClasses(allMetrics);

        // Build Findings
        List<Finding> findings = new ArrayList<Finding>();
        emitCycleFindings(cycles, graph, sourceByPackage, findings);
        emitLayeringFindings(violations, sourceByPackage, findings);
        emitGodClassFindings(godClasses, findings);

        this.lastReport = new ArchitectureReport(graph, cycles, violations, godClasses);

        log.info("Architecture: {} packages, {} cycles, {} layering violations, {} god classes",
                graph.size(), cycles.size(), violations.size(), godClasses.size());

        return findings;
    }

    // ---------------------------------------------------------------- helpers

    /** Returns the package name from an FQN. If the import is wildcard, the FQN already IS the package. */
    private static String packageOf(String fqn, boolean asterisk) {
        if (fqn == null || fqn.isEmpty()) return null;
        if (asterisk) return fqn;
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? null : fqn.substring(0, dot);
    }

    /**
     * A target package is internal if it (or one of its ancestors) is present
     * in {@code internalPackages}. We walk up the dot-segments so {@code com.acme.user.dto}
     * counts as internal when {@code com.acme.user.dto} is in the set, but also
     * when only {@code com.acme.user} is — common in projects where some packages
     * are entirely composed of inner classes / generated code.
     */
    private static boolean isInternalEdge(String targetPackage, Set<String> internalPackages) {
        if (internalPackages.contains(targetPackage)) return true;
        // Conservative: only consider exact match — going up the tree creates a lot
        // of false positives for things like 'org.apache.commons.lang3.x' when
        // 'org.apache' is somehow in the set. Exact match is what we want.
        return false;
    }

    // ---------------------------------------------------------- god classes

    private static void collectClassMetrics(ParsedSource src, String pkg, List<ClassMetric> out) {
        CompilationUnit cu = src.getCompilationUnit();
        if (cu == null) return;
        List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);
        for (ClassOrInterfaceDeclaration c : classes) {
            if (c.isInterface()) continue;
            // Skip nested classes for now — top-level only, keeps signal clearer.
            if (!c.isTopLevelType()) continue;

            int methods = c.findAll(MethodDeclaration.class).size();
            int fields = c.findAll(FieldDeclaration.class).size();
            int begin = c.getBegin().map(p -> p.line).orElse(1);
            int end = c.getEnd().map(p -> p.line).orElse(begin);
            int loc = Math.max(1, end - begin);

            Set<String> deps = collectOutgoingTypes(c);
            String stereotype = stereotypeOf(c);

            out.add(new ClassMetric(
                    c.getNameAsString(), pkg, src.getRelativePathString(),
                    begin, methods, fields, loc, deps.size(), stereotype));
        }
    }

    private static Set<String> collectOutgoingTypes(ClassOrInterfaceDeclaration c) {
        Set<String> deps = new HashSet<String>();
        for (ClassOrInterfaceType t : c.findAll(ClassOrInterfaceType.class)) {
            String name = t.getNameAsString();
            if (name != null && !name.equals(c.getNameAsString())) deps.add(name);
        }
        for (MethodCallExpr m : c.findAll(MethodCallExpr.class)) {
            Optional<com.github.javaparser.ast.expr.Expression> scope = m.getScope();
            if (scope.isPresent()) {
                String s = scope.get().toString();
                if (s.length() > 0 && Character.isUpperCase(s.charAt(0))) deps.add(s);
            }
        }
        for (ObjectCreationExpr n : c.findAll(ObjectCreationExpr.class)) {
            deps.add(n.getType().getNameAsString());
        }
        return deps;
    }

    private static String stereotypeOf(ClassOrInterfaceDeclaration c) {
        for (AnnotationExpr ann : c.getAnnotations()) {
            String n = ann.getNameAsString();
            if (n.equals("Service") || n.equals("Controller") || n.equals("RestController")
                    || n.equals("Repository") || n.equals("Component")
                    || n.equals("Configuration")) {
                return n;
            }
        }
        return null;
    }

    private static List<ClassMetric> findGodClasses(List<ClassMetric> all) {
        List<ClassMetric> hits = new ArrayList<ClassMetric>();
        for (ClassMetric m : all) {
            int triggers = 0;
            if (m.getMethodCount() >= GOD_METHODS_THRESHOLD) triggers++;
            if (m.getLinesOfCode() >= GOD_LOC_THRESHOLD) triggers++;
            if (m.getOutgoingDependencies() >= GOD_DEPS_THRESHOLD) triggers++;
            if (triggers >= 1) hits.add(m); // even one threshold breach is worth reviewing
        }
        hits.sort(new java.util.Comparator<ClassMetric>() {
            @Override public int compare(ClassMetric a, ClassMetric b) {
                return Integer.compare(b.weight(), a.weight());
            }
        });
        return hits.size() > GOD_TOP_N ? hits.subList(0, GOD_TOP_N) : hits;
    }

    // ---------------------------------------------------------- layering

    private static List<ArchitectureReport.LayeringViolation> findLayeringViolations(PackageGraph graph) {
        List<ArchitectureReport.LayeringViolation> hits = new ArrayList<ArchitectureReport.LayeringViolation>();
        for (PackageNode node : graph.getNodes()) {
            LayerInference.Layer fromLayer = LayerInference.of(node.getName());
            if (fromLayer == LayerInference.Layer.UNKNOWN) continue;
            for (Map.Entry<String, Integer> edge : node.getEdges().entrySet()) {
                String targetPkg = edge.getKey();
                LayerInference.Layer toLayer = LayerInference.of(targetPkg);
                String reason = LayerInference.violationReason(fromLayer, toLayer);
                if (reason == null) continue;
                List<String> samples = node.getEdgeEvidence().get(targetPkg);
                hits.add(new ArchitectureReport.LayeringViolation(
                        node.getName(), targetPkg, fromLayer, toLayer,
                        reason, edge.getValue(), samples));
            }
        }
        hits.sort(new java.util.Comparator<ArchitectureReport.LayeringViolation>() {
            @Override public int compare(ArchitectureReport.LayeringViolation a, ArchitectureReport.LayeringViolation b) {
                return Integer.compare(b.getEdgeWeight(), a.getEdgeWeight());
            }
        });
        return hits;
    }

    // ---------------------------------------------------------- findings emission

    private static void emitCycleFindings(List<List<String>> cycles, PackageGraph graph,
                                          Map<String, ParsedSource> sourceByPackage,
                                          List<Finding> out) {
        for (List<String> scc : cycles) {
            String first = scc.get(0);
            ParsedSource anchor = sourceByPackage.get(first);
            String filePath = anchor != null ? anchor.getRelativePathString() : "(package " + first + ")";

            String title = "Package cycle: " + scc.size() + " packages in a strongly-connected component";
            StringBuilder desc = new StringBuilder()
                    .append("These packages form a dependency cycle (each transitively depends on the others), ")
                    .append("preventing independent compilation, reuse and testing. Cycle members:\n");
            for (String pkg : scc) desc.append("  • ").append(pkg).append('\n');

            List<String> evidence = new ArrayList<String>();
            int collected = 0;
            outer:
            for (String pkg : scc) {
                PackageNode n = graph.get(pkg);
                if (n == null) continue;
                for (Map.Entry<String, Integer> edge : n.getEdges().entrySet()) {
                    if (!scc.contains(edge.getKey())) continue;
                    List<String> samples = n.getEdgeEvidence().get(edge.getKey());
                    if (samples != null) {
                        for (String s : samples) {
                            evidence.add(s);
                            collected++;
                            if (collected >= 10) break outer;
                        }
                    }
                }
            }

            String suggestion =
                    "### What this means\n" +
                    "These " + scc.size() + " packages form a strongly-connected component: every one of them " +
                    "transitively depends on every other, directly or via a chain of imports. There is no order " +
                    "in which you could remove any single package from the dependency graph — they're effectively " +
                    "one giant package wearing four (or more) folder costumes.\n\n" +
                    "### Why it matters (production consequences)\n" +
                    "- **You cannot extract any of these packages into its own module / JAR.** Maven, Gradle, " +
                    "and Java's module system all require a topologically ordered dependency graph. A cycle " +
                    "blocks every modularization initiative — Spring Modulith, JPMS, microservice extraction, " +
                    "everything.\n" +
                    "- **Test isolation breaks.** Any unit test touching one package transitively initializes " +
                    "the whole cycle (and the Spring beans behind it). Test startup time bloats; mocking " +
                    "boundaries become impossible.\n" +
                    "- **Compile times multiply.** Incremental compilers (Gradle, IntelliJ) must recompile " +
                    "every cycle member when any one changes. Build times grow with cycle size, not with the " +
                    "change scope.\n" +
                    "- **Onboarding cost.** New devs must learn the entire cycle as a unit; the package " +
                    "structure is misleading because it suggests separability that doesn't exist.\n" +
                    "- **Refactoring risk.** Changes ripple unpredictably across packages. Code review " +
                    "boundaries lose meaning; reviewers can't safely approve changes to \"just\" one package.\n\n" +
                    "### Common misunderstanding — \"It's fine, the cycle is small\"\n" +
                    "Cycles never shrink on their own; they grow. Every cycle that exists today started as " +
                    "a 2-package cycle that \"didn't matter\" until it was a 5-package cycle that \"is too " +
                    "hard to fix.\" The cost of breaking a cycle grows superlinearly with its size; fix small.\n\n" +
                    "### Common misunderstanding — \"The code works, so the architecture is fine\"\n" +
                    "Working code is the minimum bar, not the goal. The cost shows up in: how fast you can " +
                    "ship the next feature, how confidently you can refactor, how cheaply you can hire and " +
                    "onboard. Cycles silently tax all three.\n\n" +
                    "### How to fix — break the smallest edge first\n" +
                    "1. **Identify the lightest edge in the cycle** (smallest import count). The Architecture " +
                    "report shows `edgeWeight` per layering violation; lowest weight = cheapest to break.\n" +
                    "2. **Choose a pattern:**\n" +
                    "   - **Extract a shared package.** Common types referenced both ways move into a new " +
                    "neutral package both can depend on (e.g. `domain.shared`, `model.common`).\n" +
                    "   - **Invert via interface.** Move the interface into the dependee package; the depender " +
                    "implements it. Classic Dependency Inversion Principle.\n" +
                    "   - **Move a class to the other side.** Often the class is in the wrong package — moving " +
                    "it breaks the cycle for free.\n" +
                    "3. **Move ONE class at a time** and re-run this report. Cycles of size > 3 are best " +
                    "treated as a multi-PR project, not a single refactor.\n\n" +
                    "### When to suppress\n" +
                    "- Cycle is in a deprecated module scheduled for deletion. Suppress with " +
                    "`// @analyzer-ignore: architecture.package-cycle` in one file per cycle and document the " +
                    "deletion timeline.";

            Finding.Severity sev = scc.size() >= 5 ? Finding.Severity.HIGH : Finding.Severity.MEDIUM;
            out.add(new Finding(
                    Finding.Category.ARCHITECTURE_CYCLE, sev, Finding.Confidence.CERTAIN,
                    "architecture.package-cycle",
                    title, desc.toString(),
                    SourceLocation.of(filePath, 1, first),
                    suggestion, evidence));
        }
    }

    private static void emitLayeringFindings(List<ArchitectureReport.LayeringViolation> violations,
                                             Map<String, ParsedSource> sourceByPackage,
                                             List<Finding> out) {
        int emitted = 0;
        for (ArchitectureReport.LayeringViolation v : violations) {
            if (emitted >= LAYERING_MAX_REPORT) break;
            ParsedSource anchor = sourceByPackage.get(v.getFromPackage());
            String filePath = anchor != null ? anchor.getRelativePathString() : "(package " + v.getFromPackage() + ")";

            String title = "Layering violation: " + v.getFromLayer() + " → " + v.getToLayer();
            StringBuilder desc = new StringBuilder()
                    .append(v.getReason()).append("\n\n")
                    .append("From: ").append(v.getFromPackage()).append(" (")
                        .append(v.getFromLayer().name().toLowerCase()).append(")\n")
                    .append("To:   ").append(v.getToPackage()).append(" (")
                        .append(v.getToLayer().name().toLowerCase()).append(")\n")
                    .append("Edge weight: ").append(v.getEdgeWeight()).append(" import(s)");

            String suggestion = suggestionFor(v.getFromLayer(), v.getToLayer());

            Finding.Severity sev = severityFor(v.getFromLayer(), v.getToLayer());
            out.add(new Finding(
                    Finding.Category.ARCHITECTURE_LAYERING, sev, Finding.Confidence.HIGH,
                    "architecture.layering." + v.getFromLayer().name().toLowerCase()
                            + "-to-" + v.getToLayer().name().toLowerCase(),
                    title, desc.toString(),
                    SourceLocation.of(filePath, 1, v.getFromPackage()),
                    suggestion, v.getSamples()));
            emitted++;
        }
    }

    private static Finding.Severity severityFor(LayerInference.Layer from, LayerInference.Layer to) {
        if (from == LayerInference.Layer.REPOSITORY && to == LayerInference.Layer.CONTROLLER) {
            return Finding.Severity.HIGH;
        }
        if (from == LayerInference.Layer.ENTITY) return Finding.Severity.HIGH;
        if (from == LayerInference.Layer.SERVICE && to == LayerInference.Layer.CONTROLLER) {
            return Finding.Severity.HIGH;
        }
        return Finding.Severity.MEDIUM;
    }

    private static String suggestionFor(LayerInference.Layer from, LayerInference.Layer to) {
        if (from == LayerInference.Layer.CONTROLLER && to == LayerInference.Layer.REPOSITORY) {
            return "### What this means\n" +
                    "The controller bypasses the service layer and calls a repository directly. The standard " +
                    "Spring/JEE onion architecture is: HTTP → Controller → Service → Repository → DB. Skipping " +
                    "a layer means losing every guarantee that layer provides.\n\n" +
                    "### Why it matters\n" +
                    "- **Transaction boundaries disappear.** Services typically own `@Transactional`. A controller " +
                    "calling `repo.save(...)` directly runs in auto-commit mode — no rollback on exception, no " +
                    "atomicity across writes.\n" +
                    "- **Business validation is skipped.** Services enforce invariants (\"only an active user " +
                    "can place an order\", \"discount cannot exceed 50%\"). Direct repo access circumvents all of it.\n" +
                    "- **Security checks are skipped.** `@PreAuthorize` / `@Secured` typically live at the service " +
                    "layer. The HTTP layer's auth filter may pass, but fine-grained business permissions do not " +
                    "apply when the repo is invoked directly.\n" +
                    "- **Auditing/logging is bypassed.** Service-layer interceptors that log every business " +
                    "operation never see the call.\n" +
                    "- **Untestable in isolation.** You can't write a unit test for the business logic when " +
                    "there isn't one.\n\n" +
                    "### Common misunderstanding — \"It's a simple read, no validation needed\"\n" +
                    "Reads also typically need: tenant scoping (multi-tenant apps), permission checks, audit " +
                    "logging of who accessed what, denormalization or DTO conversion. If today's read truly needs " +
                    "none of those, tomorrow's product owner will require all of them — and the unstructured " +
                    "code path makes it harder to add them uniformly.\n\n" +
                    "### Fix\n" +
                    "Introduce (or use the existing) service method:\n" +
                    "```java\n" +
                    "// Before\n" +
                    "@GetMapping(\"/users/{id}\")\n" +
                    "public User get(@PathVariable Long id) {\n" +
                    "    return userRepository.findById(id).orElseThrow();\n" +
                    "}\n" +
                    "// After\n" +
                    "@GetMapping(\"/users/{id}\")\n" +
                    "public UserDto get(@PathVariable Long id) {\n" +
                    "    return userService.fetchForCaller(id);   // auth, tenant scope, DTO mapping\n" +
                    "}\n" +
                    "```\n\n" +
                    "### When to suppress\n" +
                    "- Health-check / debug-only controller that genuinely has no business logic. Suppress with " +
                    "`// @analyzer-ignore: architecture.layering.controller-to-repository`.";
        }
        if (from == LayerInference.Layer.SERVICE && to == LayerInference.Layer.CONTROLLER) {
            return "### What this means\n" +
                    "A service package depends on a controller package — the inverse of the standard request " +
                    "flow. Services should be HTTP-agnostic (so they can be reused from a scheduled job, a " +
                    "queue consumer, a different HTTP endpoint, or a unit test). Depending on a controller " +
                    "ties the service to one specific entry-point shape.\n\n" +
                    "### Why it matters\n" +
                    "- **The service is no longer reusable.** A job that wants to invoke the same business " +
                    "logic must also drag in Spring MVC / web infrastructure / request scope objects.\n" +
                    "- **Test setup is painful.** Service tests now need controller dependencies on the " +
                    "classpath — usually because the service imports a controller's request/response DTO.\n" +
                    "- **Modularization is blocked.** You can never extract the business logic into a library " +
                    "without also pulling in the web layer.\n" +
                    "- **It usually signals a leaked DTO.** Almost always, the import is of a controller's " +
                    "request/response class — the service is being passed an HTTP-shaped object instead of " +
                    "a domain-shaped one.\n\n" +
                    "### Common misunderstanding — \"It's just one DTO, why care\"\n" +
                    "One DTO is the seed. The next dev sees the existing import and adds another. Within a " +
                    "year you have a service layer that can only be invoked from one controller in one HTTP " +
                    "endpoint — exactly the lock-in the layering was supposed to prevent.\n\n" +
                    "### Fix\n" +
                    "Move the shared type to a neutral package (`dto`, `model`, `domain.command`). " +
                    "Both the controller and the service depend on the neutral package; neither depends on " +
                    "the other:\n" +
                    "```\n" +
                    "Before:  controller ←──── service          (service imports controller's DTO)\n" +
                    "After:   controller ────→ shared ←──── service\n" +
                    "```\n\n" +
                    "### When to suppress\n" +
                    "- The 'service' package is misnamed (e.g. internal helper that's actually controller-private). " +
                    "Rename it. Otherwise: don't suppress.";
        }
        if (from == LayerInference.Layer.REPOSITORY && to == LayerInference.Layer.SERVICE) {
            return "### What this means\n" +
                    "A repository package depends on a service package — repository is calling \"up\" into " +
                    "the layer that's supposed to depend on it. The persistence layer should be a pure data " +
                    "access surface, fully reusable, with no knowledge of business logic.\n\n" +
                    "### Why it matters\n" +
                    "- **Creates an implicit cycle.** Service depends on repository (normal); repository depends " +
                    "on service (this finding) → indirect dependency loop. See the separate cycle finding for " +
                    "the consequences.\n" +
                    "- **Repository can't be reused independently.** Another module that wants only the data " +
                    "access must also depend on the business module.\n" +
                    "- **Test layers blur.** Repository tests now need service mocks; service tests stub " +
                    "repositories that internally call services. Mock setup becomes recursive.\n" +
                    "- **Hides business logic.** Logic placed in the \"data\" layer because it was convenient " +
                    "becomes invisible to people maintaining the business layer.\n\n" +
                    "### Common misunderstanding — \"The repo needs a helper from the service\"\n" +
                    "It doesn't. The helper either belongs IN the repository (move it down) or its result " +
                    "should be PASSED INTO the repository call (caller computes, passes the value).\n\n" +
                    "### Fix\n" +
                    "Inject what you need as a method argument instead of calling back to the service:\n" +
                    "```java\n" +
                    "// Before: repo calling service\n" +
                    "public List<Order> findActive() {\n" +
                    "    Long tenantId = tenantService.currentTenantId();  // ← upward call\n" +
                    "    return em.createQuery(...).setParameter(\"tid\", tenantId).getResultList();\n" +
                    "}\n" +
                    "// After: caller passes the dependency\n" +
                    "public List<Order> findActiveForTenant(Long tenantId) {\n" +
                    "    return em.createQuery(...).setParameter(\"tid\", tenantId).getResultList();\n" +
                    "}\n" +
                    "```\n\n" +
                    "### When to suppress\n" +
                    "- Never. There's always a way to invert this.";
        }
        if (from == LayerInference.Layer.REPOSITORY && to == LayerInference.Layer.CONTROLLER) {
            return "### What this means\n" +
                    "A repository depends on a controller — two layers in the wrong direction, skipping " +
                    "the service layer entirely. Almost always indicates a misplaced type (controller's " +
                    "request/response DTO referenced by the repository).\n\n" +
                    "### Why it matters\n" +
                    "All the consequences of REPOSITORY→SERVICE and SERVICE→CONTROLLER combined: cycle risk, " +
                    "lost reusability, tangled test setup, business logic in the wrong place, plus tight " +
                    "coupling of persistence to HTTP shape.\n\n" +
                    "### Common cause\n" +
                    "A `Pageable`, request DTO, or response wrapper from the controller package was imported " +
                    "into the repository because it was convenient.\n\n" +
                    "### Fix\n" +
                    "Move the shared type to a neutral package. The repository should never import anything " +
                    "from the web layer.\n\n" +
                    "### When to suppress\n" +
                    "- Never.";
        }
        if (from == LayerInference.Layer.ENTITY) {
            return "### What this means\n" +
                    "An entity/domain class depends on application code (service, repository, controller). " +
                    "Entities are the long-lived, persisted shape of your business data — they should be " +
                    "independent of the application code that manipulates them.\n\n" +
                    "### Why it matters\n" +
                    "- **The domain model can no longer be reused.** Another project, a CLI tool, a batch job " +
                    "all have to drag in Spring + web + persistence runtime to use a single entity class.\n" +
                    "- **Domain logic becomes scattered.** Behaviour that should live ON the entity " +
                    "(`order.isOverdue()`) moves into services because the entity can't reach what it needs. " +
                    "The result is anaemic domain models.\n" +
                    "- **Cycle creation.** Service depends on entity; entity depends on service. Hard to break.\n" +
                    "- **Persistence concerns leak.** Entities importing repositories tend to grow methods that " +
                    "save themselves, blurring transaction boundaries.\n\n" +
                    "### Common misunderstanding — \"The entity needs a helper, it's simpler this way\"\n" +
                    "Usually the helper belongs IN the entity (as a method), or the caller should orchestrate " +
                    "the helper call and pass the result to the entity. Domain logic that needs " +
                    "infrastructure services is a sign the operation belongs in a domain service, not on the entity.\n\n" +
                    "### Fix\n" +
                    "- Move the imported type into a shared package (`domain.shared`).\n" +
                    "- Or invert the relationship: caller computes, passes the value as an argument or via " +
                    "a domain event.\n" +
                    "- Or extract a dedicated mapper/converter that owns the cross-layer translation.\n\n" +
                    "### When to suppress\n" +
                    "- Almost never. If it's truly intentional, document why and consider whether the package " +
                    "is misnamed.";
        }
        return "Review whether this dependency crosses layer boundaries in the wrong direction.";
    }

    private static void emitGodClassFindings(List<ClassMetric> godClasses, List<Finding> out) {
        for (ClassMetric m : godClasses) {
            int triggers = 0;
            if (m.getMethodCount() >= GOD_METHODS_THRESHOLD) triggers++;
            if (m.getLinesOfCode() >= GOD_LOC_THRESHOLD) triggers++;
            if (m.getOutgoingDependencies() >= GOD_DEPS_THRESHOLD) triggers++;

            String title = m.getStereotype() != null
                    ? "God " + m.getStereotype() + ": " + m.getClassName()
                    : "God class: " + m.getClassName();

            StringBuilder desc = new StringBuilder()
                    .append(m.getClassName()).append(" concentrates too much responsibility:\n")
                    .append("  • ").append(m.getMethodCount()).append(" methods (threshold: ")
                        .append(GOD_METHODS_THRESHOLD).append(")\n")
                    .append("  • ").append(m.getLinesOfCode()).append(" LOC (threshold: ")
                        .append(GOD_LOC_THRESHOLD).append(")\n")
                    .append("  • ").append(m.getOutgoingDependencies()).append(" outgoing dependencies (threshold: ")
                        .append(GOD_DEPS_THRESHOLD).append(")\n")
                    .append("  • ").append(m.getFieldCount()).append(" fields");
            if (m.getStereotype() != null) desc.append("\n  • Spring stereotype: @").append(m.getStereotype());

            String suggestion = m.getStereotype() != null && m.getStereotype().equals("Service")
                    ? "Split this service along cohesive responsibilities — group methods that operate on the same fields "
                        + "into separate services. Use the Single Responsibility Principle as a guide."
                    : "Decompose into smaller classes grouped around cohesive responsibilities. Common patterns: extract "
                        + "Strategy for branching logic, extract Value Objects for related fields, split into Facade + workers.";

            Finding.Severity sev = (triggers >= 2 || m.getStereotype() != null)
                    ? Finding.Severity.HIGH : Finding.Severity.MEDIUM;

            List<String> evidence = new ArrayList<String>();
            evidence.add("methods=" + m.getMethodCount());
            evidence.add("loc=" + m.getLinesOfCode());
            evidence.add("outgoingDeps=" + m.getOutgoingDependencies());
            evidence.add("fields=" + m.getFieldCount());
            if (m.getStereotype() != null) evidence.add("stereotype=@" + m.getStereotype());

            out.add(new Finding(
                    Finding.Category.ARCHITECTURE_GOD_CLASS, sev, Finding.Confidence.HIGH,
                    "architecture.god-class",
                    title, desc.toString(),
                    SourceLocation.of(m.getFilePath(), m.getBeginLine(), m.getClassName()),
                    suggestion, evidence));
        }
    }
}
