package dev.codeanalyzer.core.analyzer.runtime;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import dev.codeanalyzer.core.analyzer.Analyzer;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;
import dev.codeanalyzer.core.model.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inspects every {@code @Query} / named-query string in the codebase and flags
 * SQL/JPQL/HQL patterns that reliably correlate with production incidents.
 *
 * <p>Deliberately <b>not</b> a real SQL parser — that would be a full grammar
 * engineering project for marginal extra catch rate. Instead, this matches a
 * handful of patterns that almost always indicate a bug or perf trap:
 * leading wildcard LIKE, pagination without ORDER BY, native queries returning
 * entities without a result mapping, etc. PMD/SonarQube do none of this.
 *
 * <p>Each finding gets a stable {@code ruleId} starting with {@code sql.}
 * so it can be suppressed individually via {@code .codebase-analyzer.yml}.
 */
public class SqlQueryAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(SqlQueryAnalyzer.class);

    /** Spring Data + JPA query annotations we read. */
    private static final List<String> QUERY_ANNOTATIONS = Arrays.asList(
            "Query", "NamedQuery", "NamedNativeQuery"
    );

    private static final Pattern LIKE_LEADING_WILDCARD =
            Pattern.compile("\\bLIKE\\s+(?:'%[^']*'|\"[^\"]*%[^\"]*\"|:[A-Za-z_][A-Za-z0-9_]*\\s*\\+\\s*'%')",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern LIKE_PARAM_WITH_PERCENT_PREFIX =
            Pattern.compile("CONCAT\\s*\\(\\s*'%'\\s*,\\s*:", Pattern.CASE_INSENSITIVE);

    private static final Pattern HAS_ORDER_BY = Pattern.compile("\\bORDER\\s+BY\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_GROUP_BY = Pattern.compile("\\bGROUP\\s+BY\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_DISTINCT  = Pattern.compile("\\bDISTINCT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_STAR   = Pattern.compile("SELECT\\s+\\*\\s+FROM", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_ENTITY = Pattern.compile("SELECT\\s+(\\w+)\\s+FROM\\s+(\\w+)\\s+\\1\\b",
                                                                 Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_CLAUSE_BIND = Pattern.compile("\\bIN\\s*\\(\\s*:[A-Za-z_][A-Za-z0-9_]*\\s*\\)",
                                                                  Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_JOIN_FETCH = Pattern.compile("\\bJOIN\\s+FETCH\\b", Pattern.CASE_INSENSITIVE);

    @Override public String getId() { return "sql-query"; }
    @Override public String getName() { return "SQL Query Analyzer"; }

    @Override
    public List<Finding> analyze(List<ParsedSource> sources) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource src : sources) {
            CompilationUnit cu = src.getCompilationUnit();
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                for (AnnotationExpr ann : m.getAnnotations()) {
                    if (!QUERY_ANNOTATIONS.contains(ann.getNameAsString())) continue;
                    QueryInfo q = readQuery(ann);
                    if (q == null || q.sql == null || q.sql.isEmpty()) continue;
                    inspect(q, m, src, findings);
                }
            }
        }
        if (!findings.isEmpty()) {
            log.info("SQL query analysis: {} findings", findings.size());
        }
        return findings;
    }

    // ─── Inspection rules ────────────────────────────────────────────────────

    private void inspect(QueryInfo q, MethodDeclaration m, ParsedSource src, List<Finding> out) {
        String sql = q.sql;
        int line = m.getBegin().map(p -> p.line).orElse(0);
        String className = m.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(ClassOrInterfaceDeclaration::getNameAsString).orElse("");
        String memberName = m.getNameAsString();
        SourceLocation loc = SourceLocation.of(src.getRelativePathString(), line, line, className, memberName);

        // 1) Leading-wildcard LIKE — prevents index use, full table scan
        if (LIKE_LEADING_WILDCARD.matcher(sql).find()
                || LIKE_PARAM_WITH_PERCENT_PREFIX.matcher(sql).find()) {
            out.add(build(loc, "sql.like-leading-wildcard",
                    Finding.Severity.HIGH, Finding.Confidence.HIGH,
                    "LIKE with leading wildcard prevents index use",
                    "Query on " + className + "." + memberName + " uses a LIKE pattern with a leading '%' wildcard. "
                            + "Forces a full table scan — no index can be used.",
                    "### What this means\n" +
                    "B-tree indexes are sorted left-to-right. They can answer questions like \"all values starting " +
                    "with 'foo'\" by walking a small subtree. The moment your pattern begins with a wildcard " +
                    "(`'%foo'` or `'%foo%'`) the index loses its leftmost anchor — the database must scan every " +
                    "row, then evaluate the LIKE for each one.\n\n" +
                    "### Why it matters (production consequences)\n" +
                    "- **Linear scaling on the wrong axis.** A 10k-row table is fine; a 10M-row table is a " +
                    "500ms→50s degradation. Performance degrades with table growth, not query frequency, so it " +
                    "passes early load tests and dies in year-3 production.\n" +
                    "- **Lock-and-bleed under load.** Sequential scans hold shared locks longer; concurrent queries " +
                    "queue; connection pool drains.\n" +
                    "- **Query plans are stable until they aren't.** Postgres/Oracle may keep using `seq scan` " +
                    "happily on dev data but a statistics refresh can flip to a different (still bad) plan, " +
                    "creating intermittent prod incidents.\n\n" +
                    "### Common misunderstanding — \"My column has an index, so LIKE is fast\"\n" +
                    "Standard B-tree indexes ONLY help with anchored prefixes (`'foo%'`). For leading-wildcard " +
                    "searches you need either: (a) a trigram index (Postgres `pg_trgm`), (b) a reverse-indexed " +
                    "column for suffix search, (c) a full-text index, or (d) an external search backend.\n\n" +
                    "### Common misunderstanding — \"`UPPER(col) LIKE UPPER(?)` is fine\"\n" +
                    "Worse — now you have BOTH problems: leading wildcard AND a function on the indexed column, " +
                    "which independently disables the index. Use a functional index (`CREATE INDEX ... ON " +
                    "lower(col)`) and lowercase consistently.\n\n" +
                    "### Fix options (in order of preference)\n" +
                    "- **Anchored search** (`LIKE 'foo%'`) — use B-tree index directly.\n" +
                    "- **Trigram index** (Postgres `CREATE INDEX ... USING gin (col gin_trgm_ops)`) — works with " +
                    "`%foo%` patterns.\n" +
                    "- **Full-text search** (Hibernate Search, OpenSearch, Lucene) — purpose-built for substring/" +
                    "fuzzy queries.\n" +
                    "- **Push to a dedicated search backend** (Elastic/Algolia/Meilisearch) if business logic allows.\n\n" +
                    "### When to suppress\n" +
                    "- Small lookup table (< 10k rows, bounded growth). Suppress with " +
                    "`// @analyzer-ignore: sql.like-leading-wildcard` and document the size guarantee.",
                    sql, q.native_));
            return; // one query → one primary finding to avoid noise
        }

        // 2) Pagination without ORDER BY — unstable result ordering
        boolean hasPageable = hasPageableParameter(m);
        if (hasPageable && !HAS_ORDER_BY.matcher(sql).find()) {
            out.add(build(loc, "sql.pagination-without-sort",
                    Finding.Severity.HIGH, Finding.Confidence.HIGH,
                    "Pageable query without ORDER BY (unstable pagination)",
                    "Query on " + className + "." + memberName + " accepts a Pageable but has no ORDER BY clause. " +
                            "Row order is undefined.",
                    "### What this means\n" +
                    "SQL has NO default ordering. When you ask for rows without an `ORDER BY`, the database is " +
                    "free to return them in any order it likes — and that order can change between executions, " +
                    "between query plans, between server versions, between rows added or removed. `LIMIT 10 OFFSET 0` " +
                    "and `LIMIT 10 OFFSET 10` are not guaranteed to be disjoint, contiguous slices of the same set.\n\n" +
                    "### Why it matters (production consequences)\n" +
                    "- **Duplicate rows across pages.** User sees the same record on page 1 and page 2. " +
                    "Looks like a bug because it is one.\n" +
                    "- **Missing rows.** A row visible \"between\" page boundaries may never be returned at all.\n" +
                    "- **Worse under concurrent writes.** Inserts/deletes between page requests shift everything; " +
                    "the user paginating through a dashboard misses entire batches of data.\n" +
                    "- **Worse on Postgres after vacuum.** Vacuum repacks tuples; the \"natural\" order changes; " +
                    "previously-stable pagination behaviour breaks without any deploy.\n" +
                    "- **Bug surfaces in test data, then disappears.** Dev environments with 50 rows may always " +
                    "return the same physical order, hiding the bug. Production with 5M rows + concurrent writes " +
                    "produces customer complaints.\n\n" +
                    "### Common misunderstanding — \"Sorting by the primary key has no value\"\n" +
                    "It has the most value: PK ordering is stable, indexed (B-tree backing the PK), and almost " +
                    "free to compute. `ORDER BY id` is the cheapest deterministic ordering you can ask for.\n\n" +
                    "### Common misunderstanding — \"The Pageable from Spring already sorts\"\n" +
                    "Only if the caller passes a `Sort` (or you provide a default Sort). A `PageRequest.of(0, 20)` " +
                    "with no Sort parameter results in no ORDER BY being added — Spring doesn't invent one for you.\n\n" +
                    "### Common misunderstanding — \"OFFSET+LIMIT pagination is best practice\"\n" +
                    "It's the simplest, not the best. OFFSET is O(N) — the DB has to walk and discard rows. " +
                    "For deep pagination, prefer **keyset (cursor) pagination**: `WHERE id > :lastSeenId ORDER " +
                    "BY id LIMIT 20`. Always-fast, stable under concurrent writes.\n\n" +
                    "### Fix\n" +
                    "Add an `ORDER BY` on a stable, indexed column (typically the primary key):\n" +
                    "```jpql\n" +
                    "SELECT e FROM Entity e WHERE ... ORDER BY e.id\n" +
                    "```\n" +
                    "If callers may pass their own `Sort`, ensure a stable fallback: `Sort.by(\"createdAt\", \"id\")` " +
                    "keeps creation-time order but disambiguates ties with the PK.\n\n" +
                    "### When to suppress\n" +
                    "- The query genuinely only ever returns at most one page (and you've already verified the " +
                    "row count is bounded). Rare. Suppress with `// @analyzer-ignore: sql.pagination-without-sort`.",
                    sql, q.native_));
        }

        // 3) Native query returning entity without explicit mapping
        if (q.native_ && q.resultClass == null && q.resultSetMapping == null
                && (SELECT_STAR.matcher(sql).find() || SELECT_ENTITY.matcher(sql).find())) {
            out.add(build(loc, "sql.native-no-typecheck",
                    Finding.Severity.MEDIUM, Finding.Confidence.MEDIUM,
                    "Native query returning entity without resultClass / resultSetMapping",
                    "Native query on " + className + "." + memberName + " appears to return entity rows but " +
                            "declares no `resultClass` / `resultSetMapping`.",
                    "### What this means\n" +
                    "JPQL/HQL queries are parsed by Hibernate against the entity model — Hibernate knows " +
                    "which columns map to which fields, validates the projection at startup, and reports " +
                    "missing columns as a parse error. Native SQL queries are passed verbatim to the JDBC " +
                    "driver. Without `resultClass` or `resultSetMapping`, Hibernate doesn't know which entity " +
                    "type to instantiate or how to bind columns to fields.\n\n" +
                    "### Why it matters (production consequences)\n" +
                    "- **Schema drift breaks silently.** A renamed column in the DB doesn't fail startup or " +
                    "tests — it fails at first call, in production, with a cryptic `SQLException`.\n" +
                    "- **The result is `Object[]`, not your entity.** Callers cast to your entity type and hit " +
                    "`ClassCastException` at runtime, OR the framework returns scalar arrays and confused " +
                    "downstream code uses positional access (brittle to column reordering).\n" +
                    "- **Hibernate caching and dirty-checking don't apply.** Even if rows look right, they're " +
                    "not managed entities — modifications don't propagate, second-level cache misses.\n\n" +
                    "### Common misunderstanding — \"Spring Data JPA infers the result type from the method signature\"\n" +
                    "Only for JPQL. For `nativeQuery = true`, Spring Data does NOT inspect the return type to " +
                    "set a default `resultClass`. You must declare it explicitly or convert to JPQL.\n\n" +
                    "### Common misunderstanding — \"It works in dev, it'll work in prod\"\n" +
                    "Native query results are positional. Adding a column in production (`ALTER TABLE ADD " +
                    "COLUMN`) or reordering columns silently shifts which field gets which value. The query " +
                    "still runs — it just returns wrong data.\n\n" +
                    "### Fix (in order of preference)\n" +
                    "- **Convert to JPQL/HQL** so Hibernate validates the projection at parse time:\n" +
                    "  ```java\n" +
                    "  @Query(\"SELECT u FROM User u WHERE u.email = :email\")  // no nativeQuery=true\n" +
                    "  ```\n" +
                    "- **Declare `resultClass`** on the native query:\n" +
                    "  ```java\n" +
                    "  @Query(value = \"SELECT * FROM users WHERE ...\", nativeQuery = true)\n" +
                    "  // Pair with @SqlResultSetMapping or use Spring Data's interface-based projection\n" +
                    "  ```\n" +
                    "- **Use `@SqlResultSetMapping`** for column→field mapping when DB columns and entity " +
                    "field names don't match.\n\n" +
                    "### When to suppress\n" +
                    "- The query returns a scalar projection (counts, sums) that maps to a DTO, not an entity. " +
                    "Verify the return type is a primitive/DTO and suppress with " +
                    "`// @analyzer-ignore: sql.native-no-typecheck`.",
                    sql, true));
        }

        // 4) Unbounded IN clause — JDBC binding limits (Oracle 1000, others ~32k)
        if (IN_CLAUSE_BIND.matcher(sql).find() && !sql.toLowerCase().contains("limit")) {
            out.add(build(loc, "sql.in-clause-unbounded",
                    Finding.Severity.MEDIUM, Finding.Confidence.MEDIUM,
                    "IN (:ids) without obvious size bound",
                    "Query uses `IN (:param)` with a collection. JDBC drivers cap bind list sizes — large " +
                    "collections cause runtime errors.",
                    "### What this means\n" +
                    "When Spring Data expands `IN (:ids)`, it generates one bind parameter per element in the " +
                    "collection. JDBC drivers and underlying databases impose hard limits on bind parameter " +
                    "counts:\n" +
                    "- **Oracle**: 1000 elements per `IN` (hard ORA-01795)\n" +
                    "- **MS SQL Server**: 2100 total bind parameters per statement\n" +
                    "- **PostgreSQL**: 32,767 (rare to hit, but not impossible)\n" +
                    "- **MySQL**: configurable; effectively ~65k\n\n" +
                    "### Why it matters (production consequences)\n" +
                    "- **Runtime exception, not compile error.** A test with 5 IDs passes; a real customer with " +
                    "1200 IDs throws `ORA-01795` and the user sees a 500.\n" +
                    "- **Edge-case bug hard to reproduce.** Most calls work fine; only the rare giant input fails.\n" +
                    "- **Worse on jobs / bulk operations.** ETL, exports, reconciliation jobs often pass " +
                    "ever-growing collections — works in year 1, fails silently in year 3.\n\n" +
                    "### Common misunderstanding — \"It works on Postgres, that's enough\"\n" +
                    "Today's Postgres-only deployment may become tomorrow's Oracle-targeted enterprise sale. " +
                    "1000 is the lowest common denominator. Bounding the input is the only safe pattern.\n\n" +
                    "### Common misunderstanding — \"Hibernate handles this for me\"\n" +
                    "Hibernate does NOT chunk `IN (:list)` parameters. The collection is expanded as-is into " +
                    "bind parameters. Some versions log a warning; none transparently partition.\n\n" +
                    "### Fix\n" +
                    "- **Bound the input** at the caller (e.g. validate `ids.size() <= 500` and reject above).\n" +
                    "- **Partition + batch** the query for large lists:\n" +
                    "  ```java\n" +
                    "  Lists.partition(ids, 500).forEach(batch -> repo.findAllByIdIn(batch));\n" +
                    "  ```\n" +
                    "- **Temporary table join** for very large sets — insert the IDs into a temp table, then " +
                    "`JOIN` to it.\n" +
                    "- **Spring Data's `findAllById(Iterable)`** already does batching on some Spring versions; " +
                    "prefer it over a hand-written `IN` when applicable.\n\n" +
                    "### When to suppress\n" +
                    "- The caller is provably bounded (an enum, a constant list, a small DTO field). Suppress " +
                    "with `// @analyzer-ignore: sql.in-clause-unbounded`.",
                    sql, q.native_));
        }

        // 5) Implicit cross-join — selecting from multiple entity roots without an explicit join
        if (looksLikeCartesian(sql)) {
            out.add(build(loc, "sql.cartesian-join-suspect",
                    Finding.Severity.HIGH, Finding.Confidence.MEDIUM,
                    "Possible cartesian product (multiple FROM roots, no JOIN)",
                    "Query lists more than one root in FROM and doesn't appear to JOIN them — likely a cartesian " +
                    "product.",
                    "### What this means\n" +
                    "Comma-separated FROM roots (`FROM A a, B b`) without a `WHERE`/`JOIN` constraint between " +
                    "them produce the cross product of the tables — every row of A paired with every row of B. " +
                    "Two tables of 1000 rows → 1,000,000 result rows.\n\n" +
                    "### Why it matters (production consequences)\n" +
                    "- **Memory explosion.** The cross-product materializes in the DB result set before any " +
                    "filtering. The DB process can OOM-kill itself; your JVM definitely will when it tries to " +
                    "materialize the rows.\n" +
                    "- **Hidden behind WHERE clauses.** A `WHERE a.id = b.aId` filter at the end of the query " +
                    "is still a cartesian join intermediate — the optimizer may rescue it (push down predicates) " +
                    "but you can't rely on that across DBs and versions.\n" +
                    "- **Plan stability is fragile.** What runs in 100ms today on Postgres may explode tomorrow " +
                    "on the same query under MySQL or after a major version upgrade changes the planner.\n\n" +
                    "### Common misunderstanding — \"The WHERE clause makes it efficient\"\n" +
                    "Even with `WHERE a.id = b.aId`, you're relying on the optimizer to recognize the join " +
                    "condition and skip materializing the cross product. Modern optimizers usually do — but " +
                    "explicit `JOIN ... ON` is unambiguous to humans AND optimizers, and it's the SQL idiom for " +
                    "this operation. Implicit cross-joins are a 1990s ANSI-SQL holdover.\n\n" +
                    "### Common misunderstanding — \"JPQL `FROM A a, B b` is fine\"\n" +
                    "JPQL inherits SQL semantics. The same cartesian-product behaviour applies. Hibernate doesn't " +
                    "automatically infer the join condition from your entity associations.\n\n" +
                    "### Fix\n" +
                    "Use explicit `JOIN ... ON`:\n" +
                    "```sql\n" +
                    "-- Native SQL\n" +
                    "SELECT a.*, b.*\n" +
                    "FROM A a\n" +
                    "INNER JOIN B b ON b.a_id = a.id\n" +
                    "WHERE ...\n" +
                    "\n" +
                    "-- JPQL (preferred — use entity associations)\n" +
                    "SELECT a FROM A a JOIN a.bs b WHERE ...\n" +
                    "```\n\n" +
                    "### When to suppress\n" +
                    "- The query genuinely needs a cartesian product (rare — sometimes for generating combinations " +
                    "in reporting). Suppress with `// @analyzer-ignore: sql.cartesian-join-suspect`.",
                    sql, q.native_));
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static class QueryInfo {
        String sql;
        boolean native_;
        String resultClass;
        String resultSetMapping;
    }

    private QueryInfo readQuery(AnnotationExpr ann) {
        QueryInfo q = new QueryInfo();
        if (ann instanceof SingleMemberAnnotationExpr) {
            // @Query("...") — single string value
            if (((SingleMemberAnnotationExpr) ann).getMemberValue() instanceof StringLiteralExpr) {
                q.sql = ((StringLiteralExpr) ((SingleMemberAnnotationExpr) ann).getMemberValue()).getValue();
            } else {
                q.sql = ((SingleMemberAnnotationExpr) ann).getMemberValue().toString();
            }
        } else if (ann instanceof NormalAnnotationExpr) {
            for (MemberValuePair p : ((NormalAnnotationExpr) ann).getPairs()) {
                String name = p.getNameAsString();
                if (("value".equals(name) || "query".equals(name))
                        && p.getValue() instanceof StringLiteralExpr) {
                    q.sql = ((StringLiteralExpr) p.getValue()).getValue();
                } else if ("nativeQuery".equals(name)) {
                    q.native_ = "true".equalsIgnoreCase(p.getValue().toString());
                } else if ("resultClass".equals(name)) {
                    q.resultClass = p.getValue().toString();
                } else if ("resultSetMapping".equals(name)) {
                    q.resultSetMapping = p.getValue().toString();
                }
            }
        }
        return q;
    }

    private boolean hasPageableParameter(MethodDeclaration m) {
        for (Parameter p : m.getParameters()) {
            String typeName = p.getType().asString();
            if (typeName.equals("Pageable") || typeName.endsWith(".Pageable")) return true;
        }
        return false;
    }

    /** Naive heuristic: more than one root in {@code FROM A a, B b} and no JOIN keyword between roots. */
    private boolean looksLikeCartesian(String sql) {
        int fromIdx = indexOfIgnoreCase(sql, "FROM ");
        if (fromIdx < 0) return false;
        int whereIdx = indexOfIgnoreCase(sql, " WHERE ");
        int endIdx = whereIdx > 0 ? whereIdx : (sql.length() - 1);
        String fromClause = sql.substring(fromIdx + 5, endIdx);
        // commas between roots, and no JOIN keyword
        return fromClause.contains(",") && indexOfIgnoreCase(fromClause, "JOIN") < 0;
    }

    private int indexOfIgnoreCase(String haystack, String needle) {
        return haystack == null ? -1 : haystack.toLowerCase().indexOf(needle.toLowerCase());
    }

    private Finding build(SourceLocation loc, String ruleId, Finding.Severity sev, Finding.Confidence conf,
                          String title, String description, String suggestion,
                          String sqlSnippet, boolean isNative) {
        return new Finding(
                Finding.Category.SQL_PERFORMANCE, sev, conf, ruleId,
                title, description, loc, suggestion,
                Arrays.asList(
                        "Query type: " + (isNative ? "native SQL" : "JPQL/HQL"),
                        "Query (truncated): " + truncate(sqlSnippet, 200)
                )
        );
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    @Override
    public List<Finding> analyze(dev.codeanalyzer.core.analyzer.AnalysisContext context) {
        return analyze(context.getSources());
    }
}
