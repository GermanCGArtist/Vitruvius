package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ProjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Moves nodes to a parent at an index (drag in the tree); with the same parent this is a reorder. Nodes keep their
 * place in the world: a node that moves into or out of a turned or moved assembly gets a new local transform.
 * {@code index} counts the target's children before the move; negative means the end. The nodes keep their order.
 *
 * <p>Refused: a node into itself or its own descendant, a part out of or into a linked assembly (read-only), a
 * node that cannot sit there (a leaf as parent, a project below anything), and a move to another project (that is
 * {@link MoveToProject}, which also deals with the files).
 */
public final class Reparent extends BaseCommand {
    private final List<UUID> ids;
    private final UUID parentId;
    private final int index;

    public Reparent(Collection<UUID> ids, UUID parentId, int index) {
        super("Move in tree");
        this.ids = new ArrayList<>(ids);
        this.parentId = parentId;
        this.index = index;
    }

    /** Puts one node at a position among its current siblings. */
    public static Command reorder(Node n, int index) {
        return new Reparent(List.of(n.id()), n.parent() == null ? null : n.parent().id(), index);
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node target = parentId == null ? null : c.scene().byId(parentId);
        if (target == null) return new Refusal("The target no longer exists");
        Nodes.Pick p = Nodes.resolve(c.scene(), ids);
        if (p.refusal() != null) return p.refusal();
        if (target instanceof AssemblyNode a && a.isLinked()) return new Refusal(Nodes.q(a) + " is a linked assembly: its parts are read-only");
        if (Nodes.readOnly(target) != null) return new Refusal(Nodes.q(target) + " is part of a linked assembly: its content is read-only");
        for (Node n : p.nodes()) {
            if (n instanceof ProjectNode) return new Refusal("A project cannot be moved inside the tree");
            if (n instanceof LooseNode) return new Refusal("Loose placements cannot be moved");
            if (Nodes.isSelfOrAncestor(n, target)) return new Refusal("Cannot put " + Nodes.q(n) + " into itself");
            if (Nodes.readOnly(n) != null) {
                return new Refusal(Nodes.q(n) + " is part of the linked assembly " + Nodes.q(Nodes.linkedAncestor(n)) + " and cannot be moved out of it");
            }
            if (!target.canContain(n)) return new Refusal(Nodes.q(target) + " cannot hold " + Nodes.q(n));
            if (Nodes.rootOf(n) != Nodes.rootOf(target)) return new Refusal("Use Move to project to move " + Nodes.q(n) + " to another project");
        }
        if (index > target.children().size()) return new Refusal("There is no position " + index + " in " + Nodes.q(target));
        return null;
    }

    @Override
    protected void doApply() {
        Node target = ctx.scene().byId(parentId);
        List<Node> nodes = Nodes.topMost(Nodes.resolve(ctx.scene(), ids).nodes());
        boolean same = true;
        for (Node n : nodes) if (n.parent() != target) same = false;
        setLabel((same ? "Reorder " : "Move ") + Nodes.names(nodes) + (same ? "" : " to " + target.name()));
        Nodes.reattach(ctx.scene(), nodes, target, index, this::remember);
    }
}
