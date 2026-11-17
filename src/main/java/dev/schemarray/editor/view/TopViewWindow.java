package dev.schemarray.editor.view;

import dev.cg.uihost.Theme;
import dev.cg.uihost.UiHost;
import dev.schemarray.editor.EditorSession;
import dev.schemarray.editor.cam.EditorCamera;
import dev.schemarray.editor.core.Box;
import dev.schemarray.editor.core.Overlaps;
import dev.schemarray.editor.core.Vec3i;
import dev.schemarray.editor.ops.Move;
import dev.schemarray.editor.pick.WorldBoxes;
import dev.schemarray.editor.ui.TopViewMath;
import dev.schemarray.editor.ui.TopViewMath.Item;
import dev.schemarray.editor.ui.TopViewMath.View;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiButtonFlags;
import imgui.flag.ImGuiKey;
import imgui.type.ImBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Top view window (DESIGN 9): a 2D map of the scene drawn with the window's draw list, no terrain. Object boxes
 * are coloured by type (array copies fainter), overlaps are red with a hatch, areas of other mods and wire cutters
 * are dashed, and the camera shows as a dot with its view wedge and the orbit target as a ring.
 *
 * <p>Left click selects (Shift adds); a click on empty space moves the camera target there; dragging a selected object
 * moves the selection in whole blocks and runs one {@code Move} on release (Esc cancels); middle or right drag pans;
 * the wheel zooms around the cursor. The maths is in {@link TopViewMath}.
 */
public final class TopViewWindow {
    private TopViewWindow() {}

    private static final ImBoolean OPEN = new ImBoolean(true);
    private static View view;
    private static boolean follow;
    private static boolean placed;

    // interaction
    private static int panButton = -1;
    private static boolean pressed;            // a left press started on the map
    private static UUID pressHit;
    private static boolean pressShift, pressWasSelected;
    private static double pressX, pressY;
    private static boolean dragging;
    private static Vec3i dragDelta = Vec3i.ZERO;
    private static List<UUID> dragIds = List.of();

    private static WorldBoxes lastBoxes;
    private static List<Item> items = List.of();

    public static void draw(EditorSession s) {
        float sc = Ui.s();
        var vp = ImGui.getMainViewport();
        if (!placed) {
            placed = true;
            if (vp.getSizeY() < 620 * sc && s.showTopView) s.showTopView = false;     // DESIGN 4.2: starts closed on a low screen
            if (!s.showTopView) return;
        }
        OPEN.set(s.showTopView);
        float propH = Math.max(220 * sc, 0.58f * (vp.getSizeY() - 92 * sc));
        float top = vp.getPosY() + 56 * sc + propH + 8 * sc;
        float h = Math.max(220 * sc, vp.getSizeY() - top - 24 * sc - 12 * sc);
        int cond = s.resetLayout ? imgui.flag.ImGuiCond.Always : imgui.flag.ImGuiCond.FirstUseEver;
        ImGui.setNextWindowPos(vp.getPosX() + vp.getSizeX() - 332 * sc, top, cond);
        ImGui.setNextWindowSize(320 * sc, h, cond);
        ImGui.setNextWindowSizeConstraints(200 * sc, 180 * sc, 4000, 4000);
        if (Ui.beginWindow("Top view", OPEN, 0, 0.96f)) drawContent(s);
        Ui.endWindow();
        s.showTopView = OPEN.get();
    }

    private static void drawContent(EditorSession s) {
        float sc = Ui.s();
        // header extras: Fit, Follow camera, zoom text
        boolean wantFit = false;
        if (Ui.iconButton("##tv_fit", "frame", 22 * sc, false, true, "Fit everything")) wantFit = true;
        ImGui.sameLine();
        if (Ui.iconButton("##tv_follow", "local", 22 * sc, follow, true, "Follow the camera")) follow = !follow;
        ImGui.sameLine();
        ImGui.alignTextToFramePadding();
        Theme.push(UiHost.theme().small());
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, Ui.c(Theme.DIM));
        ImGui.text(view == null ? "" : TopViewMath.scaleLabel(view.scale()));
        ImGui.popStyleColor();
        ImGui.popFont();

        float legendH = ImGui.getContentRegionAvailX() >= 240 * sc ? 22 * sc : 0;
        float cw = ImGui.getContentRegionAvailX(), ch = Math.max(140 * sc, ImGui.getContentRegionAvailY() - legendH);
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY();
        ImGui.invisibleButton("##topmap", cw, ch,
                ImGuiButtonFlags.MouseButtonLeft | ImGuiButtonFlags.MouseButtonRight | ImGuiButtonFlags.MouseButtonMiddle);
        boolean hovered = ImGui.isItemHovered();

        WorldBoxes wb = s.boxes();
        if (wb != lastBoxes) {
            lastBoxes = wb;
            items = TopViewMath.items(s.scene(), wb);
        }
        if (view == null || wantFit) view = fitAll(items, cw, ch, 16 * sc);
        EditorCamera cam = EditorCamera.get();
        Vec3 camPos = cam.position();
        if (follow) view = TopViewMath.centered(view, camPos.x, camPos.z);

        float mx = ImGui.getMousePosX() - x0, my = ImGui.getMousePosY() - y0;
        handleInput(s, hovered, cw, ch, mx, my, sc);

        // ---- draw
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.pushClipRect(x0, y0, x0 + cw, y0 + ch, true);
        dl.addRectFilled(x0, y0, x0 + cw, y0 + ch, Ui.c(Theme.SLOT), 6 * sc);
        drawGrid(dl, x0, y0, cw, ch, sc);
        if (items.isEmpty()) {
            String t = "Nothing placed yet.";
            dl.addText(x0 + (cw - ImGui.calcTextSizeX(t)) * 0.5f, y0 + (ch - ImGui.getFontSize()) * 0.5f, Ui.c(Theme.DIM), t);
        }
        UUID hoverId = hovered && panButton < 0 ? TopViewMath.hit(items, view, cw, ch, mx, my) : null;
        drawItems(s, dl, x0, y0, cw, ch, hoverId, sc);
        drawOverlaps(s, dl, x0, y0, cw, ch, sc);
        if (dragging) drawDragGhost(s, dl, x0, y0, cw, ch, mx, my, sc);
        drawCamera(dl, x0, y0, cw, ch, camPos, cam, sc);
        dl.popClipRect();
        dl.addRect(x0, y0, x0 + cw, y0 + ch, Ui.c(Theme.LINE), 6 * sc);

        if (legendH > 0) drawLegend(dl, sc);
    }

    // ------------------------------------------------------------------ input

    private static View fitAll(List<Item> its, float w, float h, float margin) {
        Box u = null;
        for (Item it : its) u = u == null ? it.box() : u.union(it.box());
        View fallback = new View(0, 0, 4);
        Vec3 c = EditorCamera.get().target();
        if (u == null) fallback = new View(c.x, c.z, 4);
        return TopViewMath.fit(u, w, h, margin, fallback);
    }

    private static void handleInput(EditorSession s, boolean hovered, float w, float h, float mx, float my, float sc) {
        var io = ImGui.getIO();
        if (hovered && io.getMouseWheel() != 0 && !dragging) {
            view = TopViewMath.zoomAt(view, w, h, mx, my, Math.pow(1.15, io.getMouseWheel()));
            follow = false;
        }
        // pan: middle or right drag
        for (int b : new int[] {2, 1}) {
            if (hovered && panButton < 0 && ImGui.isMouseClicked(b) && !pressed) panButton = b;
        }
        if (panButton >= 0) {
            if (ImGui.isMouseDown(panButton)) {
                view = TopViewMath.pan(view, io.getMouseDeltaX(), io.getMouseDeltaY());
                if (io.getMouseDeltaX() != 0 || io.getMouseDeltaY() != 0) follow = false;
            } else {
                panButton = -1;
            }
        }
        // left: select, click to move the camera target, drag to move
        if (hovered && panButton < 0 && ImGui.isMouseClicked(0)) {
            pressed = true;
            pressX = mx;
            pressY = my;
            pressShift = io.getKeyShift();
            pressHit = TopViewMath.hit(items, view, w, h, mx, my);
            pressWasSelected = pressHit != null && s.selection.isSelected(pressHit);
            if (pressHit != null) {
                if (pressShift) s.selection.add(pressHit);
                else if (!pressWasSelected) s.selection.set(pressHit);
            }
        }
        if (pressed) {
            double moved = Math.hypot(mx - pressX, my - pressY);
            if (!dragging && pressHit != null && moved >= 4 * sc && ImGui.isMouseDown(0) && !s.selection.isEmpty() && !s.selectionLocked()) {
                dragging = true;
                dragIds = s.selection.ids();
            }
            if (dragging) {
                dragDelta = TopViewMath.blockDelta(view, mx - pressX, my - pressY);
                if (ImGui.isKeyPressed(ImGuiKey.Escape)) {
                    dragging = false;
                    pressed = false;
                    dragDelta = Vec3i.ZERO;
                    return;
                }
            }
            if (!ImGui.isMouseDown(0)) {
                if (dragging) {
                    if (dragDelta.x() != 0 || dragDelta.z() != 0) {
                        s.actions.transaction("Move " + (dragIds.size() == 1 ? "object" : dragIds.size() + " objects"), List.of(new Move(dragIds, dragDelta)));
                    }
                } else if (moved < 4 * sc) {
                    if (pressHit == null) {
                        double wx = TopViewMath.worldX(view, w, mx), wz = TopViewMath.worldZ(view, h, my);
                        EditorCamera cam = EditorCamera.get();
                        cam.setTarget(wx, cam.target().y, wz);
                    } else if (!pressShift && pressWasSelected && s.selection.size() > 1) {
                        s.selection.set(pressHit);                    // a plain click inside a multi-selection narrows it
                    }
                }
                dragging = false;
                pressed = false;
                dragDelta = Vec3i.ZERO;
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    private static void drawGrid(ImDrawList dl, float x0, float y0, float w, float h, float sc) {
        int step = TopViewMath.gridStep(view.scale(), 24 * sc);
        double wx0 = TopViewMath.worldX(view, w, 0), wx1 = TopViewMath.worldX(view, w, w);
        double wz0 = TopViewMath.worldZ(view, h, 0), wz1 = TopViewMath.worldZ(view, h, h);
        int line = Ui.c(Theme.LINE, 0.7f);
        for (long gx = (long) Math.floor(wx0 / step) * step; gx <= wx1; gx += step) {
            float sx = x0 + (float) TopViewMath.screenX(view, w, gx);
            dl.addLine(sx, y0, sx, y0 + h, gx == 0 ? Ui.c(Ui.AX_Z, 0.35f) : line, 1f);
        }
        for (long gz = (long) Math.floor(wz0 / step) * step; gz <= wz1; gz += step) {
            float sy = y0 + (float) TopViewMath.screenY(view, h, gz);
            dl.addLine(x0, sy, x0 + w, sy, gz == 0 ? Ui.c(Ui.AX_X, 0.35f) : line, 1f);
        }
    }

    private static float[] rect(Box b, float x0, float y0, float w, float h) {
        float ax = x0 + (float) TopViewMath.screenX(view, w, b.min().x()), bx = x0 + (float) TopViewMath.screenX(view, w, b.maxExclusive().x());
        float ay = y0 + (float) TopViewMath.screenY(view, h, b.min().z()), by = y0 + (float) TopViewMath.screenY(view, h, b.maxExclusive().z());
        if (bx - ax < 2) bx = ax + 2;
        if (by - ay < 2) by = ay + 2;
        return new float[] {ax, ay, bx, by};
    }

    private static void dashedRect(ImDrawList dl, float[] r, double on, double off, int col) {
        double[][] edges = {{r[0], r[1], r[2], r[1]}, {r[2], r[1], r[2], r[3]}, {r[2], r[3], r[0], r[3]}, {r[0], r[3], r[0], r[1]}};
        for (double[] e : edges) {
            for (double[] d : TopViewMath.dashes(e[0], e[1], e[2], e[3], on, off)) dl.addLine((float) d[0], (float) d[1], (float) d[2], (float) d[3], col, 1f);
        }
    }

    private static void drawItems(EditorSession s, ImDrawList dl, float x0, float y0, float w, float h, UUID hoverId, float sc) {
        for (Item it : items) {
            float[] r = rect(it.box(), x0, y0, w, h);
            if (r[2] < x0 || r[0] > x0 + w || r[3] < y0 || r[1] > y0 + h) continue;
            boolean sel = s.selection.isSelected(it.id());
            switch (it.kind()) {
                case AREA -> {
                    dl.addRectFilled(r[0], r[1], r[2], r[3], Ui.c(Ui.AREA, 0.06f));
                    dashedRect(dl, r, 4 * sc, 3 * sc, Ui.c(sel ? Theme.ACCENT : Ui.AREA, it.copy() ? 0.7f : 1f));
                }
                case CUTTER -> dashedRect(dl, r, 2 * sc, 2 * sc, Ui.c(sel ? Theme.ACCENT : Ui.BOOL, it.copy() ? 0.7f : 1f));
                default -> {
                    float a = sel ? (it.copy() ? 0.6f : 1f) : (it.copy() ? 0.55f * 0.7f : 0.55f);
                    dl.addRectFilled(r[0], r[1], r[2], r[3], Ui.c(sel ? Theme.ACCENT : Ui.T_OBJECT, a));
                    dl.addRect(r[0], r[1], r[2], r[3], Ui.c(0x000000, 0.5f), 0f, 0, 1f);
                }
            }
            if (it.id().equals(hoverId)) dl.addRect(r[0], r[1], r[2], r[3], Ui.c(Theme.FG, 0.6f), 0f, 0, 1f);
        }
    }

    private static void drawOverlaps(EditorSession s, ImDrawList dl, float x0, float y0, float w, float h, float sc) {
        for (Overlaps.Overlap ov : s.allOverlaps()) {
            if (s.lostTo(ov.loser(), ov.winner()) == 0) continue;
            float[] r = rect(ov.box(), x0, y0, w, h);
            dl.addRectFilled(r[0], r[1], r[2], r[3], Ui.c(Ui.OVERLAP, 0.35f));
            dl.pushClipRect(r[0], r[1], r[2], r[3], true);
            for (double[] l : TopViewMath.hatch(r[0], r[1], r[2], r[3], 4 * sc)) {
                dl.addLine((float) l[0], (float) l[1], (float) l[2], (float) l[3], Ui.c(Ui.OVERLAP), 1f);
            }
            dl.popClipRect();
        }
    }

    private static void drawDragGhost(EditorSession s, ImDrawList dl, float x0, float y0, float w, float h, float mx, float my, float sc) {
        Set<UUID> ids = new HashSet<>(dragIds);
        float dx = (float) (dragDelta.x() * view.scale()), dz = (float) (dragDelta.z() * view.scale());
        for (Item it : items) {
            if (!ids.contains(it.id())) continue;
            float[] r = rect(it.box(), x0, y0, w, h);
            float[] g = {r[0] + dx, r[1] + dz, r[2] + dx, r[3] + dz};
            dl.addRectFilled(g[0], g[1], g[2], g[3], Ui.c(Theme.ACCENT, 0.12f));
            dashedRect(dl, g, 4 * sc, 3 * sc, Ui.c(Theme.ACCENT));
        }
        String t = (dragDelta.x() >= 0 ? "+" : "") + dragDelta.x() + ", " + (dragDelta.z() >= 0 ? "+" : "") + dragDelta.z();
        Theme.push(UiHost.theme().small());
        float tw = ImGui.calcTextSizeX(t);
        float tx = x0 + mx + 12 * sc, ty = y0 + my + 12 * sc;
        dl.addRectFilled(tx - 4 * sc, ty - 2 * sc, tx + tw + 4 * sc, ty + ImGui.getFontSize() + 2 * sc, Ui.c(0x14161a, 0.85f), 4 * sc);
        dl.addText(tx, ty, Ui.c(Theme.FG), t);
        ImGui.popFont();
    }

    private static void drawCamera(ImDrawList dl, float x0, float y0, float w, float h, Vec3 pos, EditorCamera cam, float sc) {
        float cx = x0 + (float) TopViewMath.screenX(view, w, pos.x), cy = y0 + (float) TopViewMath.screenY(view, h, pos.z);
        Vec3 f = EditorCamera.forward(cam.yaw(), 0);
        double[] wd = TopViewMath.wedge(cx, cy, f.x, f.z, Math.toRadians(35), 56 * sc);
        dl.addTriangleFilled(cx, cy, (float) wd[0], (float) wd[1], (float) wd[2], (float) wd[3], Ui.c(Theme.FG, 0.13f));
        dl.addCircleFilled(cx, cy, 3.5f * sc, Ui.c(Theme.FG));
        Vec3 t = cam.target();
        dl.addCircle(x0 + (float) TopViewMath.screenX(view, w, t.x), y0 + (float) TopViewMath.screenY(view, h, t.z), 3 * sc, Ui.c(Theme.FG), 12, 1f);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            double px = mc.player.getX(), pz = mc.player.getZ();
            if (Math.hypot(px - pos.x, pz - pos.z) > 1.5) {
                dl.addCircleFilled(x0 + (float) TopViewMath.screenX(view, w, px), y0 + (float) TopViewMath.screenY(view, h, pz), 3 * sc, Ui.c(Theme.ACCENT));
            }
        }
    }

    private static void drawLegend(ImDrawList dl, float sc) {
        Theme.push(UiHost.theme().small());
        ImGui.dummy(0, 4 * sc);
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY(), fh = ImGui.getFontSize();
        String[] names = {"selected", "object", "overlap", "area", "cutter"};
        for (int i = 0; i < names.length; i++) {
            float sw = 10 * sc, sy = y + (fh - sw) * 0.5f;
            switch (i) {
                case 0 -> dl.addRectFilled(x, sy, x + sw, sy + sw, Ui.c(Theme.ACCENT));
                case 1 -> dl.addRectFilled(x, sy, x + sw, sy + sw, Ui.c(Ui.T_OBJECT, 0.55f));
                case 2 -> {
                    dl.addRectFilled(x, sy, x + sw, sy + sw, Ui.c(Ui.OVERLAP, 0.35f));
                    for (double[] l : TopViewMath.hatch(x, sy, x + sw, sy + sw, 4 * sc)) dl.addLine((float) l[0], (float) l[1], (float) l[2], (float) l[3], Ui.c(Ui.OVERLAP), 1f);
                }
                case 3 -> dashedRect(dl, new float[] {x, sy, x + sw, sy + sw}, 3 * sc, 2 * sc, Ui.c(Ui.AREA));
                default -> dashedRect(dl, new float[] {x, sy, x + sw, sy + sw}, 2 * sc, 2 * sc, Ui.c(Ui.BOOL));
            }
            dl.addText(x + sw + 4 * sc, y, Ui.c(Theme.DIM), names[i]);
            x += sw + 4 * sc + ImGui.calcTextSizeX(names[i]) + 10 * sc;
        }
        ImGui.dummy(0, fh);
        ImGui.popFont();
    }
}
