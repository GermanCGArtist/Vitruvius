package dev.schemarray.editor.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.schemarray.editor.core.ArrayModifier;
import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.BooleanModifier;
import dev.schemarray.editor.core.ExternalNode;
import dev.schemarray.editor.core.FolderNode;
import dev.schemarray.editor.core.Ids;
import dev.schemarray.editor.core.Mirror;
import dev.schemarray.editor.core.Modifier;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.Rot;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The JSON shape of a node, shared by project.json and .lasm. Field order is fixed (id, type, name, hidden, the type's
 * own fields, children), then the unknown fields in the order they were read.
 */
final class NodeJson {
    private NodeJson() {}

    // ---- reading ----

    /** A JSON object whose fields are taken one by one; what is left over is the unknown part. */
    static final class Fields {
        final JsonObject rest;
        final String where;

        Fields(JsonObject o, String where) {
            this.rest = o.deepCopy();
            this.where = where;
        }

        JsonElement take(String key) {
            return rest.remove(key);
        }

        String str(String key, String def) throws FormatException {
            JsonElement e = take(key);
            if (e == null || e.isJsonNull()) return def;
            if (!e.isJsonPrimitive()) throw new FormatException(where + ": '" + key + "' must be text");
            return e.getAsString();
        }

        boolean bool(String key, boolean def) throws FormatException {
            JsonElement e = take(key);
            if (e == null || e.isJsonNull()) return def;
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new FormatException(where + ": '" + key + "' must be true or false");
            return e.getAsBoolean();
        }

        int integer(String key, int def) throws FormatException {
            JsonElement e = take(key);
            if (e == null || e.isJsonNull()) return def;
            try {
                return e.getAsInt();
            } catch (RuntimeException ex) {
                throw new FormatException(where + ": '" + key + "' must be a whole number");
            }
        }

        JsonObject object(String key) throws FormatException {
            JsonElement e = take(key);
            if (e == null || e.isJsonNull()) return null;
            if (!e.isJsonObject()) throw new FormatException(where + ": '" + key + "' must be an object");
            return e.getAsJsonObject();
        }
    }

    static Vec3i readVec(JsonElement e, Vec3i def, String where) throws FormatException {
        if (e == null || e.isJsonNull()) return def;
        if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) throw new FormatException(where + ": expected [x, y, z]");
        try {
            JsonArray a = e.getAsJsonArray();
            return new Vec3i(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
        } catch (RuntimeException ex) {
            throw new FormatException(where + ": expected [x, y, z] of whole numbers");
        }
    }

    static Transform readTransform(JsonObject o, String where) throws FormatException {
        if (o == null) return Transform.IDENTITY;
        Fields f = new Fields(o, where + ".transform");
        Vec3i pos = readVec(f.take("pos"), Vec3i.ZERO, where + ".transform.pos");
        int rot = f.integer("rot", 0);
        if (rot < 0 || rot > 3) throw new FormatException(where + ".transform: rot must be 0 to 3");
        String m = f.str("mirror", "none");
        Mirror mirror = switch (m) {
            case "none" -> Mirror.NONE;
            case "left_right" -> Mirror.LEFT_RIGHT;
            case "front_back" -> Mirror.FRONT_BACK;
            default -> throw new FormatException(where + ".transform: unknown mirror '" + m + "'");
        };
        return new Transform(pos, Rot.values()[rot], mirror);
    }

    static List<Modifier> readMods(JsonElement e, String where) throws FormatException {
        List<Modifier> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (!e.isJsonArray()) throw new FormatException(where + ": 'mods' must be a list");
        int i = 0;
        for (JsonElement m : e.getAsJsonArray()) {
            String w = where + ".mods[" + i++ + "]";
            if (!m.isJsonObject()) throw new FormatException(w + ": must be an object");
            Fields f = new Fields(m.getAsJsonObject(), w);
            String type = f.str("type", "");
            boolean enabled = f.bool("enabled", true);
            switch (type) {
                case "array" -> {
                    ArrayModifier a = new ArrayModifier();
                    a.enabled = enabled;
                    a.count = Math.max(1, f.integer("count", 2));
                    a.useRelative = f.bool("useRelative", true);
                    JsonElement rel = f.take("relative");
                    if (rel != null && rel.isJsonArray() && rel.getAsJsonArray().size() == 3) {
                        try {
                            for (int k = 0; k < 3; k++) a.relative[k] = rel.getAsJsonArray().get(k).getAsDouble();
                        } catch (RuntimeException ex) {
                            throw new FormatException(w + ": 'relative' must be three numbers");
                        }
                    }
                    a.useConstant = f.bool("useConstant", false);
                    Vec3i c = readVec(f.take("constant"), Vec3i.ZERO, w + ".constant");
                    a.constant = new int[]{c.x(), c.y(), c.z()};
                    out.add(a);
                }
                case "boolean" -> {
                    BooleanModifier b = new BooleanModifier();
                    b.enabled = enabled;
                    String op = f.str("op", "difference");
                    b.op = switch (op) {
                        case "difference" -> BooleanModifier.Op.DIFFERENCE;
                        case "intersect" -> BooleanModifier.Op.INTERSECT;
                        default -> throw new FormatException(w + ": unknown op '" + op + "'");
                    };
                    String t = f.str("target", null);
                    if (t != null) {
                        b.targetId = Ids.parseOrNull(t);
                        if (b.targetId == null) throw new FormatException(w + ": target is not an id");
                    }
                    out.add(b);
                }
                default -> throw new FormatException(w + ": unknown modifier type '" + type + "'");
            }
        }
        return out;
    }

    /** Reads a list of nodes; {@code ids} collects the ids seen, to reject a repeated one. */
    static List<Node> readNodes(JsonElement e, String where, Set<UUID> ids) throws FormatException {
        List<Node> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (!e.isJsonArray()) throw new FormatException(where + " must be a list");
        int i = 0;
        for (JsonElement n : e.getAsJsonArray()) {
            if (!n.isJsonObject()) throw new FormatException(where + "[" + i + "] must be an object");
            out.add(readNode(n.getAsJsonObject(), where + "[" + i + "]", ids));
            i++;
        }
        return out;
    }

    static Node readNode(JsonObject o, String where, Set<UUID> ids) throws FormatException {
        Fields f = new Fields(o, where);
        String idText = f.str("id", null);
        UUID id;
        if (idText == null) {
            id = Ids.newId();
        } else {
            id = Ids.parseOrNull(idText);
            if (id == null) throw new FormatException(where + ": id '" + idText + "' is not a UUID");
        }
        if (!ids.add(id)) throw new FormatException(where + ": the id " + id + " is used twice");
        String type = f.str("type", "");
        String name = f.str("name", "");
        boolean hidden = f.bool("hidden", false);
        Node node;
        switch (type) {
            case "folder" -> {
                FolderNode n = new FolderNode(id, name);
                n.setOpen(f.bool("open", true));
                attach(n, readNodes(f.take("children"), where + ".children", ids));
                node = n;
            }
            case "object" -> {
                ObjectNode n = new ObjectNode(id, name);
                n.setFile(f.str("file", ""));
                n.setTransform(readTransform(f.object("transform"), where));
                n.setFront(f.integer("front", 0));
                n.setWire(f.bool("wire", false));
                n.setMods(readMods(f.take("mods"), where));
                JsonObject pids = f.object("placementIds");
                if (pids != null) {
                    for (Map.Entry<String, JsonElement> en : pids.entrySet()) {
                        if (!en.getValue().isJsonPrimitive()) throw new FormatException(where + ".placementIds: values must be text");
                        n.placementIds().put(en.getKey(), en.getValue().getAsString());
                    }
                }
                node = n;
            }
            case "assembly" -> {
                AssemblyNode n = new AssemblyNode(id, name);
                n.setTransform(readTransform(f.object("transform"), where));
                n.setFront(f.integer("front", 0));
                n.setMods(readMods(f.take("mods"), where));
                JsonObject src = f.object("source");
                JsonElement kids = f.take("children");
                if (src != null) {
                    Fields sf = new Fields(src, where + ".source");
                    String st = sf.str("type", "local");
                    if (st.equals("link")) {
                        String p = sf.str("path", null);
                        if (p == null) throw new FormatException(where + ".source: a link needs a 'path'");
                        n.setSource(new AssemblyNode.Link(p));
                    } else if (!st.equals("local")) {
                        throw new FormatException(where + ".source: unknown type '" + st + "'");
                    }
                }
                // the parts of a linked assembly come from the .lasm, never from this file
                if (!n.isLinked()) attach(n, readNodes(kids, where + ".children", ids));
                node = n;
            }
            case "external" -> {
                String typeId = f.str("typeId", null);
                if (typeId == null) throw new FormatException(where + ": an external node needs a 'typeId'");
                ExternalNode n = new ExternalNode(id, name, typeId);
                n.setTransform(readTransform(f.object("transform"), where));
                JsonObject data = f.object("data");
                n.setData(data == null ? new JsonObject() : data);
                List<Modifier> em = new ArrayList<>();
                for (Modifier m : readMods(f.take("mods"), where)) if (m instanceof ArrayModifier) em.add(m);
                n.setMods(em);
                node = n;
            }
            default -> throw new FormatException(where + ": unknown node type '" + type + "'");
        }
        node.setHidden(hidden);
        node.setExtra(f.rest);
        return node;
    }

    private static void attach(Node parent, List<Node> kids) {
        for (Node k : kids) parent.addChild(k);
    }

    // ---- writing ----

    static JsonArray vec(Vec3i v) {
        JsonArray a = new JsonArray();
        a.add(v.x());
        a.add(v.y());
        a.add(v.z());
        return a;
    }

    static JsonObject transform(Transform t) {
        JsonObject o = new JsonObject();
        o.add("pos", vec(t.pos()));
        o.addProperty("rot", t.rot().steps());
        o.addProperty("mirror", switch (t.mirror()) {
            case NONE -> "none";
            case LEFT_RIGHT -> "left_right";
            case FRONT_BACK -> "front_back";
        });
        return o;
    }

    static JsonArray mods(List<Modifier> mods) {
        JsonArray out = new JsonArray();
        for (Modifier m : mods) {
            JsonObject o = new JsonObject();
            if (m instanceof ArrayModifier a) {
                o.addProperty("type", "array");
                o.addProperty("enabled", a.enabled);
                o.addProperty("count", a.count);
                o.addProperty("useRelative", a.useRelative);
                JsonArray rel = new JsonArray();
                for (double d : a.relative) rel.add(d);
                o.add("relative", rel);
                o.addProperty("useConstant", a.useConstant);
                o.add("constant", vec(new Vec3i(a.constant[0], a.constant[1], a.constant[2])));
            } else if (m instanceof BooleanModifier b) {
                o.addProperty("type", "boolean");
                o.addProperty("enabled", b.enabled);
                o.addProperty("op", b.op == BooleanModifier.Op.DIFFERENCE ? "difference" : "intersect");
                if (b.targetId != null) o.addProperty("target", b.targetId.toString());
            }
            out.add(o);
        }
        return out;
    }

    static JsonArray nodes(List<Node> list) {
        JsonArray a = new JsonArray();
        for (Node n : list) a.add(node(n));
        return a;
    }

    static JsonObject node(Node n) {
        JsonObject o = new JsonObject();
        o.addProperty("id", n.id().toString());
        if (n instanceof FolderNode f) {
            o.addProperty("type", "folder");
            o.addProperty("name", n.name());
            o.addProperty("hidden", n.hidden());
            o.addProperty("open", f.open());
            o.add("children", nodes(n.children()));
        } else if (n instanceof ObjectNode ob) {
            o.addProperty("type", "object");
            o.addProperty("name", n.name());
            o.addProperty("hidden", n.hidden());
            o.addProperty("file", ob.file());
            o.add("transform", transform(ob.transform()));
            o.addProperty("front", ob.front());
            o.addProperty("wire", ob.wire());
            o.add("mods", mods(ob.mods()));
            JsonObject pids = new JsonObject();
            for (Map.Entry<String, String> e : ob.placementIds().entrySet()) pids.add(e.getKey(), new JsonPrimitive(e.getValue()));
            o.add("placementIds", pids);
        } else if (n instanceof AssemblyNode a) {
            o.addProperty("type", "assembly");
            o.addProperty("name", n.name());
            o.addProperty("hidden", n.hidden());
            o.add("transform", transform(a.transform()));
            o.addProperty("front", a.front());
            o.add("mods", mods(a.mods()));
            JsonObject src = new JsonObject();
            if (a.source() instanceof AssemblyNode.Link l) {
                src.addProperty("type", "link");
                src.addProperty("path", l.path());
            } else {
                src.addProperty("type", "local");
            }
            o.add("source", src);
            if (!a.isLinked()) o.add("children", nodes(n.children()));
        } else if (n instanceof ExternalNode x) {
            o.addProperty("type", "external");
            o.addProperty("name", n.name());
            o.addProperty("hidden", n.hidden());
            o.addProperty("typeId", x.typeId());
            o.add("transform", transform(x.transform()));
            o.add("data", x.data().deepCopy());
            if (!x.mods().isEmpty()) o.add("mods", mods(x.mods()));
        } else {
            throw new IllegalArgumentException("a " + n.getClass().getSimpleName() + " cannot be saved as a child node");
        }
        for (Map.Entry<String, JsonElement> e : n.extra().entrySet()) {
            if (!o.has(e.getKey())) o.add(e.getKey(), e.getValue().deepCopy());
        }
        return o;
    }

    static Set<UUID> newIdSet() {
        return new HashSet<>();
    }
}
