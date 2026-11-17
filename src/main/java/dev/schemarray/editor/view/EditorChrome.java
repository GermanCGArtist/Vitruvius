package dev.schemarray.editor.view;

import dev.cg.uihost.Theme;
import dev.cg.uihost.UiHost;
import dev.schemarray.editor.EditorMode;
import dev.schemarray.editor.EditorSession;
import dev.schemarray.editor.EditorSession.Tool;
import dev.schemarray.editor.cam.EditorCamera;
import dev.schemarray.editor.cam.SpectatorFollow;
import dev.schemarray.editor.core.ArrayCells;
import dev.schemarray.editor.core.ArrayModifier;
import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.Box;
import dev.schemarray.editor.core.Modifier;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.ProjectNode;
import dev.schemarray.editor.gizmo.Pivots;
import dev.schemarray.editor.ui.MenuModel;
import dev.schemarray.editor.world.Gizmo;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The editor chrome (DESIGN 5 and 6): the toolbar at the top (mode chip, Add, tools, Mirror, Snap/Pivot/Local view,
 * Undo/Redo, Windows, Push, quick guide) that narrows in three steps (labels, key caps, overflow menu), the status
 * bar with hints per mode and the selection summary, and the fly HUD. Without Litematica (no session) only the mode
 * chip and the navigation hints show.
 */
public final class EditorChrome {
    private static final int FIXED = ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoScrollbar
            | ImGuiWindowFlags.NoScrollWithMouse | ImGuiWindowFlags.NoCollapse;

    private record Hint(String[] caps, String verb) {
        static Hint of(String verb, String... caps) { return new Hint(caps, verb); }
    }

    private static final List<Hint> HINTS_IDLE = List.of(
            Hint.of("select", "LMB"),
            Hint.of("orbit", "MMB"),
            Hint.of("pan", "Shift", "MMB"),
            Hint.of("zoom", "Wheel"),
            Hint.of("fly", "hold RMB"),
            Hint.of("menu", "RMB"),
            Hint.of("add", "Shift", "A"),
            Hint.of("leave", "F6"));

    private static final List<Hint> HINTS_SELECTED = List.of(
            Hint.of("move", "G"),
            Hint.of("rotate", "R"),
            Hint.of("mirror", "M"),
            Hint.of("duplicate", "Shift", "D"),
            Hint.of("hide", "H"),
            Hint.of("delete", "X"),
            Hint.of("frame", "F"),
            Hint.of("menu", "RMB"));

    private static final List<Hint> HINTS_FLY = List.of(
            Hint.of("move", "W", "A", "S", "D"),
            Hint.of("down/up", "Q", "E"),
            Hint.of("fast", "Shift"),
            Hint.of("speed", "Wheel"),
            Hint.of("stop", "release RMB"));

    private static final List<Hint> HINTS_RENAME = List.of(Hint.of("rename", "Enter"), Hint.of("cancel", "Esc"));

    private static final List<Hint> HINTS_TREE_DRAG = List.of(
            Hint.of("reorder: drop between rows", "drag"),
            Hint.of("move in: drop on a folder, project or local assembly", "drag"),
            Hint.of("cancel", "Esc"));

    private static boolean guideRequest;

    private EditorChrome() {}

    /** Draws toolbar, status bar and the fly HUD. {@code s} is null without Litematica. */
    public static void draw(EditorSession s) {
        float sc = UiHost.scale();
        ImGuiViewport vp = ImGui.getMainViewport();
        float toolbarBottom = drawToolbar(s, vp, sc);
        drawStatusBar(s, vp, sc);
        drawFlyHud(vp, sc, toolbarBottom);
    }

    // ------------------------------------------------------------------ toolbar

    /** One toolbar button's content. */
    private record Btn(String id, String icon, String label, String cap, boolean active, boolean enabled, String tip, String pill, int pillRgb, int pillText) {
        Btn(String id, String icon, String label, String cap, boolean active, boolean enabled, String tip) {
            this(id, icon, label, cap, active, enabled, tip, null, 0, 0);
        }
    }

    private static float pillWidth(String text, float sc) {
        return Math.max(17 * sc, ImGui.calcTextSizeX(text) * 0.85f + 10 * sc);
    }

    private static float btnWidth(Btn b, boolean labels, boolean caps, float sc) {
        float w = 16 * sc + 15 * sc;
        if (labels && b.label() != null) w += 6 * sc + ImGui.calcTextSizeX(b.label());
        if (caps && b.cap() != null) w += 6 * sc + Ui.capWidth(b.cap());
        if (b.pill() != null) w += 6 * sc + pillWidth(b.pill(), sc);
        return w;
    }

    /** Draws one button at the cursor; returns true when clicked. */
    private static boolean btn(Btn b, boolean labels, boolean caps, float sc, float h) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float w = btnWidth(b, labels, caps, sc);
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        boolean hit = ImGui.invisibleButton("##tb_" + b.id(), w, h);
        boolean hot = ImGui.isItemHovered() && b.enabled();
        if (b.active() || hot) dl.addRectFilled(x, y, x + w, y + h, Ui.c(Theme.BTN), 6 * sc);
        if (b.active()) dl.addRect(x + 1, y + 1, x + w - 1, y + h - 1, Ui.c(Theme.ACCENT, 0.33f), 6 * sc);
        int rgb = !b.enabled() ? Theme.GUTTER : b.active() ? Theme.ACCENT : hot ? Theme.FG : Theme.DIM;
        float cx = x + 8 * sc;
        EditorIcons.draw(dl, b.icon(), cx + 7.5f * sc, y + h * 0.5f, 7.5f * sc, Ui.c(rgb));
        cx += 15 * sc;
        if (labels && b.label() != null) {
            cx += 6 * sc;
            dl.addText(cx, y + (h - ImGui.getFontSize()) * 0.5f, Ui.c(b.enabled() ? (b.active() ? Theme.ACCENT : hot ? Theme.FG : Theme.DIM) : Theme.GUTTER), b.label());
            cx += ImGui.calcTextSizeX(b.label());
        }
        if (caps && b.cap() != null) {
            cx += 6 * sc;
            Ui.keyCap(dl, cx, y + (h - 18 * sc) * 0.5f, b.cap(), b.enabled() ? Theme.DIM : Theme.GUTTER);
        }
        if (b.pill() != null) {
            cx += 6 * sc;
            float pw = pillWidth(b.pill(), sc), ph = 17 * sc, py = y + (h - ph) * 0.5f;
            dl.addRectFilled(cx, py, cx + pw, py + ph, Ui.c(b.pillRgb()), ph * 0.5f);
            Theme.push(UiHost.theme().small());
            dl.addText(cx + (pw - ImGui.calcTextSizeX(b.pill())) * 0.5f, py + (ph - ImGui.getFontSize()) * 0.5f, Ui.c(b.pillText()), b.pill());
            ImGui.popFont();
        }
        if (b.tip() != null && !b.tip().isEmpty()) Ui.tooltip(b.tip());
        return hit && b.enabled();
    }

    private static float drawToolbar(EditorSession s, ImGuiViewport vp, float sc) {
        float pad = 4 * sc, h = 28 * sc, barH = h + 2 * pad, top = vp.getPosY() + 10 * sc;
        Theme.push(UiHost.theme().small());
        String label = "EDITOR";
        float labelW = ImGui.calcTextSizeX(label);
        ImGui.popFont();
        float chipW = 8 * sc + labelW + 6 * sc + Ui.capWidth("F6") + 8 * sc;

        Btn add = new Btn("add", "plus", "Add", "Shift A", false, s != null, "Add (Shift A)");
        Btn sel = new Btn("sel", "cursor", "Select", "W", s != null && s.tool() == Tool.SELECT, s != null, "Select tool");
        Btn mov = new Btn("mov", "move", "Move", "G", s != null && s.tool() == Tool.MOVE, s != null, "Move tool: drag the gizmo, or press G and type an amount");
        Btn rot = new Btn("rot", "rotate", "Rotate", "R", s != null && s.tool() == Tool.ROTATE, s != null, "Rotate tool: click or drag the ring, or press R and type degrees");
        Btn box = new Btn("box", "box", "Box", "B", s != null && s.gizmo.modal() && s.gizmo.hint() == Gizmo.Hint.BOX, s != null, "Box select: drag a rectangle (Shift adds, Ctrl subtracts)");
        boolean hasSel = s != null && !s.selection.isEmpty();
        boolean canMirror = hasSel && !s.selectionLocked();
        Btn mir = new Btn("mir", "mirror", null, "M", false, canMirror, canMirror ? "Mirror X (M)" : hasSel ? "Mirror X: the selection is locked" : "Mirror X: nothing selected");
        Btn snap = new Btn("snap", "magnet", "Snap", null, s != null && s.snap, s != null, "Snap faces to other objects while dragging (hold Ctrl to invert)");
        Btn local = new Btn("local", "local", null, "/", s != null && s.localView, s != null, s != null && s.localView ? "Leave local view (/)" : "Local view: only the selection (/)");
        var undo = s == null ? null : s.undoStack();
        boolean canUndo = undo != null && undo.canUndo(), canRedo = undo != null && undo.canRedo();
        Btn und = new Btn("undo", "undo", null, null, false, canUndo, canUndo ? "Undo " + undo.undoLabel().orElse("") + " (Ctrl Z)" : "Nothing to undo");
        Btn red = new Btn("redo", "redo", null, null, false, canRedo, canRedo ? "Redo " + undo.redoLabel().orElse("") + " (Ctrl Shift Z)" : "Nothing to redo");
        Btn win = new Btn("win", "layout", null, null, false, s != null, "Windows");
        Btn topv = new Btn("topv", "map", null, null, s != null && s.showTopView, s != null, s != null && s.showTopView ? "Hide the top view" : "Show the top view");
        var sync = dev.schemarray.editor.sync.SyncService.get();
        boolean showPush = s != null && sync.installed();                 // hidden without Syncmatica (EDITOR-2.0 8)
        var syncStatus = sync.status();
        boolean pushing = syncStatus.state() == dev.schemarray.editor.sync.SyncService.State.PUSHING;
        boolean syncFailed = syncStatus.state() == dev.schemarray.editor.sync.SyncService.State.FAILED;
        int pend = sync.pending();
        boolean canPush = showPush && sync.usable() && sync.sharedProjects() > 0 && sync.connected() && !pushing;
        String pushTip = !showPush ? "" : !sync.usable() ? sync.reason()
                : sync.sharedProjects() == 0 ? "No project is shared on Syncmatica. Share one from its right-click menu."
                : !sync.connected() ? "Not connected to a server with Syncmatica"
                : pushing ? "Pushing to Syncmatica…"
                : syncFailed ? syncStatus.message() + " (click to retry)"
                : pend == 0 ? "Everything is pushed" : pend + " change" + (pend == 1 ? "" : "s") + " in shared projects aren't pushed yet";
        String pushLabel = pushing ? "Pushing " + syncStatus.done() + "/" + syncStatus.total() : "Push";
        String pill = !showPush || sync.sharedProjects() == 0 || pushing ? null : syncFailed ? "!" : String.valueOf(pend);
        int pillRgb = syncFailed ? Theme.ERR : pend > 0 ? Theme.WARN : Theme.CHIP;
        int pillText = syncFailed || pend > 0 ? 0x2a1a02 : Theme.DIM;
        Btn push = new Btn("push", "sync", pushLabel, null, false, canPush, pushTip, pill, pillRgb, pillText);
        Btn help = new Btn("help", "help", null, null, false, true, "Quick guide");
        float comboW = 128 * sc, sep = 1 * sc + 8 * sc;

        Theme.push(UiHost.theme().ui());
        float avail = vp.getSizeX() - 24 * sc;
        int stage = 0;
        float need = 0;
        for (; stage <= 3; stage++) {
            boolean labels = stage == 0, caps = stage <= 1;
            need = 2 * pad + chipW;
            if (s != null) {
                need += sep + btnWidth(add, labels, caps, sc) + sep;
                for (Btn b : new Btn[] {sel, mov, rot, box}) need += btnWidth(b, labels, caps, sc) + 4 * sc;
                need += sep + btnWidth(mir, labels, caps, sc);
                if (stage < 3) {
                    need += sep + btnWidth(snap, labels, caps, sc) + 4 * sc + comboW + 4 * sc + btnWidth(local, labels, caps, sc);
                    need += sep + btnWidth(und, false, false, sc) + 4 * sc + btnWidth(red, false, false, sc);
                    need += sep + btnWidth(win, false, false, sc) + 4 * sc + btnWidth(topv, false, false, sc);
                }
                if (showPush) need += sep + btnWidth(push, labels, false, sc);
                need += sep + btnWidth(help, false, false, sc);
            }
            if (need <= avail) break;
        }
        stage = Math.min(stage, 3);
        boolean labels = stage == 0, caps = stage <= 1, overflow = stage == 3;
        float w = Math.min(need, avail);

        ImGui.setNextWindowPos(vp.getPosX() + (vp.getSizeX() - w) * 0.5f, top);
        ImGui.setNextWindowSize(w, barH);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, pad, pad);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 8 * sc);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 4 * sc, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 9 * sc, 5 * sc);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, Theme.u32(Theme.PANEL, 0.94f));
        ImGui.pushStyleColor(ImGuiCol.Border, Theme.u32(Theme.LINE));
        if (ImGui.begin("##la_toolbar", FIXED | ImGuiWindowFlags.NoFocusOnAppearing | ImGuiWindowFlags.NoNav)) {
            ImDrawList dl = ImGui.getWindowDrawList();
            float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
            // 1. mode chip
            boolean clicked = ImGui.invisibleButton("##chip", chipW, h);
            boolean hot = ImGui.isItemHovered();
            dl.addRectFilled(x, y, x + chipW, y + h, Theme.u32(Theme.SEL, hot ? 1f : 0.85f), 6 * sc);
            Theme.push(UiHost.theme().small());
            dl.addText(x + 8 * sc, y + (h - ImGui.getFontSize()) * 0.5f, Theme.u32(Theme.ACCENT), label);
            ImGui.popFont();
            Ui.keyCap(dl, x + 8 * sc + labelW + 6 * sc, y + (h - 18 * sc) * 0.5f, "F6", Theme.DIM);
            if (hot) ImGui.setTooltip("Leave the editor (F6)");
            if (clicked) EditorMode.exit(Minecraft.getInstance(), true);

            if (s != null) {
                ImGui.sameLine(0, sep);
                // 2. Add
                if (btn(add, labels, caps, sc, h)) {
                    ContextMenus.anchor(ImGui.getItemRectMinX(), ImGui.getItemRectMaxY() + 4 * sc);
                    ContextMenus.openAdd(s);
                }
                ImGui.sameLine(0, sep);
                // 3. tools
                if (btn(sel, labels, caps, sc, h)) s.setTool(Tool.SELECT);
                ImGui.sameLine();
                if (btn(mov, labels, caps, sc, h)) s.setTool(Tool.MOVE);
                ImGui.sameLine();
                if (btn(rot, labels, caps, sc, h)) s.setTool(Tool.ROTATE);
                ImGui.sameLine();
                if (btn(box, labels, caps, sc, h)) s.gizmo.armBox();
                // 4. mirror
                ImGui.sameLine(0, sep);
                if (btn(mir, labels, caps, sc, h)) s.actions.run("t.mirror");
                if (!overflow) {
                    // 5. snap, pivot, local view
                    ImGui.sameLine(0, sep);
                    if (btn(snap, labels, caps, sc, h)) {
                        s.snap = !s.snap;
                        s.saveToolbarState();
                    }
                    ImGui.sameLine();
                    ImGui.setNextItemWidth(comboW);
                    if (ImGui.beginCombo("##pivot", s.pivotMode.label)) {
                        for (Pivots.Mode m : Pivots.Mode.values()) {
                            if (ImGui.selectable(m.label, m == s.pivotMode)) {
                                s.pivotMode = m;
                                s.saveToolbarState();
                            }
                        }
                        ImGui.endCombo();
                    }
                    Ui.tooltip("Pivot: where the gizmo sits and what rotate and mirror turn about");
                    ImGui.sameLine();
                    if (btn(local, labels, caps, sc, h)) s.toggleLocalView();
                    // 6. undo / redo
                    ImGui.sameLine(0, sep);
                    if (btn(und, false, false, sc, h)) s.actions.undo();
                    ImGui.sameLine();
                    if (btn(red, false, false, sc, h)) s.actions.redo();
                    // 7. windows
                    ImGui.sameLine(0, sep);
                    if (btn(win, false, false, sc, h)) ImGui.openPopup("##winmenu");
                    drawWindowsMenu(s);
                    ImGui.sameLine();
                    if (btn(topv, false, false, sc, h)) s.showTopView = !s.showTopView;
                }
                // 8. push
                if (showPush) {
                    ImGui.sameLine(0, sep);
                    if (btn(push, labels, false, sc, h)) sync.pushAll(s.scene());
                }
                // 9. help, or the overflow button
                ImGui.sameLine(0, sep);
                if (overflow) {
                    if (btn(new Btn("more", "kebab", null, null, false, true, "More"), false, false, sc, h)) ImGui.openPopup("##overflow");
                    drawOverflow(s);
                } else if (btn(help, false, false, sc, h)) {
                    guideRequest = true;
                }
                drawGuide(sc);
            }
        }
        ImGui.end();
        ImGui.popStyleColor(2);
        ImGui.popStyleVar(5);
        ImGui.popFont();
        return top + barH;
    }

    private static void drawWindowsMenu(EditorSession s) {
        float sc = Ui.s();
        ImGui.setNextWindowPos(ImGui.getItemRectMinX(), ImGui.getItemRectMaxY() + 4 * sc, imgui.flag.ImGuiCond.Appearing);
        if (ImGui.beginPopup("##winmenu")) {
            s.popupOpen = true;
            windowItems(s);
            ImGui.endPopup();
        }
    }

    private static void windowItems(EditorSession s) {
        if (ImGui.menuItem("Scene", "", s.showScene)) s.showScene = !s.showScene;
        if (ImGui.menuItem("Properties", "", s.showProperties)) s.showProperties = !s.showProperties;
        if (ImGui.menuItem("Top view", "", s.showTopView)) s.showTopView = !s.showTopView;
        ImGui.separator();
        if (ImGui.menuItem("Hide all windows", "Tab")) s.windowsHidden = !s.windowsHidden;
        ImGui.separator();
        if (ImGui.menuItem("Reset layout")) {
            s.resetLayout = true;
            s.showScene = true;
            s.showProperties = true;
            s.showTopView = true;
            s.windowsHidden = false;
        }
    }

    private static void drawOverflow(EditorSession s) {
        float sc = Ui.s();
        ImGui.setNextWindowPos(ImGui.getItemRectMinX(), ImGui.getItemRectMaxY() + 4 * sc, imgui.flag.ImGuiCond.Appearing);
        if (ImGui.beginPopup("##overflow")) {
            s.popupOpen = true;
            var undo = s.undoStack();
            if (ImGui.menuItem("Snap", "", s.snap)) {
                s.snap = !s.snap;
                s.saveToolbarState();
            }
            if (ImGui.beginMenu("Pivot")) {
                for (Pivots.Mode m : Pivots.Mode.values()) {
                    if (ImGui.menuItem(m.label, "", m == s.pivotMode)) {
                        s.pivotMode = m;
                        s.saveToolbarState();
                    }
                }
                ImGui.endMenu();
            }
            if (ImGui.menuItem("Local view", "/", s.localView)) s.toggleLocalView();
            ImGui.separator();
            if (ImGui.menuItem("Undo", "Ctrl Z", false, undo.canUndo())) s.actions.undo();
            if (ImGui.menuItem("Redo", "Ctrl Shift Z", false, undo.canRedo())) s.actions.redo();
            ImGui.separator();
            if (ImGui.beginMenu("Windows")) {
                windowItems(s);
                ImGui.endMenu();
            }
            if (ImGui.menuItem("Quick guide")) guideRequest = true;
            ImGui.endPopup();
        }
    }

    private static void drawGuide(float sc) {
        if (guideRequest) {
            guideRequest = false;
            ImGui.openPopup("##guide");
        }
        ImGui.setNextWindowSizeConstraints(380 * sc, 0, 460 * sc, 700 * sc);
        if (ImGui.beginPopup("##guide")) {
            Ui.label("Quick guide");
            ImGui.textWrapped("Navigate: hold the middle mouse button to orbit, Shift + middle to pan, the wheel zooms toward the cursor. "
                    + "Hold the right mouse button and use W A S D, Q and E to fly; a short right click opens the menu.");
            Ui.section("Keys");
            String[][] keys = {{"W G R", "select / move / rotate tool"}, {"G / R, X Y Z, digits", "typed move / rotate, Enter"},
                    {"B", "box select (Shift adds)"}, {"/", "local view"}, {"M", "mirror X"}, {"Shift D / Alt D", "duplicate / linked duplicate"},
                    {"Ctrl G", "create assembly"}, {"H / Alt H", "hide / show all"}, {"A / Alt A", "select all / none"},
                    {"F", "frame selection"}, {"F2", "rename"}, {"X / Delete", "delete"}, {"PgUp PgDn", "front order"},
                    {"Ctrl Z / Ctrl Shift Z", "undo / redo"}, {"Ctrl S", "save all projects"}, {"Tab", "hide windows"}};
            for (String[] k : keys) {
                ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Theme.ACCENT));
                ImGui.text(k[0]);
                ImGui.popStyleColor();
                ImGui.sameLine(190 * sc);
                ImGui.text(k[1]);
            }
            Ui.section("Non-destructive and baked");
            ImGui.textWrapped("Arrays and Booleans stay editable modifiers. Apply, Realise and Bake turn them into real objects or files "
                    + "(each can be undone with Ctrl Z).");
            ImGui.endPopup();
        }
    }

    // ------------------------------------------------------------------ status bar

    private static void drawStatusBar(EditorSession s, ImGuiViewport vp, float sc) {
        float h = 24 * sc;
        ImGui.setNextWindowPos(vp.getPosX(), vp.getPosY() + vp.getSizeY() - h);
        ImGui.setNextWindowSize(vp.getSizeX(), h);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 10 * sc, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, Theme.u32(Theme.PANEL2));
        if (ImGui.begin("##la_status", FIXED | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoFocusOnAppearing
                | ImGuiWindowFlags.NoNav | ImGuiWindowFlags.NoInputs)) {
            ImDrawList dl = ImGui.getWindowDrawList();
            float x0 = ImGui.getWindowPosX(), y0 = ImGui.getWindowPosY(), w = ImGui.getWindowWidth();
            dl.addLine(x0, y0 + 0.5f, x0 + w, y0 + 0.5f, Theme.u32(Theme.LINE), 1f);

            Theme.push(UiHost.theme().small());
            String summary = summary(s);
            float fh = ImGui.getFontSize();
            float sumW = Math.min(ImGui.calcTextSizeX(summary), w * 0.45f);
            String shown = ImGui.calcTextSizeX(summary) > sumW ? Ui.fitEnd(summary, sumW) : summary;
            dl.addText(x0 + w - 10 * sc - ImGui.calcTextSizeX(shown), y0 + (h - fh) * 0.5f, Theme.u32(Theme.FG), shown);

            float limit = x0 + w - 10 * sc - sumW - 14 * sc;
            var derived = dev.schemarray.editor.mc.DerivedFiles.shared();
            int work = derived.working(), bad = derived.failed();
            if (work > 0 || bad > 0) {
                String dt = bad > 0 ? bad + " derived file" + (bad == 1 ? "" : "s") + " failed (Retry in the Boolean card)"
                        : "Rebuilding " + work + " derived file" + (work == 1 ? "" : "s") + "…";
                float tw = ImGui.calcTextSizeX(dt);
                float dx = limit - tw - 14 * sc;
                int col = bad > 0 ? Theme.ERR : Theme.WARN;
                if (dx > x0 + 220 * sc) {
                    dl.addCircleFilled(dx + 3.5f * sc, y0 + h * 0.5f, 3.5f * sc, Theme.u32(col));
                    dl.addText(dx + 11 * sc, y0 + (h - fh) * 0.5f, Theme.u32(col), dt);
                    limit = dx - 10 * sc;
                }
            }
            // derived files over the cap (some copies keep the original file), then Syncmatica
            List<String[]> notes = new ArrayList<>();
            String capWarn = derived.capWarning();
            if (capWarn != null) notes.add(new String[] {capWarn, String.valueOf(Theme.WARN)});
            var sync = dev.schemarray.editor.sync.SyncService.get();
            if (sync.installed() && sync.sharedProjects() > 0) {
                int pend = sync.pending();
                notes.add(pend == 0 ? new String[] {"Syncmatica up to date", String.valueOf(Theme.OK)}
                        : new String[] {pend + " change" + (pend == 1 ? "" : "s") + " not pushed", String.valueOf(Theme.WARN)});
            }
            for (String[] note : notes) {
                float tw = ImGui.calcTextSizeX(note[0]);
                float dx = limit - tw - 14 * sc;
                int col = Integer.parseInt(note[1]);
                if (dx > x0 + 220 * sc) {
                    dl.addCircleFilled(dx + 3.5f * sc, y0 + h * 0.5f, 3.5f * sc, Theme.u32(col));
                    dl.addText(dx + 11 * sc, y0 + (h - fh) * 0.5f, Theme.u32(col), note[0]);
                    limit = dx - 10 * sc;
                }
            }
            List<Hint> hints = hintsFor(s);
            float x = x0 + 10 * sc;
            for (Hint hint : hints) {
                float need = hintWidth(hint, sc);
                if (x + need > limit) break;
                x = drawHint(dl, hint, x, y0, h, sc) + 10 * sc;
            }
            ImGui.popFont();
        }
        ImGui.end();
        ImGui.popStyleColor();
        ImGui.popStyleVar(3);
    }

    private static final List<Hint> HINTS_HOVER_AXIS = List.of(Hint.of("move along the axis", "drag"), Hint.of("turn snapping off", "Ctrl"));
    private static final List<Hint> HINTS_HOVER_RING = List.of(Hint.of("+90°", "click"), Hint.of("-90°", "Shift", "click"), Hint.of("rotate in 90° steps", "drag"));
    private static final List<Hint> HINTS_DRAG_MOVE = List.of(Hint.of("snap off", "Ctrl"), Hint.of("cancel", "RMB", "Esc"), Hint.of("place", "release"));
    private static final List<Hint> HINTS_DRAG_RING = List.of(Hint.of("cancel", "RMB", "Esc"), Hint.of("apply", "release"));
    private static final List<Hint> HINTS_TYPED = List.of(Hint.of("number", "0-9"), Hint.of("axis", "X", "Y", "Z"), Hint.of("negate", "-"),
            Hint.of("edit", "Backspace"), Hint.of("confirm", "Enter"), Hint.of("cancel", "Esc"));
    private static final List<Hint> HINTS_BOX = List.of(Hint.of("select", "drag"), Hint.of("add", "Shift"), Hint.of("subtract", "Ctrl"), Hint.of("cancel", "Esc"));

    private static List<Hint> hintsFor(EditorSession s) {
        if (s != null && s.eyedropping()) return List.of(Hint.of("pick the cutter", "click"), Hint.of("cancel", "Esc"));
        if (EditorCamera.get().isFlying()) return HINTS_FLY;
        if (SceneWindow.isRenaming()) return HINTS_RENAME;
        if (SceneWindow.isDragging()) return HINTS_TREE_DRAG;
        List<Hint> base = s != null && !s.selection.isEmpty() ? HINTS_SELECTED : HINTS_IDLE;
        if (s != null) {
            base = switch (s.gizmo.hint()) {
                case HOVER_AXIS -> HINTS_HOVER_AXIS;
                case HOVER_RING -> HINTS_HOVER_RING;
                case DRAG_MOVE -> HINTS_DRAG_MOVE;
                case DRAG_RING -> HINTS_DRAG_RING;
                case TYPED -> HINTS_TYPED;
                case BOX -> HINTS_BOX;
                default -> base;
            };
            if (s.localView) {                                       // the "Local view" chip comes first
                List<Hint> withChip = new ArrayList<>();
                withChip.add(Hint.of("Local view: leave", "/"));
                withChip.addAll(base);
                return withChip;
            }
        }
        return base;
    }

    /** "Nothing selected" / "tunnel_segment · 8×6×7 at 12, 64, -3 · array ×6" / "3 objects · 40×16×22". */
    private static String summary(EditorSession s) {
        if (s == null || s.selection.isEmpty()) return "Nothing selected";
        List<UUID> ids = s.selection.ids();
        Box b = s.boxes().unionOf(s.scene(), ids);
        String size = b == null ? "" : " · " + b.size().x() + "×" + b.size().y() + "×" + b.size().z();
        if (ids.size() > 1) return ids.size() + " objects" + size;
        Node n = s.scene().byId(ids.get(0));
        if (n == null) return "Nothing selected";
        if (n instanceof ProjectNode) return n.name() + " · project";
        StringBuilder sb = new StringBuilder(n.name());
        if (b != null) sb.append(" · ").append(b.size().x()).append('×').append(b.size().y()).append('×').append(b.size().z())
                .append(" at ").append(b.min().x()).append(", ").append(b.min().y()).append(", ").append(b.min().z());
        List<Modifier> mods = n instanceof ObjectNode o ? o.mods() : n instanceof AssemblyNode a ? a.mods() : List.of();
        List<ArrayModifier> arrays = new ArrayList<>();
        for (Modifier m : mods) if (m instanceof ArrayModifier am && am.enabled) arrays.add(am);
        if (!arrays.isEmpty()) sb.append(String.format(Locale.ROOT, " · array ×%d", ArrayCells.copyCountOf(arrays) + 1));
        return sb.toString();
    }

    /** Width of a hint; the small font must be current. */
    private static float hintWidth(Hint hint, float s) {
        float w = 0;
        for (String cap : hint.caps()) w += Ui.capWidth(cap) + 3 * s;
        return w + 3 * s + ImGui.calcTextSizeX(hint.verb());
    }

    /** Draws caps + verb, small font current; returns the right edge. */
    private static float drawHint(ImDrawList dl, Hint hint, float x, float y0, float h, float s) {
        float capH = 18 * s;
        for (String cap : hint.caps()) {
            x = Ui.keyCap(dl, x, y0 + (h - capH) * 0.5f, cap, Theme.DIM) + 3 * s;
        }
        x += 3 * s;
        float fh = ImGui.getFontSize();
        dl.addText(x, y0 + (h - fh) * 0.5f, Theme.u32(Theme.DIM), hint.verb());
        return x + ImGui.calcTextSizeX(hint.verb());
    }

    // ------------------------------------------------------------------ fly HUD

    /** Fly pill under the toolbar: a speed bar (log scale 0.2x to 8x) and the multiplier. Never takes input. */
    private static void drawFlyHud(ImGuiViewport vp, float s, float toolbarBottom) {
        EditorCamera cam = EditorCamera.get();
        if (!cam.isFlying()) return;
        Minecraft mc = Minecraft.getInstance();
        boolean loading = SpectatorFollow.chunksLoading(mc, cam);
        String speed = String.format(Locale.ROOT, "%.1f×", cam.speed());
        String tail = loading ? "loading chunks…" : null;

        Theme.push(UiHost.theme().mono());
        float fh = ImGui.getFontSize();
        float flyW = ImGui.calcTextSizeX("Fly"), speedW = ImGui.calcTextSizeX(speed);
        float tailW = tail == null ? 0 : ImGui.calcTextSizeX(tail) + 10 * s;
        float barW = 60 * s, barH = 5 * s, gap = 10 * s, padX = 14 * s, h = 30 * s;
        float w = padX + flyW + gap + barW + gap + speedW + tailW + padX;
        float x = vp.getPosX() + (vp.getSizeX() - w) * 0.5f, y = toolbarBottom + 10 * s;

        ImDrawList dl = ImGui.getForegroundDrawList();
        dl.addRectFilled(x, y, x + w, y + h, Theme.u32(0x14161a, 0.82f), h * 0.5f);
        dl.addRect(x, y, x + w, y + h, Theme.u32(0xffffff, 0.10f), h * 0.5f);
        float tx = x + padX, ty = y + (h - fh) * 0.5f;
        dl.addText(tx, ty, Theme.u32(Theme.FG), "Fly");
        tx += flyW + gap;
        float by = y + (h - barH) * 0.5f;
        dl.addRectFilled(tx, by, tx + barW, by + barH, Theme.u32(Theme.TRACK), barH * 0.5f);
        double t = Math.log(cam.speed() / EditorCamera.SPEED_MIN) / Math.log(EditorCamera.SPEED_MAX / EditorCamera.SPEED_MIN);
        float fill = (float) Math.max(0.03, Math.min(1.0, t)) * barW;
        dl.addRectFilled(tx, by, tx + fill, by + barH, Theme.u32(Theme.ACCENT), barH * 0.5f);
        tx += barW + gap;
        dl.addText(tx, ty, Theme.u32(Theme.ACCENT), speed);
        if (tail != null) dl.addText(tx + speedW + 10 * s, ty, Theme.u32(Theme.DIM), tail);
        ImGui.popFont();
    }
}
