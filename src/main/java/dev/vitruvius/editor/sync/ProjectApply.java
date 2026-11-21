package dev.vitruvius.editor.sync;

import com.google.gson.JsonObject;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Modifier;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Brings an open project to the state of another copy of it (a merge result read back with {@code ProjectIo.fromJson}),
 * changing the live nodes in place. Nodes keep their identity, so the undo stack, the selection and the reconciler's
 * placement ids stay valid; nodes that are new come from the other copy, and nodes that are gone are dropped. Pure.
 *
 * <p>Not copied: the project's world, dimension, path, sync mode and sync record, and an object's placement ids.
 */
public final class ProjectApply {
    private ProjectApply() {}

    /** The ids of the nodes whose fields, parent or position changed, and those that were added or dropped. */
    public static Set<UUID> apply(ProjectNode live, ProjectNode fresh) {
        Map<UUID, Node> index = new HashMap<>();
        for (Node n : live.subtree()) if (n != live) index.put(n.id(), n);
        Set<UUID> affected = new LinkedHashSet<>();

        boolean rootChanged = !live.name().equals(fresh.name()) || live.hidden() != fresh.hidden() || !live.origin().equals(fresh.origin());
        live.setName(fresh.name());
        live.setHidden(fresh.hidden());
        live.setOrigin(fresh.origin());
        JsonObject extra = fresh.extra().deepCopy();
        extra.remove(SyncPlan.KEY);
        if (live.extra().has(SyncPlan.KEY)) extra.add(SyncPlan.KEY, live.extra().get(SyncPlan.KEY));
        live.setExtra(extra);
        if (rootChanged) affected.add(live.id());

        List<Node> kids = new ArrayList<>(fresh.children());
        place(live, kids, index, affected);
        for (Node gone : index.values()) {
            if (!reached(live, gone)) affected.add(gone.id());
        }
        return affected;
    }

    private static boolean reached(ProjectNode root, Node n) {
        for (Node x = n; x != null; x = x.parent()) if (x == root) return true;
        return false;
    }

    /** Makes {@code parent}'s children the resolved {@code wanted} nodes, in order. */
    private static void place(Node parent, List<Node> wanted, Map<UUID, Node> index, Set<UUID> affected) {
        List<Node> next = new ArrayList<>();
        for (Node f : wanted) next.add(resolve(f, index, affected));
        boolean same = next.size() == parent.children().size();
        for (int i = 0; same && i < next.size(); i++) same = next.get(i) == parent.children().get(i);
        if (same) return;
        affected.add(parent.id());
        parent.clearChildren();
        for (Node n : next) {
            // If an inverted hierarchy made parent a descendant of n, sever the old ancestor link before re-parenting.
            for (Node x = parent; x != null; x = x.parent()) {
                if (x.parent() == n) {
                    n.removeChild(x);
                    break;
                }
            }
            parent.addChild(n);
        }
    }

    /** The live node for a node of the other copy: the existing one updated, else the new one itself. */
    private static Node resolve(Node f, Map<UUID, Node> index, Set<UUID> affected) {
        Node live = index.get(f.id());
        List<Node> kids = new ArrayList<>(f.children());
        boolean linked = f instanceof AssemblyNode a && a.isLinked();
        if (live == null || live.getClass() != f.getClass()) {
            affected.add(f.id());
            if (live != null) affected.add(live.id());
            if (!linked) place(f, kids, index, affected);
            return f;
        }
        if (copy(live, f)) affected.add(live.id());
        if (!linked && live.isContainer()) place(live, kids, index, affected);
        return live;
    }

    /** Copies the saved fields of {@code from} into {@code to}; true when anything changed. */
    private static boolean copy(Node to, Node from) {
        boolean changed = !to.name().equals(from.name()) || to.hidden() != from.hidden() || !to.extra().equals(from.extra());
        to.setName(from.name());
        to.setHidden(from.hidden());
        to.setExtra(from.extra().deepCopy());
        if (to instanceof ObjectNode t && from instanceof ObjectNode f) {
            changed |= !t.file().equals(f.file()) || !t.transform().equals(f.transform()) || t.front() != f.front() || !t.mods().equals(f.mods()) || t.wire() != f.wire();
            t.setFile(f.file());
            t.setTransform(f.transform());
            t.setFront(f.front());
            t.setMods(copyMods(f.mods()));
            t.setWire(f.wire());
        } else if (to instanceof AssemblyNode t && from instanceof AssemblyNode f) {
            changed |= !t.transform().equals(f.transform()) || t.front() != f.front() || !t.mods().equals(f.mods()) || !t.source().equals(f.source());
            t.setTransform(f.transform());
            t.setFront(f.front());
            t.setMods(copyMods(f.mods()));
            t.setSource(f.source());
        } else if (to instanceof ExternalNode t && from instanceof ExternalNode f) {
            changed |= !t.transform().equals(f.transform()) || !t.data().equals(f.data()) || !t.typeId().equals(f.typeId()) || !t.mods().equals(f.mods());
            t.setTransform(f.transform());
            t.setData(f.data().deepCopy());
            t.setTypeId(f.typeId());
            t.setMods(copyMods(f.mods()));
        } else if (to instanceof FolderNode t && from instanceof FolderNode f) {
            changed |= t.open() != f.open();
            t.setOpen(f.open());
        }
        return changed;
    }

    private static List<Modifier> copyMods(List<Modifier> mods) {
        List<Modifier> out = new ArrayList<>();
        for (Modifier m : mods) out.add(m.copy());
        return out;
    }
}
