package dev.codeanalyzer.core.analyzer.architecture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A node in the package dependency graph. Tracks which compilation units
 * belong to this package and the outbound edges (packages this one imports)
 * with their weights (number of import statements producing each edge).
 *
 * <p>Edges are project-internal only — imports of {@code java.*}, {@code javax.*},
 * {@code org.springframework.*} and other external packages are filtered out
 * before being added, because they don't contribute to internal architectural
 * coupling.
 */
public class PackageNode {

    private final String name;
    private final Set<String> files = new TreeSet<String>();
    /** outgoing package -> edge weight (count of imports producing the edge) */
    private final Map<String, Integer> edges = new LinkedHashMap<String, Integer>();
    /** sample import lines for a given target package (for evidence) */
    private final Map<String, List<String>> edgeEvidence = new LinkedHashMap<String, List<String>>();

    public PackageNode(String name) {
        this.name = name;
    }

    public String getName() { return name; }

    public Set<String> getFiles() { return Collections.unmodifiableSet(files); }
    public Map<String, Integer> getEdges() { return Collections.unmodifiableMap(edges); }
    public Map<String, List<String>> getEdgeEvidence() { return Collections.unmodifiableMap(edgeEvidence); }

    public void addFile(String relativePath) {
        if (relativePath != null) files.add(relativePath);
    }

    /**
     * Record an outbound dependency from this package to {@code targetPackage}.
     * The evidence (typically the import statement or source location) is kept
     * for reporting; we cap to 5 samples per edge to keep memory bounded.
     */
    public void addEdge(String targetPackage, String evidence) {
        if (targetPackage == null || targetPackage.equals(name)) return;
        Integer w = edges.get(targetPackage);
        edges.put(targetPackage, w == null ? 1 : w + 1);

        List<String> samples = edgeEvidence.get(targetPackage);
        if (samples == null) {
            samples = new ArrayList<String>();
            edgeEvidence.put(targetPackage, samples);
        }
        if (samples.size() < 5 && evidence != null) {
            samples.add(evidence);
        }
    }

    @Override
    public String toString() {
        return name + " (" + files.size() + " files, " + edges.size() + " out-edges)";
    }
}
