package dev.schemarray.editor.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The extent of an assembly: the world boxes of every part (objects with their own arrays, nested assemblies with
 * theirs, linked assemblies read from their {@code .lasm}). Pure: sizes and linked parts come from an {@link Env}.
 *
 * <p>Boxes are given in the frame of the node's parent. An assembly's own array uses the union of its parts'
 * boxes as its size ({@link #frame}), the same size the reconciler and the overlay use, so a cell is where the
 * reconciler places it. Hidden parts count (the size must not change when a part is hidden).
 *
 * <p>{@code base} says what the files of the parts are relative to (the project folder, or the folder of the
 * linked {@code .lasm}'s owner); null means "the model's own rule" and is only given for nodes in the scene.
 */
public final class AssemblyBounds {
    private AssemblyBounds() {}

    /** Deepest nesting of linked assemblies followed (a safeguard against a loop in the files). */
    public static final int MAX_DEPTH = 12;

    /** The parts of a linked assembly and the folder their files are relative to. */
    public record Linked(List<Node> parts, Path base) {}

    public interface Env {
        /** The enclosing size of the object's file, or null when it cannot be read. */
        Vec3i size(ObjectNode o, Path base);

        /** The parts a linked assembly stands for, or null when its file is missing or unreadable. */
        Linked linked(AssemblyNode a, Path base);
    }

    /**
     * One assembly seen from inside: {@code inner} are the boxes of its parts in its own frame, {@code union} the
     * box around them (null when nothing has a size), {@code cells} the copies of its enabled array (cell key to
     * the offset in its own frame; empty without an array).
     */
    public record Frame(List<Box> inner, Box union, Map<String, Vec3i> cells) {
        /** The size an array of this assembly works on, or null when nothing has a size. */
        public Vec3i size() {
            return union == null ? null : union.size();
        }
    }

    public static List<ArrayModifier> arrays(List<Modifier> mods) {
        List<ArrayModifier> out = new ArrayList<>();
        for (Modifier m : mods) if (m instanceof ArrayModifier a) out.add(a);
        return out;
    }

    public static boolean hasEnabledArray(AssemblyNode a) {
        for (Modifier m : a.mods()) if (m instanceof ArrayModifier am && am.enabled) return true;
        return false;
    }

    /** The assembly's parts and the base their files are relative to; null for a linked assembly that cannot be read. */
    public static Linked partsOf(AssemblyNode a, Path base, Env env) {
        if (a.isLinked()) return env.linked(a, base);
        return new Linked(a.children(), base);
    }

    public static Frame frame(AssemblyNode a, Path base, Env env) {
        return frame(a, base, env, 0);
    }

    private static Frame frame(AssemblyNode a, Path base, Env env, int depth) {
        Linked parts = depth > MAX_DEPTH ? null : partsOf(a, base, env);
        List<Box> inner = new ArrayList<>();
        if (parts != null) for (Node p : parts.parts()) boxesIn(p, parts.base(), env, depth + 1, inner);
        Box u = null;
        for (Box b : inner) u = u == null ? b : u.union(b);
        Map<String, Vec3i> cells = Map.of();
        if (u != null && hasEnabledArray(a)) cells = ArrayCells.cells(arrays(a.mods()), u.size());
        return new Frame(inner, u, cells);
    }

    /** All boxes of one node (and what it holds) in its parent's frame, including array copies. */
    public static List<Box> boxesIn(Node n, Path base, Env env) {
        List<Box> out = new ArrayList<>();
        boxesIn(n, base, env, 0, out);
        return out;
    }

    private static void boxesIn(Node n, Path base, Env env, int depth, List<Box> out) {
        if (n instanceof ObjectNode o) {
            Vec3i size = env.size(o, base);
            if (size == null || size.x() <= 0 || size.y() <= 0 || size.z() <= 0) return;
            Transform t = o.transform();
            Box box = Box.ofSize(size);
            out.add(box.transformed(t));
            for (Vec3i off : ArrayCells.cells(arrays(o.mods()), size).values()) {
                out.add(box.transformed(new Transform(t.apply(off), t.rot(), t.mirror())));
            }
        } else if (n instanceof AssemblyNode a) {
            Frame f = frame(a, base, env, depth);
            Transform t = a.transform();
            for (Box b : f.inner()) out.add(b.transformed(t));
            for (Vec3i off : f.cells().values()) {
                Transform ct = new Transform(t.apply(off), t.rot(), t.mirror());
                for (Box b : f.inner()) out.add(b.transformed(ct));
            }
        } else if (n instanceof FolderNode) {
            for (Node c : n.children()) boxesIn(c, base, env, depth, out);
        }
    }

    /**
     * The world boxes of a top-level assembly per cell: the base cell first, then one entry per array copy (each
     * the union of that copy's parts), given the assembly's world transform.
     */
    public static List<Box> cellUnions(Frame f, Transform world) {
        List<Box> out = new ArrayList<>();
        if (f.union() == null) return out;
        out.add(f.union().transformed(world));
        for (Vec3i off : f.cells().values()) {
            out.add(f.union().transformed(new Transform(world.apply(off), world.rot(), world.mirror())));
        }
        return out;
    }

    /** The union of boxes, or null for none. */
    public static Box union(List<Box> boxes) {
        Box u = null;
        for (Box b : boxes) u = u == null ? b : u.union(b);
        return u;
    }
}
