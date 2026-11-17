package dev.schemarray.editor.ui;

import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.LooseNode;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ProjectNode;
import dev.schemarray.editor.ops.Command;
import dev.schemarray.editor.ops.EditContext;
import dev.schemarray.editor.ops.MoveToProject;
import dev.schemarray.editor.ops.Refusal;
import dev.schemarray.editor.ops.Reparent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Drag and drop in the Scene tree (DESIGN 7.5): which zone of a row means what, and which command a drop runs. */
public final class TreeRules {
    private TreeRules() {}

    public enum Zone { BEFORE, INTO, AFTER }

    /** Where a drop lands: the new parent and the index among its children (counted before the move). */
    public record Drop(Node parent, int index) {}

    /**
     * What dropping on a row would do. {@code command} is null when it is refused and {@code refusal} says why
     * (shown in red under the cursor); {@code drop} is null when the zone cannot mean anything on that row.
     */
    public record Plan(Drop drop, Command command, String refusal) {
        public boolean valid() {
            return command != null;
        }
    }

    /** Rows that can hold children show the three zones; the others only before and after. */
    public static boolean canContainRow(Node n) {
        if (n instanceof AssemblyNode a && a.isLinked()) return false;
        return n.isContainer();
    }

    /** The zone for a pointer at {@code frac} (0 = top edge, 1 = bottom edge) of a row: 25 % / 50 % / 25 %. */
    public static Zone zone(float frac, boolean canContain) {
        if (!canContain) return frac < 0.5f ? Zone.BEFORE : Zone.AFTER;
        if (frac < 0.25f) return Zone.BEFORE;
        if (frac > 0.75f) return Zone.AFTER;
        return Zone.INTO;
    }

    /** Parent and index for a zone on a target row; empty for before/after a top-level row (nothing to reorder). */
    public static Optional<Drop> resolve(Node target, Zone zone) {
        if (zone == Zone.INTO) return Optional.of(new Drop(target, -1));
        Node parent = target.parent();
        if (parent == null) return Optional.empty();
        int i = parent.children().indexOf(target);
        return Optional.of(new Drop(parent, zone == Zone.BEFORE ? i : i + 1));
    }

    /** Plans a drop of {@code draggedIds} on {@code target} in {@code zone}, asking the command whether it can run. */
    public static Plan plan(EditContext ctx, List<UUID> draggedIds, Node target, Zone zone) {
        if (draggedIds.isEmpty()) return new Plan(null, null, "Nothing to move");
        Optional<Drop> d = resolve(target, zone);
        if (d.isEmpty()) {
            return new Plan(null, null, target instanceof LooseNode ? "Loose cannot be reordered" : "Projects cannot be reordered");
        }
        Drop drop = d.get();
        List<Node> dragged = new ArrayList<>();
        for (UUID id : draggedIds) {
            Node n = ctx.scene().byId(id);
            if (n != null) dragged.add(n);
        }
        if (dragged.isEmpty()) return new Plan(drop, null, "Nothing to move");
        for (Node n : dragged) {
            if (n instanceof ProjectNode) return new Plan(drop, null, "A project cannot be moved inside the tree");
            if (n instanceof LooseNode) return new Plan(drop, null, "Loose placements cannot be moved");
        }
        Node targetRoot = root(drop.parent());
        boolean sameRoot = true;
        for (Node n : dragged) if (root(n) != targetRoot) sameRoot = false;
        Command c = sameRoot
                ? new Reparent(draggedIds, drop.parent().id(), drop.index())
                : new MoveToProject(draggedIds, drop.parent().id(), drop.index());
        Optional<Refusal> r = c.check(ctx);
        if (r.isPresent()) return new Plan(drop, null, r.get().reason());
        return new Plan(drop, c, null);
    }

    private static Node root(Node n) {
        Node x = n;
        while (x.parent() != null) x = x.parent();
        return x;
    }
}
