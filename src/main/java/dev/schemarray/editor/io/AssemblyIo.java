package dev.schemarray.editor.io;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.Node;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes {@code .lasm} files: the node list of a project without world, dim, origin or sync.
 *
 * <pre>
 * { "format": 1, "name": text, "nodes": [ node... ], ...unknown fields kept }
 * </pre>
 * The nodes are the parts, with transforms local to the assembly. They have no parent until attached.
 */
public final class AssemblyIo {
    private AssemblyIo() {}

    public static final int FORMAT = 1;

    /** The content of a .lasm. */
    public static final class Lasm {
        public String name;
        public final List<Node> nodes = new ArrayList<>();
        public JsonObject extra = new JsonObject();

        public Lasm(String name) {
            this.name = name == null ? "" : name;
        }

        /** A .lasm holding the parts of this assembly (the same node objects; they stay in the assembly). */
        public static Lasm of(AssemblyNode a) {
            Lasm l = new Lasm(a.name());
            l.nodes.addAll(a.children());
            return l;
        }

        /** Whether the other .lasm holds the same name, parts (in every saved respect) and unknown fields. */
        public boolean structurallyEquals(Lasm o) {
            if (!name.equals(o.name) || !extra.equals(o.extra) || nodes.size() != o.nodes.size()) return false;
            for (int i = 0; i < nodes.size(); i++) if (!nodes.get(i).structurallyEquals(o.nodes.get(i))) return false;
            return true;
        }
    }

    public static String toJson(Lasm l) {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("name", l.name);
        o.add("nodes", NodeJson.nodes(l.nodes));
        for (Map.Entry<String, JsonElement> e : l.extra.entrySet()) {
            if (!o.has(e.getKey())) o.add(e.getKey(), e.getValue().deepCopy());
        }
        return JsonText.write(o);
    }

    public static Lasm fromJson(String text) throws FormatException {
        JsonElement root = JsonText.parse(text);
        if (!root.isJsonObject()) throw new FormatException(".lasm must hold an object");
        NodeJson.Fields f = new NodeJson.Fields(root.getAsJsonObject(), "lasm");
        int format = f.integer("format", FORMAT);
        if (format > FORMAT) throw new FormatException(".lasm has format " + format + ", this version reads up to " + FORMAT);
        Lasm l = new Lasm(f.str("name", ""));
        java.util.Set<UUID> ids = NodeJson.newIdSet();
        l.nodes.addAll(NodeJson.readNodes(f.take("nodes"), "nodes", ids));
        l.extra = f.rest;
        return l;
    }

    public static Lasm read(Path file) throws IOException {
        try {
            return fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (FormatException e) {
            throw new FormatException(file + ": " + e.getMessage(), e);
        }
    }

    public static void write(Lasm l, Path file) throws IOException {
        ProjectIo.writeText(toJson(l), file);
    }
}
