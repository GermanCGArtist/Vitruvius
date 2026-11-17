package dev.schemarray.editor.core;

import java.util.UUID;

/** A folder: groups nodes, has no transform. */
public final class FolderNode extends Node {
    private boolean open = true;

    public FolderNode(UUID id, String name) {
        super(id, name);
    }

    public FolderNode(String name) {
        this(Ids.newId(), name);
    }

    @Override
    public boolean isContainer() {
        return true;
    }

    /** Whether the tree shows it expanded. */
    public boolean open() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    @Override
    protected boolean fieldsEqual(Node other) {
        return open == ((FolderNode) other).open;
    }
}
