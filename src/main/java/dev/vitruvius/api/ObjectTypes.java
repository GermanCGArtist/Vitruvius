package dev.vitruvius.api;

import com.google.gson.JsonObject;
import dev.vitruvius.editor.core.ArrayCells;
import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.AssemblyBounds;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.Modifier;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** The registry of {@link SceneObjectType}s and the views of their objects. No Minecraft types, so it is unit-tested. */
public final class ObjectTypes {
    private ObjectTypes() {}

    /** One placed copy of an external object (including copies produced by ancestor assembly arrays and linked assemblies). */
    public record Placed(UUID id, UUID ownerId, String typeId, String name, String cell, Transform world, JsonObject data, boolean hidden) {}

    private static final Map<String, SceneObjectType> TYPES = Collections.synchronizedMap(new LinkedHashMap<>());
    private static volatile Supplier<Scene> sceneSource = () -> null;
    private static volatile Function<Scene, AssemblyBounds.Env> envSource = s -> null;

    /** Registers a type; a second one with the same id replaces the first. */
    public static void register(SceneObjectType type) {
        if (type == null || type.id() == null || type.id().isBlank()) return;
        TYPES.put(type.id(), type);
    }

    public static SceneObjectType get(String typeId) {
        return typeId == null ? null : TYPES.get(typeId);
    }

    public static Collection<SceneObjectType> all() {
        synchronized (TYPES) {
            return List.copyOf(TYPES.values());
        }
    }

    /** Where {@link #viewsOf} finds the scene (set by the editor). */
    public static void setSceneSource(Supplier<Scene> source) {
        sceneSource = source == null ? () -> null : source;
    }

    /** Where {@link #viewsOf} resolves assembly sizes and linked assemblies (set by the reconciler). */
    public static void setEnvSource(Function<Scene, AssemblyBounds.Env> source) {
        envSource = source == null ? s -> null : source;
    }

    /** Test hook: forgets every registered type. */
    public static void clear() {
        TYPES.clear();
    }

    /** The objects of a type in the open scene (every array copy, including inside arrayed/linked assemblies), or empty. */
    public static List<SceneObjectView> viewsOf(String typeId) {
        Scene s = sceneSource.get();
        return s == null ? List.of() : viewsIn(s, typeId, envSource.apply(s));
    }

    /** Like {@link #viewsOf}, but only the copies of objects that are one of {@code roots} or inside one of them. */
    public static List<SceneObjectView> viewsUnder(String typeId, Collection<UUID> roots) {
        Scene s = sceneSource.get();
        return s == null ? List.of() : viewsUnder(s, typeId, envSource.apply(s), roots);
    }

    /** The copies of {@link #placedIn} whose object, or the scene node that owns it (a linked assembly), is or lies under one of {@code roots}. */
    public static List<SceneObjectView> viewsUnder(Scene scene, String typeId, AssemblyBounds.Env env, Collection<UUID> roots) {
        List<SceneObjectView> out = new ArrayList<>();
        if (roots == null || roots.isEmpty()) return out;
        java.util.Set<UUID> rootSet = new java.util.HashSet<>(roots);
        for (Placed p : placedIn(scene, typeId, env)) {
            if (under(scene, p.id(), rootSet) || (p.ownerId() != null && under(scene, p.ownerId(), rootSet))) {
                out.add(new SceneObjectView(p.id(), p.name(), p.cell(), p.world(), p.data(), p.hidden()));
            }
        }
        return out;
    }

    private static boolean under(Scene scene, UUID id, java.util.Set<UUID> roots) {
        for (Node n = scene.byId(id); n != null; n = n.parent()) if (roots.contains(n.id())) return true;
        return false;
    }

    /** The objects of a type in a scene: one view per placed copy (the base copy first, then the array copies). */
    public static List<SceneObjectView> viewsIn(Scene scene, String typeId) {
        return viewsIn(scene, typeId, null);
    }

    /** Same as {@link #viewsIn(Scene, String)} with an {@link AssemblyBounds.Env} for measuring assemblies and links. */
    public static List<SceneObjectView> viewsIn(Scene scene, String typeId, AssemblyBounds.Env env) {
        List<SceneObjectView> out = new ArrayList<>();
        for (Placed p : placedIn(scene, typeId, env)) {
            out.add(new SceneObjectView(p.id(), p.name(), p.cell(), p.world(), p.data(), p.hidden()));
        }
        return out;
    }

    /**
     * All placed copies of external objects in {@code scene} (matching {@code typeId}, or all types when {@code typeId}
     * is null), expanding both the object's own array modifiers and any ancestor assembly arrays / linked assemblies.
     */
    public static List<Placed> placedIn(Scene scene, String typeId, AssemblyBounds.Env env) {
        List<Placed> out = new ArrayList<>();
        if (scene == null) return out;
        for (Node root : scene.projects()) {
            collectPlaced(root, null, null, Transform.IDENTITY, "", root.hidden(), 0, typeId, env, out);
        }
        Node loose = scene.loose();
        if (loose != null) {
            collectPlaced(loose, null, null, Transform.IDENTITY, "", loose.hidden(), 0, typeId, env, out);
        }
        return out;
    }

    private static void collectPlaced(Node n, UUID topOwner, Path base, Transform parentWorld, String cellPrefix,
                                      boolean hidden, int depth, String typeId, AssemblyBounds.Env env, List<Placed> out) {
        boolean nextHidden = hidden || n.hidden();
        if (n instanceof ExternalNode x) {
            if (typeId != null && !x.typeId().equals(typeId)) return;
            UUID owner = topOwner != null ? topOwner : x.id();
            Transform world = parentWorld.then(x.transform());
            out.add(new Placed(x.id(), owner, x.typeId(), x.name(),
                    cellPrefix.isEmpty() ? "base" : cellPrefix, world, x.data().deepCopy(), nextHidden));
            for (var c : cellsOf(x).entrySet()) {
                String cell = cellPrefix.isEmpty() ? c.getKey() : cellPrefix + "/" + c.getKey();
                Transform ct = new Transform(world.apply(c.getValue()), world.rot(), world.mirror());
                out.add(new Placed(x.id(), owner, x.typeId(), x.name(), cell, ct, x.data().deepCopy(), nextHidden));
            }
        } else if (n instanceof AssemblyNode a) {
            if (depth > AssemblyBounds.MAX_DEPTH) return;
            UUID owner = topOwner != null ? topOwner : a.id();
            AssemblyBounds.Linked parts = AssemblyBounds.partsOf(a, base, env);
            if (parts == null) return;
            Transform aw = parentWorld.then(a.transform());
            for (Node child : parts.parts()) {
                collectPlaced(child, owner, parts.base(), aw, cellPrefix, nextHidden, depth + 1, typeId, env, out);
            }
            if (AssemblyBounds.hasEnabledArray(a)) {
                AssemblyBounds.Frame f = AssemblyBounds.frame(a, base, env);
                for (var c : f.cells().entrySet()) {
                    Transform cw = new Transform(aw.apply(c.getValue()), aw.rot(), aw.mirror());
                    String nextPrefix = cellPrefix.isEmpty() ? c.getKey() : cellPrefix + "/" + c.getKey();
                    for (Node child : parts.parts()) {
                        collectPlaced(child, owner, parts.base(), cw, nextPrefix, nextHidden, depth + 1, typeId, env, out);
                    }
                }
            }
        } else {
            for (Node child : n.children()) {
                collectPlaced(child, topOwner, base, parentWorld, cellPrefix, nextHidden, depth, typeId, env, out);
            }
        }
    }

    /** The array cells of an external node (offset per cell key, the base cell not included). */
    public static Map<String, Vec3i> cellsOf(ExternalNode x) {
        List<ArrayModifier> arrays = new ArrayList<>();
        for (Modifier m : x.mods()) if (m instanceof ArrayModifier am) arrays.add(am);
        if (arrays.isEmpty()) return Map.of();
        return ArrayCells.cells(arrays, sizeOf(x));
    }

    /** The local bounds of an external node: its type's, or a single block when nobody registered the type or it fails. */
    public static Box localBounds(ExternalNode x) {
        SceneObjectType t = get(x.typeId());
        if (t != null) {
            try {
                Box b = t.bounds(x.data());
                if (b != null && b.volume() > 0) return b;
            } catch (RuntimeException | LinkageError e) {
                // a broken type: the node is drawn as a block
            }
        }
        return Box.ofSize(new Vec3i(1, 1, 1));
    }

    public static Vec3i sizeOf(ExternalNode x) {
        return localBounds(x).size();
    }

    /** The world boxes of an external node: the base copy first, then one per array cell. */
    public static List<Box> worldBoxes(Scene scene, ExternalNode x) {
        Box local = localBounds(x);
        Transform world = scene.worldTransform(x);
        List<Box> out = new ArrayList<>();
        out.add(local.transformed(world));
        for (Vec3i off : cellsOf(x).values()) out.add(local.transformed(new Transform(world.apply(off), world.rot(), world.mirror())));
        return out;
    }
}
