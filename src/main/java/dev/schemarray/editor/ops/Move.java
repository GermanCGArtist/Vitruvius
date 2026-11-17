package dev.schemarray.editor.ops;

import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Moves nodes by a world delta. A node under another selected node moves with it, a folder stands for the nodes
 * inside it, and the delta is turned into each node's own parent space (so it also works inside a turned
 * assembly). Consecutive moves of the same nodes merge into one undo step.
 */
public final class Move extends BaseCommand {
    private final List<UUID> ids;
    private Vec3i delta;

    public Move(Collection<UUID> ids, Vec3i delta) {
        super("Move");
        this.ids = new ArrayList<>(ids);
        this.delta = Objects.requireNonNull(delta);
    }

    public Vec3i delta() {
        return delta;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        return Nodes.movables(c.scene(), ids).refusal();
    }

    @Override
    protected void doApply() {
        List<Node> nodes = Nodes.movables(ctx.scene(), ids).nodes();
        describe(nodes);
        List<Transform> next = new ArrayList<>();
        for (Node n : nodes) {
            Vec3i local = Nodes.inverse(Nodes.parentWorld(ctx.scene(), n)).applyVector(delta);
            Transform t = n.localTransform();
            next.add(t.withPos(t.pos().add(local)));
        }
        for (int i = 0; i < nodes.size(); i++) {
            remember(nodes.get(i));
            Nodes.setTransform(nodes.get(i), next.get(i));
        }
    }

    @Override
    public boolean mergeWith(Command other) {
        if (!(other instanceof Move m) || !new HashSet<>(ids).equals(new HashSet<>(m.ids))) return false;
        delta = delta.add(m.delta);
        return true;
    }
}
