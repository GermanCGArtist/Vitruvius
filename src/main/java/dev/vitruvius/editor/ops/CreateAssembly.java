package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.FrontOrder;
import dev.vitruvius.editor.core.HasFront;
import dev.vitruvius.editor.core.Ids;
import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Ctrl+G: moves the selected nodes under a new local assembly placed at the selection's min corner (the smallest
 * x, y and z of their positions, unless the caller passes an {@code anchor}). Nodes keep their place in the world.
 * The assembly goes where the first selected node was, in the nearest parent that holds them all, and takes the
 * best (lowest) front number of the objects it swallowed.
 *
 * <p>When the selection is a single folder, the assembly replaces the folder and takes its content ("Create
 * assembly from folder"). Refused: nothing selected, nodes of different projects, parts of a linked assembly.
 */
public final class CreateAssembly extends BaseCommand {
    private final List<UUID> ids;
    private final String name;
    private final Vec3i anchor;
    private final UUID newId = Ids.newId();

    /** @param anchor world position of the assembly's origin, or null for the min corner of the selection */
    public CreateAssembly(Collection<UUID> ids, String name, Vec3i anchor) {
        super("Create assembly");
        this.ids = new ArrayList<>(ids);
        this.name = name;
        this.anchor = anchor;
    }

    /** The id the new assembly gets (stable across redo). */
    public UUID assemblyId() {
        return newId;
    }

    private static boolean singleFolder(List<Node> nodes) {
        return nodes.size() == 1 && nodes.get(0) instanceof FolderNode;
    }

    /** The nearest node above all of them (the folder's own parent in the folder case). */
    private static Node commonParent(List<Node> nodes) {
        if (singleFolder(nodes)) return nodes.get(0).parent();
        for (Node x = nodes.get(0).parent(); x != null; x = x.parent()) {
            boolean all = true;
            for (Node n : nodes) if (!Nodes.isSelfOrAncestor(x, n)) all = false;
            if (all) return x;
        }
        return null;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Nodes.Pick p = Nodes.resolve(c.scene(), ids);
        if (p.refusal() != null) return p.refusal();
        List<Node> nodes = Nodes.topMost(p.nodes());
        for (Node n : nodes) {
            if (n instanceof ProjectNode || n instanceof LooseNode) return new Refusal("A project or Loose cannot go into an assembly");
            if (Nodes.readOnly(n) != null) return Nodes.readOnly(n);
        }
        Node parent = commonParent(nodes);
        if (parent == null) return new Refusal("The selection spans several projects: an assembly belongs to one");
        if (singleFolder(nodes) && nodes.get(0).children().isEmpty()) return new Refusal("The folder is empty");
        if (Nodes.rootOf(parent) == null) return new Refusal("The selection is not in a project");
        return null;
    }

    @Override
    protected void doApply() {
        List<Node> nodes = Nodes.topMost(Nodes.resolve(ctx.scene(), ids).nodes());
        Node parent = commonParent(nodes);
        boolean fromFolder = singleFolder(nodes);
        FolderNode folder = fromFolder ? (FolderNode) nodes.get(0) : null;
        List<Node> content = fromFolder ? new ArrayList<>(folder.children()) : nodes;
        String asmName = name != null && !name.isBlank() ? name.strip() : fromFolder ? folder.name() : "Assembly";
        setLabel("Create assembly " + asmName);

        // where it goes: the first selected node's place, or the folder's
        int index = Integer.MAX_VALUE;
        for (Node n : nodes) {
            Node child = n;
            while (child.parent() != parent) child = child.parent();
            index = Math.min(index, Nodes.indexIn(parent, child));
        }
        Vec3i at = anchor;
        List<Node> bearers = new ArrayList<>();
        for (Node n : content) Nodes.bearers(n, bearers);
        if (at == null) {
            int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            for (Node b : bearers) {
                Vec3i w = ctx.scene().worldTransform(b).pos();
                for (int k = 0; k < 3; k++) min[k] = Math.min(min[k], w.get(k));
            }
            at = bearers.isEmpty() ? Vec3i.ZERO : new Vec3i(min[0], min[1], min[2]);
        }
        int front = 0;
        for (Node n : content) {
            for (Node s : n.subtree()) {
                if (s instanceof HasFront h && ctx.scene().ownerOf(s) == s && h.front() > 0 && (front == 0 || h.front() < front)) front = h.front();
            }
        }
        if (front == 0) front = FrontOrder.nextBack(ctx.scene().frontOwnersInWorld(ctx.worldOf(parent)));

        remember(parent);
        AssemblyNode asm = new AssemblyNode(newId, fromFolder && (name == null || name.isBlank()) ? asmName : Nodes.uniqueSiblingName(parent, asmName));
        asm.setTransform(new Transform(Nodes.inverse(ctx.scene().worldTransform(parent)).apply(at), Rot.NONE, Mirror.NONE));
        asm.setFront(front);
        if (fromFolder) asm.setHidden(folder.hidden());
        parent.addChild(Math.min(index, parent.children().size()), asm);
        Nodes.reattach(ctx.scene(), content, asm, -1, this::remember);
        if (fromFolder) {
            remember(folder);
            parent.removeChild(folder);
        }
        touch(asm);
    }
}
