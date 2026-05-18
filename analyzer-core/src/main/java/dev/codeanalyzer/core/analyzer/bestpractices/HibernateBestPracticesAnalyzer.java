package dev.codeanalyzer.core.analyzer.bestpractices;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
 * Detects Hibernate entity anti-patterns:
 *
 * 1. equals()/hashCode() using the @Id field (breaks with unsaved entities)
 * 2. @Entity without equals()/hashCode() override (breaks in Sets/Maps)
 * 3. @Entity without Serializable (fails in clustered/cached environments)
 * 4. Mutable @Id fields (setter on the ID -- risky)
 */
public class HibernateBestPracticesAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(HibernateBestPracticesAnalyzer.class);

    @Override
    public String getId() { return "hibernate-best-practices"; }

    @Override
    public String getName() { return "Hibernate Best Practices Analyzer"; }

    @Override
    public List<Finding> analyze(List<ParsedSource> sources) {
        List<Finding> findings = new ArrayList<>();

        for (ParsedSource source : sources) {
            for (ClassOrInterfaceDeclaration clazz : source.getCompilationUnit()
                    .findAll(ClassOrInterfaceDeclaration.class)) {
                if (!clazz.getAnnotationByName("Entity").isPresent()) continue;
                if (clazz.isInterface() || clazz.isAbstract()) continue;

                checkEqualsHashCode(clazz, source, findings);
                checkSerializable(clazz, source, findings);
            }
        }

        log.info("Hibernate best practices: {} findings", findings.size());
        return findings;
    }

    // -- equals/hashCode analysis --------------------------------------------

    private void checkEqualsHashCode(ClassOrInterfaceDeclaration clazz,
                                       ParsedSource source, List<Finding> findings) {
        String className = clazz.getNameAsString();

        // Find the @Id field name
        String idFieldName = null;
        for (FieldDeclaration field : clazz.getFields()) {
            if (field.getAnnotationByName("Id").isPresent()
                    || field.getAnnotationByName("EmbeddedId").isPresent()) {
                idFieldName = field.getVariables().get(0).getNameAsString();
                break;
            }
        }

        boolean hasEquals = clazz.getMethods().stream()
                .anyMatch(m -> m.getNameAsString().equals("equals")
                        && m.getParameters().size() == 1);
        boolean hasHashCode = clazz.getMethods().stream()
                .anyMatch(m -> m.getNameAsString().equals("hashCode")
                        && m.getParameters().isEmpty());

        // Case 1: No equals/hashCode at all
        if (!hasEquals && !hasHashCode) {
            findings.add(new Finding(
                    Category.HIBERNATE_EQUALS_HASHCODE,
                    Severity.MEDIUM,
                    "Entity without equals()/hashCode()",
                    String.format("%s is a JPA entity but does not override equals() or hashCode(). " +
                            "Different sessions / detached-merged copies are treated as different objects.",
                            className),
                    classLocation(source, clazz),
                    String.format(
                            "### What this means\n" +
                            "When Hibernate loads the same DB row twice — different sessions, after detach + merge, " +
                            "or via two different navigation paths — you end up with two distinct Java objects. " +
                            "Without a domain-aware `equals()`, they are NOT equal: `Object.equals` falls back to " +
                            "reference identity (`==`).\n\n" +
                            "### Why it matters (production consequences)\n" +
                            "- **`Set` / `Map` corruption.** A `Set<%s>` happily holds duplicates that are the same " +
                            "DB row. `Map.get(entity)` returns `null` for an entity you know is in the map.\n" +
                            "- **`@ManyToMany` updates do the wrong thing.** Hibernate's collection diff (add/remove) " +
                            "uses `equals()`. Without an override, every navigation looks like \"all old removed, all " +
                            "new added\" → entire join table rewritten on every save.\n" +
                            "- **Subtle bugs after detach/merge.** A controller receives a detached entity from the " +
                            "session, merges it, then compares — they differ. UI shows stale state, audit shows phantom changes.\n" +
                            "- **Cache invalidation misses.** 2nd-level cache keys depend on equality.\n\n" +
                            "### Common misunderstanding — \"Lombok's @EqualsAndHashCode fixes this\"\n" +
                            "It makes it **worse** on entities. By default `@EqualsAndHashCode` uses all fields — " +
                            "which means equals/hashCode access lazy associations, triggering SQL loads (and " +
                            "potential `LazyInitializationException` outside a session). A logging call that " +
                            "stringifies an entity can fire dozens of queries. Use `@EqualsAndHashCode(of = \"id\")` " +
                            "explicitly, or write the methods by hand.\n\n" +
                            "### Common misunderstanding — \"Just use the @Id field\"\n" +
                            "Risky if the entity ever lives before persist. While `id == null`, all transient " +
                            "instances are equal under naive ID-equality. Worse: the hashCode changes the moment " +
                            "the ID is assigned, breaking any `Set` that holds the transient entity (see the " +
                            "separate `hashCode-uses-id` finding). The pattern below uses a constant hashCode + " +
                            "null-guarded equals, which is safe across the entire lifecycle.\n\n" +
                            "### Recommended implementation\n" +
                            "Prefer a natural business key (email, code, composite unique fields) when one exists. " +
                            "Otherwise, ID-based with the transient-safe pattern:\n" +
                            "```java\n" +
                            "@Override\n" +
                            "public boolean equals(Object o) {\n" +
                            "    if (this == o) return true;\n" +
                            "    if (!(o instanceof %s)) return false;\n" +
                            "    %s other = (%s) o;\n" +
                            "    // Prefer business key:\n" +
                            "    // return Objects.equals(businessKey, other.businessKey);\n" +
                            "    // ID-based fallback (null-safe):\n" +
                            "    return %s != null && Objects.equals(%s, other.%s);\n" +
                            "}\n" +
                            "@Override\n" +
                            "public int hashCode() {\n" +
                            "    // MUST be constant when equals is ID-based — ID is null pre-persist\n" +
                            "    return getClass().hashCode();\n" +
                            "}\n" +
                            "```\n\n" +
                            "### When to suppress\n" +
                            "- The entity is never compared, never stored in a Set/Map, never detached-then-merged. " +
                            "Rare in practice — usually only true for write-only audit log entities.",
                            className, className, className, className,
                            idFieldName != null ? idFieldName : "id",
                            idFieldName != null ? idFieldName : "id",
                            idFieldName != null ? idFieldName : "id")
            ));
            return;
        }

        // Case 2: equals without hashCode or vice versa
        if (hasEquals != hasHashCode) {
            String present = hasEquals ? "equals()" : "hashCode()";
            String missing = hasEquals ? "hashCode()" : "equals()";
            findings.add(new Finding(
                    Category.HIBERNATE_EQUALS_HASHCODE,
                    Severity.HIGH,
                    "Entity with " + present + " but not " + missing,
                    String.format("%s overrides %s but not %s — violates the Java contract.",
                            className, present, missing),
                    classLocation(source, clazz),
                    String.format(
                            "### What this means\n" +
                            "The `Object` contract requires: *if `a.equals(b)` then `a.hashCode() == b.hashCode()`*. " +
                            "Overriding one without the other breaks this invariant — your equals method may " +
                            "consider two instances equal, but they'll land in different hash buckets (or vice versa).\n\n" +
                            "### Why it matters\n" +
                            "- **Hash collections silently misbehave.** `HashSet.contains()` returns false for an " +
                            "entity you just added (different bucket from current hashCode). `HashMap.get()` " +
                            "returns null for a key that's structurally equal to one already in the map.\n" +
                            "- **The bug is non-deterministic.** It only surfaces when hash bucket layouts align " +
                            "or misalign — works in dev, fails in production at random load.\n" +
                            "- **Compounded for JPA entities.** Hibernate's collection diff and 2nd-level cache " +
                            "both rely on the contract holding. Half-overriding it is worse than not overriding at all.\n\n" +
                            "### Common misunderstanding — \"I only need %s, why bother with %s\"\n" +
                            "Because the JDK collections framework, third-party libraries, and Hibernate itself " +
                            "call both. You don't get to pick which one is consulted at runtime — they're a pair " +
                            "by contract.\n\n" +
                            "### How to fix\n" +
                            "Add the missing method. Pattern in the related \"Entity without equals()/hashCode()\" finding.\n\n" +
                            "### When to suppress\n" +
                            "Never. The Java spec requires both — there is no legitimate single-override case.",
                            present, missing)
            ));
            return;
        }

        // Case 3: equals/hashCode that uses the @Id field — check for the anti-pattern
        if (idFieldName != null && hasEquals) {
            Optional<MethodDeclaration> hashCodeMethod = clazz.getMethods().stream()
                    .filter(m -> m.getNameAsString().equals("hashCode") && m.getParameters().isEmpty())
                    .findFirst();

            if (hashCodeMethod.isPresent()) {
                String hashBody = hashCodeMethod.get().toString();
                boolean hashUsesId = hashBody.contains(idFieldName)
                        && !hashBody.contains("getClass().hashCode()");

                if (hashUsesId) {
                    findings.add(new Finding(
                            Category.HIBERNATE_EQUALS_HASHCODE,
                            Severity.HIGH,
                            "hashCode() uses @Id field (breaks before persist)",
                            String.format("%s.hashCode() depends on the @Id field '%s'. The ID is null before " +
                                    "persist and assigned during flush — hashCode changes mid-lifecycle.",
                                    className, idFieldName),
                            classLocation(source, clazz),
                            String.format(
                                    "### What this means\n" +
                                    "For most ID generation strategies (`GenerationType.IDENTITY`, `SEQUENCE`, `AUTO`), " +
                                    "the `@Id` field is `null` while the entity is transient and only gets a value " +
                                    "after the DB INSERT. Anything you computed from that field — including hashCode — " +
                                    "changes the moment the entity is persisted.\n\n" +
                                    "### Why it matters — concrete failure mode\n" +
                                    "```\n" +
                                    "1. Entity created:        id = null,  hashCode = X\n" +
                                    "2. set.add(entity):       stored in bucket for hash X\n" +
                                    "3. repo.save(entity):     id = 42,    hashCode = Y\n" +
                                    "4. set.contains(entity):  looks in bucket Y — entity is in bucket X — returns FALSE\n" +
                                    "5. set.remove(entity):    same problem — silently does nothing\n" +
                                    "```\n" +
                                    "The Set is now corrupted: the entity is unreachable through normal API, but " +
                                    "still consumes memory and shows up in iteration. Equally broken for `HashMap` keys.\n\n" +
                                    "### Why it matters — Hibernate-specific consequence\n" +
                                    "Hibernate uses `@OneToMany` / `@ManyToMany` Set fields internally. The same bug " +
                                    "applies — adding a transient child entity to a parent's Set, then persisting, " +
                                    "leaves the child in the wrong bucket. The next `parent.getChildren().contains(child)` " +
                                    "returns false even though it's in the collection.\n\n" +
                                    "### Common misunderstanding — \"`Objects.hash(id)` is null-safe so I'm fine\"\n" +
                                    "Null-safety isn't the problem. `Objects.hash(null)` returns a stable value, but " +
                                    "it's a *different* value from `Objects.hash(42)`. The hashCode still changes when " +
                                    "the ID is assigned. The mathematical safety guarantee is irrelevant to the bug.\n\n" +
                                    "### How to fix\n" +
                                    "```java\n" +
                                    "// When equals is ID-based, hashCode MUST be constant\n" +
                                    "@Override\n" +
                                    "public int hashCode() {\n" +
                                    "    return getClass().hashCode();  // same for all instances of this entity class\n" +
                                    "}\n" +
                                    "```\n\n" +
                                    "### Performance note\n" +
                                    "Yes, a constant hashCode degrades HashMap/HashSet lookup to O(n). " +
                                    "This is fine for small collections (99%% of entity usage — a User has a few " +
                                    "Roles, an Order has a handful of LineItems). If you genuinely need O(1) lookup " +
                                    "across thousands of entities, use a natural business key (email, code, UUID) " +
                                    "instead of the database ID.\n\n" +
                                    "### When to suppress\n" +
                                    "- The entity ID is assigned client-side BEFORE persist (e.g., application-generated " +
                                    "UUID via `@PrePersist`). In that case the ID is stable throughout the lifecycle " +
                                    "and ID-based hashCode is safe. Suppress with " +
                                    "`// @analyzer-ignore: hibernate.id-hashcode` and document the ID strategy.")
                    ));
                }
            }
        }
    }

    // -- Serializable check --------------------------------------------------

    private void checkSerializable(ClassOrInterfaceDeclaration clazz,
                                     ParsedSource source, List<Finding> findings) {
        String className = clazz.getNameAsString();

        boolean implementsSerializable = clazz.getImplementedTypes().stream()
                .anyMatch(t -> t.getNameAsString().equals("Serializable"));

        // Check parent class too
        boolean extendsSerializable = clazz.getExtendedTypes().stream()
                .anyMatch(t -> {
                    String name = t.getNameAsString();
                    // Common serializable base classes
                    return name.contains("Abstract") || name.contains("Base");
                });

        if (!implementsSerializable && !extendsSerializable) {
            findings.add(new Finding(
                    Category.HIBERNATE_EQUALS_HASHCODE, // reuse category
                    Severity.LOW,
                    "Entity does not implement Serializable",
                    String.format("%s is a JPA entity but does not implement Serializable.",
                            className),
                    classLocation(source, clazz),
                    String.format(
                            "### What this means\n" +
                            "The JPA 2.x spec (§2.1) requires entity classes to implement `Serializable` if " +
                            "instances will be passed by value, detached, or used in composite keys. Hibernate " +
                            "doesn't enforce this at runtime, but several features rely on it.\n\n" +
                            "### When it matters (rank-ordered by likelihood of biting you)\n" +
                            "1. **Second-level cache** (Ehcache, Hazelcast, Infinispan) — serialization is mandatory; " +
                            "absence throws `NotSerializableException` at cache put time.\n" +
                            "2. **Composite primary keys** (`@IdClass` / `@EmbeddedId`) — the ID class MUST be " +
                            "Serializable per the JPA spec; the framework will fail at startup if not.\n" +
                            "3. **Clustered HTTP sessions** — Tomcat / WebLogic session replication serializes everything " +
                            "stored in the session. An entity stored on session → cluster member crash → " +
                            "`NotSerializableException`, session lost.\n" +
                            "4. **Distributed caches in front of the DB** (Redis with Java serialization, Coherence).\n" +
                            "5. **RMI / EJB remote calls / JMS message payloads** — rare in modern apps.\n\n" +
                            "### Common misunderstanding — \"We're a REST/JSON app, this doesn't apply\"\n" +
                            "Largely true *today*, but: (a) any team that later turns on 2nd-level cache discovers " +
                            "this at the worst moment; (b) clustered session replication may be added by ops without " +
                            "changing app code; (c) the cost of preventing this is one interface + one constant.\n\n" +
                            "### How to fix\n" +
                            "```java\n" +
                            "@Entity\n" +
                            "public class %s implements Serializable {\n" +
                            "    private static final long serialVersionUID = 1L;\n" +
                            "    // ...\n" +
                            "}\n" +
                            "```\n" +
                            "If the entity extends an abstract base class, add `Serializable` to the base instead.\n\n" +
                            "### When to suppress\n" +
                            "- No 2nd-level cache, no clustered sessions, no composite keys, no remoting. Likely safe " +
                            "but cheap to fix anyway. Severity is LOW precisely because this is advisory.",
                            className)
            ));
        }
    }

    private SourceLocation classLocation(ParsedSource source, ClassOrInterfaceDeclaration clazz) {
        int line = clazz.getBegin().map(p -> p.line).orElse(0);
        return SourceLocation.of(source.getRelativePathString(), line, line,
                clazz.getNameAsString(), null);
    }
}
