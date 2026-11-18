package dev.vitruvius.editor.sync.history;

import com.google.gson.JsonObject;
import dev.vitruvius.editor.sync.SyncManifest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One commit as read from a manifest placement on the server: a snapshot of the scene (project.json without what stays per
 * player) and the Syncmatica placement id of every cell file it needs. {@code placementId} is the manifest placement itself
 * (null for a commit that is not on the server yet). Pure.
 */
public record Commit(String id, List<String> parents, String branch, String by, long at, String message, boolean draft,
                     JsonObject project, Map<String, String> cells, String placementId) {
    public Commit {
        parents = List.copyOf(parents);
        cells = Map.copyOf(cells);
        branch = CommitMeta.isMain(branch) ? CommitMeta.MAIN : branch;
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * The commit a manifest stands for. A version 1 manifest (no history fields) is the live state of main: a draft whose
     * id is its placement id and that has no parent.
     */
    public static Commit of(SyncManifest.Payload pl, String placementId) {
        CommitMeta m = pl.meta();
        if (m == null) return new Commit("v1-" + placementId, List.of(), CommitMeta.MAIN, pl.by(), pl.at(), "", true, pl.project(), pl.cells(), placementId);
        return new Commit(m.commit(), m.parents(), m.branch(), pl.by(), pl.at(), m.message(), m.draft(), pl.project(), pl.cells(), placementId);
    }

    public CommitMeta meta() {
        return new CommitMeta(id, parents, branch, draft, message);
    }

    public Commit withPlacement(String placement) {
        return new Commit(id, parents, branch, by, at, message, draft, project, cells, placement);
    }

    public boolean main() {
        return CommitMeta.isMain(branch);
    }

    /** First line of the message, or a placeholder for a draft or an empty message. */
    public String title() {
        if (draft) return "working changes";
        int nl = message.indexOf('\n');
        String t = (nl < 0 ? message : message.substring(0, nl)).strip();
        return t.isEmpty() ? "(no message)" : t;
    }

    public String shortId() {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
