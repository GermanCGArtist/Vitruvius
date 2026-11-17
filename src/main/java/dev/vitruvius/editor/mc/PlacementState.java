package dev.vitruvius.editor.mc;

import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.nio.file.Path;

/** What one Litematica placement holds, in model types (no Minecraft). {@code file} may be null (in-memory schematic). */
public record PlacementState(Path file, Vec3i origin, Rot rot, Mirror mirror, boolean enabled, boolean render, String name) {
    /** Origin, rotation and mirror as a transform, in the canonical form. */
    public Transform transform() {
        return new Transform(origin, rot, mirror).canonical();
    }
}
