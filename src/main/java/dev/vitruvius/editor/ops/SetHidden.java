package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Hides or shows nodes (H / Alt+H). */
public final class SetHidden extends BaseCommand {
    private final List<UUID> ids;
    private final boolean hidden;

    public SetHidden(Collection<UUID> ids, boolean hidden) {
        super(hidden ? "Hide" : "Show");
        this.ids = new ArrayList<>(ids);
        this.hidden = hidden;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Nodes.Pick p = Nodes.resolve(c.scene(), ids);
        if (p.refusal() != null) return p.refusal();
        for (Node n : p.nodes()) {
            Refusal r = Nodes.readOnly(n);
            if (r != null) return r;
        }
        return null;
    }

    @Override
    protected void doApply() {
        List<Node> nodes = Nodes.resolve(ctx.scene(), ids).nodes();
        describe(nodes);
        for (Node n : nodes) {
            remember(n);
            n.setHidden(hidden);
        }
    }
}
