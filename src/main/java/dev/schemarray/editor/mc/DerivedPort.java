package dev.schemarray.editor.mc;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Which derived file (trims, Booleans) a placed copy of an object uses right now; no Minecraft types. A copy is
 * identified by its slot: the object id for the base cell of a model object, a derived id for every other copy
 * (array cells, Loose objects' cells, parts of linked assemblies).
 */
@FunctionalInterface
public interface DerivedPort {
    /** The ready derived file, or null to use the object's own file. */
    Path fileFor(UUID slot);
}
