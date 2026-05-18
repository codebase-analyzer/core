package dev.codeanalyzer.core.analyzer.architecture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory directed graph of package -> package dependencies, with
 * Tarjan's Strongly Connected Components algorithm to surface cycles.
 *
 * <p>Each SCC of size &gt; 1 (or a single node with a self-loop, though we
 * filter self-edges at insertion) is a dependency cycle. We return them
 * sorted descending by size so the worst offenders surface first.
 *
 * <p>Pure data structure — no JavaParser or filesystem coupling, so it's
 * trivially unit-testable.
 */
public class PackageGraph {

    private final Map<String, PackageNode> nodes = new LinkedHashMap<String, PackageNode>();

    public PackageNode getOrCreate(String packageName) {
        PackageNode n = nodes.get(packageName);
        if (n == null) {
            n = new PackageNode(packageName);
            nodes.put(packageName, n);
        }
        return n;
    }

    public PackageNode get(String packageName) { return nodes.get(packageName); }

    public Collection<PackageNode> getNodes() { return Collections.unmodifiableCollection(nodes.values()); }

    public int size() { return nodes.size(); }

    /**
     * Compute strongly connected components using Tarjan's algorithm
     * (iterative form to avoid blowing the JVM stack on huge graphs).
     * Returns only SCCs of size &gt;= 2 (= actual cycles), sorted descending
     * by component size.
     */
    public List<List<String>> findCycles() {
        TarjanState state = new TarjanState();
        for (String node : nodes.keySet()) {
            if (!state.indices.containsKey(node)) {
                strongConnect(node, state);
            }
        }
        List<List<String>> cycles = new ArrayList<List<String>>();
        for (List<String> scc : state.sccs) {
            if (scc.size() >= 2) cycles.add(scc);
        }
        cycles.sort(new java.util.Comparator<List<String>>() {
            @Override public int compare(List<String> a, List<String> b) {
                return Integer.compare(b.size(), a.size());
            }
        });
        return cycles;
    }

    // Iterative Tarjan SCC — avoids recursion limits on large graphs.
    private void strongConnect(String start, TarjanState st) {
        Deque<Frame> stack = new ArrayDeque<Frame>();
        stack.push(new Frame(start, neighborsOf(start).iterator()));
        st.indices.put(start, st.index);
        st.lowlinks.put(start, st.index);
        st.index++;
        st.onStack.add(start);
        st.tarjanStack.push(start);

        while (!stack.isEmpty()) {
            Frame f = stack.peek();
            if (f.it.hasNext()) {
                String w = f.it.next();
                if (!st.indices.containsKey(w)) {
                    st.indices.put(w, st.index);
                    st.lowlinks.put(w, st.index);
                    st.index++;
                    st.onStack.add(w);
                    st.tarjanStack.push(w);
                    stack.push(new Frame(w, neighborsOf(w).iterator()));
                } else if (st.onStack.contains(w)) {
                    int low = Math.min(st.lowlinks.get(f.node), st.indices.get(w));
                    st.lowlinks.put(f.node, low);
                }
            } else {
                stack.pop();
                int nodeLow = st.lowlinks.get(f.node);
                int nodeIdx = st.indices.get(f.node);
                if (nodeLow == nodeIdx) {
                    List<String> scc = new ArrayList<String>();
                    String w;
                    do {
                        w = st.tarjanStack.pop();
                        st.onStack.remove(w);
                        scc.add(w);
                    } while (!w.equals(f.node));
                    st.sccs.add(scc);
                }
                if (!stack.isEmpty()) {
                    Frame parent = stack.peek();
                    int parentLow = Math.min(st.lowlinks.get(parent.node), st.lowlinks.get(f.node));
                    st.lowlinks.put(parent.node, parentLow);
                }
            }
        }
    }

    private Collection<String> neighborsOf(String node) {
        PackageNode n = nodes.get(node);
        if (n == null) return Collections.emptyList();
        // Only include neighbors that are real nodes in the graph (internal).
        List<String> result = new ArrayList<String>();
        for (String target : n.getEdges().keySet()) {
            if (nodes.containsKey(target)) result.add(target);
        }
        return result;
    }

    private static final class Frame {
        final String node;
        final java.util.Iterator<String> it;
        Frame(String node, java.util.Iterator<String> it) {
            this.node = node;
            this.it = it;
        }
    }

    private static final class TarjanState {
        int index = 0;
        final Map<String, Integer> indices = new HashMap<String, Integer>();
        final Map<String, Integer> lowlinks = new HashMap<String, Integer>();
        final Set<String> onStack = new HashSet<String>();
        final Deque<String> tarjanStack = new ArrayDeque<String>();
        final List<List<String>> sccs = new ArrayList<List<String>>();
    }
}
