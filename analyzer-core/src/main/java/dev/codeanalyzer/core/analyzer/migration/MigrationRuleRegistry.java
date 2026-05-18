package dev.codeanalyzer.core.analyzer.migration;

import dev.codeanalyzer.core.model.Finding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Static registry of all migration rules across the supported migration paths:
 * <ul>
 *   <li><b>Java 8 → 17/21</b> — modules removed in 9+, deprecated APIs, JEP-driven removals.</li>
 *   <li><b>Spring Boot 2.x → 3.x</b> — Jakarta EE namespace migration, removed extension points,
 *       config-property renames.</li>
 *   <li><b>Hibernate 5 → 6</b> — Criteria API, Query API, identifier generator changes,
 *       package moves.</li>
 * </ul>
 *
 * <p>Rules are pre-grouped by {@link MigrationRuleType} for O(1) lookup in the analyzer.
 * Each rule carries an {@code estimatedMinutes} field; the score calculator multiplies that
 * by the number of matched occurrences to produce a person-day projection.
 */
public final class MigrationRuleRegistry {

    public static final String AREA_JAVA = "Java";
    /** Covers both Spring Boot 2→3 and raw Spring Framework 5→6 — same Jakarta namespace migration. */
    public static final String AREA_SPRING_BOOT = "Spring";
    public static final String AREA_HIBERNATE = "Hibernate";

    private static final Map<MigrationRuleType, List<MigrationRule>> RULES_BY_TYPE = build();

    private MigrationRuleRegistry() {}

    public static List<MigrationRule> getRulesByType(MigrationRuleType type) {
        return RULES_BY_TYPE.getOrDefault(type, Collections.emptyList());
    }

    public static List<MigrationRule> getAllRules() {
        List<MigrationRule> all = new ArrayList<>();
        for (List<MigrationRule> bucket : RULES_BY_TYPE.values()) all.addAll(bucket);
        return all;
    }

    // ─── Rule construction ────────────────────────────────────────────────────

    private static Map<MigrationRuleType, List<MigrationRule>> build() {
        Map<MigrationRuleType, List<MigrationRule>> m = new EnumMap<>(MigrationRuleType.class);
        for (MigrationRuleType t : MigrationRuleType.values()) m.put(t, new ArrayList<>());

        addJavaRules(m);
        addSpringBootRules(m);
        addHibernateRules(m);

        // Freeze
        for (MigrationRuleType t : MigrationRuleType.values()) {
            m.put(t, Collections.unmodifiableList(m.get(t)));
        }
        return Collections.unmodifiableMap(m);
    }

    // ─── Java 8 → 17/21 ───────────────────────────────────────────────────────

    private static void addJavaRules(Map<MigrationRuleType, List<MigrationRule>> m) {
        // JEP 320 — Java EE / CORBA modules removed in JDK 11
        addImport(m, "java.jee.jaxb", AREA_JAVA, "8", "17",
                "javax.xml.bind.",
                "Use jakarta.xml.bind.* (Jakarta JAXB 4.x) or add the jaxb-api + jaxb-runtime dependencies explicitly",
                Finding.Severity.CRITICAL,
                "javax.xml.bind.* removed (JEP 320)",
                javaImportSuggestion(
                        "javax.xml.bind.JAXBContext", "jakarta.xml.bind.JAXBContext",
                        "Add jakarta.xml.bind:jakarta.xml.bind-api and a runtime like org.glassfish.jaxb:jaxb-runtime"),
                30);

        addImport(m, "java.jee.activation", AREA_JAVA, "8", "17",
                "javax.activation.",
                "Use jakarta.activation.* and add jakarta.activation:jakarta.activation-api",
                Finding.Severity.CRITICAL,
                "javax.activation.* removed (JEP 320)",
                javaImportSuggestion(
                        "javax.activation.DataHandler", "jakarta.activation.DataHandler",
                        "Add jakarta.activation:jakarta.activation-api"),
                15);

        addImport(m, "java.jee.annotation", AREA_JAVA, "8", "17",
                "javax.annotation.",
                "Use jakarta.annotation.* (Jakarta Annotations 2.x) and add jakarta.annotation:jakarta.annotation-api",
                Finding.Severity.HIGH,
                "javax.annotation.* removed (JEP 320)",
                javaImportSuggestion(
                        "javax.annotation.PostConstruct", "jakarta.annotation.PostConstruct",
                        "Add jakarta.annotation:jakarta.annotation-api"),
                10);

        addImport(m, "java.jee.transaction", AREA_JAVA, "8", "17",
                "javax.transaction.",
                "Use jakarta.transaction.* and add jakarta.transaction:jakarta.transaction-api",
                Finding.Severity.HIGH,
                "javax.transaction.* removed (JEP 320)",
                "Replace `javax.transaction.*` with `jakarta.transaction.*`. Note: package was split " +
                        "from Java EE — add the Jakarta API jar to your dependencies.",
                10);

        addImport(m, "java.corba", AREA_JAVA, "8", "17",
                "org.omg.CORBA",
                "CORBA was removed in JDK 11 (JEP 320). Migrate to a different RPC mechanism (gRPC, REST, JMS).",
                Finding.Severity.CRITICAL,
                "CORBA removed (JEP 320)",
                "CORBA (`org.omg.CORBA`, `javax.rmi.CORBA`) is fully removed from the JDK. " +
                        "There is no drop-in replacement — choose a modern RPC: gRPC, REST/HTTP, or JMS.",
                480);

        addImport(m, "java.security.acl", AREA_JAVA, "8", "17",
                "java.security.acl.",
                "java.security.acl was removed in JDK 14. Use java.security.Permission / PermissionCollection.",
                Finding.Severity.HIGH,
                "java.security.acl removed (JEP 411)",
                "Replace `java.security.acl.*` (Acl, AclEntry, Owner, Permission, Group) with " +
                        "`java.security.Permission` and `java.security.PermissionCollection`.",
                60);

        addImport(m, "java.nashorn", AREA_JAVA, "8", "17",
                "jdk.nashorn.",
                "Nashorn was removed in JDK 15. Use GraalJS or another scripting engine.",
                Finding.Severity.HIGH,
                "Nashorn JS engine removed (JEP 372)",
                "Nashorn is gone. Migrate to **GraalJS** (`org.graalvm.js:js`) or switch to a different " +
                        "scripting approach. The JSR-223 API surface (`ScriptEngine`) is similar.",
                240);

        addImport(m, "java.rmi.activation", AREA_JAVA, "8", "17",
                "java.rmi.activation.",
                "RMI Activation system was removed in JDK 17 (JEP 407).",
                Finding.Severity.CRITICAL,
                "RMI Activation removed (JEP 407)",
                "The RMI Activation system (`java.rmi.activation.*`) was removed. Use plain RMI exporters " +
                        "or migrate to a different distributed-call mechanism.",
                240);

        addImport(m, "java.applet", AREA_JAVA, "8", "17",
                "java.applet.",
                "Applet API was deprecated in JDK 9 and removed in 17. Migrate to a desktop or web alternative.",
                Finding.Severity.CRITICAL,
                "java.applet.* deprecated for removal",
                "The Applet API is dead. Rewrite as a desktop app (JavaFX/Swing) or a web frontend.",
                480);

        addImport(m, "java.sun.misc", AREA_JAVA, "8", "17",
                "sun.misc.",
                "sun.misc.* is fully encapsulated. Use the public alternatives: VarHandle, MethodHandles, etc.",
                Finding.Severity.HIGH,
                "sun.misc.* internal API not accessible",
                "`sun.misc.Unsafe`, `sun.misc.BASE64Encoder`, etc. are no longer accessible. " +
                        "Replacements:\n" +
                        "- `Unsafe` → `VarHandle` (java.lang.invoke) or `MemorySegment` (Foreign Function API)\n" +
                        "- `BASE64Encoder/Decoder` → `java.util.Base64`\n" +
                        "- `URLClassPath` / `Launcher` → use the standard `ClassLoader` API",
                120);

        addImport(m, "java.javafx", AREA_JAVA, "8", "17",
                "javafx.",
                "JavaFX is no longer bundled with the JDK from version 11+. Add openjfx dependencies explicitly.",
                Finding.Severity.MEDIUM,
                "JavaFX no longer bundled in JDK",
                "JavaFX was unbundled from the JDK starting with version 11. Add OpenJFX as an " +
                        "explicit dependency (e.g. `org.openjfx:javafx-controls:21`). Module-path setup " +
                        "is required.",
                60);

        addClassReference(m, "java.unsafe", AREA_JAVA, "8", "17",
                "Unsafe",
                "Use VarHandle, MethodHandles, or the Foreign Memory API.",
                Finding.Severity.HIGH,
                "sun.misc.Unsafe usage",
                "`Unsafe` is encapsulated. Replace with `VarHandle` (java.lang.invoke) for atomic field " +
                        "access, or the Foreign Memory API for off-heap.",
                60);
    }

    // ─── Spring Boot 2.x → 3.x ────────────────────────────────────────────────

    private static void addSpringBootRules(Map<MigrationRuleType, List<MigrationRule>> m) {
        // Jakarta EE 9+ namespace migration — the headline change in Spring Boot 3.
        addImport(m, "spring.javax.persistence", AREA_SPRING_BOOT, "2", "3",
                "javax.persistence.",
                "Replace with jakarta.persistence.* (Jakarta Persistence 3.x).",
                Finding.Severity.CRITICAL,
                "javax.persistence → jakarta.persistence",
                "Spring Boot 3 uses Jakarta EE 9+. Mechanical replacement:\n" +
                        "```diff\n" +
                        "- import javax.persistence.Entity;\n" +
                        "+ import jakarta.persistence.Entity;\n" +
                        "```\n" +
                        "Use OpenRewrite recipe `org.openrewrite.java.migrate.jakarta.JavaxPersistenceToJakartaPersistence` for bulk migration.",
                5);

        addImport(m, "spring.javax.servlet", AREA_SPRING_BOOT, "2", "3",
                "javax.servlet.",
                "Replace with jakarta.servlet.* (Jakarta Servlet 5.0+).",
                Finding.Severity.CRITICAL,
                "javax.servlet → jakarta.servlet",
                "Spring Boot 3 requires Servlet API 6 (Jakarta namespace). Replace `javax.servlet.*` " +
                        "with `jakarta.servlet.*` everywhere — filters, listeners, HttpServletRequest, etc.",
                5);

        addImport(m, "spring.javax.validation", AREA_SPRING_BOOT, "2", "3",
                "javax.validation.",
                "Replace with jakarta.validation.* (Jakarta Bean Validation 3.x).",
                Finding.Severity.CRITICAL,
                "javax.validation → jakarta.validation",
                "Spring Boot 3 uses Jakarta Bean Validation 3.0. Replace `javax.validation.*` " +
                        "(@NotNull, @Size, @Valid, etc.) with `jakarta.validation.*`.",
                5);

        addImport(m, "spring.javax.ws", AREA_SPRING_BOOT, "2", "3",
                "javax.ws.rs.",
                "Replace with jakarta.ws.rs.* (Jakarta RESTful Web Services 3.x).",
                Finding.Severity.CRITICAL,
                "javax.ws.rs → jakarta.ws.rs",
                "JAX-RS moved to `jakarta.ws.rs.*`. Used in REST clients/servers.",
                10);

        addImport(m, "spring.javax.mail", AREA_SPRING_BOOT, "2", "3",
                "javax.mail.",
                "Replace with jakarta.mail.* (Jakarta Mail 2.x).",
                Finding.Severity.HIGH,
                "javax.mail → jakarta.mail",
                "JavaMail moved to `jakarta.mail.*` in Spring Boot 3.",
                10);

        // Removed extension points in Spring Boot 3 / Spring Security 6
        addClassReference(m, "spring.websecurityconfigureradapter", AREA_SPRING_BOOT, "2", "3",
                "WebSecurityConfigurerAdapter",
                "Replace with component-based security: declare a SecurityFilterChain @Bean.",
                Finding.Severity.HIGH,
                "WebSecurityConfigurerAdapter removed in Spring Security 6",
                "`WebSecurityConfigurerAdapter` is gone. Replace:\n" +
                        "```java\n" +
                        "@Bean\n" +
                        "SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {\n" +
                        "    http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())\n" +
                        "        .httpBasic(Customizer.withDefaults());\n" +
                        "    return http.build();\n" +
                        "}\n" +
                        "```",
                60);

        addClassReference(m, "spring.antpathrequestmatcher", AREA_SPRING_BOOT, "2", "3",
                "AntPathRequestMatcher",
                "Spring Security 6 prefers MvcRequestMatcher or PathPatternRequestMatcher; AntPathRequestMatcher still works but is discouraged.",
                Finding.Severity.LOW,
                "AntPathRequestMatcher (legacy)",
                "Consider `MvcRequestMatcher.Builder` (mvc) or `PathPatternRequestMatcher` (servlet) " +
                        "for new code. Existing AntPathRequestMatcher usage still works in 6.x.",
                15);

        addClassReference(m, "spring.requestmappinghandlermapping.removed", AREA_SPRING_BOOT, "2", "3",
                "PathMatcher",
                "Spring 6 uses PathPattern by default instead of PathMatcher for request mapping.",
                Finding.Severity.MEDIUM,
                "PathMatcher → PathPattern (Spring 6)",
                "Spring Framework 6 switched URL matching to `PathPattern` (faster, more predictable). " +
                        "If you rely on AntPathMatcher behaviour (e.g. `**` matching `/`), test carefully.",
                30);

        // application.properties changes (Spring Boot 3 renamed many keys)
        addConfigProperty(m, "spring.config.server.servlet.path", AREA_SPRING_BOOT, "2", "3",
                "server.servlet.path",
                "server.servlet.context-path",
                Finding.Severity.LOW,
                "server.servlet.path renamed",
                "The property `server.servlet.path` was removed. Use `server.servlet.context-path` instead.",
                5);

        addConfigProperty(m, "spring.config.management.metrics.export", AREA_SPRING_BOOT, "2", "3",
                "management.metrics.export.",
                "management.<system>.metrics.export.",
                Finding.Severity.MEDIUM,
                "management.metrics.export.* properties restructured",
                "Many `management.metrics.export.*` properties moved to system-specific namespaces " +
                        "(e.g. `management.prometheus.metrics.export.*`). Run with `--debug` against " +
                        "Spring Boot 3 to see deprecation warnings, then move the keys.",
                30);

        addConfigProperty(m, "spring.config.spring.security.user", AREA_SPRING_BOOT, "2", "3",
                "spring.security.user.",
                "spring.security.user.",
                Finding.Severity.LOW,
                "spring.security.user.* — verify defaults",
                "The `spring.security.user.*` properties are still supported but the default " +
                        "auto-configured user behaviour changed. Audit any default-user wiring in tests.",
                10);

        // Removed / changed annotations
        addImport(m, "spring.org.springframework.boot.web.servlet.error", AREA_SPRING_BOOT, "2", "3",
                "org.springframework.boot.autoconfigure.web.ErrorProperties",
                "ErrorProperties moved package in Spring Boot 3.",
                Finding.Severity.LOW,
                "ErrorProperties package change",
                "`ErrorProperties` moved from `web.ErrorProperties` to `web.servlet.error.ErrorProperties` (servlet) " +
                        "or `web.reactive.error.ErrorProperties` (reactive). Update the import.",
                5);

        addImport(m, "spring.org.springframework.boot.cloudfoundry", AREA_SPRING_BOOT, "2", "3",
                "org.springframework.cloud.config.client.ConfigClientProperties",
                "Spring Cloud Config Client behaves differently in Spring Cloud 2022.x (Spring Boot 3-compatible).",
                Finding.Severity.MEDIUM,
                "Spring Cloud 2021.x not compatible with Spring Boot 3",
                "Upgrade Spring Cloud to **2022.x** or later. The 2021.x train only supports Spring Boot 2.6/2.7.",
                60);

        // spring.factories deprecated, use new file format
        addConfigProperty(m, "spring.config.spring.factories", AREA_SPRING_BOOT, "2", "3",
                "spring.factories",
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                Finding.Severity.HIGH,
                "spring.factories EnableAutoConfiguration deprecated",
                "The `META-INF/spring.factories` entry for `EnableAutoConfiguration` is removed in 3.0. " +
                        "Move auto-configurations to `META-INF/spring/org.springframework.boot.autoconfigure" +
                        ".AutoConfiguration.imports` (one FQN per line).",
                30);
    }

    // ─── Hibernate 5 → 6 ──────────────────────────────────────────────────────

    private static void addHibernateRules(Map<MigrationRuleType, List<MigrationRule>> m) {
        // Criteria API removed
        addImport(m, "hibernate.criterion", AREA_HIBERNATE, "5", "6",
                "org.hibernate.criterion.",
                "The legacy Criteria API is gone. Use JPA Criteria (jakarta.persistence.criteria) or HQL.",
                Finding.Severity.CRITICAL,
                "org.hibernate.criterion.* removed",
                "The classic Hibernate Criteria API (`Criteria`, `Criterion`, `Restrictions`, `Order`, " +
                        "`Projections`) was removed in Hibernate 6. Migrate to JPA Criteria:\n" +
                        "```java\n" +
                        "CriteriaBuilder cb = em.getCriteriaBuilder();\n" +
                        "CriteriaQuery<Foo> q = cb.createQuery(Foo.class);\n" +
                        "Root<Foo> root = q.from(Foo.class);\n" +
                        "q.select(root).where(cb.equal(root.get(\"active\"), true));\n" +
                        "```\n" +
                        "Or rewrite as HQL/JPQL — usually shorter.",
                240);

        addImport(m, "hibernate.query.deprecated", AREA_HIBERNATE, "5", "6",
                "org.hibernate.Query",
                "org.hibernate.Query is deprecated; use org.hibernate.query.Query or jakarta.persistence.TypedQuery.",
                Finding.Severity.MEDIUM,
                "org.hibernate.Query deprecated",
                "`org.hibernate.Query` is replaced by `org.hibernate.query.Query<T>` or the standard " +
                        "`jakarta.persistence.TypedQuery<T>`. Add the generic type parameter for type safety.",
                20);

        addImport(m, "hibernate.javax.persistence", AREA_HIBERNATE, "5", "6",
                "javax.persistence.",
                "Hibernate 6 is Jakarta-native. javax.persistence imports must move to jakarta.persistence.",
                Finding.Severity.CRITICAL,
                "javax.persistence → jakarta.persistence (Hibernate 6)",
                "Hibernate 6 uses Jakarta Persistence 3.x exclusively. Same migration as Spring Boot 3.",
                5);

        // @Type changes
        addAnnotation(m, "hibernate.type.legacy", AREA_HIBERNATE, "5", "6",
                "Type",
                "The string-based @Type(type=\"...\") form is removed. Use the new class-based @Type(MyUserType.class).",
                Finding.Severity.HIGH,
                "@Type(type=\"...\") form removed",
                "Hibernate 6 removed the string-based `@Type(type = \"json\")` form. Use the class-based " +
                        "form:\n" +
                        "```java\n" +
                        "// Before (Hibernate 5)\n" +
                        "@Type(type = \"com.vladmihalcea.hibernate.type.json.JsonType\")\n" +
                        "private Map<String, Object> attrs;\n" +
                        "\n" +
                        "// After (Hibernate 6)\n" +
                        "@Type(JsonType.class)\n" +
                        "private Map<String, Object> attrs;\n" +
                        "```",
                15);

        addAnnotation(m, "hibernate.genericgenerator", AREA_HIBERNATE, "5", "6",
                "GenericGenerator",
                "Many generators were renamed/removed in Hibernate 6. Use built-in @SequenceGenerator/@TableGenerator or @IdGeneratorType.",
                Finding.Severity.HIGH,
                "@GenericGenerator generators changed",
                "Hibernate 6 deprecated `@GenericGenerator` for most usages and renamed several built-in " +
                        "generators. Prefer JPA's `@SequenceGenerator` / `@TableGenerator`, or use the new " +
                        "`@IdGeneratorType` for custom generators.",
                30);

        // Removed identifier generator config
        addConfigProperty(m, "hibernate.id.new_generator_mappings", AREA_HIBERNATE, "5", "6",
                "hibernate.id.new_generator_mappings",
                "(removed — default is always true in Hibernate 6)",
                Finding.Severity.MEDIUM,
                "hibernate.id.new_generator_mappings removed",
                "The setting `hibernate.id.new_generator_mappings` was removed (default is always true now). " +
                        "If you set it to `false` to keep legacy IDs, you must change identifier strategies — " +
                        "test database schema implications carefully.",
                30);

        addConfigProperty(m, "hibernate.allow_update_outside_transaction", AREA_HIBERNATE, "5", "6",
                "hibernate.allow_update_outside_transaction",
                "(removed)",
                Finding.Severity.LOW,
                "hibernate.allow_update_outside_transaction removed",
                "All mutations now require an active transaction in Hibernate 6. Audit code that flushes " +
                        "without an explicit transaction.",
                15);

        // Package renames
        addImport(m, "hibernate.dialect.legacy", AREA_HIBERNATE, "5", "6",
                "org.hibernate.dialect.PostgreSQL9Dialect",
                "Use PostgreSQLDialect (versionless) in Hibernate 6.",
                Finding.Severity.MEDIUM,
                "Versioned dialect classes deprecated",
                "Hibernate 6 prefers versionless dialect classes (`PostgreSQLDialect`, `MySQLDialect`, " +
                        "`OracleDialect`) that auto-detect the database version. Versioned classes " +
                        "(`PostgreSQL9Dialect`, etc.) are deprecated.",
                10);

        addImport(m, "hibernate.envers.legacy", AREA_HIBERNATE, "5", "6",
                "org.hibernate.envers.tools.",
                "Envers internal tooling packages moved.",
                Finding.Severity.LOW,
                "Envers package restructure",
                "If you depend on `org.hibernate.envers.tools.*` directly (rare), check the new package " +
                        "layout in Hibernate 6 docs.",
                30);
    }

    // ─── Helper constructors ──────────────────────────────────────────────────

    private static void addImport(Map<MigrationRuleType, List<MigrationRule>> m, String id,
                                   String area, String src, String tgt, String pattern,
                                   String replacement, Finding.Severity sev,
                                   String title, String suggestion, int minutes) {
        m.get(MigrationRuleType.IMPORT_PATTERN).add(
                new MigrationRule(id, area, src, tgt, MigrationRuleType.IMPORT_PATTERN,
                        pattern, replacement, sev, title, suggestion, minutes));
    }

    private static void addClassReference(Map<MigrationRuleType, List<MigrationRule>> m, String id,
                                           String area, String src, String tgt, String pattern,
                                           String replacement, Finding.Severity sev,
                                           String title, String suggestion, int minutes) {
        m.get(MigrationRuleType.CLASS_REFERENCE).add(
                new MigrationRule(id, area, src, tgt, MigrationRuleType.CLASS_REFERENCE,
                        pattern, replacement, sev, title, suggestion, minutes));
    }

    private static void addAnnotation(Map<MigrationRuleType, List<MigrationRule>> m, String id,
                                       String area, String src, String tgt, String pattern,
                                       String replacement, Finding.Severity sev,
                                       String title, String suggestion, int minutes) {
        m.get(MigrationRuleType.ANNOTATION).add(
                new MigrationRule(id, area, src, tgt, MigrationRuleType.ANNOTATION,
                        pattern, replacement, sev, title, suggestion, minutes));
    }

    private static void addConfigProperty(Map<MigrationRuleType, List<MigrationRule>> m, String id,
                                           String area, String src, String tgt, String pattern,
                                           String replacement, Finding.Severity sev,
                                           String title, String suggestion, int minutes) {
        m.get(MigrationRuleType.CONFIG_PROPERTY).add(
                new MigrationRule(id, area, src, tgt, MigrationRuleType.CONFIG_PROPERTY,
                        pattern, replacement, sev, title, suggestion, minutes));
    }

    private static String javaImportSuggestion(String fromImport, String toImport, String depHint) {
        return "Replace the import:\n" +
                "```java\n" +
                "// Before\n" +
                "import " + fromImport + ";\n" +
                "// After\n" +
                "import " + toImport + ";\n" +
                "```\n" +
                "**Dependency change**: " + depHint;
    }
}
