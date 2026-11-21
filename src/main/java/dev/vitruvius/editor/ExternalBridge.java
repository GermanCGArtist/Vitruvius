package dev.vitruvius.editor;

import com.google.gson.JsonObject;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.ops.AddExternal;
import dev.vitruvius.editor.ops.Delete;
import dev.vitruvius.editor.ops.SetExternalData;
import dev.vitruvius.editor.ops.SetTransform;

import java.util.List;
import java.util.UUID;

/**
 * How another mod keeps scene objects of its own type in step with its own state (Heavymatica mirrors its placements
 * as objects the user can select and move). The edits are applied straight to the scene, not through the undo stack:
 * they are bookkeeping, not something the user did, so Undo must not walk through them. The user's own edits (moving
 * the object with the gizmo) are normal undoable commands, and the mod reads the result back through
 * {@code VitruviusApi.objectsOfType}. Client thread only; every call is a no-op while the editor is not running.
 */
public final class ExternalBridge {
    private ExternalBridge() {}

    private static EditorSession session() {
        return EditorMode.isActive() ? EditorSession.get() : null;
    }

    public static boolean ready() {
        return session() != null;
    }

    /** Adds an external node under Loose; returns its id, or null. */
    public static UUID add(String typeId, String name, JsonObject data, int x, int y, int z) {
        EditorSession s = session();
        if (s == null) return null;
        try {
            AddExternal cmd = new AddExternal(s.scene().loose().id(), -1, typeId, name, data, new Vec3i(x, y, z));
            cmd.apply(s.context());
            return cmd.nodeId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean exists(UUID id) {
        EditorSession s = session();
        return s != null && s.scene().byId(id) instanceof ExternalNode;
    }

    /** Sets the node's local pose. {@code mirror}: 0 none, 1 left-right (flips z), 2 front-back (flips x). */
    public static boolean setPose(UUID id, int x, int y, int z, int rotSteps, int mirror) {
        EditorSession s = session();
        if (s == null || !(s.scene().byId(id) instanceof ExternalNode)) return false;
        try {
            Mirror m = mirror == 1 ? Mirror.LEFT_RIGHT : mirror == 2 ? Mirror.FRONT_BACK : Mirror.NONE;
            new SetTransform(id, new Transform(new Vec3i(x, y, z), Rot.NONE.rotate(rotSteps), m)).apply(s.context());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean setData(UUID id, JsonObject data) {
        EditorSession s = session();
        if (s == null || !(s.scene().byId(id) instanceof ExternalNode)) return false;
        try {
            new SetExternalData(id, data).apply(s.context());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean remove(UUID id) {
        EditorSession s = session();
        if (s == null) return false;
        Node n = s.scene().byId(id);
        if (!(n instanceof ExternalNode)) return false;
        try {
            new Delete(List.of(id)).apply(s.context());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
