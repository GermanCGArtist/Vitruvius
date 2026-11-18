package dev.vitruvius.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Three-way merge of two edited copies of one project, by node id (live sharing, EDITOR-2.0 section 8). Pure.
 *
 * <p>{@code base} is the project as it was when this client last synced, {@code ours} the project now, {@code theirs} the
 * project another client pushed. A node is one unit: its own fields (everything except {@code children}) and the id of its
 * parent. Per node id:
 * <ul>
 *   <li>same on both sides: kept;
 *   <li>only one side changed it (a move, a new array, a delete, a new node): that side's version;
 *   <li>both changed it: a conflict. A side that deleted loses against a side that edited (no work is thrown away); when
 *       both edited, the version whose canonical JSON text sorts larger wins. The rule only looks at the two versions, so
 *       both clients end with the same project whichever one merges.
 * </ul>
 * Sibling order follows the one side that reordered, else the base order, with new nodes last in id order. A parent that
 * was deleted sends its remaining children to the project root, and a move cycle is broken at the smallest id.
 *
 * <p>Fields of the project itself that belong to one computer ({@code world}, {@code dim}, {@code sync}, the sync record) are
 * always taken from {@code ours}.
 */
public final class ProjectMerge {
    private ProjectMerge() {}

    public record Result(JsonObject project, int conflicts, boolean changed) {}

    private static final Set<String> LOCAL_FIELDS = Set.of("world", "dim", "sync", SyncPlan.KEY, "nodes");
    private static final String ROOT = "";

    /** One node as merged: its fields without children, and its parent id ({@link #ROOT} for the project). */
    private record Flat(JsonObject fields, String parent, boolean container) {
        boolean same(Flat o) {
            return o != null && parent.equals(o.parent) && container == o.container && fields.equals(o.fields);
        }

        String canon() {
            return parent + "|" + container + "|" + fields;
        }
    }

    private static final class Tree {
        final Map<String, Flat> nodes = new LinkedHashMap<>();
        final Map<String, List<String>> order = new HashMap<>();
        Flat root;
    }

    private static Tree flatten(JsonObject project) {
        Tree t = new Tree();
        JsonObject rootFields = new JsonObject();
        for (var e : project.entrySet()) {
            if (!LOCAL_FIELDS.contains(e.getKey())) rootFields.add(e.getKey(), e.getValue().deepCopy());
        }
        t.root = new Flat(rootFields, ROOT, true);
        walk(project.get("nodes"), ROOT, t);
        return t;
    }

    private static void walk(JsonElement list, String parent, Tree t) {
        if (list == null || !list.isJsonArray()) return;
        List<String> ids = t.order.computeIfAbsent(parent, k -> new ArrayList<>());
        for (JsonElement el : list.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject n = el.getAsJsonObject();
            if (!n.has("id")) continue;
            String id = n.get("id").getAsString();
            JsonObject fields = new JsonObject();
            for (var e : n.entrySet()) if (!e.getKey().equals("children")) fields.add(e.getKey(), e.getValue().deepCopy());
            t.nodes.put(id, new Flat(fields, parent, n.has("children")));
            ids.add(id);
            walk(n.get("children"), id, t);
        }
    }

    /** The version that wins when both sides changed a node (or null when both deleted it). */
    private static Flat pick(Flat o, Flat t) {
        if (o == null) return t;
        if (t == null) return o;
        return o.canon().compareTo(t.canon()) >= 0 ? o : t;
    }

    public static Result merge(JsonObject base, JsonObject ours, JsonObject theirs) {
        Tree b = flatten(base), o = flatten(ours), t = flatten(theirs);
        int conflicts = 0;
        Map<String, Flat> out = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>(o.nodes.keySet());
        ids.addAll(t.nodes.keySet());
        for (String id : ids) {
            Flat fb = b.nodes.get(id), fo = o.nodes.get(id), ft = t.nodes.get(id);
            Flat r;
            if (same(fo, ft)) r = fo;
            else if (same(fo, fb)) r = ft;
            else if (same(ft, fb)) r = fo;
            else {
                conflicts++;
                r = fo == null ? ft : ft == null ? fo : pick(fo, ft);
            }
            if (r != null) out.put(id, r);
        }
        // a node whose side deleted it while the other did not touch it is gone: it is not in `ids` from the deleting side,
        // and `same(fo, fb)`/`same(ft, fb)` above already dropped it. Nodes only the base knew are gone on both sides.

        Flat rootFb = b.root, rootFo = o.root, rootFt = t.root;
        Flat root;
        if (same(rootFo, rootFt)) root = rootFo;
        else if (same(rootFo, rootFb)) root = rootFt;
        else if (same(rootFt, rootFb)) root = rootFo;
        else {
            conflicts++;
            root = pick(rootFo, rootFt);
        }

        // parents that are gone, and cycles
        Map<String, String> parent = new HashMap<>();
        for (var e : out.entrySet()) {
            String p = e.getValue().parent;
            parent.put(e.getKey(), !p.equals(ROOT) && !out.containsKey(p) ? ROOT : p);
        }
        for (String start : new TreeSet<>(parent.keySet())) {
            Set<String> seen = new HashSet<>();
            String x = start;
            while (!x.equals(ROOT) && seen.add(x)) x = parent.get(x);
            if (!x.equals(ROOT)) parent.put(x, ROOT);   // x is on a cycle: cut it here
        }

        Map<String, List<String>> kids = new LinkedHashMap<>();
        for (String id : out.keySet()) kids.computeIfAbsent(parent.get(id), k -> new ArrayList<>()).add(id);
        JsonObject result = new JsonObject();
        for (var e : ours.entrySet()) if (LOCAL_FIELDS.contains(e.getKey()) && !e.getKey().equals("nodes")) result.add(e.getKey(), e.getValue().deepCopy());
        for (var e : root.fields.entrySet()) result.add(e.getKey(), e.getValue().deepCopy());
        result.add("nodes", build(ROOT, kids, out, b, o, t));

        boolean changed = !result.equals(ours);
        return new Result(result, conflicts, changed);
    }

    /** Whether two copies of a project hold the same scene (the fields that belong to one computer are not compared). */
    public static boolean equivalent(JsonObject a, JsonObject b) {
        return !merge(a, a, b).changed();
    }

    private static boolean same(Flat a, Flat b) {
        if (a == null || b == null) return a == b;
        return a.same(b);
    }

    private static JsonArray build(String parent, Map<String, List<String>> kids, Map<String, Flat> nodes, Tree b, Tree o, Tree t) {
        List<String> ids = ordered(parent, kids.getOrDefault(parent, List.of()), b, o, t);
        JsonArray arr = new JsonArray();
        for (String id : ids) {
            Flat f = nodes.get(id);
            JsonObject n = f.fields.deepCopy();
            JsonArray sub = build(id, kids, nodes, b, o, t);
            if (sub.size() > 0 || f.container) n.add("children", sub);
            arr.add(n);
        }
        return arr;
    }


    private static List<String> ordered(String parent, List<String> members, Tree b, Tree o, Tree t) {
        Set<String> set = new HashSet<>(members);
        List<String> fb = filter(b.order.get(parent), set), fo = filter(o.order.get(parent), set), ft = filter(t.order.get(parent), set);
        List<String> chosen;
        if (fo.equals(fb) || fo.isEmpty()) chosen = ft.isEmpty() ? fb : ft;
        else if (ft.equals(fb) || ft.isEmpty()) chosen = fo;
        else chosen = fb;
        List<String> out = new ArrayList<>(chosen);
        Set<String> placed = new HashSet<>(out);
        TreeSet<String> rest = new TreeSet<>();
        for (String id : members) if (!placed.contains(id)) rest.add(id);
        out.addAll(rest);
        return out;
    }

    private static List<String> filter(List<String> order, Set<String> keep) {
        List<String> out = new ArrayList<>();
        if (order != null) for (String id : order) if (keep.contains(id)) out.add(id);
        return out;
    }
}
