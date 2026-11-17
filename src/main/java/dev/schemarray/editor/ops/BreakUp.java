package dev.schemarray.editor.ops;

import dev.schemarray.editor.core.ArrayCells;
import dev.schemarray.editor.core.ArrayModifier;
import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.FolderNode;
import dev.schemarray.editor.core.Ids;
import dev.schemarray.editor.core.Modifier;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Break up in place: the assembly becomes a folder of its parts with the assembly's transform baked into them
 * ({@code assembly.then(part)}), so nothing moves. The assembly's front number goes to the parts that are now
 * things of their own, and its other modifiers (Booleans) are copied onto those parts.
 *
 * <p>When the assembly has an enabled array it is realised first: the result is a folder
 * {@code <name> (realised)} holding one such folder per cell (the original and its copies, as in
 * {@link RealiseArray}). That needs {@code size}, the assembly's bounding box; pass null when there is no array.
 *
 * <p>With {@code copyFiles} every object gets its own copy of its file (the menu's "Realise (independent copies)").
 * A linked assembly is refused: make it local first (the UI runs {@link MakeLocal} and this in one transaction).
 */
public final class BreakUp extends BaseCommand {
    private final UUID assemblyId;
    private final Vec3i size;
    private final boolean copyFiles;
    private final Map<String, UUID> folderIds = new HashMap<>();
    private final Map<String, Map<UUID, UUID>> cellIds = new HashMap<>();

    public BreakUp(UUID assemblyId, Vec3i size, boolean copyFiles) {
        super(copyFiles ? "Realise assembly" : "Break up");
        this.assemblyId = assemblyId;
        this.size = size;
        this.copyFiles = copyFiles;
    }

    public BreakUp(UUID assemblyId, Vec3i size) {
        this(assemblyId, size, false);
    }

    private static List<ArrayModifier> arrays(AssemblyNode a) {
        List<ArrayModifier> out = new ArrayList<>();
        for (Modifier m : a.mods()) if (m instanceof ArrayModifier x) out.add(x);
        return out;
    }

    private static boolean arrayed(AssemblyNode a) {
        for (ArrayModifier m : arrays(a)) if (m.enabled) return true;
        return false;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node n = c.scene().byId(assemblyId);
        if (!(n instanceof AssemblyNode a)) return new Refusal("Only an assembly can be broken up");
        if (a.isLinked()) return new Refusal(Nodes.q(a) + " is linked: make it local first, then break it up");
        Refusal ro = Nodes.readOnly(a);
        if (ro != null) return ro;
        if (a.parent() == null) return new Refusal(Nodes.q(a) + " is not in the tree");
        if (arrayed(a) && size == null) return new Refusal("The size of " + Nodes.q(a) + " is needed to realise its array");
        return null;
    }

    @Override
    protected void doApply() {
        AssemblyNode a = (AssemblyNode) ctx.scene().byId(assemblyId);
        Node parent = a.parent();
        remember(parent);
        remember(a);
        setLabel((copyFiles ? "Realise " : "Break up ") + a.name());
        int index = Nodes.indexIn(parent, a);
        List<Modifier> others = new ArrayList<>();
        for (Modifier m : a.mods()) if (!(m instanceof ArrayModifier)) others.add(m.copy());
        Map<String, Vec3i> cells = arrayed(a) ? ArrayCells.cells(arrays(a), size) : Map.of();
        String baseName = a.name();
        Transform base = a.transform();
        int front = a.front();
        boolean hidden = a.hidden();

        // copies first: they are deep copies of the parts while these are still in the assembly
        List<AssemblyNode> copyNodes = new ArrayList<>();
        List<String> copyKeys = new ArrayList<>();
        List<Transform> copyTransforms = new ArrayList<>();
        for (Map.Entry<String, Vec3i> cell : cells.entrySet()) {
            Map<String, String> cache = new HashMap<>();
            Nodes.Copier copier = new Nodes.Copier(cellIds.computeIfAbsent(cell.getKey(), k -> new HashMap<>()),
                    o -> copyFiles ? FileOps.copyBeside(ctx, journal(), a, o.file(), cache) : o.file(), false, new HashMap<>(), this::remember);
            AssemblyNode copy = (AssemblyNode) copier.copy(a);
            copier.remapBooleans(copy);
            copyNodes.add(copy);
            copyKeys.add(cell.getKey());
            copyTransforms.add(base.withPos(base.pos().add(base.applyVector(cell.getValue()))));
        }
        List<Node> folders = new ArrayList<>();
        folders.add(breakOne(a, "", baseName, base, others, front, hidden, true));
        for (int i = 0; i < copyNodes.size(); i++) {
            folders.add(breakOne(copyNodes.get(i), copyKeys.get(i), baseName + " " + (i + 2), copyTransforms.get(i), others, front, hidden, false));
        }
        parent.removeChild(a);
        Node result;
        if (folders.size() == 1) {
            result = folders.get(0);
        } else {
            FolderNode outer = new FolderNode(folderIds.computeIfAbsent("outer", k -> Ids.newId()), baseName + " (realised)");
            for (Node f : folders) outer.addChild(f);
            outer.setHidden(hidden);
            result = outer;
        }
        result.setName(Nodes.uniqueSiblingName(parent, result.name()));
        parent.addChild(index, result);
        touch(result);
    }

    /** Builds the folder of one instance; {@code original} says the parts are in the scene already. */
    private FolderNode breakOne(AssemblyNode x, String key, String name, Transform t, List<Modifier> others, int front, boolean hidden, boolean original) {
        FolderNode f = new FolderNode(folderIds.computeIfAbsent(key, k -> Ids.newId()), name);
        f.setHidden(hidden);
        if (original && copyFiles) {
            Map<String, String> cache = new HashMap<>();
            for (Node part : x.children()) {
                for (Node s : part.subtree()) {
                    if (s instanceof ObjectNode o) {
                        remember(o);
                        o.setFile(FileOps.copyBeside(ctx, journal(), o, o.file(), cache));
                    }
                }
            }
        }
        List<Node> top = new ArrayList<>();
        for (Node part : new ArrayList<>(x.children())) bake(part, t, top, original);
        for (Node b : top) {
            if (original) remember(b);
            Nodes.setFront(b, front);
            List<Modifier> mods = Nodes.mods(b);
            if (mods != null) for (Modifier m : others) mods.add(m.copy());
        }
        for (Node part : new ArrayList<>(x.children())) f.addChild(part);
        return f;
    }

    /** Bakes {@code t} into the top-most transform-bearing nodes at or below {@code n}. */
    private void bake(Node n, Transform t, List<Node> top, boolean original) {
        if (Nodes.hasTransform(n)) {
            if (original) remember(n);
            Nodes.setTransform(n, t.then(n.localTransform()));
            top.add(n);
        } else {
            for (Node c : n.children()) bake(c, t, top, original);
        }
    }
}
