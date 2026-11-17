package dev.vitruvius.editor.mc;

import dev.vitruvius.editor.core.ArrayCells;
import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.AssemblyBounds;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.Ids;
import dev.vitruvius.editor.core.Modifier;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.ProjectLayout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The planning and writing half of the reconciler (EDITOR-2.0 section 4), with no Minecraft types: it talks to the
 * placement list through {@link PlacementPort}, so the tests run it against a fake. {@link Reconciler} feeds it the
 * live world each tick.
 *
 * <p>One {@link #reconcile} call:
 * <ol>
 *   <li>walks the scene: every visible ObjectNode (not hidden itself or through an ancestor, in a project of the
 *       current world and dimension) gives its base cell and its array cells, each with a world transform;
 *   <li>per cell, finds the placement by the stored hash id; creates it when missing or of the wrong file (at most
 *       {@link #BUDGET} creations per call, the rest wait for the next), or moves it when it differs from the model;
 *   <li>removes placements the model no longer wants (hidden nodes, array cells that are gone, nodes that left the
 *       model) and never touches a placement it does not know;
 *   <li>reads back the base placement: when it was moved outside the editor (it differs from what this class last
 *       wrote or saw), the model takes the move and the {@link DriftListener} hears about it.
 * </ol>
 * The editor changing the model is not drift: the placement still equals the last state this class saw, so the model
 * wins and the placement follows. The first time a placement is met (after a join) the model wins for project
 * objects, and the placement wins for Loose objects (Litematica is their source of truth).
 *
 * <p>Arrays on assemblies (local or linked) repeat every part for each cell (size = the union of the parts' boxes, see
 * {@link AssemblyBounds}); the placements of those cells live in the same runtime map as linked parts, keyed
 * {@code instanceId:cell:partId}, and are not saved. Linked assemblies: the parts come from the {@code .lasm} (cached by modification time); the placements of a part
 * are kept in a runtime map under {@code Ids.derived(instanceId + ":" + partId)} and never saved. Linked parts are
 * read only, so a move outside the editor is put back instead of adopted.
 */
public final class ReconcileCore {
    /** Most placements created in one call. */
    public static final int BUDGET = 64;
    /** Ticks between a creation and the chunk rebuild of its batch. */
    public static final int REBUILD_DELAY = 20;
    private static final int SUBREGION_EVERY = 5;

    public record Result(int created, int updated, int removed, int adopted, int deferred) {}

    /** The placement ids of one object's cells and the identity its derived files are keyed by. */
    private record Slot(Map<String, String> ids, UUID base) {}

    /**
     * One placed copy of an object as the last pass saw it, for derived files (masks) and Syncmatica: {@code slot}
     * identifies the copy ({@link #slotOf}), {@code ids} is the live cell-to-placement map of its object.
     */
    public record CellRef(UUID slot, ObjectNode object, Path base, String cell, Transform world, Node owner, ProjectNode project,
                          Map<String, String> ids) {
        /** The Litematica placement of this copy, or null when it is not created yet. */
        public String placementId() {
            return ids.get(cell);
        }
    }

    private final PlacementPort port;
    private final SizeSource sizes;
    private final LasmCache lasms;
    private final Path looseBase;
    private DriftListener listener = DriftListener.NONE;
    private int budget = BUDGET;
    /** Local view (runtime only, never saved): the owners that stay visible; null = everything. */
    private Set<UUID> localOnly;
    /** Derived files (trims and Booleans): which file the base cell of an object uses right now. */
    private DerivedPort derived = slot -> null;
    /** Every placed copy of the last pass (see {@link CellRef}). */
    private List<CellRef> cellRefs = List.of();
    /** Where the derived files of Loose objects live (a folder with {@code .cache/derived}); null = none. */
    private Path looseCache;
    /** Base placements this class hid because their object is a wire cutter (so un-wiring shows them again). */
    private final Set<String> wireHidden = new HashSet<>();

    /** Base placement id to the transform this class last wrote or saw. */
    private final Map<String, Transform> lastSeen = new HashMap<>();
    private Set<String> prevModelIds = new HashSet<>();
    private final Map<UUID, Map<String, String>> linkedIds = new HashMap<>();
    private final Set<String> batch = new HashSet<>();
    private int batchAt;
    private final TreeSet<Integer> fullRebuildAt = new TreeSet<>();

    /** @param looseBase the folder relative file paths of Loose objects are resolved against (may be null) */
    public ReconcileCore(PlacementPort port, SizeSource sizes, LasmCache lasms, Path looseBase) {
        this.port = port;
        this.sizes = sizes;
        this.lasms = lasms;
        this.looseBase = looseBase;
    }

    public void setListener(DriftListener l) {
        this.listener = l == null ? DriftListener.NONE : l;
    }

    /**
     * Local view: only these owners (ids of objects or assemblies, as {@code Scene.ownerOf} gives them) stay
     * placed; the others in projects are treated as hidden until this is cleared with null. Loose objects are not
     * affected (their placement is Litematica's own, and removing it would mark them missing).
     */
    public void setLocalView(Set<UUID> owners) {
        localOnly = owners == null ? null : Set.copyOf(owners);
    }

    public void setDerived(DerivedPort d) {
        this.derived = d == null ? slot -> null : d;
    }

    /** The copies the last pass placed (visible ones only), in walk order: base cell first for every object. */
    public List<CellRef> cellRefs() {
        return cellRefs;
    }

    /** The folder that holds {@code .cache/derived} for Loose objects, or null. */
    public Path looseCache() {
        return looseCache;
    }

    public void setLooseCache(Path dir) {
        this.looseCache = dir;
    }

    /** The key of a copy for {@link DerivedPort}: the object id for the base cell of a model object, else derived from it. */
    static UUID slotOf(UUID base, String cell) {
        return ObjectNode.BASE_CELL.equals(cell) ? base : Ids.derived(base + "#" + cell);
    }

    /** Sizes and linked parts with this reconciler's rules (for planning masks). */
    public AssemblyBounds.Env boundsEnv(Scene scene) {
        return new CoreEnv(scene);
    }

    /** The absolute file of an object, whose files are relative to {@code base} (null: the model's rule), or null. */
    public Path fileOf(Scene scene, ObjectNode o, Path base) {
        return base != null ? inside(base, o.file()) : resolve(scene.projectOf(o), o.file());
    }

    /** The absolute folder of a project, or null for Loose. */
    public Path projectDir(ProjectNode p) {
        return projectBase(p);
    }

    public void setBudget(int budget) {
        this.budget = Math.max(1, budget);
    }

    /** Forgets the parts read from {@code .lasm} files, so every linked assembly reads its file again (Reload). */
    public void reloadLinks() {
        lasms.clear();
    }

    /** Hash ids of the restored runtime placements still to be claimed by the first pass (the rest are leftovers). */
    private Set<String> restored = new HashSet<>();

    /**
     * The runtime placements (parts of linked assemblies and of array cells): key to cell to hash id, empty entries
     * left out. The caller saves it so a restart can {@link #restoreRuntime} it.
     */
    public Map<String, Map<String, String>> runtimeIds() {
        Map<String, Map<String, String>> out = new java.util.TreeMap<>();
        for (var e : linkedIds.entrySet()) if (!e.getValue().isEmpty()) out.put(e.getKey().toString(), new java.util.TreeMap<>(e.getValue()));
        return out;
    }

    /**
     * Gives back the runtime placements of an earlier session (call after {@link #reset}, before the first
     * {@link #reconcile}). The first pass adopts each one whose key is wanted again, so nothing is recreated, and
     * removes those whose key is not wanted any more. Only ids listed here are ever touched.
     */
    public void restoreRuntime(Map<String, Map<String, String>> saved) {
        for (var e : saved.entrySet()) {
            UUID key;
            try {
                key = UUID.fromString(e.getKey());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Map<String, String> m = linkedIds.computeIfAbsent(key, k -> new LinkedHashMap<>());
            m.putAll(e.getValue());
            restored.addAll(e.getValue().values());
        }
    }

    /** Forgets everything remembered between calls (a new world). Placements are left alone. */
    public void reset() {
        lastSeen.clear();
        prevModelIds = new HashSet<>();
        linkedIds.clear();
        restored = new HashSet<>();
        batch.clear();
        batchAt = 0;
        fullRebuildAt.clear();
        lasms.clear();
    }

    /** Marks all managed placements for a chunk rebuild at these ticks (after a join, Litematica needs it). */
    public void scheduleFullRebuild(int... ticks) {
        for (int t : ticks) fullRebuildAt.add(t);
    }

    /** Removes the placements of a project that is being closed (the ids of its linked parts go on the next call). */
    public void release(ProjectNode p) {
        for (Node n : p.subtree()) {
            if (!(n instanceof ObjectNode o)) continue;
            for (String id : o.placementIds().values()) {
                port.remove(id);
                lastSeen.remove(id);
            }
        }
    }

    private final class Run {
        final Scene scene;
        final int tick;
        final Set<String> existing;
        final Set<String> modelIds = new HashSet<>();
        final Set<String> claimed = new HashSet<>();
        final List<CellRef> cells = new ArrayList<>();
        int created, updated, removed, adopted, deferred;

        Run(Scene scene, int tick, Set<String> existing) {
            this.scene = scene;
            this.tick = tick;
            this.existing = existing;
        }
    }

    /**
     * One pass.
     *
     * @param world the world key of the player now; a project of another world or dimension has its placements removed
     * @param dim   the dimension of the player now
     * @param tick  a counter that grows by one per call (for the delayed rebuilds)
     */
    public Result reconcile(Scene scene, String world, String dim, int tick) {
        Run r = new Run(scene, tick, new HashSet<>(port.allHashIds()));
        for (ProjectNode p : new ArrayList<>(scene.projects())) {
            boolean here = p.world().equals(world) && p.dim().equals(dim);
            visit(p, !here, null, r);
        }
        visit(scene.loose(), false, null, r);

        // placements of nodes that left the model (deleted, project closed)
        for (String id : prevModelIds) {
            if (!r.modelIds.contains(id) && r.existing.remove(id)) {
                port.remove(id);
                lastSeen.remove(id);
                r.removed++;
            }
        }
        prevModelIds = r.modelIds;
        cellRefs = r.cells;
        if (!restored.isEmpty()) {
            for (String id : restored) {
                if (!r.modelIds.contains(id) && r.existing.remove(id)) {
                    port.remove(id);
                    lastSeen.remove(id);
                    r.removed++;
                }
            }
            linkedIds.values().forEach(m -> m.values().removeIf(id -> restored.contains(id) && !r.modelIds.contains(id)));
            linkedIds.values().removeIf(Map::isEmpty);
            restored = new HashSet<>();
        }

        if (batchAt != 0 && tick >= batchAt) {
            for (String id : batch) if (r.existing.contains(id)) port.markRebuild(id);
            batch.clear();
            batchAt = 0;
        }
        if (!fullRebuildAt.isEmpty() && fullRebuildAt.first() <= tick) {
            while (!fullRebuildAt.isEmpty() && fullRebuildAt.first() <= tick) fullRebuildAt.pollFirst();
            for (String id : r.modelIds) if (r.existing.contains(id)) port.markRebuild(id);
        }
        return new Result(r.created, r.updated, r.removed, r.adopted, r.deferred);
    }

    // ------------------------------------------------------------------------------------------ the walk

    private void visit(Node n, boolean off, ProjectNode proj, Run r) {
        boolean o = off || n.hidden();
        if (n instanceof ProjectNode p) proj = p;
        if (!o && localOnly != null && proj != null && (n instanceof ObjectNode || n instanceof AssemblyNode)
                && !localOnly.contains(r.scene.ownerOf(n).id())) o = true;
        if (n instanceof ObjectNode ob) {
            object(new Slot(ob.placementIds(), ob.id()), ob, o, r.scene.worldTransform(ob), proj, r, false, null, null);
        } else if (n instanceof AssemblyNode a) {
            assembly(a, o, proj, r);
        } else {
            for (Node c : new ArrayList<>(n.children())) visit(c, o, proj, r);
        }
    }

    /**
     * An assembly in the model, local or linked. Its base cell is the assembly itself: a local assembly's parts are
     * model nodes with their own placement ids; a linked one's parts come from the {@code .lasm}. Every array cell
     * repeats all parts at the cell's transform; those placements are runtime-only (see the class comment).
     */
    private void assembly(AssemblyNode a, boolean off, ProjectNode proj, Run r) {
        Transform aw = r.scene.worldTransform(a);
        Node top = r.scene.ownerOf(a);
        Path base = projectBase(proj);
        AssemblyBounds.Env env = new CoreEnv(r.scene);
        AssemblyBounds.Linked parts = AssemblyBounds.partsOf(a, base, env);
        Map<String, Vec3i> cells = Map.of();
        if (!off && parts != null && AssemblyBounds.hasEnabledArray(a)) cells = AssemblyBounds.frame(a, base, env).cells();
        if (a.isLinked()) {
            if (parts != null) for (Node p : parts.parts()) visitRuntime(p, off, aw, a.id(), "", parts.base(), proj, r, 0, env, top);
        } else {
            for (Node c : new ArrayList<>(a.children())) visit(c, off, proj, r);
        }
        if (parts == null) return;
        for (var cell : cells.entrySet()) {
            Transform cw = new Transform(aw.apply(cell.getValue()), aw.rot(), aw.mirror());
            for (Node p : new ArrayList<>(parts.parts())) visitRuntime(p, off, cw, a.id(), cell.getKey(), parts.base(), proj, r, 0, env, top);
        }
    }

    /**
     * A part whose placements are not stored in the model (a part of a linked assembly, or of an array cell). Its
     * placement ids live in {@link #linkedIds} under {@code Ids.derived(instanceId + ":" + cell + ":" + partId)}
     * (without the cell part for the base cell, as before).
     */
    private void visitRuntime(Node node, boolean off, Transform parentWorld, UUID instanceId, String cell, Path base, ProjectNode proj, Run r,
                              int depth, AssemblyBounds.Env env, Node top) {
        boolean o = off || node.hidden();
        Transform w = parentWorld.then(node.localTransform());
        UUID key = Ids.derived(cell.isEmpty() ? instanceId + ":" + node.id() : instanceId + ":" + cell + ":" + node.id());
        if (node instanceof ObjectNode ob) {
            Slot s = new Slot(linkedIds.computeIfAbsent(key, k -> new LinkedHashMap<>()), key);
            object(s, ob, o, w, proj, r, true, base, top);
        } else if (node instanceof AssemblyNode a) {
            if (depth >= AssemblyBounds.MAX_DEPTH) return;
            AssemblyBounds.Linked parts = AssemblyBounds.partsOf(a, base, env);
            if (parts == null) return;
            Map<String, Vec3i> cells = o || !AssemblyBounds.hasEnabledArray(a) ? Map.of() : AssemblyBounds.frame(a, base, env).cells();
            for (Node p : parts.parts()) visitRuntime(p, o, w, key, "", parts.base(), proj, r, depth + 1, env, top);
            for (var c : cells.entrySet()) {
                Transform cw = new Transform(w.apply(c.getValue()), w.rot(), w.mirror());
                for (Node p : parts.parts()) visitRuntime(p, o, cw, key, c.getKey(), parts.base(), proj, r, depth + 1, env, top);
            }
        } else {
            for (Node c : node.children()) visitRuntime(c, o, w, instanceId, cell, base, proj, r, depth, env, top);
        }
    }

    /** What files are relative to for the nodes of a project (null for Loose: its own rule in {@link #resolve}). */
    private Path projectBase(ProjectNode proj) {
        if (proj == null || proj.path().isEmpty()) return null;
        return (looseBase != null ? looseBase.resolve(proj.path()) : Path.of(proj.path())).toAbsolutePath().normalize();
    }

    /** Sizes and linked parts for {@link AssemblyBounds}, with the rules of this reconciler. */
    private final class CoreEnv implements AssemblyBounds.Env {
        private final Scene scene;

        CoreEnv(Scene scene) {
            this.scene = scene;
        }

        @Override
        public Vec3i size(ObjectNode o, Path base) {
            Path f = base != null ? inside(base, o.file()) : resolve(scene.projectOf(o), o.file());
            return f == null ? null : sizes.size(f);
        }

        @Override
        public AssemblyBounds.Linked linked(AssemblyNode a, Path base) {
            Path from = base != null ? base : projectBase(scene.projectOf(a));
            if (from == null) return null;
            Path target = LinkPaths.target(from, ((AssemblyNode.Link) a.source()).path());
            List<Node> parts = lasms.parts(target);
            return parts == null ? null : new AssemblyBounds.Linked(parts, LinkPaths.baseOf(from, target));
        }
    }

    private static Path inside(Path base, String file) {
        if (file == null || file.isEmpty()) return null;
        try {
            return ProjectLayout.resolveInside(base, file);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------ one object

    private void object(Slot s, ObjectNode ob, boolean off, Transform world, ProjectNode proj, Run r, boolean linked, Path base, Node top) {
        Map<String, String> ids = s.ids;
        r.modelIds.addAll(ids.values());
        if (off) {
            for (String id : ids.values()) {
                if (r.existing.remove(id)) {
                    port.remove(id);
                    lastSeen.remove(id);
                    r.removed++;
                }
            }
            return;
        }
        Path file = base != null ? inside(base, ob.file()) : resolve(proj, ob.file());
        Vec3i size = file == null ? null : sizes.size(file);
        if (size == null) return;   // unreadable: leave its placements as they are

        String baseId = ids.get(ObjectNode.BASE_CELL);
        PlacementState bs = baseId != null && r.existing.contains(baseId) ? port.read(baseId) : null;
        if (!linked && proj == null && baseId != null && bs == null) {
            ob.setMissing(true);   // a Loose object whose placement is not in Litematica: keep the node, stop reconciling it
            return;
        }
        ob.setMissing(false);

        // drift: the base placement was moved outside the editor
        if (bs != null && !linked) {
            Transform actual = bs.transform(), seen = lastSeen.get(baseId);
            boolean external = seen == null ? proj == null : !actual.equals(seen);
            if (external && !actual.equals(world)) {
                Node parent = ob.parent();
                Transform pw = parent == null ? Transform.IDENTITY : r.scene.worldTransform(parent);
                Transform local = TransformMath.inverse(pw).then(actual);
                Transform before = ob.transform();
                ob.setTransform(local);
                world = actual;
                r.adopted++;
                listener.onExternalChange(new ExternalChange(ob, before, local));
            }
        }

        Map<String, Transform> want = new LinkedHashMap<>();
        want.put(ObjectNode.BASE_CELL, world);
        List<ArrayModifier> arrays = new ArrayList<>();
        for (Modifier m : ob.mods()) if (m instanceof ArrayModifier am) arrays.add(am);
        for (var c : ArrayCells.cells(arrays, size).entrySet()) {
            want.put(c.getKey(), new Transform(world.apply(c.getValue()), world.rot(), world.mirror()));
        }

        // cells that are no longer wanted
        for (var it = ids.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (want.containsKey(e.getKey())) continue;
            if (r.existing.remove(e.getValue())) {
                port.remove(e.getValue());
                lastSeen.remove(e.getValue());
                r.removed++;
            }
            it.remove();
        }

        boolean enabled = bs == null || bs.enabled();
        boolean render = !ob.wire() && (bs == null || bs.render() || (baseId != null && wireHidden.contains(baseId)));
        Node owner = top != null ? top : r.scene.ownerOf(ob);
        for (var e : want.entrySet()) {
            String key = e.getKey();
            Transform t = e.getValue();
            boolean isBase = key.equals(ObjectNode.BASE_CELL);
            String id = ids.get(key);
            if (id != null && !r.claimed.add(id)) id = null;   // another node already owns this placement
            PlacementState st = id == null || !r.existing.contains(id) ? null : isBase ? bs : port.read(id);
            if (!ob.wire()) r.cells.add(new CellRef(slotOf(s.base(), key), ob, base, key, t, owner, proj, ids));
            Path cellFile = ob.wire() ? file : derivedOr(slotOf(s.base(), key), file);
            boolean wrongFile = st != null && !samePath(st.file(), cellFile);
            if (st == null || wrongFile) {
                if (r.created >= budget) {
                    r.deferred++;
                    continue;
                }
                if (wrongFile) {
                    port.remove(id);
                    r.existing.remove(id);
                    lastSeen.remove(id);
                }
                String nid = port.create(cellFile, t.pos(), t.rot(), t.mirror(), isBase ? ob.name() : ob.name() + " #" + key, enabled, render, id);
                if (nid == null) continue;
                ids.put(key, nid);
                r.claimed.add(nid);
                r.existing.add(nid);
                r.modelIds.add(nid);
                r.created++;
                if (isBase) lastSeen.put(nid, t);
                if (batchAt == 0 || batchAt <= r.tick) {
                    batch.clear();
                    batchAt = r.tick + REBUILD_DELAY;
                }
                batch.add(nid);
                continue;
            }
            if (isBase && !linked) {
                // a wire cutter keeps its own placement hidden; un-wiring shows it again (only if this class hid it)
                if (ob.wire() && st.render()) {
                    port.update(id, st.origin(), st.rot(), st.mirror(), st.enabled(), false);
                    wireHidden.add(id);
                    PlacementState again = port.read(id);
                    if (again != null) st = again;
                } else if (!ob.wire() && !st.render() && wireHidden.remove(id)) {
                    port.update(id, st.origin(), st.rot(), st.mirror(), st.enabled(), true);
                    PlacementState again = port.read(id);
                    if (again != null) st = again;
                }
            }
            boolean tdiff = !st.transform().equals(t);
            boolean sdiff = !isBase && (st.enabled() != enabled || st.render() != render);
            if (tdiff || sdiff) {
                port.update(id, t.pos(), t.rot(), t.mirror(), isBase ? st.enabled() : enabled, isBase ? st.render() : render);
                r.updated++;
            }
            if (isBase) lastSeen.put(id, t);
            else if (r.tick % SUBREGION_EVERY == 0 && baseId != null && r.existing.contains(ids.get(ObjectNode.BASE_CELL))) {
                port.copySubRegions(ids.get(ObjectNode.BASE_CELL), id);
            }
        }
    }

    private Path derivedOr(UUID slot, Path original) {
        Path d = derived.fileFor(slot);
        return d != null ? d : original;
    }

    // ------------------------------------------------------------------------------------------ files

    /**
     * A file path of the model as an absolute path: relative to the project folder inside a project (an absolute
     * path is refused there), absolute or relative to the schematics folder for Loose objects. Null when unusable.
     */
    Path resolve(ProjectNode proj, String file) {
        if (file == null || file.isEmpty()) return null;
        try {
            if (proj != null) {
                return proj.path().isEmpty() ? null : ProjectLayout.resolveInside((looseBase != null ? looseBase.resolve(proj.path()).normalize() : Path.of(proj.path())), file);
            }
            Path p = Path.of(file);
            if (p.isAbsolute()) return p.normalize();
            return looseBase == null ? null : looseBase.resolve(file).normalize();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean samePath(Path a, Path b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
