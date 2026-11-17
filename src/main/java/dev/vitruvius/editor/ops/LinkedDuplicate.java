package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.Vec3i;

import java.util.Collection;
import java.util.UUID;

/**
 * Alt+D: a duplicate that stays tied to the original.
 * <ul>
 *   <li>An object shares the same {@code file}.</li>
 *   <li>A linked assembly keeps the same link path.</li>
 *   <li>A local assembly gets copies of its parts, and the original and the copy both get
 *       {@code extra.linkedGroup=<uuid>} so a later step can keep their parts in sync. Nothing keeps them in sync
 *       yet: that is a gap to settle (see the WP 1.2 notes).</li>
 * </ul>
 * Everything else is as in {@link Duplicate}.
 */
public final class LinkedDuplicate extends Duplicate {
    public LinkedDuplicate(Collection<UUID> ids, Vec3i offset) {
        super(ids, offset, false, true, "Linked duplicate");
    }
}
