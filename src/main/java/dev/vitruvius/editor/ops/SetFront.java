package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.FrontOrder;
import dev.vitruvius.editor.core.HasFront;
import dev.vitruvius.editor.core.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Front order edits through {@link FrontOrder}: bring to front, forward, backward, send to back, or put one node at
 * an exact number. The order is per world and shared by all open projects (and Loose) of that world; the whole
 * order is renumbered 1..n, and several selected nodes keep their order among themselves. Only objects and
 * assemblies that count as one thing (not parts of an assembly) have a front order.
 */
public final class SetFront extends BaseCommand {
    private enum Kind { FRONT, BACK, FORWARD, BACKWARD, TO }

    private final List<UUID> ids;
    private final Kind kind;
    private final int number;
    private final Map<Node, Integer> before = new IdentityHashMap<>();

    private SetFront(String verb, Kind kind, Collection<UUID> ids, int number) {
        super(verb);
        this.kind = kind;
        this.ids = new ArrayList<>(ids);
        this.number = number;
    }

    public static SetFront front(Collection<UUID> ids) {
        return new SetFront("Bring to front", Kind.FRONT, ids, 0);
    }

    public static SetFront back(Collection<UUID> ids) {
        return new SetFront("Send to back", Kind.BACK, ids, 0);
    }

    public static SetFront forward(Collection<UUID> ids) {
        return new SetFront("Bring forward", Kind.FORWARD, ids, 0);
    }

    public static SetFront backward(Collection<UUID> ids) {
        return new SetFront("Send backward", Kind.BACKWARD, ids, 0);
    }

    /** Puts the node at place {@code number} (1 = front), clamped to the number of nodes in the world. */
    public static SetFront to(UUID id, int number) {
        return new SetFront("Set front order", Kind.TO, List.of(id), number);
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Nodes.Pick p = Nodes.resolve(c.scene(), ids);
        if (p.refusal() != null) return p.refusal();
        for (Node n : p.nodes()) {
            if (!(n instanceof HasFront)) return new Refusal(Nodes.q(n) + " has no front order");
            Node owner = c.scene().ownerOf(n);
            if (owner != n) return new Refusal(Nodes.q(n) + " is a part of " + Nodes.q(owner) + "; the front order applies to the whole assembly");
            if (Nodes.readOnly(n) != null) return Nodes.readOnly(n);
        }
        if (kind == Kind.TO && number < 1) return new Refusal("The front order starts at 1");
        return null;
    }

    @Override
    protected void doApply() {
        List<Node> nodes = Nodes.resolve(ctx.scene(), ids).nodes();
        describe(nodes);
        before.clear();
        Map<String, List<HasFront>> byWorld = new LinkedHashMap<>();
        for (Node n : nodes) byWorld.computeIfAbsent(ctx.worldOf(n), w -> new ArrayList<>()).add((HasFront) n);
        for (Map.Entry<String, List<HasFront>> e : byWorld.entrySet()) {
            List<HasFront> all = ctx.scene().frontOwnersInWorld(e.getKey());
            for (HasFront h : all) before.put((Node) h, h.front());
            List<HasFront> sel = e.getValue();
            switch (kind) {
                case FRONT -> FrontOrder.front(all, sel);
                case BACK -> FrontOrder.back(all, sel);
                case FORWARD -> FrontOrder.forward(all, sel);
                case BACKWARD -> FrontOrder.backward(all, sel);
                case TO -> placeAt(all, sel.get(0), number);
            }
        }
        for (Map.Entry<Node, Integer> e : before.entrySet()) {
            if (Nodes.front(e.getKey()) != e.getValue()) touch(e.getKey());
        }
    }

    private static void placeAt(List<HasFront> all, HasFront node, int number) {
        List<HasFront> order = FrontOrder.sorted(all);
        order.remove(node);
        order.add(Math.min(number - 1, order.size()), node);
        for (int i = 0; i < order.size(); i++) order.get(i).setFront(i + 1);
    }

    @Override
    protected void undoExtra() {
        for (Map.Entry<Node, Integer> e : before.entrySet()) Nodes.setFront(e.getKey(), e.getValue());
    }
}
