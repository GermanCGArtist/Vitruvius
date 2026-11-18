package dev.vitruvius.editor.sync.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The history fields of a manifest (version 2): which commit it is, its parents, the branch, whether it is still a draft
 * and the message. A draft is the working state of one branch: each push replaces it. Sealing it (the Commit button)
 * writes the same commit again with {@code draft=false} and a message; from then on its placement is never removed by a push.
 * Pure.
 */
public record CommitMeta(String commit, List<String> parents, String branch, boolean draft, String message) {
    public static final String MAIN = "main";

    public CommitMeta {
        parents = List.copyOf(parents);
        branch = branch == null || branch.isBlank() ? MAIN : branch;
        message = message == null ? "" : message;
    }

    public static boolean isMain(String branch) {
        return branch == null || branch.isBlank() || MAIN.equals(branch);
    }

    /** A branch name as written in a placement name: no ']' (it would end the bracket), no line breaks, at most 32 characters. */
    public static String cleanBranch(String branch) {
        if (isMain(branch)) return MAIN;
        String s = branch.strip().replace(']', ')').replace('[', '(').replaceAll("[\r\n\t]", " ");
        if (s.length() > 32) s = s.substring(0, 32);
        return s.isBlank() ? MAIN : s;
    }

    public void write(JsonObject o) {
        o.addProperty("commit", commit);
        JsonArray p = new JsonArray();
        for (String s : parents) p.add(s);
        o.add("parents", p);
        o.addProperty("branch", branch);
        o.addProperty("draft", draft);
        o.addProperty("msg", message);
    }

    public static CommitMeta read(JsonObject o) {
        String commit = str(o, "commit");
        if (commit.isEmpty()) throw new IllegalArgumentException("the manifest has no commit id");
        List<String> parents = new ArrayList<>();
        if (o.has("parents") && o.get("parents").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("parents")) if (e.isJsonPrimitive()) parents.add(e.getAsString());
        }
        boolean draft = o.has("draft") && o.get("draft").isJsonPrimitive() && o.get("draft").getAsBoolean();
        return new CommitMeta(commit, parents, str(o, "branch"), draft, str(o, "msg"));
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
