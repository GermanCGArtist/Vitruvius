package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Modifier;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a command needs to undo itself on the model: the saved fields and the child list of every node it
 * {@link #remember remembers}, and the list of open projects when it {@link #rememberScene asks}. It also collects the ids
 * and projects a command touched, for the {@link ChangeSink}.
 *
 * <p>Not saved on purpose: {@code ObjectNode.placementIds}. They are bookkeeping of the reconciler, which may have
 * changed them since; restoring old ones would lose track of live placements.
 */
final class Snapshot {
    private final Scene scene;
    private final Map<Node, Runnable> fieldRestore = new LinkedHashMap<>();
    private final Map<Node, List<Node>> childLists = new LinkedHashMap<>();
    private final Set<Node> touched = new LinkedHashSet<>();
    private final Set<UUID> ids = new LinkedHashSet<>();
    private final Set<UUID> roots = new LinkedHashSet<>();
    private List<ProjectNode> projects;
    private ProjectNode active;

    Snapshot(Scene scene) {
        this.scene = scene;
    }

    /** Notes the node (and its subtree, and its project) as affected, without saving its fields. */
    void touch(Node n) {
        if (touched.add(n)) {
            for (Node x : n.subtree()) ids.add(x.id());
            UUID r = rootOf(n);
            if (r != null) roots.add(r);
        }
    }

    /** Saves the fields and the child list of the node, and touches it. Only the first call per node counts. */
    void remember(Node n) {
        touch(n);
        if (fieldRestore.containsKey(n)) return;
        fieldRestore.put(n, fieldsOf(n));
        if (n.isContainer()) childLists.put(n, new ArrayList<>(n.children()));
    }

    /** Saves which projects are open and which is active. */
    void rememberScene() {
        if (projects == null) {
            projects = new ArrayList<>(scene.projects());
            active = scene.activeProject();
        }
    }

    private UUID rootOf(Node n) {
        Node x = n;
        while (x.parent() != null) x = x.parent();
        return x instanceof ProjectNode || x instanceof LooseNode ? x.id() : null;
    }

    /** Puts everything remembered back. */
    void restore() {
        for (Node c : childLists.keySet()) c.clearChildren();
        for (Runnable r : fieldRestore.values()) r.run();
        for (Map.Entry<Node, List<Node>> e : childLists.entrySet()) {
            for (Node child : e.getValue()) e.getKey().addChild(child);
        }
        if (projects != null) {
            for (ProjectNode p : new ArrayList<>(scene.projects())) scene.closeProject(p);
            for (ProjectNode p : projects) scene.openProject(p);
            scene.setActiveProject(active);
        }
    }

    /** Tells the sink what was touched, as it is now and as it was when remembered. */
    void report(ChangeSink sink) {
        Set<UUID> allIds = new LinkedHashSet<>(ids);
        Set<UUID> allRoots = new LinkedHashSet<>(roots);
        for (Node n : touched) {
            for (Node x : n.subtree()) allIds.add(x.id());
            UUID r = rootOf(n);
            if (r != null) allRoots.add(r);
        }
        if (projects != null) {
            for (ProjectNode p : projects) allRoots.add(p.id());
            for (ProjectNode p : scene.projects()) allRoots.add(p.id());
        }
        for (UUID r : allRoots) sink.markDirty(r);
        if (!allIds.isEmpty()) sink.affected(allIds);
    }

    // ---- per type ----

    private static List<Modifier> copyMods(List<Modifier> mods) {
        List<Modifier> out = new ArrayList<>();
        for (Modifier m : mods) out.add(m.copy());
        return out;
    }

    private static Runnable fieldsOf(Node n) {
        String name = n.name();
        boolean hidden = n.hidden();
        JsonObject extra = n.extra().deepCopy();
        Runnable common = () -> {
            n.setName(name);
            n.setHidden(hidden);
            n.setExtra(extra.deepCopy());
        };
        if (n instanceof ObjectNode o) {
            String file = o.file();
            Transform t = o.transform();
            int front = o.front();
            List<Modifier> mods = copyMods(o.mods());
            boolean wire = o.wire();
            return () -> {
                common.run();
                o.setFile(file);
                o.setTransform(t);
                o.setFront(front);
                o.setMods(copyMods(mods));
                o.setWire(wire);
            };
        }
        if (n instanceof AssemblyNode a) {
            Transform t = a.transform();
            int front = a.front();
            List<Modifier> mods = copyMods(a.mods());
            AssemblyNode.Source src = a.source();
            return () -> {
                common.run();
                a.setTransform(t);
                a.setFront(front);
                a.setMods(copyMods(mods));
                a.setSource(src);
            };
        }
        if (n instanceof ExternalNode x) {
            Transform t = x.transform();
            JsonObject data = x.data().deepCopy();
            String type = x.typeId();
            List<Modifier> mods = copyMods(x.mods());
            return () -> {
                common.run();
                x.setTransform(t);
                x.setData(data.deepCopy());
                x.setTypeId(type);
                x.setMods(copyMods(mods));
            };
        }
        if (n instanceof FolderNode f) {
            boolean open = f.open();
            return () -> {
                common.run();
                f.setOpen(open);
            };
        }
        if (n instanceof ProjectNode p) {
            String world = p.world(), dim = p.dim(), path = p.path();
            Vec3i origin = p.origin();
            ProjectNode.Sync sync = p.sync();
            return () -> {
                common.run();
                p.setWorld(world);
                p.setDim(dim);
                p.setPath(path);
                p.setOrigin(origin);
                p.setSync(sync);
            };
        }
        return common;
    }
}
