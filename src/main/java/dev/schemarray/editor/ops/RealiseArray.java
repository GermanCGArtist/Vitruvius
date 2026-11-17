package dev.schemarray.editor.ops;

import dev.schemarray.editor.core.ArrayCells;
import dev.schemarray.editor.core.ArrayModifier;
import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.FolderNode;
import dev.schemarray.editor.core.Ids;
import dev.schemarray.editor.core.Modifier;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns an array into separate nodes. The node becomes a folder {@code <name> (realised)} at the same place in its
 * parent. The folder holds the original (its array modifiers removed, other modifiers kept) and one copy per array
 * cell, each at {@code pos + the cell offset} turned the way the node is turned. Copies are independent nodes with
 * new ids that share the original's files (no file is copied), carry the other modifiers, and keep its front
 * number. For an object, the placement of each cell is handed to its copy ({@code placementIds}), so Litematica keeps
 * the placements it already has; undo hands them back.
 *
 * <p>The caller passes {@code size}, the bounding box of the object (or assembly) in blocks, which the array maths
 * needs. Works on objects and on local or linked assemblies (a linked assembly's copies are links to the same file).
 */
public final class RealiseArray extends BaseCommand {
    private final UUID nodeId;
    private final Vec3i size;
    private final UUID folderId = Ids.newId();
    private final Map<String, Map<UUID, UUID>> cellIds = new HashMap<>();
    private final Map<String, Node> copies = new LinkedHashMap<>();

    public RealiseArray(UUID nodeId, Vec3i size) {
        super("Realise array");
        this.nodeId = nodeId;
        this.size = size;
    }

    private static List<ArrayModifier> arrays(List<Modifier> mods) {
        List<ArrayModifier> out = new ArrayList<>();
        for (Modifier m : mods) if (m instanceof ArrayModifier a) out.add(a);
        return out;
    }

    /** The folder the node becomes (stable across redo). */
    public UUID folderId() {
        return folderId;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node n = c.scene().byId(nodeId);
        if (n == null) return new Refusal("The item no longer exists");
        List<Modifier> mods = Nodes.mods(n);
        if (mods == null) return new Refusal(Nodes.q(n) + " cannot have an array");
        Refusal ro = Nodes.readOnly(n);
        if (ro != null) return ro;
        List<ArrayModifier> arrays = arrays(mods);
        if (!arrays.stream().anyMatch(a -> a.enabled)) return new Refusal(Nodes.q(n) + " has no enabled array to realise");
        if (n.parent() == null) return new Refusal(Nodes.q(n) + " is not in the tree");
        if (ArrayCells.cells(arrays, size).isEmpty()) return new Refusal("The array makes no copies");
        return null;
    }

    @Override
    protected void doApply() {
        Node n = ctx.scene().byId(nodeId);
        Node parent = n.parent();
        remember(parent);
        remember(n);
        List<ArrayModifier> arrays = arrays(Nodes.mods(n));
        Map<String, Vec3i> cells = ArrayCells.cells(arrays, size);
        setLabel("Realise array of " + n.name() + " (" + cells.size() + " copies)");
        FolderNode folder = new FolderNode(folderId, Nodes.uniqueSiblingName(parent, n.name() + " (realised)"));
        parent.addChild(Nodes.indexIn(parent, n), folder);
        folder.addChild(n);
        Nodes.mods(n).removeIf(m -> m instanceof ArrayModifier);
        Map<String, String> oldPlacements = n instanceof ObjectNode o ? new LinkedHashMap<>(o.placementIds()) : Map.of();
        copies.clear();
        int number = 2;
        Transform base = n.localTransform();
        for (Map.Entry<String, Vec3i> cell : cells.entrySet()) {
            Map<UUID, UUID> ids = cellIds.computeIfAbsent(cell.getKey(), k -> new HashMap<>());
            Nodes.Copier copier = new Nodes.Copier(ids, ObjectNode::file, false, new HashMap<>(), this::remember);
            Node copy = copier.copy(n);
            copier.remapBooleans(copy);
            copy.setName(n.name() + " " + number++);
            Nodes.setTransform(copy, base.withPos(base.pos().add(base.applyVector(cell.getValue()))));
            if (copy instanceof ObjectNode co && n instanceof ObjectNode) {
                String id = oldPlacements.get(cell.getKey());
                if (id != null) co.placementIds().put(ObjectNode.BASE_CELL, id);
            }
            folder.addChild(copy);
            copies.put(cell.getKey(), copy);
        }
        if (n instanceof ObjectNode o) for (String key : cells.keySet()) o.placementIds().remove(key);
        touch(folder);
    }

    @Override
    protected void undoExtra() {
        Node n = ctx.scene().byId(nodeId);
        if (!(n instanceof ObjectNode o)) return;
        for (Map.Entry<String, Node> e : copies.entrySet()) {
            if (e.getValue() instanceof ObjectNode co) {
                String id = co.placementIds().get(ObjectNode.BASE_CELL);
                if (id != null) o.placementIds().put(e.getKey(), id);
            }
        }
    }
}
