package dev.schemarray.editor.ops;

import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.Transform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** A rotation or mirror of nodes in world space: each node's world transform becomes {@code map.then(world)}. */
abstract class WorldMapEdit extends BaseCommand {
    private final List<UUID> ids;

    WorldMapEdit(String verb, Collection<UUID> ids) {
        super(verb);
        this.ids = new ArrayList<>(ids);
    }

    /** The world-space map (a rotation or mirror about the pivot). */
    abstract Transform map();

    abstract Refusal extraRefusal();

    @Override
    protected final Refusal refusal(EditContext c) {
        Refusal r = extraRefusal();
        return r != null ? r : Nodes.movables(c.scene(), ids).refusal();
    }

    @Override
    protected final void doApply() {
        List<Node> nodes = Nodes.movables(ctx.scene(), ids).nodes();
        describe(nodes);
        Transform map = map();
        List<Transform> next = new ArrayList<>();
        for (Node n : nodes) {
            Transform world = ctx.scene().worldTransform(n);
            next.add(Nodes.inverse(Nodes.parentWorld(ctx.scene(), n)).then(map.then(world)));
        }
        for (int i = 0; i < nodes.size(); i++) {
            remember(nodes.get(i));
            Nodes.setTransform(nodes.get(i), next.get(i));
        }
    }
}
