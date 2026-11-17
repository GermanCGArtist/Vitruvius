package dev.schemarray.editor.ops;

import java.util.Collection;
import java.util.UUID;

/** Where commands report what they changed, so the Minecraft side knows what to save, reconcile or rebuild. */
public interface ChangeSink {
    /**
     * The project (or the Loose node's id, for Loose changes) that now differs from what is saved. Called after
     * apply and after revert.
     */
    void markDirty(UUID projectId);

    /**
     * Ids of the nodes whose placements, derived files or outlines may be out of date: the nodes a command changed
     * with their descendants, nodes it added, and nodes it removed (the reconciler must drop their placements).
     */
    void affected(Collection<UUID> nodeIds);

    ChangeSink NONE = new ChangeSink() {
        @Override
        public void markDirty(UUID projectId) {}

        @Override
        public void affected(Collection<UUID> nodeIds) {}
    };
}
