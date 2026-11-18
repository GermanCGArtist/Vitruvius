package dev.vitruvius.editor.view;

import dev.cg.uihost.Theme;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.sync.SyncHistory;
import dev.vitruvius.editor.sync.SyncService;
import dev.vitruvius.editor.sync.SyncmaticaBridge;
import dev.vitruvius.editor.sync.history.Commit;
import dev.vitruvius.editor.sync.history.CommitGraph;
import dev.vitruvius.editor.sync.history.Retention;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The History window: the branches of the active shared project, the commit log of one of them, and the actions on them
 * (commit, new branch, switch, merge into the checked-out branch, restore an old commit, clean up). All of the logic is in
 * {@link SyncHistory}; this class only draws and forwards clicks.
 */
public final class HistoryWindow {
    private HistoryWindow() {}

    private static final ImBoolean OPEN = new ImBoolean(true);
    private static final ImString MESSAGE = new ImString(200);
    private static final ImString NEW_BRANCH = new ImString(40);
    private static final ImInt DAYS = new ImInt(30);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

    private static String shownBranch;       // the branch whose log is listed
    private static String selectedCommit;
    private static Retention.Prune pending;  // a cleanup waiting for confirmation
    private static String pendingLabel = "";

    public static void draw(EditorSession s) {
        float sc = Ui.s();
        var vp = ImGui.getMainViewport();
        OPEN.set(s.showHistory);
        ImGui.setNextWindowPos(vp.getPosX() + vp.getSizeX() * 0.5f - 230 * sc, vp.getPosY() + 90 * sc, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(460 * sc, 560 * sc, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSizeConstraints(340 * sc, 300 * sc, 4000, 4000);
        if (Ui.beginWindow("History", OPEN, 0, 0.96f)) drawContent(s);
        Ui.endWindow();
        s.showHistory = OPEN.get();
    }

    private static void drawContent(EditorSession s) {
        float sc = Ui.s();
        ProjectNode p = s.scene().activeProject();
        if (p == null) {
            Ui.dim("Select a project in the Scene window.");
            return;
        }
        SyncService svc = SyncService.get();
        if (p.sync() != ProjectNode.Sync.SHARED) {
            Ui.dim("\"" + p.name() + "\" is not shared. Share it with Syncmatica to get branches and history.");
            return;
        }
        if (!SyncmaticaBridge.available() || !SyncmaticaBridge.connected()) {
            Ui.dim("Not connected to a server with Syncmatica.");
            return;
        }
        SyncHistory h = svc.history();
        SyncHistory.View v = h.view(p);
        CommitGraph g = v.graph();
        Ui.label(p.name());
        ImGui.sameLine();
        Ui.colored(Ui.LINK, "on " + v.branch());
        if (v.busy()) {
            ImGui.sameLine();
            Ui.dim("working…");
        }

        // ---- commit
        Ui.section("Commit");
        ImGui.setNextItemWidth(-90 * sc);
        ImGui.inputTextWithHint("##msg", "Message", MESSAGE);
        ImGui.sameLine();
        if (Ui.button("Commit", true, v.canCommit() && !v.busy(), 80 * sc, v.canCommit() ? null : "Nothing changed since the last commit")) {
            h.commit(p, MESSAGE.get());
            MESSAGE.set("");
        }

        // ---- branches
        Ui.section("Branches");
        List<String> branches = g.branches();
        if (!branches.contains(v.branch())) branches = append(branches, v.branch());
        if (shownBranch == null || !branches.contains(shownBranch)) shownBranch = v.branch();
        for (String b : branches) {
            Commit head = g.head(b).orElse(null);
            boolean current = b.equals(v.branch());
            String line = (current ? "● " : "   ") + b + (head == null ? "  (not pushed yet)" : "    " + head.by() + ", " + TIME.format(Instant.ofEpochMilli(head.at()))
                    + (head.draft() ? ", working changes" : "")) + (g.diverged(b) ? "  (diverged)" : "")
                    + (!current && g.ahead(b, v.branch()) ? "  ahead" : "");
            if (ImGui.selectable(line + "##br_" + b, b.equals(shownBranch))) {
                shownBranch = b;
                selectedCommit = null;
            }
        }
        boolean other = !shownBranch.equals(v.branch());
        boolean has = g.head(shownBranch).isPresent();
        float bw = 112 * sc;
        if (Ui.button("Switch to it", false, other && has && !v.busy(), bw, other ? null : "Already on it")) h.checkout(p, shownBranch);
        ImGui.sameLine();
        if (Ui.button("Merge into " + trim(v.branch()), false, other && has && !v.busy(), 150 * sc,
                other ? "Merges the newest commit of " + shownBranch + " into " + v.branch() + " and commits it" : "Pick another branch")) h.merge(p, shownBranch);
        ImGui.sameLine();
        if (Ui.button("Delete", false, other && !shownBranch.equals("main"), 70 * sc, "Removes the branch's commits that no other branch contains")) {
            pending = h.previewDeleteBranch(p, shownBranch);
            pendingLabel = "Delete branch " + shownBranch;
        }
        ImGui.setNextItemWidth(160 * sc);
        ImGui.inputTextWithHint("##nb", "New branch name", NEW_BRANCH);
        ImGui.sameLine();
        if (Ui.button("New branch", false, !NEW_BRANCH.get().isBlank(), 100 * sc, "Enter a name")) {
            h.newBranch(p, NEW_BRANCH.get());
            NEW_BRANCH.set("");
        }

        // ---- log
        Ui.section("History of " + shownBranch);
        Commit head = g.head(shownBranch).orElse(null);
        float logH = Math.max(120 * sc, ImGui.getContentRegionAvailY() - 110 * sc);
        if (ImGui.beginChild("##log", 0, logH, true)) {
            if (head == null) Ui.dim("No commits on this branch yet.");
            else {
                for (Commit c : g.log(head.id())) {
                    String line = TIME.format(Instant.ofEpochMilli(c.at())) + "  " + c.by() + "  " + c.title()
                            + (c.parents().size() > 1 ? "  (merge)" : "") + (c.branch().equals(shownBranch) ? "" : "  [" + c.branch() + "]");
                    if (ImGui.selectable(line + "##c_" + c.id(), c.id().equals(selectedCommit))) selectedCommit = c.id();
                    if (ImGui.isItemHovered()) Ui.tooltip(c.shortId() + (c.message().isEmpty() ? "" : "\n" + c.message()));
                }
            }
        }
        ImGui.endChild();
        Commit sel = selectedCommit == null ? null : g.get(selectedCommit);
        if (Ui.button("Restore this state", false, sel != null && !v.busy(), 140 * sc,
                sel == null ? "Select a commit" : "Brings the project back to this commit and commits it on " + v.branch())) h.restore(p, sel.id());

        // ---- cleanup
        Ui.section("Clean up");
        ImGui.setNextItemWidth(80 * sc);
        ImGui.inputInt("days##d", DAYS);
        if (DAYS.get() < 1) DAYS.set(1);
        ImGui.sameLine();
        if (Ui.button("Older commits", false, true, 120 * sc, "Drops sealed commits older than this, except branch heads, and the files only they use")) {
            pending = h.previewOlderThan(p, DAYS.get());
            pendingLabel = "Delete commits older than " + DAYS.get() + " days";
        }
        if (pending != null) {
            if (pending.isEmpty()) {
                Ui.dim("Nothing to remove.");
                if (Ui.button("OK", false, true, 60 * sc, null)) pending = null;
            } else {
                Ui.colored(Theme.WARN, pendingLabel + ": " + pending.commits().size() + " commits, " + pending.placements().size() + " placements on the server. This cannot be undone.");
                if (Ui.button("Remove", true, true, 80 * sc, null)) {
                    h.cleanup(pending);
                    pending = null;
                }
                ImGui.sameLine();
                if (Ui.button("Cancel", false, true, 80 * sc, null)) pending = null;
            }
        }
    }

    private static List<String> append(List<String> l, String x) {
        List<String> out = new java.util.ArrayList<>(l);
        out.add(x);
        return out;
    }

    private static String trim(String b) {
        return b.length() > 12 ? b.substring(0, 11) + "…" : b;
    }
}
