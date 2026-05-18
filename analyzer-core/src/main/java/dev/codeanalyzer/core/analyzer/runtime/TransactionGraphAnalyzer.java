package dev.codeanalyzer.core.analyzer.runtime;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.Type;
import dev.codeanalyzer.core.analyzer.Analyzer;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;
import dev.codeanalyzer.core.model.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cross-method transactional analysis — catches the silent data-integrity
 * bugs that single-method analyzers miss.
 *
 * <p>This is the bug class that takes down production: a service method that
 * writes to the DB but isn't transactional, a long transaction holding rows
 * for seconds, a read query bizarrely wrapped in a read/write tx, or a
 * {@code REQUIRES_NEW} called inside a loop spawning thousands of tx.
 *
 * <p>This pass works intra-class to keep things fast and high-signal. A full
 * inter-class call graph would catch more but is best left for a later phase
 * (and would slow scans on huge codebases). The intra-class scope already
 * catches the most common forgot-to-annotate patterns.
 *
 * <p>Rules emitted (all under category {@link Finding.Category#SPRING_TRANSACTIONAL_MISUSE}):
 * <ul>
 *   <li>{@code tx.write-no-transaction} (CRITICAL) — bean method calls repo write
 *       methods but has no {@code @Transactional} anywhere in scope.</li>
 *   <li>{@code tx.long-transaction} (HIGH) — {@code @Transactional} method body
 *       contains 5+ repository calls (likely held connection too long).</li>
 *   <li>{@code tx.read-not-readonly} (LOW, MEDIUM confidence) — {@code @Transactional}
 *       method with only read calls — should be {@code readOnly = true}.</li>
 *   <li>{@code tx.requires-new-in-loop} (HIGH) — a {@code REQUIRES_NEW}
 *       transactional call inside a loop body — N transactions instead of one.</li>
 * </ul>
 */
public class TransactionGraphAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(TransactionGraphAnalyzer.class);

    /** Annotations that mark a class as a Spring-managed bean we should analyze. */
    private static final Set<String> BEAN_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "Service", "Component", "Controller", "RestController"
    ));

    /** Repository write-method name prefixes (Spring Data conventions). */
    private static final List<String> WRITE_METHOD_PREFIXES = Arrays.asList(
            "save", "delete", "remove", "update", "insert", "persist", "merge", "flush"
    );

    /** Repository read-method name prefixes. */
    private static final List<String> READ_METHOD_PREFIXES = Arrays.asList(
            "find", "get", "load", "read", "fetch", "search", "exists", "count", "stream", "query"
    );

    /** Threshold for "long transaction" — repo calls within a single @Transactional method. */
    private static final int LONG_TX_THRESHOLD = 5;

    /**
     * Type-name suffixes that mark a field as a persistence access point. A method
     * call whose receiver isn't a field of one of these types is NOT a repo call,
     * no matter how the method is named. This is what stops {@code StringUtils.removeAll(...)}
     * being flagged as a repository write.
     */
    private static final List<String> REPOSITORY_TYPE_SUFFIXES = Arrays.asList(
            "Repository", "Repo", "Dao", "DAO", "Mapper", "Store"
    );

    /** Type names (exact, not suffix) that are also persistence access points. */
    private static final Set<String> REPOSITORY_TYPE_EXACT = new HashSet<>(Arrays.asList(
            "EntityManager", "Session", "SessionFactory",
            "JdbcTemplate", "NamedParameterJdbcTemplate",
            "MongoTemplate", "RedisTemplate", "JpaTemplate", "HibernateTemplate"
    ));

    @Override public String getId() { return "tx-graph"; }
    @Override public String getName() { return "Transaction Graph Analyzer"; }

    @Override
    public List<Finding> analyze(List<ParsedSource> sources) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource src : sources) {
            CompilationUnit cu = src.getCompilationUnit();
            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!isAnalyzableBean(clazz)) continue;
                analyzeClass(clazz, src, findings);
            }
        }
        if (!findings.isEmpty()) {
            log.info("Transaction graph: {} findings", findings.size());
        }
        return findings;
    }

    private boolean isAnalyzableBean(ClassOrInterfaceDeclaration clazz) {
        if (clazz.isInterface()) return false;
        for (String ann : BEAN_ANNOTATIONS) {
            if (clazz.getAnnotationByName(ann).isPresent()) return true;
        }
        return false;
    }

    private void analyzeClass(ClassOrInterfaceDeclaration clazz, ParsedSource src, List<Finding> findings) {
        // Class-level @Transactional inherits to all public methods unless overridden
        boolean classTransactional = hasTransactional(clazz);

        // Discover repository-typed fields in this class. The classifier uses this
        // set so a call like `userRepository.save(x)` is a real repo write but
        // `StringUtils.removeAll(...)` (static, no field of that name) is not.
        Set<String> repoFields = collectRepositoryFieldNames(clazz);

        for (MethodDeclaration m : clazz.getMethods()) {
            if (!m.isPublic() && !classTransactional) continue;  // private + no class tx: not entry point

            boolean methodTransactional = hasTransactional(m);
            boolean effectivelyTransactional = methodTransactional || classTransactional;
            boolean readOnly = isReadOnlyTransactional(m) || (classTransactional && isReadOnlyTransactional(clazz));

            List<MethodCallExpr> calls = m.findAll(MethodCallExpr.class);
            int writeCalls = 0;
            int readCalls = 0;
            int repoCalls = 0;
            MethodCallExpr firstWriteCall = null;
            for (MethodCallExpr c : calls) {
                CallKind k = classifyCall(c, repoFields);
                switch (k) {
                    case WRITE:
                        writeCalls++; repoCalls++;
                        if (firstWriteCall == null) firstWriteCall = c;
                        break;
                    case READ:
                        readCalls++; repoCalls++;
                        break;
                    default: break;
                }
            }

            // Rule 1: tx.write-no-transaction — writes but not transactional at all
            if (writeCalls > 0 && !effectivelyTransactional) {
                findings.add(buildWriteNoTx(m, clazz, src, firstWriteCall, writeCalls));
            }

            // Rule 2: tx.long-transaction — @Transactional with 5+ repo calls
            if (effectivelyTransactional && repoCalls >= LONG_TX_THRESHOLD) {
                findings.add(buildLongTx(m, clazz, src, repoCalls, writeCalls));
            }

            // Rule 3: tx.read-not-readonly — @Transactional reads only, not readOnly
            if (methodTransactional && writeCalls == 0 && readCalls >= 1 && !readOnly) {
                findings.add(buildReadNotReadonly(m, clazz, src, readCalls));
            }

            // Rule 4: tx.requires-new-in-loop — REQUIRES_NEW propagation INSIDE this class
            //   called from inside a loop in another method
            // For now, simpler intra-method check: this method's body has a loop containing
            // a self-call to another method that uses REQUIRES_NEW.
            checkRequiresNewInLoop(m, clazz, src, findings);
        }
    }

    // ─── Call classification ─────────────────────────────────────────────────

    private enum CallKind { WRITE, READ, OTHER }

    /**
     * Classifies a method call as a repository write, repository read, or neither.
     *
     * <p>Requires BOTH:
     * <ol>
     *   <li>The call's receiver (scope) is a repository-typed field of the enclosing
     *       class — {@code userRepo.save(x)} OK, {@code StringUtils.remove(s)} not.</li>
     *   <li>The method name matches a Spring Data write/read prefix
     *       ({@code save*}, {@code delete*}, {@code find*}, etc.).</li>
     * </ol>
     *
     * <p>Calls with no receiver (implicit {@code this}), chained call receivers
     * ({@code getRepo().save(x)}), or PascalCase receivers (static utility calls)
     * are all returned as OTHER. This is a deliberate trade-off — we accept a few
     * missed repo calls (false negatives) to eliminate the previous flood of
     * false positives where any method named {@code remove*} got flagged.
     */
    private CallKind classifyCall(MethodCallExpr c, Set<String> repoFields) {
        // 1. Receiver gate: must be a known repository field of THIS class.
        if (!isCallOnRepositoryField(c, repoFields)) return CallKind.OTHER;

        // 2. Method name gate: Spring Data convention.
        String name = c.getNameAsString();
        for (String w : WRITE_METHOD_PREFIXES) {
            if (name.equals(w) || (name.startsWith(w) && name.length() > w.length()
                    && Character.isUpperCase(name.charAt(w.length())))) {
                return CallKind.WRITE;
            }
        }
        for (String r : READ_METHOD_PREFIXES) {
            if (name.equals(r) || (name.startsWith(r) && name.length() > r.length()
                    && Character.isUpperCase(name.charAt(r.length())))) {
                return CallKind.READ;
            }
        }
        return CallKind.OTHER;
    }

    /**
     * True if {@code call.getScope()} is a {@link NameExpr} whose identifier
     * matches a repository field discovered on the enclosing class. Static calls
     * (capitalized scope), chained calls, and implicit-{@code this} calls all
     * return false.
     */
    private boolean isCallOnRepositoryField(MethodCallExpr call, Set<String> repoFields) {
        if (repoFields.isEmpty()) return false;
        Expression scope = call.getScope().orElse(null);
        if (scope == null) return false;       // implicit this — not a field call
        if (scope instanceof ThisExpr) return false; // explicit this.method() — not a field
        if (!(scope instanceof NameExpr)) return false; // chained or complex expression
        String receiver = ((NameExpr) scope).getNameAsString();
        // Reject obvious static calls (uppercase first letter = type name).
        if (receiver.isEmpty() || Character.isUpperCase(receiver.charAt(0))) return false;
        return repoFields.contains(receiver);
    }

    /**
     * Collects field names whose declared TYPE is a repository / persistence
     * access point. Detection is type-based — we don't trust field naming alone
     * because callers happily call random helpers {@code repository} or {@code dao}.
     *
     * <p>A type qualifies when:
     * <ul>
     *   <li>The simple type name ends with one of {@link #REPOSITORY_TYPE_SUFFIXES}
     *       (e.g. {@code UserRepository}, {@code OrderDao}, {@code MerchantMapper}),
     *       OR</li>
     *   <li>The simple type name is in {@link #REPOSITORY_TYPE_EXACT}
     *       (e.g. {@code EntityManager}, {@code JdbcTemplate}).</li>
     * </ul>
     */
    private Set<String> collectRepositoryFieldNames(ClassOrInterfaceDeclaration clazz) {
        Set<String> out = new HashSet<>();
        for (FieldDeclaration f : clazz.getFields()) {
            Type type = f.getElementType();
            String typeName = simpleTypeName(type.toString());
            if (!isRepositoryType(typeName)) continue;
            for (VariableDeclarator v : f.getVariables()) {
                out.add(v.getNameAsString());
            }
        }
        return out;
    }

    /** Returns the simple name from a possibly-generic type string: {@code List<UserRepository>} → {@code List}. */
    private String simpleTypeName(String raw) {
        if (raw == null) return "";
        int lt = raw.indexOf('<');
        String trimmed = lt > 0 ? raw.substring(0, lt) : raw;
        int dot = trimmed.lastIndexOf('.');
        return dot >= 0 ? trimmed.substring(dot + 1) : trimmed;
    }

    private boolean isRepositoryType(String typeName) {
        if (typeName == null || typeName.isEmpty()) return false;
        if (REPOSITORY_TYPE_EXACT.contains(typeName)) return true;
        for (String suffix : REPOSITORY_TYPE_SUFFIXES) {
            if (typeName.endsWith(suffix) && typeName.length() > suffix.length()) return true;
        }
        return false;
    }

    // ─── REQUIRES_NEW in loop ────────────────────────────────────────────────

    private void checkRequiresNewInLoop(MethodDeclaration m, ClassOrInterfaceDeclaration clazz,
                                         ParsedSource src, List<Finding> out) {
        // Find any same-class method using REQUIRES_NEW
        Set<String> requiresNewMethods = new HashSet<>();
        for (MethodDeclaration peer : clazz.getMethods()) {
            if (peer == m) continue;
            if (peer.getAnnotations().stream().anyMatch(this::isRequiresNew)) {
                requiresNewMethods.add(peer.getNameAsString());
            }
        }
        if (requiresNewMethods.isEmpty()) return;

        // Look for method calls to those names from inside a loop body
        boolean inLoop = false;
        for (MethodCallExpr c : m.findAll(MethodCallExpr.class)) {
            if (!requiresNewMethods.contains(c.getNameAsString())) continue;
            boolean enclosedByLoop = c.findAncestor(ForStmt.class).isPresent()
                    || c.findAncestor(ForEachStmt.class).isPresent()
                    || c.findAncestor(WhileStmt.class).isPresent();
            if (enclosedByLoop) {
                int line = c.getBegin().map(p -> p.line).orElse(0);
                SourceLocation loc = SourceLocation.of(src.getRelativePathString(), line, line,
                        clazz.getNameAsString(), m.getNameAsString());
                out.add(new Finding(
                        Finding.Category.SPRING_TRANSACTIONAL_MISUSE,
                        Finding.Severity.HIGH, Finding.Confidence.HIGH,
                        "tx.requires-new-in-loop",
                        "@Transactional(REQUIRES_NEW) called inside a loop",
                        "Method " + clazz.getNameAsString() + "." + m.getNameAsString()
                                + "() calls a REQUIRES_NEW-annotated method inside a loop body. "
                                + "Each iteration opens its own fresh transaction.",
                        loc,
                        "### What this means\n" +
                        "`Propagation.REQUIRES_NEW` always suspends the current transaction (if any) and starts " +
                        "a brand-new one — independent commit, independent rollback, **separate DB connection**. " +
                        "Inside a loop, this means: N iterations → N fresh transactions → up to 2×N connection " +
                        "checkouts (the suspended outer one + the new inner one), N commits, N round-trips.\n\n" +
                        "### Why it matters\n" +
                        "- **Connection pool exhaustion (real fast).** While the new inner tx runs, the outer tx " +
                          "is suspended but its connection **stays checked out**. A loop of 100 iterations holds " +
                          "the outer connection AND borrows 1 inner connection per iteration. Under concurrency " +
                          "this is how you turn a working app into HikariCP timeouts.\n" +
                        "- **Latency explodes.** Each `REQUIRES_NEW` commit triggers an `fsync` on the WAL — " +
                          "typically ~1-5ms on SSD, ~10ms+ on networked storage. 1000 iterations = a guaranteed " +
                          "1-10 seconds of nothing but commit overhead.\n" +
                        "- **Partial-failure debugging hell.** Some iterations commit, others roll back. Producing " +
                          "a consistent state after a mid-loop crash is your problem, not the framework's.\n\n" +
                        "### Common misunderstanding — when REQUIRES_NEW is actually needed\n" +
                        "Most developers reach for `REQUIRES_NEW` thinking \"I want my work committed even if the " +
                        "outer fails.\" That's almost never what you want. Legitimate uses are narrow:\n" +
                        "- Audit / log writes that must survive a business-logic rollback.\n" +
                        "- Idempotency / outbox markers that need to commit before a remote call.\n" +
                        "- Coordinating across systems where the outer tx is on a different resource.\n\n" +
                        "If you're using `REQUIRES_NEW` because \"this thing should still happen if the outer " +
                        "fails\" — challenge that. The outer probably **shouldn't** fail in those scenarios in the " +
                        "first place; fix the root cause.\n\n" +
                        "### Fix options\n" +
                        "- **Batch the call.** Collect inputs in the loop, invoke the REQUIRES_NEW method ONCE " +
                          "with a list. One tx per batch instead of one per item.\n" +
                        "- **Drop REQUIRES_NEW** to the default `REQUIRED` if the isolation isn't actually needed. " +
                          "Keep the loop in the single outer transaction.\n" +
                        "- **Move the loop inside.** Refactor so the REQUIRES_NEW method takes a list and the loop " +
                          "lives inside it (still one fresh tx, but only one).\n" +
                        "- **Manual `TransactionTemplate`** for explicit control + clarity at call sites.\n\n" +
                        "### When to suppress\n" +
                        "- The loop is bounded to a tiny N (≤5) and you genuinely need per-iteration isolation " +
                          "(rare; multi-tenant batch updates can qualify). Suppress with " +
                          "`// @analyzer-ignore: tx.requires-new-in-loop` and add a comment justifying the bound.",
                        Arrays.asList(
                                "Call site: " + clazz.getNameAsString() + "." + m.getNameAsString() + ":" + line,
                                "Target methods using REQUIRES_NEW: " + requiresNewMethods
                        )
                ));
                inLoop = true;
                break;  // one finding per outer method is enough
            }
        }
    }

    // ─── Finding builders ────────────────────────────────────────────────────

    private Finding buildWriteNoTx(MethodDeclaration m, ClassOrInterfaceDeclaration clazz,
                                    ParsedSource src, MethodCallExpr writeCall, int writeCallCount) {
        int line = m.getBegin().map(p -> p.line).orElse(0);
        SourceLocation loc = SourceLocation.of(src.getRelativePathString(), line, line,
                clazz.getNameAsString(), m.getNameAsString());
        String writeName = writeCall != null ? writeCall.getNameAsString() : "save";
        return new Finding(
                Finding.Category.SPRING_TRANSACTIONAL_MISUSE,
                Finding.Severity.CRITICAL, Finding.Confidence.MEDIUM,
                "tx.write-no-transaction",
                "Service method writes to the DB but has no @Transactional",
                "Method " + clazz.getNameAsString() + "." + m.getNameAsString()
                        + "() calls " + writeCallCount + " repository write method(s) (e.g. `" + writeName + "`) "
                        + "but neither the method nor its class is @Transactional.",
                loc,
                "### What this means\n" +
                "Without `@Transactional`, JDBC runs in **auto-commit mode**: each statement is its own tiny "
                + "transaction, committed immediately. If statement #3 of " + writeCallCount + " fails, "
                + "statements #1 and #2 are already committed — you're left with partial state and no way to "
                + "roll back.\n\n" +
                "### Why it matters\n" +
                "- **Data integrity bugs that surface in production.** Half-finished writes (an order created "
                + "but its line items lost, a user updated but their permissions row missing) corrupt your "
                + "domain invariants silently.\n" +
                "- **No rollback on exception.** A runtime exception thrown after partial writes leaves the DB "
                + "in an inconsistent state. With `@Transactional`, Spring rolls back unchecked exceptions by default.\n" +
                "- **Lost batch performance.** Without a tx, every statement triggers a round-trip + commit, "
                + "and Hibernate cannot batch inserts/updates.\n" +
                "- **Per-statement locks.** Each write acquires + releases its row lock independently. Concurrent "
                + "writers can interleave in surprising ways.\n\n" +
                "### Common misunderstanding — proxy self-invocation\n" +
                "It's tempting to add `@Transactional` to a *helper* method in the same class:\n" +
                "```java\n" +
                "public void publicEntry() {\n" +
                "    doWrites();                       // calls 'this.doWrites()' — bypasses the proxy\n" +
                "}\n" +
                "@Transactional\n" +
                "private void doWrites() { ... }       // tx annotation is IGNORED\n" +
                "```\n" +
                "Spring `@Transactional` only works via the AOP proxy. A call to `this.method()` from inside the "
                + "same bean bypasses the proxy entirely — the annotation has zero effect. The annotation must "
                + "be on the **method called from outside** (typically the public service entry point), OR you "
                + "must call through the proxy (`self.doWrites()`, `applicationContext.getBean(...).doWrites()`).\n\n" +
                "### Fix\n" +
                "Add `@Transactional` to this method (or move the write to a method that has one):\n" +
                "```java\n" +
                "@Transactional\n" +
                "public void " + m.getNameAsString() + "(...) {\n" +
                "    repo." + writeName + "(...);\n" +
                "    // other writes — now atomic, with rollback on exception\n" +
                "}\n" +
                "```\n" +
                "If most writes in the class need a tx, prefer `@Transactional` at the **class level** so new "
                + "methods inherit it by default.\n\n" +
                "### When to suppress\n" +
                "- The writes are intentionally fire-and-forget (audit logs, metrics) where partial state is acceptable.\n" +
                "- The method is itself called from another `@Transactional` method that the analyzer can't see "
                + "(inter-class call — we only inspect intra-class for speed). Suppress with "
                + "`// @analyzer-ignore: tx.write-no-transaction` and add a comment pointing to the outer caller.",
                Arrays.asList(
                        writeCallCount + " repository write call(s) detected in this method",
                        "Class @Transactional: no",
                        "Method @Transactional: no"
                )
        );
    }

    private Finding buildLongTx(MethodDeclaration m, ClassOrInterfaceDeclaration clazz, ParsedSource src,
                                 int repoCalls, int writeCalls) {
        int line = m.getBegin().map(p -> p.line).orElse(0);
        SourceLocation loc = SourceLocation.of(src.getRelativePathString(), line, line,
                clazz.getNameAsString(), m.getNameAsString());
        return new Finding(
                Finding.Category.SPRING_TRANSACTIONAL_MISUSE,
                Finding.Severity.HIGH, Finding.Confidence.MEDIUM,
                "tx.long-transaction",
                "@Transactional method holds connection across many repo calls",
                "Method " + clazz.getNameAsString() + "." + m.getNameAsString()
                        + "() is @Transactional and makes " + repoCalls + " repository calls in one transaction "
                        + "(" + writeCalls + " writes). The DB connection and any acquired locks are held for "
                        + "the entire duration of the method.",
                loc,
                "### What this means\n" +
                "A single `@Transactional` method body contains " + repoCalls + " repository calls — above our "
                + "threshold of " + LONG_TX_THRESHOLD + ". The transaction stays open for the full execution: "
                + "connection checked out from the pool, locks held, undo log growing.\n\n" +
                "### Why it matters\n" +
                "- **Connection-pool exhaustion.** Every active tx pins one connection. A long tx multiplied by "
                + "concurrent traffic burns through HikariCP's pool fast. Under load you'll see "
                + "`HikariPool-1 - Connection is not available, request timed out` in your logs.\n" +
                "- **Lock contention.** Row + index locks acquired by any write inside the tx are held until "
                + "commit. Other writers queue, latency goes up, deadlocks become more likely.\n" +
                "- **Bloated undo / WAL.** Postgres keeps tuple versions alive for as long as the oldest tx; "
                + "long transactions delay VACUUM and bloat tables. MySQL/InnoDB grows the undo log.\n" +
                "- **First-level cache grows.** Hibernate's `Session` keeps every loaded entity. " + repoCalls
                + " reads in one tx can pin tens of thousands of entities in memory.\n" +
                "- **Replication lag.** Writes within long tx commit late, so read replicas lag behind.\n\n" +
                "### Common misunderstanding — \"it's all in one method, so it has to be one tx\"\n" +
                "No. Atomicity should be scoped to the **invariant being protected**, not to the calling method. "
                + "If you're sending an email, refreshing a cache, or calling a remote service inside the tx, "
                + "that work doesn't need the DB lock and should be moved out. A typical pattern:\n" +
                "```java\n" +
                "public void processOrder(Order o) {        // no @Transactional here\n" +
                "    OrderResult r = persistAtomically(o);   // @Transactional — tight & fast\n" +
                "    publishEvent(r);                        // outside the tx\n" +
                "    notifyDownstream(r);                    // outside the tx\n" +
                "}\n" +
                "```\n\n" +
                "### Fix options\n" +
                "- **Split the work.** Extract sub-flows that don't need atomicity into separate "
                + "non-`@Transactional` methods called from outside the tx scope.\n" +
                "- **Use `TransactionTemplate`** for the tight read+write window only, with everything else outside.\n" +
                "- **Batch writes.** Collect changes in memory; one `saveAll(...)` at the end.\n" +
                "- **Move I/O out.** Anything that talks to the network, files, or remote APIs should NEVER "
                + "be inside a DB transaction — that's where you get pool exhaustion under partial outages.\n\n" +
                "### When to suppress\n" +
                "- This is a deliberate batch job where the long tx is required for cross-row consistency "
                + "(e.g. month-end accounting close).\n" +
                "- The repo calls are all reads on the same root entity (consider `readOnly = true` instead). "
                + "Suppress with `// @analyzer-ignore: tx.long-transaction` and document why.",
                Arrays.asList(
                        "Total repo calls in method: " + repoCalls,
                        "Repository writes: " + writeCalls,
                        "Threshold: " + LONG_TX_THRESHOLD
                )
        );
    }

    private Finding buildReadNotReadonly(MethodDeclaration m, ClassOrInterfaceDeclaration clazz,
                                          ParsedSource src, int readCalls) {
        int line = m.getBegin().map(p -> p.line).orElse(0);
        SourceLocation loc = SourceLocation.of(src.getRelativePathString(), line, line,
                clazz.getNameAsString(), m.getNameAsString());
        String returnType = m.getType() != null ? m.getType().toString() : "void";
        return new Finding(
                Finding.Category.SPRING_TRANSACTIONAL_MISUSE,
                Finding.Severity.LOW, Finding.Confidence.MEDIUM,
                "tx.read-not-readonly",
                "@Transactional without readOnly on read method",
                "@Transactional on " + clazz.getNameAsString() + "." + m.getNameAsString()
                        + "() looks like a read-only operation but does not set readOnly=true.",
                loc,
                "### What this means\n" +
                "The method is annotated `@Transactional` and only calls repository read methods "
                + "(" + readCalls + " read call(s), 0 writes), but does not pass `readOnly = true`. "
                + "By default Spring opens a regular read-write transaction, with the full overhead that implies.\n\n" +
                "### Why it matters\n" +
                "- **Hibernate dirty-checking still runs.** Every entity loaded inside this transaction is "
                + "snapshotted and compared against its current state at flush/commit. For a pure read, this is "
                + "100% wasted CPU and memory.\n" +
                "- **Flush mode stays AUTO.** Any query inside the tx may trigger a flush of unrelated pending "
                + "changes — surprising performance hits and ordering bugs.\n" +
                "- **Replica routing is disabled.** Drivers / proxies (Oracle, MySQL ProxySQL, AWS RDS proxy, "
                + "Spring's `LazyConnectionDataSourceProxy`) only route to a read replica when the tx is marked "
                + "read-only. You're loading your primary DB for queries that could go to a replica.\n" +
                "- **Some DBs apply MVCC fast paths only for read-only tx** (Postgres, Oracle).\n\n" +
                "### Common misunderstanding — the REQUIRED-propagation trap\n" +
                "It's tempting to fix this at the **repository** level instead of the service:\n" +
                "```java\n" +
                "@Transactional(propagation = Propagation.REQUIRED, readOnly = true)\n" +
                "public boolean existsXxx(...) { ... }\n" +
                "```\n" +
                "**This does nothing.** With `Propagation.REQUIRED` (the default), the inner method joins the "
                + "outer transaction opened by the service. When a transaction is joined, its `readOnly`, "
                + "`propagation`, and `isolation` attributes are **silently dropped** — the active tx keeps the "
                + "outer settings. The repo's `readOnly=true` is ignored.\n\n" +
                "The fix must live on the **outermost** `@Transactional` in the call chain — usually the service.\n\n" +
                "### Fix\n" +
                "```java\n" +
                "@Transactional(readOnly = true)\n" +
                "public " + returnType + " " + m.getNameAsString() + "(...) {\n" +
                "    return repo.findXxx(...);\n" +
                "}\n" +
                "```\n" +
                "If the method genuinely writes in some branches, split it: extract the read-only path into a " +
                "`@Transactional(readOnly = true)` method that the write path orchestrates.\n\n" +
                "### When to suppress\n" +
                "- The method calls business logic (not just repos) that mutates state inside the same tx — " +
                  "but our detector only saw reads. Suppress with `// @analyzer-ignore: tx.read-not-readonly`.\n" +
                "- The method calls a `REQUIRES_NEW` write method that legitimately wants its own non-readOnly " +
                  "scope. Rare; verify before suppressing.",
                Arrays.asList(
                        "Read calls: " + readCalls,
                        "Write calls: 0",
                        "readOnly attribute: missing on outer @Transactional"
                )
        );
    }

    // ─── Annotation introspection ────────────────────────────────────────────

    private boolean hasTransactional(MethodDeclaration m) {
        return m.getAnnotationByName("Transactional").isPresent();
    }

    private boolean hasTransactional(ClassOrInterfaceDeclaration c) {
        return c.getAnnotationByName("Transactional").isPresent();
    }

    private boolean isReadOnlyTransactional(MethodDeclaration m) {
        return m.getAnnotationByName("Transactional")
                .map(this::annotationHasReadOnlyTrue).orElse(false);
    }

    private boolean isReadOnlyTransactional(ClassOrInterfaceDeclaration c) {
        return c.getAnnotationByName("Transactional")
                .map(this::annotationHasReadOnlyTrue).orElse(false);
    }

    private boolean annotationHasReadOnlyTrue(AnnotationExpr ann) {
        if (!(ann instanceof NormalAnnotationExpr)) return false;
        for (MemberValuePair p : ((NormalAnnotationExpr) ann).getPairs()) {
            if ("readOnly".equals(p.getNameAsString())
                    && "true".equalsIgnoreCase(p.getValue().toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isRequiresNew(AnnotationExpr ann) {
        if (!"Transactional".equals(ann.getNameAsString())) return false;
        if (!(ann instanceof NormalAnnotationExpr)) return false;
        for (MemberValuePair p : ((NormalAnnotationExpr) ann).getPairs()) {
            if ("propagation".equals(p.getNameAsString())) {
                String val = p.getValue().toString();
                return val.contains("REQUIRES_NEW");
            }
        }
        return false;
    }

    @Override
    public List<Finding> analyze(dev.codeanalyzer.core.analyzer.AnalysisContext context) {
        return analyze(context.getSources());
    }
}
