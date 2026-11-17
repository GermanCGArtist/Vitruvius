package dev.vitruvius.editor.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One schematic file placed in the world.
 *
 * <p>{@code file} is relative to the project folder ({@code files/...}, '/' separators); for Loose objects it is
 * whatever path the migration or the placement gave. {@code wire} marks an object that only shows an outline and
 * never takes part in overlaps. {@code placementIds} maps a cell key to the hash id of the Litematica placement for
 * that cell: the keys of the array cells ({@code ArrayCells.cells}) plus {@link #BASE_CELL} for the object itself.
 */
public final class ObjectNode extends Node implements HasFront {
    /** The key of the object's own (non-copy) placement in {@link #placementIds()}. */
    public static final String BASE_CELL = "base";

    private String file = "";
    private Transform transform = Transform.IDENTITY;
    private int front;
    private List<Modifier> mods = new ArrayList<>();
    private boolean wire;
    private Map<String, String> placementIds = new LinkedHashMap<>();
    private boolean missing;

    public ObjectNode(UUID id, String name) {
        super(id, name);
    }

    public ObjectNode(String name, String file) {
        this(Ids.newId(), name);
        this.file = file;
    }

    public String file() {
        return file;
    }

    public void setFile(String file) {
        this.file = file == null ? "" : file;
    }

    public Transform transform() {
        return transform;
    }

    public void setTransform(Transform t) {
        this.transform = Objects.requireNonNull(t);
    }

    @Override
    public Transform localTransform() {
        return transform;
    }

    @Override
    public int front() {
        return front;
    }

    @Override
    public void setFront(int front) {
        this.front = front;
    }

    /** The live, editable modifier list. */
    public List<Modifier> mods() {
        return mods;
    }

    public void setMods(List<Modifier> mods) {
        this.mods = new ArrayList<>(mods);
    }

    public boolean wire() {
        return wire;
    }

    public void setWire(boolean wire) {
        this.wire = wire;
    }

    /** The live, editable cell key to placement hash id map. */
    public Map<String, String> placementIds() {
        return placementIds;
    }

    public void setPlacementIds(Map<String, String> ids) {
        this.placementIds = new LinkedHashMap<>(ids);
    }

    /**
     * Runtime flag, never saved and not part of equality: the reconciler found that this Loose object's placement is
     * not in Litematica. The node is kept and no longer reconciled; the UI greys it out ("placement not found").
     * It only leaves through an explicit Delete, and the flag clears by itself when the placement is there again.
     */
    public boolean missing() {
        return missing;
    }

    public void setMissing(boolean missing) {
        this.missing = missing;
    }

    @Override
    protected boolean fieldsEqual(Node other) {
        ObjectNode o = (ObjectNode) other;
        return file.equals(o.file) && transform.equals(o.transform) && front == o.front && mods.equals(o.mods)
                && wire == o.wire && placementIds.equals(o.placementIds);
    }
}
