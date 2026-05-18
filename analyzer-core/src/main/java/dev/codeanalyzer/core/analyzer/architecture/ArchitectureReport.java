package dev.codeanalyzer.core.analyzer.architecture;

import java.util.Collections;
import java.util.List;

/**
 * Aggregated architecture analysis output exposed to the HTML report.
 *
 * <p>Holds the package graph (so the report can render package counts and
 * top fan-out lists), the detected cycles (each a list of package names),
 * the layering violations (textual entries), and the top god classes.
 *
 * <p>Findings are also produced individually so they appear in the standard
 * filtered finding list — this object is the structured view for the
 * dedicated Architecture page.
 */
public class ArchitectureReport {

    private final PackageGraph graph;
    private final List<List<String>> cycles;
    private final List<LayeringViolation> violations;
    private final List<ClassMetric> godClasses;

    public ArchitectureReport(PackageGraph graph,
                              List<List<String>> cycles,
                              List<LayeringViolation> violations,
                              List<ClassMetric> godClasses) {
        this.graph = graph;
        this.cycles = Collections.unmodifiableList(cycles);
        this.violations = Collections.unmodifiableList(violations);
        this.godClasses = Collections.unmodifiableList(godClasses);
    }

    public PackageGraph getGraph() { return graph; }
    public List<List<String>> getCycles() { return cycles; }
    public List<LayeringViolation> getViolations() { return violations; }
    public List<ClassMetric> getGodClasses() { return godClasses; }

    public boolean isEmpty() {
        return (graph == null || graph.size() == 0)
                && cycles.isEmpty() && violations.isEmpty() && godClasses.isEmpty();
    }

    public static ArchitectureReport empty() {
        return new ArchitectureReport(new PackageGraph(),
                Collections.<List<String>>emptyList(),
                Collections.<LayeringViolation>emptyList(),
                Collections.<ClassMetric>emptyList());
    }

    /**
     * A single layering violation: a package in one layer importing a package
     * in another layer it shouldn't depend on. Carries the from/to packages,
     * their inferred layers, a textual reason, and up to a few sample import
     * lines for evidence.
     */
    public static class LayeringViolation {
        private final String fromPackage;
        private final String toPackage;
        private final LayerInference.Layer fromLayer;
        private final LayerInference.Layer toLayer;
        private final String reason;
        private final int edgeWeight;
        private final List<String> samples;

        public LayeringViolation(String fromPackage, String toPackage,
                                 LayerInference.Layer fromLayer, LayerInference.Layer toLayer,
                                 String reason, int edgeWeight, List<String> samples) {
            this.fromPackage = fromPackage;
            this.toPackage = toPackage;
            this.fromLayer = fromLayer;
            this.toLayer = toLayer;
            this.reason = reason;
            this.edgeWeight = edgeWeight;
            this.samples = samples != null
                    ? Collections.unmodifiableList(samples)
                    : Collections.<String>emptyList();
        }

        public String getFromPackage() { return fromPackage; }
        public String getToPackage() { return toPackage; }
        public LayerInference.Layer getFromLayer() { return fromLayer; }
        public LayerInference.Layer getToLayer() { return toLayer; }
        public String getReason() { return reason; }
        public int getEdgeWeight() { return edgeWeight; }
        public List<String> getSamples() { return samples; }
    }
}
