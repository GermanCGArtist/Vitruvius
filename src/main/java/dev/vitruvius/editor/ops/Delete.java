package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ProjectNode;

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
        List<dev.vitruvius.editor.core.ExternalNode> gone = new ArrayList<>();
        for (Node n : nodes) collectExternal(n, gone);
        for (Node n : nodes) n.parent().removeChild(n);
        for (dev.vitruvius.editor.core.ExternalNode x : gone) {          // the type's owner may have a thing of its own to remove
            dev.vitruvius.api.SceneObjectType type = dev.vitruvius.api.ObjectTypes.get(x.typeId());
            if (type == null) continue;
            try {
                type.onRemoved(x.data().deepCopy());
            } catch (RuntimeException ignored) {
                // another mod's problem must not undo the delete
            }
        }
    }

    private static void collectExternal(Node n, List<dev.vitruvius.editor.core.ExternalNode> out) {
        if (n instanceof dev.vitruvius.editor.core.ExternalNode x) out.add(x);
        for (Node c : n.children()) collectExternal(c, out);
    }
}
