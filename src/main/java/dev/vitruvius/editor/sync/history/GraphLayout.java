package dev.vitruvius.editor.sync.history;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Pure layout of a {@link CommitGraph} as a Git-style lane graph: topological newest-first rows, vertical branch lanes,
 * branch-head badges, and parent/merge edges. No ImGui or Minecraft imports.
 */
public final class GraphLayout {
    private GraphLayout() {}

    public record Row(Commit commit, int lane, int color, List<String> heads, List<String> divergedHeads) {}

    public record Edge(int fromRow, int fromLane, int toRow, int toLane, int color, boolean merge) {}

    public record Layout(List<Row> rows, List<Edge> edges, int laneCount) {
        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    /** Builds a graph layout of every commit in {@code graph}. */
    public static Layout build(CommitGraph graph) {
        return build(graph, null);
    }

    /** Builds a graph layout of {@code graph}, or of one branch's history when {@code onlyBranch} is non-null. */
    public static Layout build(CommitGraph graph, String onlyBranch) {
        List<Commit> pool;
        if (onlyBranch == null) {
            pool = graph.all();
        } else {
            Commit h = graph.head(onlyBranch).orElse(null);
            pool = h == null ? List.of() : graph.log(h.id());
        }
        if (pool.isEmpty()) return new Layout(List.of(), List.of(), 0);

        Map<String, Commit> included = new HashMap<>();
        for (Commit c : pool) included.put(c.id(), c);

        List<Commit> ordered = topoSort(pool, included);

        // Deterministic color index per branch (main = 0)
        Map<String, Integer> colors = new HashMap<>();
        colors.put(CommitMeta.MAIN, 0);
        int nextColor = 1;
        for (String b : graph.branches()) {
            if (!colors.containsKey(b)) colors.put(b, nextColor++);
        }

        // Branch head badges per commit id
        Map<String, List<String>> heads = new HashMap<>();
        Map<String, List<String>> diverged = new HashMap<>();
        for (String b : graph.branches()) {
            List<Commit> tips = graph.tips(b);
            if (!tips.isEmpty()) {
                heads.computeIfAbsent(tips.get(0).id(), k -> new ArrayList<>()).add(b);
                for (int i = 1; i < tips.size(); i++) {
                    diverged.computeIfAbsent(tips.get(i).id(), k -> new ArrayList<>()).add(b);
                }
            }
        }

        // Assign lanes top-to-bottom
        List<String> active = new ArrayList<>();
        Map<String, Integer> rowOf = new HashMap<>();
        Map<String, Integer> laneOf = new HashMap<>();
        int maxLane = 0;

        for (int r = 0; r < ordered.size(); r++) {
            Commit c = ordered.get(r);
            int lane = -1;
            for (int l = 0; l < active.size(); l++) {
                if (c.id().equals(active.get(l))) {
                    if (lane < 0) lane = l;
                    else active.set(l, null);
                }
            }
            if (lane < 0) {
                if (c.main() && !active.isEmpty() && active.get(0) == null) {
                    lane = 0;
                } else {
                    lane = firstFree(active);
                }
            }
            while (active.size() <= lane) active.add(null);
            rowOf.put(c.id(), r);
            laneOf.put(c.id(), lane);
            if (lane > maxLane) maxLane = lane;

            List<String> parents = new ArrayList<>();
            for (String p : c.parents()) if (included.containsKey(p)) parents.add(p);

            if (parents.isEmpty()) {
                active.set(lane, null);
            } else {
                String p0 = parents.get(0);
                if (active.contains(p0)) {
                    active.set(lane, null);
                } else {
                    active.set(lane, p0);
                }
                for (int i = 1; i < parents.size(); i++) {
                    String pk = parents.get(i);
                    if (!active.contains(pk)) {
                        int fl = firstFree(active);
                        if (fl == active.size()) active.add(pk);
                        else active.set(fl, pk);
                        if (fl > maxLane) maxLane = fl;
                    }
                }
            }
        }

        List<Row> rows = new ArrayList<>(ordered.size());
        List<Edge> edges = new ArrayList<>();
        for (int r = 0; r < ordered.size(); r++) {
            Commit c = ordered.get(r);
            int lane = laneOf.get(c.id());
            int col = colors.getOrDefault(c.branch(), 1);
            rows.add(new Row(c, lane, col,
                    List.copyOf(heads.getOrDefault(c.id(), List.of())),
                    List.copyOf(diverged.getOrDefault(c.id(), List.of()))));
            for (int i = 0; i < c.parents().size(); i++) {
                String pid = c.parents().get(i);
                Integer toRow = rowOf.get(pid);
                if (toRow == null) continue;
                int toLane = laneOf.get(pid);
                boolean merge = i > 0;
                Commit pc = included.get(pid);
                int edgeCol = merge ? colors.getOrDefault(pc.branch(), col) : col;
                edges.add(new Edge(r, lane, toRow, toLane, edgeCol, merge));
            }
        }

        return new Layout(List.copyOf(rows), List.copyOf(edges), maxLane + 1);
    }

    private static int firstFree(List<String> active) {
        for (int i = 0; i < active.size(); i++) if (active.get(i) == null) return i;
        return active.size();
    }

    private static List<Commit> topoSort(List<Commit> pool, Map<String, Commit> included) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (Commit c : pool) inDegree.put(c.id(), 0);
        for (Commit c : pool) {
            Set<String> seenParents = new HashSet<>();
            for (String p : c.parents()) {
                if (included.containsKey(p) && seenParents.add(p)) {
                    inDegree.merge(p, 1, Integer::sum);
                }
            }
        }
        Comparator<Commit> order = Comparator.comparingLong(Commit::at).reversed()
                .thenComparing(c -> c.main() ? 0 : 1)
                .thenComparing(Commit::id);
        PriorityQueue<Commit> ready = new PriorityQueue<>(order);
        for (Commit c : pool) if (inDegree.get(c.id()) == 0) ready.add(c);

        List<Commit> out = new ArrayList<>(pool.size());
        Set<String> placed = new HashSet<>();
        while (!ready.isEmpty()) {
            Commit c = ready.poll();
            if (!placed.add(c.id())) continue;
            out.add(c);
            Set<String> seenParents = new HashSet<>();
            for (String p : c.parents()) {
                if (!included.containsKey(p) || !seenParents.add(p)) continue;
                int left = inDegree.merge(p, -1, Integer::sum);
                if (left == 0) ready.add(included.get(p));
            }
        }
        if (out.size() < pool.size()) {
            List<Commit> rest = new ArrayList<>();
            for (Commit c : pool) if (!placed.contains(c.id())) rest.add(c);
            rest.sort(order);
            out.addAll(rest);
        }
        return out;
    }
}
