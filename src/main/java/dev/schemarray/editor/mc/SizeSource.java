package dev.schemarray.editor.mc;

import dev.schemarray.editor.core.Vec3i;

import java.nio.file.Path;

/** The enclosing size of a schematic file, or null when it cannot be read. */
public interface SizeSource {
    Vec3i size(Path file);
}
