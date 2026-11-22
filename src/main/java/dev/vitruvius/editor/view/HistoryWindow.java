package dev.vitruvius.editor.view;

import dev.cg.uihost.Theme;
import dev.cg.uihost.UiHost;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.sync.SyncHistory;
import dev.vitruvius.editor.sync.SyncService;
import dev.vitruvius.editor.sync.SyncmaticaBridge;
import dev.vitruvius.editor.sync.history.Commit;
import dev.vitruvius.editor.sync.history.CommitGraph;
import dev.vitruvius.editor.sync.history.GraphLayout;
import dev.vitruvius.editor.sync.history.Retention;
import imgui.ImDrawList;
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
 * The History window: the branches of the active shared project, a Git-style branch graph (or single-branch log), and
 * the actions on them (commit, new branch, switch, merge into the checked-out branch, restore an old commit, clean up).
 * All of the logic is in {@link SyncHistory} and {@link GraphLayout}; this class only draws and forwards clicks.
 */
public final class HistoryWindow {
    private HistoryWindow() {}

    private static final ImBoolean OPEN = new ImBoolean(true);
    private static final ImString MESSAGE = new ImString(200);
    private static final ImString NEW_BRANCH = new ImString(40);
    private static final ImInt DAYS = new ImInt(30);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

    /** Distinct branch lane colors (index 0 = main). */
    private static final int[] LANE_COLORS = {
            Ui.LINK,          // main (sky blue)
            Ui.AX_Y,          // branch 1 (green)
            Ui.AREA,          // branch 2 (amber)
            Ui.BOOL,          // branch 3 (violet)
            Ui.AX_X,          // branch 4 (coral)
            0x5eead4,         // branch 5 (teal)
            0xf472b6,         // branch 6 (pink)
            0xfacc15          // branch 7 (yellow)
    };

    private static String shownBranch;       // the branch selected for Switch/Merge/Delete or single-branch filter
    private static String selectedCommit;
    private static boolean allBranches = true;
    private static Retention.Prune pending;  // a cleanup waiting for confirmation
    private static String pendingLabel = "";

    public static void draw(EditorSession s) {
        float sc = Ui.s();
        var vp = ImGui.getMainViewport();
        OPEN.set(s.showHistory);
        ImGui.setNextWindowPos(vp.getPosX() + vp.getSizeX() * 0.5f - 260 * sc, vp.getPosY() + 80 * sc, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(520 * sc, 600 * sc, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSizeConstraints(360 * sc, 320 * sc, 4000, 4000);
        if (Ui.beginWindow("06 HISTORY###History", OPEN, 0, 0.96f)) drawContent(s);
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
        for (int i = 0; i < branches.size(); i++) {
            String b = branches.get(i);
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

        // ---- graph / history
        Ui.section("History");
        int mode = Ui.segmented("##hist_mode", new String[] {"All branches (Graph)", "Branch: " + trim(shownBranch)}, allBranches ? 0 : 1, ImGui.getContentRegionAvailX(), false);
        if (mode >= 0) allBranches = mode == 0;

        GraphLayout.Layout layout = GraphLayout.build(g, allBranches ? null : shownBranch);
        float logH = Math.max(140 * sc, ImGui.getContentRegionAvailY() - 118 * sc);
        if (ImGui.beginChild("##graph", 0, logH, true)) {
            if (layout.isEmpty()) {
                Ui.dim(allBranches ? "No commits yet." : "No commits on " + shownBranch + " yet.");
            } else {
                drawGraph(layout, v.branch());
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

    private static void drawGraph(GraphLayout.Layout layout, String currentBranch) {
        float sc = Ui.s();
        float rowH = 26 * sc;
        float laneStep = 16 * sc;
        float railPad = 12 * sc;
        float railW = railPad + Math.max(1, layout.laneCount()) * laneStep + 8 * sc;
        float originX = ImGui.getCursorScreenPosX();
        float originY = ImGui.getCursorScreenPosY();
        float availW = Math.max(200 * sc, ImGui.getContentRegionAvailX());
        ImDrawList dl = ImGui.getWindowDrawList();

        // 1. Row click targets & selection/hover backgrounds
        List<GraphLayout.Row> rows = layout.rows();
        for (int r = 0; r < rows.size(); r++) {
            GraphLayout.Row row = rows.get(r);
            Commit c = row.commit();
            float ry = originY + r * rowH;
            boolean isSel = c.id().equals(selectedCommit);
            ImGui.setCursorScreenPos(originX, ry);
            if (ImGui.invisibleButton("##grow_" + c.id(), availW, rowH)) {
                selectedCommit = c.id();
                if (!row.heads().isEmpty()) shownBranch = row.heads().get(0);
            }
            boolean hov = ImGui.isItemHovered();
            if (isSel) {
                dl.addRectFilled(originX, ry + 1, originX + availW, ry + rowH - 1, Ui.c(Theme.SEL), 0f);
                dl.addRectFilled(originX, ry + 1, originX + 2 * sc, ry + rowH - 1, Ui.c(Theme.ACCENT), 0f);
            } else if (hov) {
                dl.addRectFilled(originX, ry + 1, originX + availW, ry + rowH - 1, Ui.c(Theme.SURFACE), 0f);
            }
            if (hov) {
                StringBuilder tip = new StringBuilder();
                tip.append(c.shortId()).append(" · ").append(c.branch());
                if (c.draft()) tip.append(" (working changes)");
                if (c.parents().size() > 1) tip.append(" (merge)");
                tip.append("\n").append(c.by()).append(" · ").append(TIME.format(Instant.ofEpochMilli(c.at())));
                if (!c.message().isEmpty()) tip.append("\n\n").append(c.message());
                ImGui.setTooltip(tip.toString());
            }
        }

        // 2. Edges (drawn behind commit nodes)
        float lineW = 2f * sc;
        for (GraphLayout.Edge e : layout.edges()) {
            float x1 = originX + railPad + e.fromLane() * laneStep;
            float y1 = originY + e.fromRow() * rowH + rowH * 0.5f;
            float x2 = originX + railPad + e.toLane() * laneStep;
            float y2 = originY + e.toRow() * rowH + rowH * 0.5f;
            int col = Ui.c(laneColor(e.color()), e.merge() ? 0.78f : 0.92f);
            if (e.fromLane() == e.toLane()) {
                dl.addLine(x1, y1, x2, y2, col, lineW);
            } else {
                float dy = Math.min(rowH * 0.85f, (y2 - y1) * 0.5f);
                if (e.toRow() - e.fromRow() == 1) {
                    dl.addBezierCubic(x1, y1, x1, y1 + dy, x2, y2 - dy, x2, y2, col, lineW);
                } else if (e.merge()) {
                    // Merge edge curves out into the parent's lane immediately, then drops vertically
                    float midY = y1 + rowH;
                    dl.addBezierCubic(x1, y1, x1, y1 + rowH * 0.55f, x2, midY - rowH * 0.45f, x2, midY, col, lineW);
                    dl.addLine(x2, midY, x2, y2, col, lineW);
                } else {
                    // Branch-off edge drops vertically in child's lane, then curves into parent's lane at the bottom
                    float midY = y2 - rowH;
                    dl.addLine(x1, y1, x1, midY, col, lineW);
                    dl.addBezierCubic(x1, midY, x1, midY + rowH * 0.55f, x2, y2 - rowH * 0.45f, x2, y2, col, lineW);
                }
            }
        }

        // 3. Commit nodes + branch head pills + labels
        float dotR = 4.5f * sc;
        for (int r = 0; r < rows.size(); r++) {
            GraphLayout.Row row = rows.get(r);
            Commit c = row.commit();
            float cx = originX + railPad + row.lane() * laneStep;
            float ry = originY + r * rowH;
            float cy = ry + rowH * 0.5f;
            int rgb = laneColor(row.color());
            boolean isSel = c.id().equals(selectedCommit);

            if (isSel) {
                dl.addCircle(cx, cy, dotR + 2.5f * sc, Ui.c(Theme.FG, 0.9f), 16, 1.5f * sc);
            }
            if (c.draft()) {
                dl.addCircleFilled(cx, cy, dotR, Ui.c(Theme.PANEL), 16);
                dl.addCircle(cx, cy, dotR, Ui.c(rgb), 16, 2f * sc);
            } else if (c.parents().size() > 1) {
                dl.addCircleFilled(cx, cy, dotR + 1f * sc, Ui.c(Theme.PANEL), 16);
                dl.addCircle(cx, cy, dotR + 1f * sc, Ui.c(rgb), 16, 1.8f * sc);
                dl.addCircleFilled(cx, cy, dotR * 0.5f, Ui.c(rgb), 12);
            } else {
                dl.addCircleFilled(cx, cy, dotR, Ui.c(rgb), 16);
                dl.addCircle(cx, cy, dotR, Ui.c(Theme.PANEL), 16, 1.2f * sc);
            }

            // Right-hand text area
            float tx = originX + railW;
            float maxX = originX + availW - 6 * sc;

            // Right-aligned metadata (author + time)
            String meta = c.by() + " · " + TIME.format(Instant.ofEpochMilli(c.at()));
            Theme.push(UiHost.theme().small());
            float metaW = ImGui.calcTextSizeX(meta);
            float metaX = Math.max(tx + 80 * sc, maxX - metaW);
            dl.addText(metaX, ry + (rowH - ImGui.getFontSize()) * 0.5f, Ui.c(Theme.DIM), meta);
            ImGui.popFont();

            float textLimit = metaX - 8 * sc;

            // Branch head badges
            for (String b : row.heads()) {
                tx = drawBranchBadge(dl, tx, ry, rowH, b, b.equals(currentBranch), false, rgb, textLimit);
            }
            for (String b : row.divergedHeads()) {
                tx = drawBranchBadge(dl, tx, ry, rowH, b + "!", false, true, Theme.WARN, textLimit);
            }

            // Commit title
            if (tx < textLimit - 12 * sc) {
                String title = c.title();
                if (c.parents().size() > 1 && !title.toLowerCase(java.util.Locale.ROOT).startsWith("merge")) {
                    title = "Merge · " + title;
                }
                String shown = Ui.fitEnd(title, textLimit - tx);
                int titleCol = c.draft() ? Theme.DIM : Theme.FG;
                dl.addText(tx, ry + (rowH - ImGui.getFontSize()) * 0.5f, Ui.c(titleCol), shown);
            }
        }
    }

    private static float drawBranchBadge(ImDrawList dl, float x, float ry, float rowH, String label,
                                         boolean current, boolean warn, int rgb, float maxX) {
        float sc = Ui.s();
        Theme.push(UiHost.theme().monoS());
        String text = (current ? "● " : "") + label;
        float tw = ImGui.calcTextSizeX(text);
        float bw = tw + 10 * sc;
        float bh = 16 * sc;
        if (x + bw > maxX) {
            ImGui.popFont();
            return x;
        }
        float by = ry + (rowH - bh) * 0.5f;
        dl.addRectFilled(x, by, x + bw, by + bh, Ui.c(rgb, current ? 0.24f : 0.14f), 0f);
        dl.addRect(x, by, x + bw, by + bh, Ui.c(rgb, current ? 0.90f : 0.55f), 0f);
        dl.addText(x + 5 * sc, by + (bh - ImGui.getFontSize()) * 0.5f, Ui.c(warn ? Theme.WARN : (current ? Theme.FG : rgb)), text);
        ImGui.popFont();
        return x + bw + 5 * sc;
    }

    private static int laneColor(int index) {
        return switch (Math.floorMod(index, 8)) {
            case 0 -> Ui.LINK;
            case 1 -> Ui.AX_Y;
            case 2 -> Ui.AREA;
            case 3 -> Ui.BOOL;
            case 4 -> Ui.AX_X;
            case 5 -> Theme.ACCENT;
            case 6 -> Theme.META;
            default -> Theme.WARN;
        };
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
