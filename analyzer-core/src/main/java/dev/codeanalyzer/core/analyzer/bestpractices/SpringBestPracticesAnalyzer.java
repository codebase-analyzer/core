package dev.codeanalyzer.core.analyzer.bestpractices;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import dev.codeanalyzer.core.analyzer.Analyzer;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.Finding.Category;
import dev.codeanalyzer.core.model.Finding.Severity;
import dev.codeanalyzer.core.model.ParsedSource;
import dev.codeanalyzer.core.model.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects Spring Framework anti-patterns and misuses:
 *
 * 1. @Transactional on private/final methods (silently ignored by Spring proxies)
 * 2. @Transactional without readOnly=true on read-only methods
 * 3. @Transactional(propagation=REQUIRES_NEW) inside @Transactional (deadlock risk)
 * 4. @Async on methods called from the same class (proxy bypass)
 * 5. Self-invocation of any proxied method (@Transactional, @Cacheable, @Async)
 */
public class SpringBestPracticesAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(SpringBestPracticesAnalyzer.class);

    private static final Set<String> PROXY_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "Transactional", "Async", "Cacheable", "CacheEvict", "CachePut"
    ));

    private static final Set<String> READ_PREFIXES = Arrays.asList(
            "get", "find", "fetch", "load", "read", "search", "list",
            "count", "exists", "is", "has", "check", "lookup", "retrieve",
            "select", "query"
    ).stream().collect(Collectors.toCollection(LinkedHashSet::new));

    @Override
    public String getId() { return "spring-best-practices"; }

    @Override
    public String getName() { return "Spring Best Practices Analyzer"; }

    @Override
    public List<Finding> analyze(List<ParsedSource> sources) {
        List<Finding> findings = new ArrayList<>();

        for (ParsedSource source : sources) {
            for (ClassOrInterfaceDeclaration clazz : source.getCompilationUnit()
                    .findAll(ClassOrInterfaceDeclaration.class)) {
                if (clazz.isInterface()) continue;

                checkTransactionalOnPrivateOrFinal(clazz, source, findings);
                checkTransactionalReadOnly(clazz, source, findings);
                checkRequiresNewNesting(clazz, source, findings);
                checkAsyncSelfInvocation(clazz, source, findings);
                checkProxiedSelfInvocation(clazz, source, findings);
            }
        }

        log.info("Spring best practices: {} findings", findings.size());
        return findings;
    }

    // -- @Transactional on private/final methods -----------------------------

    private void checkTransactionalOnPrivateOrFinal(ClassOrInterfaceDeclaration clazz,
                                                      ParsedSource source, List<Finding> findings) {
        for (MethodDeclaration method : clazz.getMethods()) {
            if (!method.getAnnotationByName("Transactional").isPresent()) continue;

            String methodName = method.getNameAsString();
            String className = clazz.getNameAsString();

            if (method.isPrivate()) {
                findings.add(new Finding(
                        Category.SPRING_TRANSACTIONAL_MISUSE,
                        Severity.CRITICAL,
                        Finding.Confidence.HIGH,
                        "spring.transactional-private",
                        "@Transactional on private method (silently ignored)",
                        String.format("@Transactional on private method %s.%s() — Spring proxies cannot " +
                                "intercept private methods. The annotation has zero effect.",
                                className, methodName),
                        methodLocation(source, method, className),
                        String.format(
                                "### What this means\n" +
                                "Spring's `@Transactional` is implemented as an AOP proxy: when an external " +
                                "caller invokes a public bean method, the proxy intercepts the call, opens a " +
                                "transaction, runs the real method, then commits/rollbacks. Private methods " +
                                "are by definition NOT externally callable — and even if reflection or another " +
                                "method in the same class invokes them, the proxy is bypassed. The " +
                                "`@Transactional` annotation is **completely ignored**.\n\n" +
                                "### Why it's CRITICAL\n" +
                                "- **Silent data corruption.** The method writes to the DB believing it has " +
                                "atomicity. It does not. Statement-level auto-commit kicks in instead.\n" +
                                "- **No exception rollback.** Any RuntimeException leaves the half-completed " +
                                "writes committed.\n" +
                                "- **LazyInitializationException on reads.** No transaction means no Hibernate " +
                                "session bound to the thread — lazy associations fail.\n" +
                                "- **Zero compiler/runtime warning.** Spring doesn't log anything; the annotation " +
                                "just dissolves into the void.\n\n" +
                                "### Common misunderstanding — \"It's called from within the same bean, that's fine\"\n" +
                                "No. Self-invocation (`this.doWork()`) still bypasses the proxy. Only calls from " +
                                "OTHER beans go through the proxy. So even making the method public and calling " +
                                "it via `this.` would not start a transaction.\n\n" +
                                "### How to fix\n" +
                                "```java\n" +
                                "// Option 1: make it (at least) protected and ensure it's called via the proxy\n" +
                                "@Transactional\n" +
                                "protected void %s() { ... }\n" +
                                "\n" +
                                "// Option 2: move the transactional method to a separate bean and inject it\n" +
                                "@Service\n" +
                                "class Helper {\n" +
                                "    @Transactional public void %s() { ... }  // public + called from other bean = proxied\n" +
                                "}\n" +
                                "```\n\n" +
                                "### When to suppress\n" +
                                "Never. There is no legitimate use of `@Transactional` on a private method.",
                                methodName, methodName),
                        java.util.Collections.<String>emptyList()
                ));
            }

            if (method.isFinal()) {
                findings.add(new Finding(
                        Category.SPRING_TRANSACTIONAL_MISUSE,
                        Severity.CRITICAL,
                        "@Transactional on final method (silently ignored)",
                        String.format("@Transactional on final method %s.%s() — CGLIB proxies cannot override " +
                                "final methods. The annotation has zero effect.",
                                className, methodName),
                        methodLocation(source, method, className),
                        String.format(
                                "### What this means\n" +
                                "Spring uses CGLIB proxies for classes that don't implement an interface " +
                                "(the default for `@Service`, `@Component`, `@Repository`). CGLIB works by " +
                                "subclassing your bean class and overriding methods — which means a `final` " +
                                "method cannot be intercepted. The `@Transactional` annotation is silently " +
                                "skipped — same failure mode as on private methods.\n\n" +
                                "### Why it's CRITICAL\n" +
                                "Identical consequences to `@Transactional` on private methods: no transaction " +
                                "boundary, no rollback, possible `LazyInitializationException`, silent data " +
                                "corruption on exception.\n\n" +
                                "### Common misunderstanding — \"`final` makes it safe / immutable\"\n" +
                                "`final` on methods is a JVM-level inheritance restriction, unrelated to " +
                                "thread-safety or immutability. It's making your code *less* safe here by " +
                                "disabling the framework feature you asked for.\n\n" +
                                "### Common misunderstanding — \"Switching to JDK dynamic proxies fixes it\"\n" +
                                "Partly true — if the bean implements an interface, Spring uses JDK proxies " +
                                "which proxy at the interface level, ignoring `final` on the implementation. " +
                                "But this requires that callers depend on the interface, not the class, AND " +
                                "the transactional method is part of the interface. Removing `final` is simpler " +
                                "and less invasive.\n\n" +
                                "### How to fix\n" +
                                "Remove the `final` modifier:\n" +
                                "```java\n" +
                                "@Transactional\n" +
                                "public void %s() { ... }   // was: public final void\n" +
                                "```\n\n" +
                                "### When to suppress\n" +
                                "Never.",
                                methodName)
                ));
            }
        }
    }

    // -- @Transactional without readOnly on read methods ----------------------

    private void checkTransactionalReadOnly(ClassOrInterfaceDeclaration clazz,
                                              ParsedSource source, List<Finding> findings) {
        for (MethodDeclaration method : clazz.getMethods()) {
            if (!method.getAnnotationByName("Transactional").isPresent()) continue;

            String methodName = method.getNameAsString();
            String className = clazz.getNameAsString();

            // Check if method name suggests read-only
            boolean looksReadOnly = READ_PREFIXES.stream()
                    .anyMatch(p -> methodName.toLowerCase().startsWith(p.toLowerCase()));
            if (!looksReadOnly) continue;

            // Check if readOnly is already set
            String annText = method.getAnnotationByName("Transactional").get().toString();
            if (annText.contains("readOnly") || annText.contains("read_only")) continue;

            findings.add(new Finding(
                    Category.SPRING_TRANSACTIONAL_MISUSE,
                    Severity.LOW,
                    "@Transactional without readOnly on read method",
                    String.format("@Transactional on %s.%s() looks like a read-only operation " +
                            "(name starts with `get`/`find`/`exists`/...) but does not set readOnly=true.",
                            className, methodName),
                    methodLocation(source, method, className),
                    String.format(
                            "### What this means\n" +
                            "The method name follows a read-only convention (`get*`, `find*`, `exists*`, " +
                            "`count*`, `is*`, `has*`, `list*`, `search*`, `lookup*`, `retrieve*`, `select*`, " +
                            "`query*`) but the `@Transactional` annotation opens a regular read-write transaction.\n\n" +
                            "### Why it matters\n" +
                            "- Hibernate dirty-checking still runs on every loaded entity — pure waste for a read.\n" +
                            "- Some DBs / drivers / proxies route read-only tx to a read replica only when marked.\n" +
                            "- Flush mode stays AUTO; unrelated queued changes can flush mid-read.\n\n" +
                            "### Common misunderstanding — \"My repository already has readOnly=true\"\n" +
                            "Putting `@Transactional(propagation = REQUIRED, readOnly = true)` on the repository " +
                            "does **NOTHING** when called from this service. The repo joins the outer service tx, " +
                            "and joined transactions silently drop the inner method's `readOnly`/`propagation`/" +
                            "`isolation` attributes. The fix must live on the **outermost** `@Transactional` " +
                            "(usually the service). See the more detailed `tx.read-not-readonly` finding for the " +
                            "full mechanics.\n\n" +
                            "### How to fix\n" +
                            "```java\n" +
                            "// Before\n" +
                            "@Transactional\n" +
                            "public List<...> %s(...) { ... }\n" +
                            "\n" +
                            "// After\n" +
                            "@Transactional(readOnly = true)\n" +
                            "public List<...> %s(...) { ... }\n" +
                            "```\n\n" +
                            "### When to suppress\n" +
                            "- The method actually writes despite the name — consider renaming so the contract is clear.\n" +
                            "- Suppress with `// @analyzer-ignore: spring_transactional_misuse` on the offending method.",
                            methodName, methodName)
            ));
        }
    }

    // -- REQUIRES_NEW inside @Transactional ----------------------------------

    private void checkRequiresNewNesting(ClassOrInterfaceDeclaration clazz,
                                           ParsedSource source, List<Finding> findings) {
        for (MethodDeclaration method : clazz.getMethods()) {
            if (!method.getAnnotationByName("Transactional").isPresent()) continue;

            String annText = method.getAnnotationByName("Transactional").get().toString();
            if (!annText.contains("REQUIRES_NEW")) continue;

            String methodName = method.getNameAsString();
            String className = clazz.getNameAsString();

            // Check if this method is called from another @Transactional method in the same class
            boolean calledFromSameClass = false;
            for (MethodDeclaration otherMethod : clazz.getMethods()) {
                if (otherMethod == method) continue;
                if (!otherMethod.getAnnotationByName("Transactional").isPresent()) continue;

                boolean callsTarget = otherMethod.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> call.getNameAsString().equals(methodName)
                                && !call.getScope().isPresent());
                if (callsTarget) {
                    calledFromSameClass = true;
                    break;
                }
            }

            if (calledFromSameClass) {
                findings.add(new Finding(
                        Category.SPRING_TRANSACTIONAL_MISUSE,
                        Severity.HIGH,
                        "REQUIRES_NEW called from same class (proxy bypass + deadlock risk)",
                        String.format("@Transactional(REQUIRES_NEW) on %s.%s() is invoked from another " +
                                "@Transactional method in the same class — the proxy is bypassed.",
                                className, methodName),
                        methodLocation(source, method, className),
                        String.format(
                                "### What this means\n" +
                                "There are two compounded problems:\n" +
                                "1. **Self-invocation bypasses the proxy.** Spring's transaction interceptor " +
                                "lives in the AOP proxy wrapper. A `this.%s()` call goes to the raw method " +
                                "directly — the proxy never sees it, so REQUIRES_NEW is silently dropped. " +
                                "The inner method runs inside the OUTER transaction, sharing its propagation, " +
                                "isolation, readOnly, timeout — everything you specified is ignored.\n" +
                                "2. **Even fixed correctly (via separate bean), REQUIRES_NEW is dangerous.** " +
                                "It opens a separate transaction on a **separate connection**, while suspending " +
                                "the outer one. If both touch the same rows, you deadlock against yourself: " +
                                "the outer tx holds row X, the inner tx waits for row X. No timeout, no recovery.\n\n" +
                                "### Why it matters (production consequences)\n" +
                                "- **Silent atomicity bug.** The developer intended independent commit boundaries; " +
                                "they got one big transaction instead. Failures that *should* leave the audit / " +
                                "outbox log committed roll everything back.\n" +
                                "- **Self-deadlock if you \"fix\" it naively.** Moving the method to a separate " +
                                "bean (the standard fix) re-enables REQUIRES_NEW and immediately creates the " +
                                "deadlock risk you didn't have before.\n" +
                                "- **Doubled connection demand.** Each REQUIRES_NEW call holds outer connection + " +
                                "inner connection. A loop multiplies this — connection pool exhausts fast.\n\n" +
                                "### Common misunderstanding — \"REQUIRES_NEW guarantees my work commits\"\n" +
                                "Only if it runs to completion without the outer tx interfering. If the outer " +
                                "deadlocks against the inner (which you just made more likely), neither commits. " +
                                "If the outer commits before the inner finishes, you have ordering issues. The " +
                                "guarantee is weaker than it appears.\n\n" +
                                "### Common misunderstanding — \"It's only a problem if I call it in a loop\"\n" +
                                "No — even a single REQUIRES_NEW under a same-class call gives you the proxy " +
                                "bypass bug. Loop just amplifies the connection-pool cost.\n\n" +
                                "### How to fix\n" +
                                "```java\n" +
                                "// 1. Move the REQUIRES_NEW method to a separate bean\n" +
                                "@Service\n" +
                                "public class %sHelper {\n" +
                                "    @Transactional(propagation = Propagation.REQUIRES_NEW)\n" +
                                "    public void %s() { ... }\n" +
                                "}\n" +
                                "\n" +
                                "// 2. Inject and call — now the proxy intercepts\n" +
                                "@Autowired private %sHelper helper;\n" +
                                "\n" +
                                "@Transactional\n" +
                                "public void outerMethod() {\n" +
                                "    helper.%s();   // proxied → opens its own transaction\n" +
                                "}\n" +
                                "```\n" +
                                "**Then audit for self-deadlock risk:** ensure outer and inner don't lock the " +
                                "same rows. If they do, either drop REQUIRES_NEW (use REQUIRED) or restructure " +
                                "so the inner finishes before the outer touches the contested rows.\n\n" +
                                "### When to suppress\n" +
                                "- The outer method's `@Transactional` is unused (always invoked outside any " +
                                "transactional context). Rare; verify with call-site analysis before suppressing.",
                                methodName, className, methodName, className, methodName)
                ));
            }
        }
    }

    // -- @Async self-invocation ----------------------------------------------

    private void checkAsyncSelfInvocation(ClassOrInterfaceDeclaration clazz,
                                            ParsedSource source, List<Finding> findings) {
        List<MethodDeclaration> asyncMethods = clazz.getMethods().stream()
                .filter(m -> m.getAnnotationByName("Async").isPresent())
                .collect(Collectors.toList());

        if (asyncMethods.isEmpty()) return;

        Set<String> asyncMethodNames = asyncMethods.stream()
                .map(MethodDeclaration::getNameAsString)
                .collect(Collectors.toSet());

        String className = clazz.getNameAsString();

        // Check if any non-async method in the same class calls an async method
        for (MethodDeclaration method : clazz.getMethods()) {
            if (method.getAnnotationByName("Async").isPresent()) continue;

            List<MethodCallExpr> selfCalls = method.findAll(MethodCallExpr.class).stream()
                    .filter(call -> asyncMethodNames.contains(call.getNameAsString()))
                    .filter(call -> !call.getScope().isPresent()
                            || call.getScope().get() instanceof ThisExpr)
                    .collect(Collectors.toList());

            for (MethodCallExpr call : selfCalls) {
                findings.add(new Finding(
                        Category.SPRING_ASYNC_MISUSE,
                        Severity.HIGH,
                        "@Async method called from same class (runs synchronously)",
                        String.format("%s.%s() invokes @Async method %s() directly — the proxy is bypassed, " +
                                "so the call runs SYNCHRONOUSLY in the caller's thread.",
                                className, method.getNameAsString(), call.getNameAsString()),
                        methodLocation(source, method, className),
                        String.format(
                                "### What this means\n" +
                                "Spring's `@Async` is implemented as a proxy interceptor: when an external " +
                                "caller invokes the method, the proxy hands the work to a thread pool and " +
                                "returns immediately. A `this.%s()` call from the same class skips the proxy " +
                                "entirely — the method runs **inline, on the caller's thread, blocking**. " +
                                "The annotation is completely inert.\n\n" +
                                "### Why it matters (production consequences)\n" +
                                "- **The latency the dev was avoiding hits the caller.** A controller that " +
                                "calls `processNotification()` thinking it returns instantly actually blocks " +
                                "for the full notification work — request timeout cascades.\n" +
                                "- **Exceptions surface on the caller.** `@Async` swallows exceptions (or routes " +
                                "them to an `AsyncUncaughtExceptionHandler`). Synchronous execution throws " +
                                "them right back at the caller, breaking error handling that assumed fire-and-forget.\n" +
                                "- **Backpressure semantics flip.** The thread pool's task queue, rejection " +
                                "policy, and concurrency limits all stop applying. A spike that the pool would " +
                                "have shed now consumes request threads instead.\n" +
                                "- **Silent. Zero warning at startup or runtime.**\n\n" +
                                "### Common misunderstanding — \"I see the @Async, so it's async\"\n" +
                                "The annotation is necessary but not sufficient. It only takes effect when " +
                                "called *through* the proxy. The simplest way to verify: log " +
                                "`Thread.currentThread().getName()` inside the method. If you see the request " +
                                "thread, the proxy was bypassed.\n\n" +
                                "### Common misunderstanding — \"`CompletableFuture.runAsync(...)` is the same\"\n" +
                                "Different mechanism. `CompletableFuture.runAsync` uses the JVM common pool by " +
                                "default — separate thread, no transaction context, no security context, no " +
                                "MDC propagation. `@Async` (when proxied properly) lets you configure a named " +
                                "pool and reuses Spring infrastructure. They are not drop-in replacements.\n\n" +
                                "### How to fix\n" +
                                "```java\n" +
                                "// Move @Async work to a separate bean\n" +
                                "@Service\n" +
                                "public class %sAsyncService {\n" +
                                "    @Async\n" +
                                "    public void %s() { ... }\n" +
                                "}\n" +
                                "\n" +
                                "// Inject + call from the original class — now proxied, truly async\n" +
                                "@Autowired private %sAsyncService asyncService;\n" +
                                "\n" +
                                "public void %s() {\n" +
                                "    asyncService.%s();   // returns immediately, runs on async pool\n" +
                                "}\n" +
                                "```\n\n" +
                                "### Alternative — self-injection (last resort)\n" +
                                "```java\n" +
                                "@Autowired private ApplicationContext context;\n" +
                                "\n" +
                                "public void %s() {\n" +
                                "    context.getBean(%s.class).%s();   // goes through the proxy\n" +
                                "}\n" +
                                "```\n" +
                                "This works but creates a circular-ish dependency that's brittle under config " +
                                "changes. Splitting into two beans is cleaner.\n\n" +
                                "### When to suppress\n" +
                                "Never. There's no legitimate use of `@Async` that should run synchronously.",
                                call.getNameAsString(),
                                className, call.getNameAsString(),
                                className,
                                method.getNameAsString(), call.getNameAsString(),
                                method.getNameAsString(), className, call.getNameAsString())
                ));
            }
        }
    }

    // -- Generic proxied self-invocation (Transactional, Cacheable) -----------

    private void checkProxiedSelfInvocation(ClassOrInterfaceDeclaration clazz,
                                              ParsedSource source, List<Finding> findings) {
        // Collect methods with proxy-dependent annotations (excluding @Async, handled above)
        Map<String, String> proxiedMethods = new HashMap<>();
        for (MethodDeclaration method : clazz.getMethods()) {
            for (String ann : PROXY_ANNOTATIONS) {
                if (ann.equals("Async")) continue; // handled separately
                if (method.getAnnotationByName(ann).isPresent()) {
                    proxiedMethods.put(method.getNameAsString(), ann);
                    break;
                }
            }
        }

        if (proxiedMethods.isEmpty()) return;

        String className = clazz.getNameAsString();

        for (MethodDeclaration method : clazz.getMethods()) {
            List<MethodCallExpr> selfCalls = method.findAll(MethodCallExpr.class).stream()
                    .filter(call -> proxiedMethods.containsKey(call.getNameAsString()))
                    .filter(call -> !call.getScope().isPresent()
                            || call.getScope().get() instanceof ThisExpr)
                    // Don't report if the calling method itself is the proxied method
                    .filter(call -> !call.getNameAsString().equals(method.getNameAsString()))
                    .collect(Collectors.toList());

            for (MethodCallExpr call : selfCalls) {
                String targetAnn = proxiedMethods.get(call.getNameAsString());

                // Skip if already reported as @Async
                if (targetAnn.equals("Async")) continue;

                findings.add(new Finding(
                        Category.SPRING_TRANSACTIONAL_MISUSE,
                        Severity.HIGH,
                        String.format("@%s method called from same class (proxy bypass)", targetAnn),
                        String.format("%s.%s() invokes @%s method %s() — self-invocation bypasses the proxy, " +
                                "@%s is silently ignored.",
                                className, method.getNameAsString(), targetAnn,
                                call.getNameAsString(), targetAnn),
                        methodLocation(source, method, className),
                        String.format(
                                "### What this means\n" +
                                "Spring's `@%s` is implemented as an AOP proxy. When OTHER beans call your " +
                                "bean's method, they actually call the proxy wrapper, which runs the cross-" +
                                "cutting logic (open tx / consult cache / etc.) and then delegates to your " +
                                "real method. A `this.%s()` call inside the same class skips the wrapper " +
                                "entirely — the annotation has zero effect.\n\n" +
                                "### Why it matters — annotation-specific consequences\n" +
                                "- **`@Transactional`** — no transaction boundary. Each statement auto-commits; " +
                                "exceptions don't roll back; lazy associations fail; readOnly/isolation/timeout " +
                                "all ignored.\n" +
                                "- **`@Cacheable`** — cache is never consulted. The method runs on every call, " +
                                "the cache stays empty, performance degrades silently.\n" +
                                "- **`@CacheEvict`** — cache is never evicted. Stale data lingers until TTL.\n" +
                                "- **`@CachePut`** — cache never updated. Subsequent `@Cacheable` reads return " +
                                "stale or miss entirely.\n\n" +
                                "### Common misunderstanding — \"It's in the same class, the proxy still knows\"\n" +
                                "It does not. The proxy is a wrapper around the bean instance; your `this` " +
                                "reference is the wrapped object, not the wrapper. The two are different " +
                                "JVM objects. Only references injected via Spring point at the proxy.\n\n" +
                                "### Common misunderstanding — \"Some annotations work, some don't\"\n" +
                                "All proxy-based annotations have the same blind spot: `@Transactional`, " +
                                "`@Async`, `@Cacheable`, `@CacheEvict`, `@CachePut`, `@Retryable`, `@PreAuthorize`, " +
                                "`@PostAuthorize`, `@Validated`, `@Scheduled` (if dynamically registered). " +
                                "Same root cause, same fix.\n\n" +
                                "### How to fix\n" +
                                "```java\n" +
                                "// Move %s() to a separate @Service bean\n" +
                                "@Service\n" +
                                "public class SomethingHelper {\n" +
                                "    @%s\n" +
                                "    public void %s() { ... }\n" +
                                "}\n" +
                                "\n" +
                                "// Inject and call — the proxy is now consulted\n" +
                                "@Autowired private SomethingHelper helper;\n" +
                                "\n" +
                                "public void %s() {\n" +
                                "    helper.%s();   // routed through the proxy, @%s applies\n" +
                                "}\n" +
                                "```\n\n" +
                                "### When to suppress\n" +
                                "Never — by definition the annotation was placed expecting it to work; if you " +
                                "don't need it, just remove the annotation.",
                                targetAnn,
                                call.getNameAsString(),
                                call.getNameAsString(), targetAnn, call.getNameAsString(),
                                method.getNameAsString(), call.getNameAsString(), targetAnn)
                ));
            }
        }
    }

    private SourceLocation methodLocation(ParsedSource source, MethodDeclaration method,
                                            String className) {
        int line = method.getBegin().map(p -> p.line).orElse(0);
        int endLine = method.getEnd().map(p -> p.line).orElse(line);
        return SourceLocation.of(source.getRelativePathString(), line, endLine,
                className, method.getNameAsString());
    }
}
