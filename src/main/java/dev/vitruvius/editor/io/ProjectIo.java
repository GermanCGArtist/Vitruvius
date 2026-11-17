package dev.vitruvius.editor.io;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.vitruvius.editor.core.Ids;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Vec3i;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes {@code project.json}.
 *
 * <pre>
 * { "format": 1, "id": uuid, "name": text, "world": text, "dim": text, "origin": [x,y,z], "sync": "local"|"shared",
 *   "hidden": bool, "nodes": [ node... ], ...unknown fields kept }
 * </pre>
 * The same model always gives the same bytes. Fields this version does not know (on the project and on every node)
 * are read into {@code Node.extra()} and written back. The project's {@code path} is not saved: it is set to the
 * folder the file was read from.
 */
public final class ProjectIo {
    private ProjectIo() {}

    /** The format version this code writes and the newest it reads. */
    public static final int FORMAT = 1;

    public static String toJson(ProjectNode p) {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("id", p.id().toString());
        o.addProperty("name", p.name());
        o.addProperty("world", p.world());
        o.addProperty("dim", p.dim());
        o.add("origin", NodeJson.vec(p.origin()));
        o.addProperty("sync", p.sync() == ProjectNode.Sync.SHARED ? "shared" : "local");
        o.addProperty("hidden", p.hidden());
        o.add("nodes", NodeJson.nodes(p.children()));
        for (Map.Entry<String, JsonElement> e : p.extra().entrySet()) {
            if (!o.has(e.getKey())) o.add(e.getKey(), e.getValue().deepCopy());
        }
        return JsonText.write(o);
    }

    public static ProjectNode fromJson(String text) throws FormatException {
        JsonElement root = JsonText.parse(text);
        if (!root.isJsonObject()) throw new FormatException("project.json must hold an object");
        NodeJson.Fields f = new NodeJson.Fields(root.getAsJsonObject(), "project");
        int format = f.integer("format", FORMAT);
        if (format > FORMAT) throw new FormatException("project.json has format " + format + ", this version reads up to " + FORMAT);
        String idText = f.str("id", null);
        UUID id = idText == null ? Ids.newId() : Ids.parseOrNull(idText);
        if (id == null) throw new FormatException("project: id '" + idText + "' is not a UUID");
        ProjectNode p = new ProjectNode(id, f.str("name", ""));
        p.setWorld(f.str("world", ""));
        p.setDim(f.str("dim", "minecraft:overworld"));
        p.setOrigin(NodeJson.readVec(f.take("origin"), Vec3i.ZERO, "project.origin"));
        String sync = f.str("sync", "local");
        p.setSync(switch (sync) {
            case "local" -> ProjectNode.Sync.LOCAL;
            case "shared" -> ProjectNode.Sync.SHARED;
            default -> throw new FormatException("project: unknown sync '" + sync + "'");
        });
        p.setHidden(f.bool("hidden", false));
        java.util.Set<UUID> ids = NodeJson.newIdSet();
        ids.add(id);
        for (Node n : NodeJson.readNodes(f.take("nodes"), "nodes", ids)) p.addChild(n);
        p.setExtra(f.rest);
        return p;
    }

    /** Reads a project.json; the project's path becomes the folder that holds the file. */
    public static ProjectNode read(Path file) throws IOException {
        ProjectNode p;
        try {
            p = fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (FormatException e) {
            throw new FormatException(file + ": " + e.getMessage(), e);
        }
        Path dir = file.toAbsolutePath().getParent();
        p.setPath(dir == null ? "" : dir.toString());
        return p;
    }

    /** Writes the project to this file (creating folders), through a temporary file so a crash never leaves half a file. */
    public static void write(ProjectNode p, Path file) throws IOException {
        writeText(toJson(p), file);
    }

    static void writeText(String text, Path file) throws IOException {
        Path abs = file.toAbsolutePath();
        Files.createDirectories(abs.getParent());
        Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, abs, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
