package dev.vitruvius.editor.mc;

import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Vec3i;

import java.nio.file.Path;
import java.util.Set;

/**
 * The few things the reconciler needs from the placement list. {@link LitematicaAdapter} is the real one; the tests
 * use a fake. No Minecraft types here.
 */
public interface PlacementPort {
    /**
     * Creates a placement of this file and returns its hash id, or null when the file cannot be loaded.
     *
     * @param preferredId a hash id to reuse (stable ids across sessions); ignored if null, not a UUID or in use
     */
    String create(Path file, Vec3i origin, Rot rot, Mirror mirror, String name, boolean enabled, boolean render, String preferredId);

    default String create(Path file, Vec3i origin, Rot rot, Mirror mirror, String name, boolean enabled, boolean render) {
        return create(file, origin, rot, mirror, name, enabled, render, null);
    }

    void update(String hashId, Vec3i origin, Rot rot, Mirror mirror, boolean enabled, boolean render);

    void remove(String hashId);

    /** The placement's state, or null if there is no such placement. */
    PlacementState read(String hashId);

    /** The hash ids of all real placements (not the shape preview). Also refreshes the adapter's index. */
    Set<String> allHashIds();

    void markRebuild(String hashId);

    /** Makes the sub-region on/off states of {@code toId} equal those of {@code fromId}. */
    void copySubRegions(String fromId, String toId);
}
