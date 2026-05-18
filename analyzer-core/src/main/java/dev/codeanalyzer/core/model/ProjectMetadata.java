package dev.codeanalyzer.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProjectMetadata {

    private final String javaVersion;
    private final String buildTool;
    private final Map<String, String> dependencies;
    private final Map<String, String> properties;

    /** Legacy constructor — properties default to empty. Prefer the full constructor. */
    public ProjectMetadata(String javaVersion, String buildTool, Map<String, String> dependencies) {
        this(javaVersion, buildTool, dependencies, Collections.<String, String>emptyMap());
    }

    /**
     * Full constructor including resolved pom/gradle properties. Properties are
     * critical for multi-module Maven projects where version coordinates are
     * declared in the root pom's {@code <properties>} block but the actual
     * {@code <dependency>} entries live in submodules we don't scan.
     */
    public ProjectMetadata(String javaVersion, String buildTool,
                           Map<String, String> dependencies, Map<String, String> properties) {
        this.javaVersion = javaVersion;
        this.buildTool = buildTool;
        this.dependencies = Collections.unmodifiableMap(new LinkedHashMap<>(dependencies));
        this.properties = Collections.unmodifiableMap(
                properties != null ? new LinkedHashMap<>(properties) : new LinkedHashMap<String, String>());
    }

    public String getJavaVersion() { return javaVersion; }
    public String getBuildTool() { return buildTool; }
    public Map<String, String> getDependencies() { return dependencies; }
    public Map<String, String> getProperties() { return properties; }

    public String getDependencyVersion(String artifactId) {
        return dependencies.get(artifactId);
    }

    public boolean hasDependency(String artifactId) {
        return dependencies.containsKey(artifactId);
    }

    /**
     * Looks up a value across both the dependency map and the properties map.
     * Useful when a framework's version is declared in {@code <properties>}
     * but the {@code <dependency>} entry isn't in the root pom.
     */
    public String findVersionByPropertyKey(String propertyKey) {
        return properties.get(propertyKey);
    }

    public static ProjectMetadata empty() {
        return new ProjectMetadata(null, null,
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap());
    }
}
