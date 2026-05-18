package dev.codeanalyzer.core.parser;

import dev.codeanalyzer.core.model.ProjectMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProjectMetadataDetector {

    private static final Logger log = LoggerFactory.getLogger(ProjectMetadataDetector.class);

    public ProjectMetadata detect(Path projectRoot) {
        Path pomFile = projectRoot.resolve("pom.xml");
        if (Files.isRegularFile(pomFile)) {
            return parseMaven(pomFile);
        }

        Path gradleFile = projectRoot.resolve("build.gradle");
        if (Files.isRegularFile(gradleFile)) {
            return parseGradle(gradleFile);
        }

        Path gradleKts = projectRoot.resolve("build.gradle.kts");
        if (Files.isRegularFile(gradleKts)) {
            return parseGradle(gradleKts);
        }

        log.info("No pom.xml or build.gradle found, skipping metadata detection");
        return ProjectMetadata.empty();
    }

    private ProjectMetadata parseMaven(Path pomFile) {
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, String> dependencies = new LinkedHashMap<>();
        Map<String, String> managedVersions = new LinkedHashMap<>();
        String javaVersion = null;

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomFile.toFile());
            doc.getDocumentElement().normalize();

            // Collect properties for variable resolution
            collectProperties(doc, properties);

            // Walk parent POM chain to collect inherited properties and managed versions
            collectParentProperties(pomFile.getParent(), doc, properties, managedVersions, builder, 0);

            // Add built-in Maven properties — used downstream to identify
            // the project's own internal modules and skip them from the
            // "libraries" listing in the technologies section.
            String projectVersion = getChildText(doc.getDocumentElement(), "version");
            if (projectVersion != null) {
                properties.putIfAbsent("project.version", projectVersion);
            }
            String projectGroupId = getChildText(doc.getDocumentElement(), "groupId");
            if (projectGroupId != null) {
                properties.putIfAbsent("project.groupId", projectGroupId);
            }
            String parentVersion = getParentVersion(doc);
            String parentGroupId = getParentGroupId(doc);
            if (parentVersion != null) {
                properties.putIfAbsent("project.parent.version", parentVersion);
                if (projectVersion == null) {
                    properties.putIfAbsent("project.version", parentVersion);
                }
            }
            if (parentGroupId != null) {
                properties.putIfAbsent("project.parent.groupId", parentGroupId);
                if (projectGroupId == null) {
                    properties.putIfAbsent("project.groupId", parentGroupId);
                }
            }

            // Detect Java version
            javaVersion = properties.get("java.version");
            if (javaVersion == null) javaVersion = properties.get("maven.compiler.source");
            if (javaVersion == null) javaVersion = properties.get("maven.compiler.target");
            if (javaVersion == null) javaVersion = properties.get("maven.compiler.release");

            // Collect dependencyManagement versions (from this POM and parents)
            collectManagedVersions(doc, properties, managedVersions);

            // Collect dependencies
            NodeList depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                Element dep = (Element) depNodes.item(i);
                // Skip dependencies inside dependencyManagement
                if (isInsideDependencyManagement(dep)) continue;
                String artifactId = getChildText(dep, "artifactId");
                String version = getChildText(dep, "version");
                if (artifactId != null) {
                    String groupId = getChildText(dep, "groupId");
                    if (version != null) {
                        version = resolveProperty(version, properties);
                    }
                    String key = groupId != null ? groupId + ":" + artifactId : artifactId;
                    if (version == null || version.contains("${")) {
                        String managed = managedVersions.get(key);
                        if (managed != null) {
                            version = managed;
                        }
                    }
                    dependencies.put(key, version);
                }
            }

            log.info("Detected Maven project: Java {}, {} dependencies, {} properties",
                    javaVersion, dependencies.size(), properties.size());
        } catch (Exception e) {
            log.warn("Failed to parse pom.xml: {}", e.getMessage());
        }

        return new ProjectMetadata(javaVersion, "Maven", dependencies, properties);
    }

    private void collectProperties(Document doc, Map<String, String> properties) {
        NodeList propNodes = doc.getElementsByTagName("properties");
        if (propNodes.getLength() > 0) {
            Element propsEl = (Element) propNodes.item(0);
            NodeList children = propsEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element) {
                    Element prop = (Element) children.item(i);
                    properties.putIfAbsent(prop.getTagName(), prop.getTextContent().trim());
                }
            }
        }
    }

    private void collectParentProperties(Path pomDir, Document doc, Map<String, String> properties,
                                          Map<String, String> managedVersions, DocumentBuilder builder, int depth) {
        if (depth >= 5) return;

        NodeList parentNodes = doc.getElementsByTagName("parent");
        if (parentNodes.getLength() == 0) return;

        Element parentEl = (Element) parentNodes.item(0);
        String relativePath = getChildText(parentEl, "relativePath");

        // Default to ../pom.xml per Maven convention
        if (relativePath == null) {
            relativePath = "../pom.xml";
        }
        // Empty relativePath means "look in local repo" — can't resolve that
        if (relativePath.trim().isEmpty()) return;

        Path parentPom = pomDir.resolve(relativePath).normalize();
        if (Files.isDirectory(parentPom)) {
            parentPom = parentPom.resolve("pom.xml");
        }
        if (!Files.isRegularFile(parentPom)) {
            log.debug("Parent POM not found at {}, skipping property inheritance", parentPom);
            return;
        }

        try {
            Document parentDoc = builder.parse(parentPom.toFile());
            parentDoc.getDocumentElement().normalize();

            // Collect parent properties (child wins on conflict via putIfAbsent)
            collectProperties(parentDoc, properties);

            // Collect managed versions from parent
            collectManagedVersions(parentDoc, properties, managedVersions);

            // Recurse up the parent chain
            collectParentProperties(parentPom.getParent(), parentDoc, properties, managedVersions, builder, depth + 1);
        } catch (Exception e) {
            log.debug("Failed to parse parent POM {}: {}", parentPom, e.getMessage());
        }
    }

    private void collectManagedVersions(Document doc, Map<String, String> properties,
                                         Map<String, String> managedVersions) {
        try {
            collectManagedVersions(doc, properties, managedVersions,
                    DocumentBuilderFactory.newInstance().newDocumentBuilder(), 0);
        } catch (Exception e) {
            log.debug("collectManagedVersions failed: {}", e.getMessage());
        }
    }

    /**
     * Collects {@code dependencyManagement} entries from this POM, and recursively
     * pulls in entries from any BOMs imported via {@code <type>pom</type><scope>import</scope>}
     * by reading them from the local Maven repository (~/.m2). This is what
     * unlocks dependency-version resolution for Spring Boot, Quarkus, Micronaut,
     * and other BOM-heavy projects.
     */
    private void collectManagedVersions(Document doc, Map<String, String> properties,
                                         Map<String, String> managedVersions,
                                         DocumentBuilder builder, int depth) {
        if (depth > 5) return; // guard against pathological BOM cycles

        NodeList mgmtNodes = doc.getElementsByTagName("dependencyManagement");
        if (mgmtNodes.getLength() == 0) return;

        Element mgmt = (Element) mgmtNodes.item(0);
        NodeList mgmtDeps = mgmt.getElementsByTagName("dependency");
        for (int i = 0; i < mgmtDeps.getLength(); i++) {
            Element dep = (Element) mgmtDeps.item(i);
            String artifactId = getChildText(dep, "artifactId");
            String version = getChildText(dep, "version");
            String groupId = getChildText(dep, "groupId");
            String type = getChildText(dep, "type");
            String scope = getChildText(dep, "scope");

            if (artifactId == null) continue;

            String resolvedVersion = version != null ? resolveProperty(version, properties) : null;

            // Case 1: BOM import — resolve from ~/.m2 and recurse
            if ("pom".equalsIgnoreCase(type) && "import".equalsIgnoreCase(scope)
                    && groupId != null && resolvedVersion != null && !resolvedVersion.contains("${")) {
                importBom(groupId, artifactId, resolvedVersion, properties, managedVersions, builder, depth);
                continue;
            }

            // Case 2: plain managed version
            if (resolvedVersion != null) {
                String key = groupId != null ? groupId + ":" + artifactId : artifactId;
                managedVersions.putIfAbsent(key, resolvedVersion);
            }
        }
    }

    /**
     * Loads a BOM from the local Maven repository and merges its properties
     * and managed versions. Quietly no-ops if the BOM isn't in the local cache
     * (we don't fetch remotely — that would require Maven Resolver).
     */
    private void importBom(String groupId, String artifactId, String version,
                            Map<String, String> properties, Map<String, String> managedVersions,
                            DocumentBuilder builder, int depth) {
        Path bomPath = localRepoPomPath(groupId, artifactId, version);
        if (bomPath == null || !Files.isRegularFile(bomPath)) {
            log.debug("BOM not in local repo (skipping): {}:{}:{}", groupId, artifactId, version);
            return;
        }
        try {
            Document bomDoc = builder.parse(bomPath.toFile());
            bomDoc.getDocumentElement().normalize();
            // Merge BOM properties (child wins via putIfAbsent)
            collectProperties(bomDoc, properties);
            // Add BOM's own project version as a built-in property
            String bomVersion = getChildText(bomDoc.getDocumentElement(), "version");
            if (bomVersion != null) {
                properties.putIfAbsent("project.version", bomVersion);
            }
            // Recurse — BOMs can import other BOMs (Spring Boot imports spring-framework-bom, etc.)
            collectManagedVersions(bomDoc, properties, managedVersions, builder, depth + 1);
            log.debug("Imported BOM {}:{}:{}", groupId, artifactId, version);
        } catch (Exception e) {
            log.debug("Failed to parse BOM {}:{}:{} — {}", groupId, artifactId, version, e.getMessage());
        }
    }

    /** Computes the path to a POM in the local Maven repository (~/.m2/repository). */
    private Path localRepoPomPath(String groupId, String artifactId, String version) {
        String userHome = System.getProperty("user.home");
        if (userHome == null) return null;
        Path repo = java.nio.file.Paths.get(userHome, ".m2", "repository");
        Path artifactDir = repo;
        for (String part : groupId.split("\\.")) {
            artifactDir = artifactDir.resolve(part);
        }
        return artifactDir.resolve(artifactId).resolve(version).resolve(artifactId + "-" + version + ".pom");
    }

    private String getParentVersion(Document doc) {
        NodeList parentNodes = doc.getElementsByTagName("parent");
        if (parentNodes.getLength() > 0) {
            return getChildText((Element) parentNodes.item(0), "version");
        }
        return null;
    }

    private String getParentGroupId(Document doc) {
        NodeList parentNodes = doc.getElementsByTagName("parent");
        if (parentNodes.getLength() > 0) {
            return getChildText((Element) parentNodes.item(0), "groupId");
        }
        return null;
    }

    private boolean isInsideDependencyManagement(Element dep) {
        org.w3c.dom.Node parent = dep.getParentNode();
        while (parent != null) {
            if (parent instanceof Element && "dependencyManagement".equals(((Element) parent).getTagName())) {
                return true;
            }
            parent = parent.getParentNode();
        }
        return false;
    }

    private ProjectMetadata parseGradle(Path gradleFile) {
        Map<String, String> dependencies = new LinkedHashMap<>();
        String javaVersion = null;

        try {
            String content = new String(Files.readAllBytes(gradleFile), StandardCharsets.UTF_8);

            // Detect Java version from sourceCompatibility, targetCompatibility, or toolchain
            Pattern javaVersionPattern = Pattern.compile(
                "(?:sourceCompatibility|targetCompatibility|languageVersion)\\s*[=.]\\s*['\"]?(?:JavaLanguageVersion\\.of\\()?([\\d.]+)\\)?['\"]?");
            Matcher jvMatcher = javaVersionPattern.matcher(content);
            if (jvMatcher.find()) {
                javaVersion = jvMatcher.group(1);
            }

            // Detect dependencies: implementation 'group:artifact:version'
            Pattern depPattern = Pattern.compile(
                "(?:implementation|api|compile|runtimeOnly|compileOnly|testImplementation)\\s*['\"]([^'\"]+:[^'\"]+):([^'\"]+)['\"]");
            Matcher depMatcher = depPattern.matcher(content);
            while (depMatcher.find()) {
                dependencies.put(depMatcher.group(1), depMatcher.group(2));
            }

            log.info("Detected Gradle project: Java {}, {} dependencies", javaVersion, dependencies.size());
        } catch (IOException e) {
            log.warn("Failed to parse build.gradle: {}", e.getMessage());
        }

        return new ProjectMetadata(javaVersion, "Gradle", dependencies);
    }

    private String getChildText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }

    private String resolveProperty(String value, Map<String, String> properties) {
        if (value == null) return null;
        Pattern pattern = Pattern.compile("\\$\\{(.+?)}");
        Matcher matcher = pattern.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String propName = matcher.group(1);
            String resolved = properties.getOrDefault(propName, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
