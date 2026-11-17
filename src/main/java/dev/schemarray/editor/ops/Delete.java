package dev.schemarray.editor.ops;

import dev.schemarray.editor.core.LooseNode;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ProjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Removes nodes (with everything below them) from the tree. Undo puts them back at the same parent and index. The
 * files stay where they are: nothing is hard-deleted, and unused ones are cleaned up on save. Boolean modifiers that
 * pointed at a deleted node keep the stale id; the Properties window shows them as having no target.
 */
public final class Delete extends BaseCommand {
    private final List<UUID> ids;

    public Delete(Collection<UUID> ids) {
        super("Delete");
        this.ids = new ArrayList<>(ids);
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Nodes.Pick p = Nodes.resolve(c.scene(), ids);
        if (p.refusal() != null) return p.refusal();
        for (Node n : p.nodes()) {
            if (n instanceof ProjectNode) return new Refusal("A project cannot be deleted here; close it instead");
            if (n instanceof LooseNode) return new Refusal("Loose placements cannot be deleted as a group; delete the items");
            Refusal r = Nodes.readOnly(n);
            if (r != null) return r;
        }
        return null;
    }

    @Override
    protected void doApply() {
        List<Node> nodes = Nodes.topMost(Nodes.resolve(ctx.scene(), ids).nodes());
        describe(nodes);
        for (Node n : nodes) {
            touch(n);
            remember(n.parent());
        }
        for (Node n : nodes) n.parent().removeChild(n);
    }
}
