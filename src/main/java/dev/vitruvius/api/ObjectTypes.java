package dev.vitruvius.api;

import dev.vitruvius.editor.core.ArrayCells;
import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.Modifier;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** The registry of {@link SceneObjectType}s and the views of their objects. No Minecraft types, so it is unit-tested. */
public final class ObjectTypes {
    private ObjectTypes() {}

    private static final Map<String, SceneObjectType> TYPES = Collections.synchronizedMap(new LinkedHashMap<>());
    private static volatile Supplier<Scene> sceneSource = () -> null;

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

    /** Test hook: forgets every registered type. */
    public static void clear() {
        TYPES.clear();
    }

    /** The objects of a type in the open scene (every array copy), or an empty list. */
    public static List<SceneObjectView> viewsOf(String typeId) {
        Scene s = sceneSource.get();
        return s == null ? List.of() : viewsIn(s, typeId);
    }

    /** The objects of a type in a scene: one view per placed copy (the base copy first, then the array copies). */
    public static List<SceneObjectView> viewsIn(Scene scene, String typeId) {
        List<SceneObjectView> out = new ArrayList<>();
        for (Node n : scene.allNodes()) {
            if (!(n instanceof ExternalNode x) || !x.typeId().equals(typeId)) continue;
            boolean hidden = false;
            for (Node a = x; a != null; a = a.parent()) if (a.hidden()) hidden = true;
            Transform world = scene.worldTransform(x);
            out.add(new SceneObjectView(x.id(), x.name(), "base", world, x.data().deepCopy(), hidden));
            for (var c : cellsOf(x).entrySet()) {
                out.add(new SceneObjectView(x.id(), x.name(), c.getKey(), new Transform(world.apply(c.getValue()), world.rot(), world.mirror()),
                        x.data().deepCopy(), hidden));
            }
        }
        return out;
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
