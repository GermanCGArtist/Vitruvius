package dev.vitruvius.editor.core;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Node ids are UUIDs. */
public final class Ids {
    private Ids() {}

    public static UUID newId() {
        return UUID.randomUUID();
    }

    /** The UUID in this text, or null if it is not one. */
    public static UUID parseOrNull(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A stable id derived from a text (same text, same id); used where an old format has no UUIDs of its own. */
    public static UUID derived(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
