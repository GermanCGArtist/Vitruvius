package dev.vitruvius.editor.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The cells a bake (or any export of what is placed) has to cover: every object of the given nodes, once per place
 * it appears at, with its transform in the world. Array copies of objects and assemblies, nested assemblies and
 * linked assemblies are expanded exactly as the reconciler places them. Pure: sizes and linked parts come from an
 * {@link AssemblyBounds.Env}. Hidden nodes are skipped.
 */
public final class BakeParts {
    private BakeParts() {}

    /** One placed copy of an object: the object, the cell ("" for the original), what its file is relative to, and its world transform. */
    public record Part(ObjectNode object, String cell, Path base, Transform world) {
        /** "Tower", or "Tower #1,0,0" for an array copy. */
        public String label() {
            return cell.isEmpty() ? object.name() : object.name() + " #" + cell;
        }
    }

    /**
     * @param nodes       the nodes to bake (objects, assemblies, folders; others are ignored)
     * @param parentWorld the world transform of their parent (what {@code Scene.worldTransform(parent)} gives)
     * @param base        what object files of the model are relative to, or null for "the model's rule" (see {@link AssemblyBounds})
     */
    public static List<Part> collect(List<Node> nodes, Transform parentWorld, Path base, AssemblyBounds.Env env) {
        List<Part> out = new ArrayList<>();
        for (Node n : nodes) walk(n, parentWorld, base, env, "", 0, out);
        return out;
    }

    private static void walk(Node n, Transform parentWorld, Path base, AssemblyBounds.Env env, String prefix, int depth, List<Part> out) {
        if (n.hidden() || depth > AssemblyBounds.MAX_DEPTH) return;
        if (n instanceof ObjectNode o) {
            Transform w = parentWorld.then(o.transform());
            out.add(new Part(o, prefix, base, w));
            Vec3i size = env.size(o, base);
            if (size == null) return;
            for (Map.Entry<String, Vec3i> c : ArrayCells.cells(AssemblyBounds.arrays(o.mods()), size).entrySet()) {
                out.add(new Part(o, join(prefix, c.getKey()), base, new Transform(w.apply(c.getValue()), w.rot(), w.mirror())));
            }
        } else if (n instanceof AssemblyNode a) {
            AssemblyBounds.Linked parts = AssemblyBounds.partsOf(a, base, env);
            if (parts == null) return;
            Transform aw = parentWorld.then(a.transform());
            for (Node p : parts.parts()) walk(p, aw, parts.base(), env, prefix, depth + 1, out);
            if (AssemblyBounds.hasEnabledArray(a)) {
                for (Map.Entry<String, Vec3i> c : AssemblyBounds.frame(a, base, env).cells().entrySet()) {
                    Transform cw = new Transform(aw.apply(c.getValue()), aw.rot(), aw.mirror());
                    for (Node p : parts.parts()) walk(p, cw, parts.base(), env, join(prefix, c.getKey()), depth + 1, out);
                }
            }
        } else if (n instanceof FolderNode) {
            for (Node c : n.children()) walk(c, parentWorld, base, env, prefix, depth, out);
        }
    }

    private static String join(String prefix, String cell) {
        return prefix.isEmpty() ? cell : prefix + "/" + cell;
    }
}
