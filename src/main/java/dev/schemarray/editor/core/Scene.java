package dev.schemarray.editor.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The open projects plus the one Loose node. Lookups walk the tree, so they always see the current state (no index
 * to keep in step); use {@link #index()} for many lookups in a row.
 */
public final class Scene {
    private final List<ProjectNode> projects = new ArrayList<>();
    private final LooseNode loose;
    private ProjectNode active;

    public Scene() {
        this(new LooseNode());
    }

    public Scene(LooseNode loose) {
        this.loose = Objects.requireNonNull(loose);
    }

    public List<ProjectNode> projects() {
        return Collections.unmodifiableList(projects);
    }

    public LooseNode loose() {
        return loose;
    }

    /** The top-level nodes: the open projects in order, then Loose. */
    public List<Node> roots() {
        List<Node> out = new ArrayList<>(projects);
        out.add(loose);
        return out;
    }

    /** The active project (where new objects go), or null when none is open. */
    public ProjectNode activeProject() {
        return active;
    }

    /** @throws IllegalArgumentException if it is not an open project (null clears it) */
    public void setActiveProject(ProjectNode p) {
        if (p != null && !containsProject(p)) throw new IllegalArgumentException("not an open project: " + p);
        if (active != null) active.setActive(false);
        active = p;
        if (p != null) p.setActive(true);
    }

    /** Opens a project; the first one opened becomes active. */
    public void openProject(ProjectNode p) {
        Objects.requireNonNull(p);
        if (containsProject(p)) return;
        for (ProjectNode o : projects) if (o.id().equals(p.id())) throw new IllegalArgumentException("project " + p.id() + " is already open");
        projects.add(p);
        if (active == null) setActiveProject(p);
    }

    /** Closes a project; if it was active, the first remaining one becomes active. */
    public boolean closeProject(ProjectNode p) {
        if (!projects.remove(p)) return false;
        p.setActive(false);
        if (active == p) {
            active = null;
            if (!projects.isEmpty()) setActiveProject(projects.get(0));
        }
        return true;
    }

    private boolean containsProject(ProjectNode p) {
        for (ProjectNode o : projects) if (o == p) return true;
        return false;
    }

    /** Every node in the scene, depth first (projects, then Loose). */
    public List<Node> allNodes() {
        List<Node> out = new ArrayList<>();
        for (Node r : roots()) out.addAll(r.subtree());
        return out;
    }

    /** The node with this id, or null (the first in tree order if an id is used twice, e.g. by two links of one .lasm). */
    public Node byId(UUID id) {
        for (Node r : roots()) {
            for (Node n : r.subtree()) if (n.id().equals(id)) return n;
        }
        return null;
    }

    /** All nodes by id, for many lookups; a snapshot of the tree as it is now. First one wins on a duplicate id. */
    public Map<UUID, Node> index() {
        Map<UUID, Node> m = new LinkedHashMap<>();
        for (Node n : allNodes()) m.putIfAbsent(n.id(), n);
        return m;
    }

    /** The parent of a node; null for projects and Loose. */
    public Node parentOf(Node n) {
        return n.parent();
    }

    /** The project that holds this node, or null (Loose, or not attached). */
    public ProjectNode projectOf(Node n) {
        for (Node x = n; x != null; x = x.parent()) if (x instanceof ProjectNode p) return p;
        return null;
    }

    /**
     * The node's transform in the world: the local transforms of its ancestors from the root down, then its own.
     * Folders, projects and Loose contribute nothing.
     */
    public Transform worldTransform(Node n) {
        List<Node> chain = new ArrayList<>();
        for (Node x = n; x != null; x = x.parent()) chain.add(x);
        Transform t = Transform.IDENTITY;
        for (int i = chain.size() - 1; i >= 0; i--) t = t.then(chain.get(i).localTransform());
        return t;
    }

    /**
     * What counts as one thing for picking, front order and overlaps: the topmost assembly above the node (the node
     * included), else the node itself.
     */
    public Node ownerOf(Node n) {
        Node owner = n;
        for (Node x = n; x != null; x = x.parent()) if (x instanceof AssemblyNode) owner = x;
        return owner;
    }

    /**
     * The owners that take part in the front order of a world: objects and assemblies that are their own owner (not
     * parts of an assembly, not External) in the projects of that world and in Loose. Wire objects are included.
     */
    public List<HasFront> frontOwnersInWorld(String world) {
        List<HasFront> out = new ArrayList<>();
        List<Node> roots = new ArrayList<>();
        for (ProjectNode p : projects) if (p.world().equals(world)) roots.add(p);
        roots.add(loose);
        for (Node r : roots) {
            for (Node n : r.subtree()) {
                if (n instanceof HasFront f && ownerOf(n) == n) out.add(f);
            }
        }
        return out;
    }
}
