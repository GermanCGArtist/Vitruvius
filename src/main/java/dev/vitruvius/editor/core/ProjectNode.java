package dev.vitruvius.editor.core;

import java.util.Objects;
import java.util.UUID;

/**
 * A project: top level only. It has no transform; {@code origin} is the anchor new objects are placed at (and
 * where the project was created) and does not move its children.
 *
 * <p>{@code path} (the project folder) and {@code active} are session state, not saved in project.json: the
 * folder is wherever the file was read from, and {@link Scene} decides which project is active.
 */
public final class ProjectNode extends Node {
    public enum Sync { LOCAL, SHARED }

    private String path = "";
    private String world = "";
    private String dim = "minecraft:overworld";
    private Vec3i origin = Vec3i.ZERO;
    private boolean active;
    private Sync sync = Sync.LOCAL;

    public ProjectNode(UUID id, String name) {
        super(id, name);
    }

    public ProjectNode(String name) {
        this(Ids.newId(), name);
    }

    @Override
    public boolean isContainer() {
        return true;
    }

    public String path() {
        return path;
    }

    public void setPath(String path) {
        this.path = path == null ? "" : path;
    }

    public String world() {
        return world;
    }

    public void setWorld(String world) {
        this.world = world == null ? "" : world;
    }

    public String dim() {
        return dim;
    }

    public void setDim(String dim) {
        this.dim = dim == null ? "" : dim;
    }

    public Vec3i origin() {
        return origin;
    }

    public void setOrigin(Vec3i origin) {
        this.origin = Objects.requireNonNull(origin);
    }

    public boolean active() {
        return active;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    public Sync sync() {
        return sync;
    }

    public void setSync(Sync sync) {
        this.sync = Objects.requireNonNull(sync);
    }

    @Override
    protected boolean fieldsEqual(Node other) {
        ProjectNode o = (ProjectNode) other;
        return world.equals(o.world) && dim.equals(o.dim) && origin.equals(o.origin) && sync == o.sync;
    }
}
