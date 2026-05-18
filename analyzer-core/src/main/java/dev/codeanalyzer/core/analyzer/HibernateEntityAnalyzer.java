package dev.codeanalyzer.core.analyzer;

import dev.codeanalyzer.core.analyzer.hibernate.EntityGraphModel;
import dev.codeanalyzer.core.analyzer.hibernate.EntityGraphModel.*;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.Finding.Category;
import dev.codeanalyzer.core.model.Finding.Severity;
import dev.codeanalyzer.core.model.ParsedSource;
import dev.codeanalyzer.core.model.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Two-pass Hibernate entity analyzer with graph-aware severity scoring
 * and enriched, actionable suggestions with code examples.
 */
public class HibernateEntityAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(HibernateEntityAnalyzer.class);

    private static final int SIMPLE_ENTITY_FIELD_THRESHOLD = 5;
    private static final int COMPLEX_ENTITY_FIELD_THRESHOLD = 10;
    private static final int CASCADE_THRESHOLD = 3;

    @Override
    public String getId() { return "hibernate-entity"; }

    @Override
    public String getName() { return "Hibernate Entity Analyzer"; }

    @Override
    public List<Finding> analyze(List<ParsedSource> sources) {
        EntityGraphModel graph = new EntityGraphModel();
        graph.build(sources);

        List<Finding> findings = new ArrayList<>();
        detectCycles(graph, findings);

        for (EntityInfo entity : graph.getAllEntities()) {
            for (RelationInfo rel : entity.getRelations()) {
                if (rel.isCollection()) {
                    analyzeCollectionRelation(entity, rel, graph, findings);
                } else {
                    analyzeSingleRelation(entity, rel, graph, findings);
                }
            }
        }

        log.info("Hibernate analysis: {} entities in graph, {} findings produced",
                graph.getAllEntities().size(), findings.size());
        return findings;
    }

    // -- Cycle detection -----------------------------------------------------

    private void detectCycles(EntityGraphModel graph, List<Finding> findings) {
        List<EagerCycle> cycles = graph.detectEagerCycles();
        Set<String> reportedCycles = new HashSet<>();

        for (EagerCycle cycle : cycles) {
            String cycleKey = normalizeCycleKey(cycle);
            if (reportedCycles.contains(cycleKey)) continue;
            reportedCycles.add(cycleKey);

            CyclePath first = cycle.getPath().get(0);
            String entityA = first.entity.getName();
            String fieldA = first.relation.getFieldName();
            String entityB = first.relation.getTargetTypeName();

            findings.add(new Finding(
                    Category.HIBERNATE_EAGER_CYCLE,
                    Severity.CRITICAL,
                    "Bidirectional EAGER cycle detected",
                    String.format("EAGER fetch cycle: %s. Every query touching any participant " +
                            "triggers cascading SELECTs across all entities in the loop.",
                            cycle.describe()),
                    SourceLocation.of(
                            first.entity.getSource().getRelativePathString(),
                            first.relation.getLine(), first.relation.getLine(),
                            entityA, fieldA),
                    String.format(
                            "### What this means\n" +
                            "%s holds an EAGER reference to %s, and %s eagerly references back into the cycle. " +
                            "When Hibernate loads any one of them, it tries to load the others. The first-level " +
                            "session cache prevents *infinite* recursion, but it does NOT prevent the cascade of " +
                            "extra SQL SELECTs — every entity still has to be materialized at least once per session.\n\n" +
                            "### Why it matters (production consequences)\n" +
                            "- **List queries explode.** A simple `findAll()` on %s emits N additional SELECTs " +
                            "per row to hydrate the chain. A page of 50 entities becomes 200+ queries.\n" +
                            "- **JOIN FETCH on multiple bags throws MultipleBagFetchException.** Once one cycle " +
                            "participant is a `List`, JOIN FETCH on more than one collection at a time fails at " +
                            "runtime. You'll find out in QA at best, production at worst.\n" +
                            "- **REST/JSON serialization stack-overflows.** Bidirectional EAGER + Jackson loops " +
                            "endlessly on serialize. Most teams patch the symptom (`@JsonIgnore`, `@JsonManagedReference`) " +
                            "without fixing the underlying fetch problem — see below.\n" +
                            "- **Memory pressure.** Each loaded entity sits in the persistence context until the " +
                            "session closes. EAGER cycles balloon working-set size.\n\n" +
                            "### Common misunderstanding — \"I added @JsonIgnore, the problem is solved\"\n" +
                            "No. `@JsonIgnore` (and `@JsonManagedReference`/`@JsonBackReference`) only affect " +
                            "**JSON serialization**. Hibernate still EAGER-fetches the cycle — the SQL cost stays " +
                            "exactly the same, you just don't see the data in the JSON response. The performance " +
                            "bug is one layer below your serialization layer. Fix the fetch strategy, then " +
                            "serialization rules become trivial.\n\n" +
                            "### Common misunderstanding — \"JOIN FETCH solves it\"\n" +
                            "JOIN FETCH only helps the *specific query that uses it*. The field stays EAGER, so " +
                            "every other query that touches this entity (auto-generated finders, audit queries, " +
                            "lazy navigation from a different entry point) still pays the full cost.\n\n" +
                            "### Common misunderstanding — \"The cache will deduplicate it\"\n" +
                            "True only inside a single Hibernate session. New session = empty cache = full re-fetch.\n\n" +
                            "### How to fix\n" +
                            "Break the cycle at the back-reference (the inverse side, often `@OneToMany` or the " +
                            "reverse `@ManyToOne` from a child to parent):\n" +
                            "```java\n" +
                            "// In %s.java — set the back-reference to LAZY\n" +
                            "@%s(fetch = FetchType.LAZY)\n" +
                            "private %s %s;\n" +
                            "```\n" +
                            "For specific queries that need both sides, use JOIN FETCH or `@EntityGraph` per-query.\n\n" +
                            "### When to suppress\n" +
                            "Almost never. If you genuinely need both sides loaded everywhere, you're describing a " +
                            "single denormalized entity, not two — consider `@Embedded` or merging the tables.",
                            entityA, entityB, entityB, entityA,
                            entityB,
                            cycle.getPath().size() > 1 ? cycle.getPath().get(1).relation.getAnnotationType()
                                    : first.relation.getAnnotationType(),
                            entityA,
                            cycle.getPath().size() > 1 ? cycle.getPath().get(1).relation.getFieldName()
                                    : fieldA)
            ));
        }

        if (!cycles.isEmpty()) {
            log.info("Detected {} unique EAGER cycles", reportedCycles.size());
        }
    }

    private String normalizeCycleKey(EagerCycle cycle) {
        List<String> names = new ArrayList<>();
        for (CyclePath cp : cycle.getPath()) {
            names.add(cp.entity.getName());
        }
        Collections.sort(names);
        return String.join("->", names);
    }

    // -- Collection analysis -------------------------------------------------

    private void analyzeCollectionRelation(EntityInfo entity, RelationInfo rel,
                                            EntityGraphModel graph, List<Finding> findings) {
        if (rel.isEffectivelyEager()) {
            findings.add(new Finding(
                    Category.HIBERNATE_FETCH_STRATEGY,
                    Severity.HIGH,
                    "EAGER fetch on collection",
                    String.format("@%s on %s.%s uses FetchType.EAGER. Every query for %s loads the entire %s " +
                            "collection — even when it's never accessed.",
                            rel.getAnnotationType(), entity.getName(), rel.getFieldName(),
                            entity.getName(), rel.getFieldName()),
                    location(entity, rel),
                    String.format(
                            "### What this means\n" +
                            "An EAGER `@%s` collection is fetched on every load of %s, regardless of whether the " +
                            "caller needs it. There is no way to opt out at the query level — the field is wired " +
                            "into every read path.\n\n" +
                            "### Why it matters (production consequences)\n" +
                            "- **List queries trigger one extra SELECT per row** (Hibernate default strategy is " +
                            "`select` on collections). A `findAll()` returning 100 %s rows fires 100 additional " +
                            "queries for %s — the classic N+1 in its most expensive form.\n" +
                            "- **Cartesian product explosion.** If Hibernate switches to JOIN fetching, two EAGER " +
                            "collections on the same entity multiply rows (10 × 10 = 100 returned rows, then " +
                            "in-memory deduplication). With three collections it's 1000.\n" +
                            "- **MultipleBagFetchException at runtime.** As soon as you JOIN FETCH this collection " +
                            "alongside another `List`/`Bag`, Hibernate throws — there's no way to query both at once.\n" +
                            "- **OutOfMemoryError on bulk operations.** EAGER collections in batch jobs balloon " +
                            "the working set. A nightly job that worked fine at 1k rows OOMs at 10k.\n\n" +
                            "### Common misunderstanding — \"It's just one extra query, why care\"\n" +
                            "It's not one query — it's one query **per row of every list query that returns %s**. " +
                            "If %s appears in any paged list, the cost multiplies by page size on every request.\n\n" +
                            "### Common misunderstanding — \"JOIN FETCH at the query level fixes it\"\n" +
                            "JOIN FETCH only helps **that one query**. The field stays EAGER everywhere else — " +
                            "REST endpoints, internal services, scheduled jobs, repository derived methods, all " +
                            "still pay the N+1 cost. The fix has to be at the field declaration.\n\n" +
                            "### How to fix\n" +
                            "```java\n" +
                            "// Before (current)\n" +
                            "@%s(fetch = FetchType.EAGER)\n" +
                            "private List<%s> %s;\n" +
                            "\n" +
                            "// After (recommended)\n" +
                            "@%s(fetch = FetchType.LAZY)\n" +
                            "@BatchSize(size = 20)        // batches N+1 access when the collection IS needed\n" +
                            "private List<%s> %s;\n" +
                            "```\n" +
                            "Use JOIN FETCH or `@EntityGraph` in the specific queries that need the data:\n" +
                            "```java\n" +
                            "@EntityGraph(attributePaths = {\"%s\"})\n" +
                            "List<%s> findActive();\n" +
                            "```\n\n" +
                            "### When to suppress\n" +
                            "- Truly tiny, bounded collection that is always required (e.g. an `enum`-mapped " +
                            "`@ElementCollection` of 2-3 values). Even then, LAZY + `@BatchSize` costs nothing extra. " +
                            "Suppress with `// @analyzer-ignore: hibernate.eager-collection`.",
                            rel.getAnnotationType(), entity.getName(),
                            entity.getName(), rel.getFieldName(),
                            entity.getName(), entity.getName(),
                            rel.getAnnotationType(), rel.getTargetTypeName(), rel.getFieldName(),
                            rel.getAnnotationType(), rel.getTargetTypeName(), rel.getFieldName(),
                            rel.getFieldName(), entity.getName())
            ));
        } else if (!rel.hasBatchSize() && !rel.hasFetchAnnotation()) {
            findings.add(new Finding(
                    Category.HIBERNATE_N_PLUS_ONE,
                    Severity.INFO,
                    "LAZY collection without @BatchSize",
                    String.format("@%s on %s.%s is LAZY (good) but has no @BatchSize. If iterated " +
                            "in a loop, each access fires a separate SELECT.",
                            rel.getAnnotationType(), entity.getName(), rel.getFieldName()),
                    location(entity, rel),
                    String.format(
                            "### What this means\n" +
                            "LAZY by itself defers loading until first access — good. But when you load a list of " +
                            "%s and then iterate accessing `.get%s()` on each one, Hibernate fires one SELECT per " +
                            "iteration (the canonical N+1). `@BatchSize(n)` tells Hibernate: \"when you have to " +
                            "fetch this collection for one entity, fetch it for up to n proxies that need loading.\"\n\n" +
                            "### Why it matters\n" +
                            "- **N+1 in real scenarios.** Any list-then-foreach pattern — REST endpoints returning " +
                            "lists with associations, scheduled report generation, audit log assembly — pays this cost.\n" +
                            "- **Hard to spot in code review.** The repository call looks innocent; the N+1 happens " +
                            "in the view layer or DTO mapper, far from the SQL.\n" +
                            "- **`@BatchSize` is free when unused.** If you never touch the collection, no SELECTs " +
                            "fire at all. The annotation only kicks in when loading is unavoidable.\n\n" +
                            "### Common misunderstanding — \"BatchSize is JPA standard\"\n" +
                            "It is not. `@BatchSize` is Hibernate-specific (`org.hibernate.annotations.BatchSize`). " +
                            "JPA has no equivalent. If you target multiple JPA providers, you need provider-specific " +
                            "annotations or rely on per-query JOIN FETCH.\n\n" +
                            "### Common misunderstanding — \"JOIN FETCH and @BatchSize are interchangeable\"\n" +
                            "They solve different problems. JOIN FETCH is **per-query** (controlled at the call " +
                            "site, loads exactly what you asked for). `@BatchSize` is **per-field** (applies " +
                            "wherever the entity is loaded, batches the inevitable N+1). Use both: JOIN FETCH " +
                            "when you know up-front you need the data, `@BatchSize` as a safety net for lazy " +
                            "navigation paths you didn't anticipate.\n\n" +
                            "### Quick fix\n" +
                            "```java\n" +
                            "@%s\n" +
                            "@BatchSize(size = 20)  // loads up to 20 collections in one SELECT when triggered\n" +
                            "private List<%s> %s;\n" +
                            "```\n\n" +
                            "### When to suppress\n" +
                            "- The collection is never accessed outside a single query that already uses JOIN FETCH.\n" +
                            "- Target entity is in a non-Hibernate JPA provider context (no @BatchSize support).\n" +
                            "- Severity is INFO precisely because this is advisory — suppress freely if you've audited.",
                            entity.getName(),
                            Character.toUpperCase(rel.getFieldName().charAt(0)) + rel.getFieldName().substring(1),
                            rel.getAnnotationType(), rel.getTargetTypeName(), rel.getFieldName())
            ));
        }
    }

    // -- Single association analysis -----------------------------------------

    private void analyzeSingleRelation(EntityInfo entity, RelationInfo rel,
                                        EntityGraphModel graph, List<Finding> findings) {
        if (!rel.isEffectivelyEager()) {
            return;
        }

        RiskAssessment risk = assessRisk(entity, rel, graph);
        String implicitLabel = rel.isImplicitEager() ? " (JPA default)" : "";

        findings.add(new Finding(
                Category.HIBERNATE_FETCH_STRATEGY,
                risk.severity,
                "EAGER fetch on single association" + implicitLabel + " - " + risk.riskLevel,
                String.format("@%s on %s.%s uses EAGER fetch%s. %s",
                        rel.getAnnotationType(), entity.getName(), rel.getFieldName(),
                        implicitLabel, risk.explanation),
                location(entity, rel),
                risk.suggestion
        ));
    }

    private RiskAssessment assessRisk(EntityInfo entity, RelationInfo rel,
                                       EntityGraphModel graph) {
        EntityInfo target = rel.getResolvedTarget();
        String ann = rel.getAnnotationType();
        String eName = entity.getName();
        String fName = rel.getFieldName();
        String tName = rel.getTargetTypeName();

        if (target == null) {
            return new RiskAssessment(
                    Severity.MEDIUM, "unknown risk",
                    String.format("Target type '%s' could not be resolved in the entity graph. " +
                            "Unable to assess cascade depth -- review manually.", tName),
                    buildSuggestion(ann, eName, fName, tName,
                            "### Action required\n" +
                            "The target entity is in a module or dependency not included in this scan. " +
                            "Review manually to assess whether EAGER is justified.\n")
            );
        }

        int transitiveEagerCount = graph.countTransitiveEagerDepth(target, 5);

        // Case 1: Deep cascade
        if (transitiveEagerCount >= CASCADE_THRESHOLD) {
            return new RiskAssessment(
                    Severity.HIGH, "cascade risk",
                    String.format("Target entity %s has %d transitive EAGER associations. " +
                            "Loading %s.%s triggers a cascade of SELECTs.",
                            tName, transitiveEagerCount, eName, fName),
                    buildSuggestion(ann, eName, fName, tName,
                            String.format(
                            "### Why this is HIGH severity\n" +
                            "Loading %s eagerly pulls in %s, which itself eagerly loads %d more " +
                            "associations. A single query for %s can generate 5+ additional SELECTs. " +
                            "In a list query returning N rows, this multiplies to N * %d+ extra queries.\n",
                            fName, tName, transitiveEagerCount, eName, transitiveEagerCount))
            );
        }

        // Case 2: Heavy entity
        if (target.getFieldCount() > COMPLEX_ENTITY_FIELD_THRESHOLD) {
            return new RiskAssessment(
                    Severity.MEDIUM, "heavy entity",
                    String.format("Target entity %s has %d fields. " +
                            "EAGER loading adds substantial data transfer.",
                            tName, target.getFieldCount()),
                    buildSuggestion(ann, eName, fName, tName,
                            String.format(
                            "### Impact\n" +
                            "%s has %d columns. Every query for %s will SELECT all those columns " +
                            "via JOIN or sub-SELECT, even when only %s's own fields are needed.\n",
                            tName, target.getFieldCount(), eName, eName))
            );
        }

        // Case 3: Indirect cascade
        if (target.getEagerRelationCount() > 0 && transitiveEagerCount > 0) {
            return new RiskAssessment(
                    Severity.MEDIUM, "indirect cascade",
                    String.format("Target entity %s has %d EAGER association(s), " +
                            "causing %d total transitive loads.",
                            tName, target.getEagerRelationCount(), transitiveEagerCount),
                    buildSuggestion(ann, eName, fName, tName,
                            String.format(
                            "### Impact\n" +
                            "%s has its own EAGER associations (%d), creating a loading chain " +
                            "of %d total entities. Consider LAZY if %s is loaded frequently.\n",
                            tName, target.getEagerRelationCount(), transitiveEagerCount, eName))
            );
        }

        // Case 4: Leaf entity
        if (target.isLeafEntity()) {
            return new RiskAssessment(
                    Severity.LOW, "leaf entity (likely intentional)",
                    String.format("Target entity %s is a simple entity (%d fields, no EAGER associations). " +
                            "EAGER is likely intentional.",
                            tName, target.getFieldCount()),
                    String.format(
                            "### No action needed\n" +
                            "%s is a simple entity (%d fields, no further associations). " +
                            "EAGER loading a small lookup/status table is a common and valid pattern.\n" +
                            "### Consider LAZY only if\n" +
                            "- %s is loaded in bulk (list queries returning 100+ rows)\n" +
                            "- %s is rarely accessed after loading %s\n" +
                            "- You observe excessive JOINs in SQL logs",
                            tName, target.getFieldCount(), eName, tName, eName)
            );
        }

        // Case 5: Standard
        return new RiskAssessment(
                Severity.MEDIUM, "standard",
                String.format("Target entity %s has %d fields and no deep cascades.",
                        tName, target.getFieldCount()),
                buildSuggestion(ann, eName, fName, tName,
                        String.format(
                        "### Impact\n" +
                        "Every query for %s will JOIN or sub-SELECT %s (%d fields). " +
                        "This is not critical but adds unnecessary overhead when %s is not needed.\n",
                        eName, tName, target.getFieldCount(), tName))
        );
    }

    /**
     * Builds a standard suggestion with before/after code and contextual explanation.
     */
    private String buildSuggestion(String annotation, String entityName, String fieldName,
                                    String targetType, String contextSection) {
        return String.format(
                "%s" +
                "### How to fix\n" +
                "```\n" +
                "// Before (current)\n" +
                "@%s  // defaults to EAGER or explicitly EAGER\n" +
                "private %s %s;\n" +
                "\n" +
                "// After (recommended)\n" +
                "@%s(fetch = FetchType.LAZY)\n" +
                "private %s %s;\n" +
                "```\n" +
                "### When you need the data\n" +
                "Use JOIN FETCH in the specific query that needs %s:\n" +
                "```\n" +
                "SELECT e FROM %s e JOIN FETCH e.%s WHERE ...\n" +
                "```\n" +
                "### Alternative: @EntityGraph\n" +
                "```\n" +
                "@EntityGraph(attributePaths = {\"%s\"})\n" +
                "List<%s> findByStatus(...);\n" +
                "```",
                contextSection,
                annotation, targetType, fieldName,
                annotation, targetType, fieldName,
                fieldName,
                entityName, fieldName,
                fieldName, entityName);
    }

    private SourceLocation location(EntityInfo entity, RelationInfo rel) {
        return SourceLocation.of(
                entity.getSource().getRelativePathString(),
                rel.getLine(), rel.getLine(),
                entity.getName(), rel.getFieldName());
    }

    private static class RiskAssessment {
        final Severity severity;
        final String riskLevel;
        final String explanation;
        final String suggestion;

        RiskAssessment(Severity severity, String riskLevel, String explanation, String suggestion) {
            this.severity = severity;
            this.riskLevel = riskLevel;
            this.explanation = explanation;
            this.suggestion = suggestion;
        }
    }
}
