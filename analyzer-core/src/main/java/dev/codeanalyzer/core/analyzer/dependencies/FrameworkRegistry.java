package dev.codeanalyzer.core.analyzer.dependencies;

import dev.codeanalyzer.core.model.ProjectMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Curated catalogue of well-known Java frameworks and libraries we want to
 * surface in the report's Dependencies page.
 *
 * <p>The original Technologies section relied only on the (often nearly-empty)
 * root-pom {@code <dependencies>} map and a hardcoded substring blacklist —
 * which missed almost every framework on real multi-module projects because
 * versions are typically declared once in {@code <properties>} and consumed
 * downstream by submodule POMs.
 *
 * <p>This registry detects each framework via <em>either</em>:
 * <ul>
 *   <li>a known {@code <property>} key (e.g. {@code spring.version}, {@code hibernate.version}),
 *       OR</li>
 *   <li>a known {@code groupId:artifactId} coordinate.</li>
 * </ul>
 *
 * <p>Each detected framework is returned with its resolved version and a
 * provenance label so the report can show <em>where</em> the version came from
 * (pom property vs. declared dependency vs. inherited from parent).
 */
public final class FrameworkRegistry {

    /** Category buckets used in the report grouping. Order here drives display order. */
    public enum Category {
        FRAMEWORK("Frameworks"),
        WEB_UI("Web / UI"),
        PERSISTENCE("Persistence"),
        MESSAGING("Messaging"),
        LIBRARY("Libraries"),
        LOGGING("Logging"),
        JSON("Serialization"),
        TESTING("Testing"),
        DATABASE("Database / Drivers"),
        BUILD("Build / Plugins");

        private final String label;
        Category(String label) { this.label = label; }
        public String label() { return label; }
    }

    private static final List<Spec> SPECS = buildSpecs();

    private FrameworkRegistry() {}

    /**
     * Detects all known frameworks/libraries present in the given metadata.
     * Returns them in registry order, grouped per category for display.
     */
    public static List<Detection> detect(ProjectMetadata meta) {
        if (meta == null) return Collections.emptyList();
        List<Detection> out = new ArrayList<>();
        for (Spec spec : SPECS) {
            Detection d = spec.match(meta);
            if (d != null) out.add(d);
        }
        return out;
    }

    // ─── Spec model ──────────────────────────────────────────────────────────

    /** A detection result with provenance info. */
    public static final class Detection {
        public final String name;
        public final Category category;
        public final String version;        // resolved version string, possibly "unknown"
        public final String source;         // human-readable provenance label

        Detection(String name, Category category, String version, String source) {
            this.name = name;
            this.category = category;
            this.version = version;
            this.source = source;
        }
    }

    /** A framework spec — name, category, and the property/coord patterns to look for. */
    private static final class Spec {
        final String name;
        final Category category;
        final List<String> propertyKeys;       // exact keys to check in <properties>
        final List<String> coordPrefixes;      // groupId:artifactId prefixes (matches start)
        final List<String> groupIdPrefixes;    // groupId prefix-only (any artifact)

        Spec(String name, Category category, List<String> propertyKeys,
             List<String> coordPrefixes, List<String> groupIdPrefixes) {
            this.name = name;
            this.category = category;
            this.propertyKeys = propertyKeys;
            this.coordPrefixes = coordPrefixes;
            this.groupIdPrefixes = groupIdPrefixes;
        }

        /** Tries to match in the metadata; returns null if no match. */
        Detection match(ProjectMetadata meta) {
            // 1) Try property keys first — they almost always carry the resolved version.
            if (meta.getProperties() != null) {
                for (String key : propertyKeys) {
                    String v = meta.getProperties().get(key);
                    if (v != null && !v.isEmpty() && !v.contains("${")) {
                        return new Detection(name, category, v, "property: " + key);
                    }
                }
            }
            // 2) Try dependency coords — these may or may not have a resolved version.
            if (meta.getDependencies() != null) {
                for (Map.Entry<String, String> e : meta.getDependencies().entrySet()) {
                    String coord = e.getKey();
                    String version = e.getValue();
                    if (matchesCoord(coord)) {
                        String v = (version == null || version.isEmpty() || version.contains("${"))
                                ? "(version unresolved)" : version;
                        return new Detection(name, category, v, "dependency: " + coord);
                    }
                }
            }
            return null;
        }

        private boolean matchesCoord(String coord) {
            if (coord == null) return false;
            for (String p : coordPrefixes) {
                if (coord.equals(p) || coord.startsWith(p + ":") || coord.startsWith(p)) return true;
            }
            int colon = coord.indexOf(':');
            String group = colon > 0 ? coord.substring(0, colon) : coord;
            for (String gp : groupIdPrefixes) {
                if (group.equals(gp) || group.startsWith(gp + ".")) return true;
            }
            return false;
        }
    }

    // ─── Curated catalogue ───────────────────────────────────────────────────

    private static List<Spec> buildSpecs() {
        List<Spec> s = new ArrayList<>();

        // ── Spring ecosystem ────────────────────────────────────────────────
        s.add(new Spec("Spring Boot", Category.FRAMEWORK,
                Arrays.asList("spring-boot.version", "spring.boot.version", "springBoot.version"),
                Arrays.asList("org.springframework.boot:spring-boot",
                              "org.springframework.boot:spring-boot-starter"),
                Collections.singletonList("org.springframework.boot")));
        s.add(new Spec("Spring Framework", Category.FRAMEWORK,
                Arrays.asList("spring.version", "spring-framework.version",
                              "spring-core.version", "spring-context.version"),
                Arrays.asList("org.springframework:spring-core",
                              "org.springframework:spring-context",
                              "org.springframework:spring-web",
                              "org.springframework:spring-webmvc"),
                Collections.<String>emptyList()));
        s.add(new Spec("Spring Security", Category.FRAMEWORK,
                Arrays.asList("spring-security.version", "spring.security.version"),
                Collections.singletonList("org.springframework.security:"),
                Collections.singletonList("org.springframework.security")));
        s.add(new Spec("Spring Cloud", Category.FRAMEWORK,
                Arrays.asList("spring-cloud.version", "spring-cloud-dependencies.version"),
                Collections.singletonList("org.springframework.cloud:"),
                Collections.singletonList("org.springframework.cloud")));
        s.add(new Spec("Spring Batch", Category.FRAMEWORK,
                Arrays.asList("spring-batch.version", "spring.batch.version"),
                Collections.singletonList("org.springframework.batch:"),
                Collections.singletonList("org.springframework.batch")));
        s.add(new Spec("Spring Data", Category.FRAMEWORK,
                Arrays.asList("spring-data.version", "spring-data-jpa.version", "spring-data-commons.version"),
                Collections.singletonList("org.springframework.data:"),
                Collections.singletonList("org.springframework.data")));
        s.add(new Spec("Spring WebFlow", Category.FRAMEWORK,
                Arrays.asList("spring-webflow.version", "spring.webflow.version"),
                Collections.singletonList("org.springframework.webflow:"),
                Collections.singletonList("org.springframework.webflow")));
        s.add(new Spec("Spring Integration", Category.FRAMEWORK,
                Arrays.asList("spring-integration.version"),
                Collections.singletonList("org.springframework.integration:"),
                Collections.singletonList("org.springframework.integration")));

        // ── Persistence ─────────────────────────────────────────────────────
        s.add(new Spec("Hibernate ORM", Category.PERSISTENCE,
                Arrays.asList("hibernate.version", "hibernate-core.version", "hibernate.core.version",
                              "hibernate-orm.version", "hibernate.orm.version"),
                Arrays.asList("org.hibernate:hibernate-core", "org.hibernate.orm:hibernate-core"),
                Arrays.asList("org.hibernate", "org.hibernate.orm")));
        s.add(new Spec("Hibernate Validator", Category.PERSISTENCE,
                Arrays.asList("hibernate-validator.version", "hibernate.validator.version"),
                Collections.singletonList("org.hibernate.validator:"),
                Collections.singletonList("org.hibernate.validator")));
        s.add(new Spec("Hibernate Search", Category.PERSISTENCE,
                Arrays.asList("hibernate-search.version", "hibernate.search.version"),
                Collections.singletonList("org.hibernate.search:"),
                Collections.singletonList("org.hibernate.search")));
        s.add(new Spec("EclipseLink", Category.PERSISTENCE,
                Arrays.asList("eclipselink.version"),
                Collections.singletonList("org.eclipse.persistence:eclipselink"),
                Collections.singletonList("org.eclipse.persistence")));
        s.add(new Spec("MyBatis", Category.PERSISTENCE,
                Arrays.asList("mybatis.version"),
                Collections.singletonList("org.mybatis:mybatis"),
                Collections.singletonList("org.mybatis")));
        s.add(new Spec("Liquibase", Category.PERSISTENCE,
                Arrays.asList("liquibase.version", "maven-liquibase-plugin.version"),
                Collections.singletonList("org.liquibase:liquibase-core"),
                Collections.singletonList("org.liquibase")));
        s.add(new Spec("Flyway", Category.PERSISTENCE,
                Arrays.asList("flyway.version"),
                Collections.singletonList("org.flywaydb:flyway-core"),
                Collections.singletonList("org.flywaydb")));

        // ── Web / UI ────────────────────────────────────────────────────────
        s.add(new Spec("JSF (Jakarta Faces)", Category.WEB_UI,
                Arrays.asList("jsf.version", "mojarra.version", "jakarta.faces.version", "jakarta-faces.version"),
                Arrays.asList("com.sun.faces:jsf-api", "com.sun.faces:jsf-impl",
                              "org.glassfish:jakarta.faces", "jakarta.faces:jakarta.faces-api"),
                Collections.<String>emptyList()));
        s.add(new Spec("PrimeFaces", Category.WEB_UI,
                Arrays.asList("primefaces.version"),
                Collections.singletonList("org.primefaces:primefaces"),
                Collections.singletonList("org.primefaces")));
        s.add(new Spec("RichFaces", Category.WEB_UI,
                Arrays.asList("richfaces.version"),
                Collections.singletonList("org.richfaces:"),
                Collections.singletonList("org.richfaces")));
        s.add(new Spec("Vaadin", Category.WEB_UI,
                Arrays.asList("vaadin.version"),
                Collections.singletonList("com.vaadin:vaadin"),
                Collections.singletonList("com.vaadin")));
        s.add(new Spec("Thymeleaf", Category.WEB_UI,
                Arrays.asList("thymeleaf.version"),
                Collections.singletonList("org.thymeleaf:thymeleaf"),
                Collections.singletonList("org.thymeleaf")));
        s.add(new Spec("FreeMarker", Category.WEB_UI,
                Arrays.asList("freemarker.version"),
                Collections.singletonList("org.freemarker:freemarker"),
                Collections.<String>emptyList()));

        // ── Messaging / Integration ─────────────────────────────────────────
        s.add(new Spec("ActiveMQ", Category.MESSAGING,
                Arrays.asList("activemq.version"),
                Collections.singletonList("org.apache.activemq:"),
                Collections.singletonList("org.apache.activemq")));
        s.add(new Spec("Apache Camel", Category.MESSAGING,
                Arrays.asList("camel.version"),
                Collections.singletonList("org.apache.camel:"),
                Collections.singletonList("org.apache.camel")));
        s.add(new Spec("Apache CXF", Category.MESSAGING,
                Arrays.asList("cxf.version"),
                Collections.singletonList("org.apache.cxf:"),
                Collections.singletonList("org.apache.cxf")));
        s.add(new Spec("RabbitMQ (amqp-client)", Category.MESSAGING,
                Arrays.asList("rabbitmq.version", "amqp-client.version"),
                Collections.singletonList("com.rabbitmq:amqp-client"),
                Collections.<String>emptyList()));
        s.add(new Spec("Kafka Clients", Category.MESSAGING,
                Arrays.asList("kafka.version", "kafka-clients.version"),
                Collections.singletonList("org.apache.kafka:kafka-clients"),
                Collections.<String>emptyList()));

        // ── Runtime / Server ────────────────────────────────────────────────
        s.add(new Spec("Tomcat", Category.FRAMEWORK,
                Arrays.asList("tomcat.version", "tomcat-embed.version"),
                Arrays.asList("org.apache.tomcat:tomcat-catalina", "org.apache.tomcat.embed:tomcat-embed-core"),
                Collections.singletonList("org.apache.tomcat")));
        s.add(new Spec("Jetty", Category.FRAMEWORK,
                Arrays.asList("jetty.version"),
                Collections.singletonList("org.eclipse.jetty:jetty-server"),
                Collections.singletonList("org.eclipse.jetty")));
        s.add(new Spec("Netty", Category.FRAMEWORK,
                Arrays.asList("netty.version"),
                Collections.singletonList("io.netty:netty-all"),
                Collections.singletonList("io.netty")));
        s.add(new Spec("Quarkus", Category.FRAMEWORK,
                Arrays.asList("quarkus.version", "quarkus.platform.version"),
                Collections.singletonList("io.quarkus:quarkus-core"),
                Collections.singletonList("io.quarkus")));
        s.add(new Spec("Micronaut", Category.FRAMEWORK,
                Arrays.asList("micronaut.version"),
                Collections.singletonList("io.micronaut:micronaut-core"),
                Collections.singletonList("io.micronaut")));

        // ── Serialization / JSON ────────────────────────────────────────────
        s.add(new Spec("Jackson", Category.JSON,
                Arrays.asList("jackson.version", "jackson-databind.version", "jackson-bom.version"),
                Arrays.asList("com.fasterxml.jackson.core:jackson-databind",
                              "com.fasterxml.jackson.core:jackson-core"),
                Collections.singletonList("com.fasterxml.jackson")));
        s.add(new Spec("Gson", Category.JSON,
                Arrays.asList("gson.version"),
                Collections.singletonList("com.google.code.gson:gson"),
                Collections.<String>emptyList()));
        s.add(new Spec("JAXB (jakarta.xml.bind)", Category.JSON,
                Arrays.asList("jaxb.version", "jakarta.xml.bind.version"),
                Arrays.asList("jakarta.xml.bind:jakarta.xml.bind-api",
                              "javax.xml.bind:jaxb-api",
                              "org.glassfish.jaxb:jaxb-runtime"),
                Collections.<String>emptyList()));

        // ── Logging ─────────────────────────────────────────────────────────
        s.add(new Spec("Logback", Category.LOGGING,
                Arrays.asList("logback.version"),
                Collections.singletonList("ch.qos.logback:logback-classic"),
                Collections.singletonList("ch.qos.logback")));
        s.add(new Spec("Log4j 2", Category.LOGGING,
                Arrays.asList("log4j2.version", "log4j.version"),
                Arrays.asList("org.apache.logging.log4j:log4j-core",
                              "org.apache.logging.log4j:log4j-api"),
                Collections.singletonList("org.apache.logging.log4j")));
        s.add(new Spec("SLF4J", Category.LOGGING,
                Arrays.asList("slf4j.version"),
                Collections.singletonList("org.slf4j:slf4j-api"),
                Collections.singletonList("org.slf4j")));

        // ── Common libraries ────────────────────────────────────────────────
        s.add(new Spec("Lombok", Category.LIBRARY,
                Arrays.asList("lombok.version"),
                Collections.singletonList("org.projectlombok:lombok"),
                Collections.<String>emptyList()));
        s.add(new Spec("MapStruct", Category.LIBRARY,
                Arrays.asList("mapstruct.version"),
                Collections.singletonList("org.mapstruct:mapstruct"),
                Collections.singletonList("org.mapstruct")));
        s.add(new Spec("Guava", Category.LIBRARY,
                Arrays.asList("guava.version"),
                Collections.singletonList("com.google.guava:guava"),
                Collections.<String>emptyList()));
        s.add(new Spec("Apache Commons Lang", Category.LIBRARY,
                Arrays.asList("commons-lang3.version", "commons.lang3.version"),
                Collections.singletonList("org.apache.commons:commons-lang3"),
                Collections.<String>emptyList()));
        s.add(new Spec("Apache Commons IO", Category.LIBRARY,
                Arrays.asList("commons-io.version"),
                Collections.singletonList("commons-io:commons-io"),
                Collections.<String>emptyList()));
        s.add(new Spec("Apache Commons Collections", Category.LIBRARY,
                Arrays.asList("commons-collections.version", "commons-collections4.version"),
                Arrays.asList("commons-collections:commons-collections",
                              "org.apache.commons:commons-collections4"),
                Collections.<String>emptyList()));
        s.add(new Spec("Apache HttpClient", Category.LIBRARY,
                Arrays.asList("httpclient.version", "httpcore.version", "apache-httpclient.version"),
                Arrays.asList("org.apache.httpcomponents:httpclient",
                              "org.apache.httpcomponents:httpcore",
                              "org.apache.httpcomponents.client5:httpclient5"),
                Collections.<String>emptyList()));
        s.add(new Spec("OkHttp", Category.LIBRARY,
                Arrays.asList("okhttp.version"),
                Collections.singletonList("com.squareup.okhttp3:okhttp"),
                Collections.<String>emptyList()));
        s.add(new Spec("Caffeine", Category.LIBRARY,
                Arrays.asList("caffeine.version"),
                Collections.singletonList("com.github.ben-manes.caffeine:caffeine"),
                Collections.<String>emptyList()));
        s.add(new Spec("Kotlin", Category.LIBRARY,
                Arrays.asList("kotlin.version"),
                Collections.singletonList("org.jetbrains.kotlin:kotlin-stdlib"),
                Collections.singletonList("org.jetbrains.kotlin")));
        s.add(new Spec("Iban4j", Category.LIBRARY,
                Arrays.asList("iban4j.version"),
                Collections.singletonList("org.iban4j:iban4j"),
                Collections.<String>emptyList()));

        // ── Testing ─────────────────────────────────────────────────────────
        s.add(new Spec("JUnit 5 (Jupiter)", Category.TESTING,
                Arrays.asList("junit-jupiter.version", "junit5.version"),
                Collections.singletonList("org.junit.jupiter:junit-jupiter"),
                Collections.singletonList("org.junit.jupiter")));
        s.add(new Spec("JUnit 4", Category.TESTING,
                Arrays.asList("junit.version"),
                Collections.singletonList("junit:junit"),
                Collections.<String>emptyList()));
        s.add(new Spec("TestNG", Category.TESTING,
                Arrays.asList("testng.version"),
                Collections.singletonList("org.testng:testng"),
                Collections.<String>emptyList()));
        s.add(new Spec("Mockito", Category.TESTING,
                Arrays.asList("mockito.version"),
                Arrays.asList("org.mockito:mockito-core", "org.mockito:mockito-all"),
                Collections.singletonList("org.mockito")));
        s.add(new Spec("AssertJ", Category.TESTING,
                Arrays.asList("assertj.version"),
                Collections.singletonList("org.assertj:assertj-core"),
                Collections.<String>emptyList()));
        s.add(new Spec("Hamcrest", Category.TESTING,
                Arrays.asList("hamcrest.version"),
                Collections.singletonList("org.hamcrest:hamcrest"),
                Collections.<String>emptyList()));

        // ── Database drivers ────────────────────────────────────────────────
        s.add(new Spec("Oracle JDBC", Category.DATABASE,
                Arrays.asList("ojdbc.version", "oracle.jdbc.version"),
                Arrays.asList("com.oracle.database.jdbc:ojdbc8", "com.oracle.ojdbc:ojdbc8"),
                Collections.singletonList("com.oracle")));
        s.add(new Spec("PostgreSQL JDBC", Category.DATABASE,
                Arrays.asList("postgresql.version"),
                Collections.singletonList("org.postgresql:postgresql"),
                Collections.<String>emptyList()));
        s.add(new Spec("MySQL JDBC", Category.DATABASE,
                Arrays.asList("mysql.version", "mysql-connector.version"),
                Arrays.asList("mysql:mysql-connector-java", "com.mysql:mysql-connector-j"),
                Collections.<String>emptyList()));
        s.add(new Spec("H2", Category.DATABASE,
                Arrays.asList("h2.version"),
                Collections.singletonList("com.h2database:h2"),
                Collections.<String>emptyList()));
        s.add(new Spec("HikariCP", Category.DATABASE,
                Arrays.asList("hikaricp.version", "hikari.version"),
                Collections.singletonList("com.zaxxer:HikariCP"),
                Collections.<String>emptyList()));

        return Collections.unmodifiableList(s);
    }
}
