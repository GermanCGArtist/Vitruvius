package dev.schemarray.editor.ops;

import dev.schemarray.editor.core.FrontOrder;
import dev.schemarray.editor.core.LooseNode;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ProjectNode;
import dev.schemarray.editor.core.Vec3i;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shift+D: a deep copy with new ids, put right after the original and moved by {@code offset} (world blocks). The
 * copy sits behind everything in the front order. Names get a number when the parent already has one ({@code a 2}).
 *
 * <p>The copy is independent: each object's {@code .litematic} is copied to a free name next to the original (undo
 * moves the copies to {@code .trash}). Pass {@code copyFiles=false} to share the files. A linked assembly is copied
 * as the same link (its parts are not in the model). Boolean modifiers whose target is copied along point at the copy.
 */
public class Duplicate extends BaseCommand {
    private final List<UUID> ids;
    private final Vec3i offset;
    private final boolean copyFiles;
    private final boolean linked;
    private final Map<UUID, UUID> idMap = new HashMap<>();
    private final Map<UUID, UUID> groupIds = new HashMap<>();
    private final List<UUID> created = new ArrayList<>();

    public Duplicate(Collection<UUID> ids, Vec3i offset) {
        this(ids, offset, true);
    }

    public Duplicate(Collection<UUID> ids, Vec3i offset, boolean copyFiles) {
        this(ids, offset, copyFiles, false, "Duplicate");
    }

    Duplicate(Collection<UUID> ids, Vec3i offset, boolean copyFiles, boolean linked, String verb) {
        super(verb);
        this.ids = new ArrayList<>(ids);
        this.offset = offset;
        this.copyFiles = copyFiles;
        this.linked = linked;
    }

    /** The ids of the top-level copies made by the last apply, in selection order (to select them afterwards). */
    public List<UUID> createdIds() {
        return List.copyOf(created);
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Nodes.Pick p = Nodes.resolve(c.scene(), ids);
        if (p.refusal() != null) return p.refusal();
        for (Node n : p.nodes()) {
            if (n instanceof ProjectNode || n instanceof LooseNode) return new Refusal("Projects and Loose cannot be duplicated");
            if (Nodes.readOnly(n) != null) return new Refusal(Nodes.q(n) + " is part of a linked assembly and cannot be duplicated on its own");
        }
        return null;
    }

    @Override
    protected void doApply() {
        List<Node> nodes = Nodes.topMost(Nodes.resolve(ctx.scene(), ids).nodes());
        describe(nodes);
        created.clear();
        Map<String, String> fileCache = new HashMap<>();
        Nodes.Copier copier = new Nodes.Copier(idMap,
                o -> linked || !copyFiles ? o.file() : FileOps.copyBeside(ctx, journal(), o, o.file(), fileCache),
                linked, groupIds, this::remember);
        List<Node> copies = new ArrayList<>();
        int nextFront = FrontOrder.nextBack(ctx.scene().frontOwnersInWorld(ctx.worldOf(nodes.get(0))));
        for (Node n : nodes) {
            Node parent = n.parent();
            remember(parent);
            Node copy = copier.copy(n);
            copy.setName(Nodes.uniqueSiblingName(parent, n.name()));
            parent.addChild(Nodes.indexIn(parent, n) + 1, copy);
            List<Node> bearers = new ArrayList<>();
            Nodes.bearers(copy, bearers);
            for (Node b : bearers) {
                Vec3i local = Nodes.inverse(Nodes.parentWorld(ctx.scene(), b)).applyVector(offset);
                var t = b.localTransform();
                Nodes.setTransform(b, t.withPos(t.pos().add(local)));
            }
            for (Node x : copy.subtree()) {
                if ((x instanceof dev.schemarray.editor.core.HasFront) && ctx.scene().ownerOf(x) == x) Nodes.setFront(x, nextFront++);
            }
            touch(copy);
            created.add(copy.id());
            copies.add(copy);
        }
        for (Node copy : copies) copier.remapBooleans(copy);
    }
}
