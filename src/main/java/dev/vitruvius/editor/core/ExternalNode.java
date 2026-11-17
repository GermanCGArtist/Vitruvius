package dev.vitruvius.editor.core;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An object of a type another mod registers (e.g. {@code heavymatica:area}). The editor only moves it; its
 * {@code data} is opaque JSON owned by that type. It never affects blocks, so it has no front order, no modifiers
 * and takes no part in overlaps. It can carry an array (the only modifier it accepts).
 */
public final class ExternalNode extends Node {
    private String typeId;
    private Transform transform = Transform.IDENTITY;
    private JsonObject data = new JsonObject();
    private List<Modifier> mods = new ArrayList<>();

    public ExternalNode(UUID id, String name, String typeId) {
        super(id, name);
        this.typeId = Objects.requireNonNull(typeId, "typeId");
    }

    public ExternalNode(String name, String typeId) {
        this(Ids.newId(), name, typeId);
    }

    public String typeId() {
        return typeId;
    }

    public void setTypeId(String typeId) {
        this.typeId = Objects.requireNonNull(typeId);
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

    /** The type's own data; the live object. */
    public JsonObject data() {
        return data;
    }

    public void setData(JsonObject data) {
        this.data = data == null ? new JsonObject() : data;
    }

    /** The live modifier list (arrays only). */
    public List<Modifier> mods() {
        return mods;
    }

    public void setMods(List<Modifier> mods) {
        this.mods = new ArrayList<>(mods);
    }

    @Override
    protected boolean fieldsEqual(Node other) {
        ExternalNode o = (ExternalNode) other;
        return typeId.equals(o.typeId) && transform.equals(o.transform) && data.equals(o.data) && mods.equals(o.mods);
    }
}
