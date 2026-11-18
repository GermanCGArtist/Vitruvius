package dev.vitruvius.editor.sync.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.vitruvius.editor.sync.ProjectMerge;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merge and restore as plans over a {@link CommitGraph}: they say what the new commit holds, they do not publish anything.
 * The merge is the project merge of live sharing ({@link ProjectMerge}) with the merge base of the two heads as common
 * ancestor, and the same rule for cell files per slot. Pure.
 */
public final class BranchOps {
    private BranchOps() {}

    /** What a commit holds: the scene and the placement of each cell file. */
    public record Snapshot(JsonObject project, Map<String, String> cells) {}

    public enum Kind { UP_TO_DATE, FAST_FORWARD, MERGE }

    /**
     * @param parents   the parents the new commit gets: the head of the receiving branch first, then the head merged in
     * @param conflicts nodes (and cells) both sides changed; the rule picks one version, the count is shown
     * @param base      the merge base, or null for unrelated histories
     */
    public record MergeResult(Kind kind, Snapshot snapshot, List<String> parents, int conflicts, Commit base) {}

    public static Snapshot snapshot(Commit c) {
        return new Snapshot(c.project().deepCopy(), new LinkedHashMap<>(c.cells()));
    }

    /** Merges the head of {@code from} into the head of {@code into}. */
    public static MergeResult merge(CommitGraph g, Commit into, Commit from) {
        if (g.isAncestor(from.id(), into.id())) return new MergeResult(Kind.UP_TO_DATE, snapshot(into), List.of(into.id()), 0, from);
        if (g.isAncestor(into.id(), from.id())) return new MergeResult(Kind.FAST_FORWARD, snapshot(from), List.of(into.id(), from.id()), 0, into);
        Commit base = g.mergeBase(into.id(), from.id());
        JsonObject baseProject = base != null ? base.project() : empty(into.project());
        Map<String, String> baseCells = base != null ? base.cells() : Map.of();
        ProjectMerge.Result r = ProjectMerge.merge(baseProject, into.project(), from.project());
        int[] conflicts = {r.conflicts()};
        Map<String, String> cells = mergeCells(baseCells, into.cells(), from.cells(), conflicts);
        return new MergeResult(Kind.MERGE, new Snapshot(r.project(), cells), List.of(into.id(), from.id()), conflicts[0], base);
    }

    private static JsonObject empty(JsonObject like) {
        JsonObject o = new JsonObject();
        if (like.has("id")) o.add("id", like.get("id").deepCopy());
        o.add("nodes", new JsonArray());
        return o;
    }

    /**
     * Three-way merge of the cell placements by slot: a side that changed a cell (new placement id, or removed it) wins
     * over a side that did not; when both changed it, the larger id wins (the same on both clients), and a removal loses
     * against a change.
     */
    static Map<String, String> mergeCells(Map<String, String> base, Map<String, String> ours, Map<String, String> theirs, int[] conflicts) {
        Set<String> keys = new LinkedHashSet<>(ours.keySet());
        keys.addAll(theirs.keySet());
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : keys) {
            String b = base.get(k), o = ours.get(k), t = theirs.get(k);
            String r;
            if (eq(o, t)) r = o;
            else if (eq(o, b)) r = t;
            else if (eq(t, b)) r = o;
            else {
                conflicts[0]++;
                r = o == null ? t : t == null ? o : o.compareTo(t) >= 0 ? o : t;
            }
            if (r != null) out.put(k, r);
        }
        return out;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
