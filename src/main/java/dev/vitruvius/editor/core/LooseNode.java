package dev.vitruvius.editor.core;

import java.util.UUID;

/** The one top-level node for placements that belong to no project. Not saved in a project. */
public final class LooseNode extends Node {
    public LooseNode(UUID id) {
        super(id, "Loose");
    }

    public LooseNode() {
        this(Ids.newId());
    }

    @Override
    public boolean isContainer() {
        return true;
    }

    @Override
    protected boolean fieldsEqual(Node other) {
        return true;
    }
}
