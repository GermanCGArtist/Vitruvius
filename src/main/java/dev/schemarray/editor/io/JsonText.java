package dev.schemarray.editor.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Map;

/**
 * Stable pretty printing: two-space indent, keys in insertion order, arrays that hold only plain values on one line,
 * a newline at the end. The same tree always gives the same text. (Numbers keep the text they were read with.)
 */
final class JsonText {
    private JsonText() {}

    static String write(JsonElement e) {
        StringBuilder sb = new StringBuilder();
        write(e, sb, 0);
        sb.append('\n');
        return sb.toString();
    }

    static JsonElement parse(String text) throws FormatException {
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException ex) {
            throw new FormatException("not valid JSON: " + ex.getMessage(), ex);
        }
    }

    private static void write(JsonElement e, StringBuilder sb, int indent) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.size() == 0) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                pad(sb, indent + 2);
                sb.append(new JsonPrimitive(en.getKey())).append(": ");
                write(en.getValue(), sb, indent + 2);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append('}');
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() == 0) {
                sb.append("[]");
                return;
            }
            boolean flat = true;
            for (JsonElement x : a) if (x.isJsonObject() || x.isJsonArray()) flat = false;
            if (flat) {
                sb.append('[');
                for (int i = 0; i < a.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(a.get(i));
                }
                sb.append(']');
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) sb.append(",\n");
                pad(sb, indent + 2);
                write(a.get(i), sb, indent + 2);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append(']');
        } else {
            sb.append(e);
        }
    }

    private static void pad(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append(' ');
    }
}
