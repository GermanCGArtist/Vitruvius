package dev.vitruvius.editor.view;

import dev.cg.uihost.Theme;
import dev.cg.uihost.UiHost;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.world.Gizmo;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

/**
 * The HUD pill under the toolbar (DESIGN 6.4) while a typed transform, a gizmo drag or a ring drag runs: the verb,
 * axis chips (clickable during a typed move), the amount with a blinking caret, a dim tail and the snap target. Drag
 * HUDs never take input; the typed one is a small {@code NoDecoration} window so the chips can be clicked.
 */
public final class TransformHud {
    private TransformHud() {}

    private static final int FLAGS = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoFocusOnAppearing | ImGuiWindowFlags.NoNav
            | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoScrollbar;

    private static final int[] AX = {Ui.AX_X, Ui.AX_Y, Ui.AX_Z};
    private static final String[] AX_NAME = {"X", "Y", "Z"};

    public static void draw(EditorSession s) {
        Gizmo.Hud boxHud = s.boxEdit.hud();
        boolean fromBox = boxHud != null;
        Gizmo.Hud h = fromBox ? boxHud : s.gizmo.hud();
        if (h == null) return;
        float sc = UiHost.scale();
        ImGuiViewport vp = ImGui.getMainViewport();
        Theme.push(UiHost.theme().mono());
        float fh = ImGui.getFontSize();
        float pad = 14 * sc, gap = 8 * sc, chip = 18 * sc, hgt = 30 * sc;
        float verbW = ImGui.calcTextSizeX(h.verb());
        float chipsW = h.chips() >= 0 ? 3 * chip + 2 * 3 * sc + gap : 0;
        float amountW = ImGui.calcTextSizeX(h.amount());
        float caretW = h.typed() ? 2 * sc + 3 * sc : 0;
        String tail = h.tail() == null ? "" : h.tail();
        float tailW = tail.isEmpty() ? 0 : gap + ImGui.calcTextSizeX(tail);
        String hint = h.typed() ? "Enter confirm · Esc cancel" : "";
        float hintW = hint.isEmpty() ? 0 : gap + ImGui.calcTextSizeX(hint);
        String snap = h.snap() == null ? "" : "snapped to " + h.snap();
        float snapW = snap.isEmpty() ? 0 : gap * 1.5f + ImGui.calcTextSizeX(snap);
        float w = pad + verbW + gap + chipsW + amountW + caretW + tailW + hintW + snapW + pad;

        float toolbarBottom = vp.getPosY() + 10 * sc + 36 * sc;           // toolbar: top 10, 28 high plus 2 x 4 padding
        float x = vp.getPosX() + (vp.getSizeX() - w) * 0.5f, y = toolbarBottom + 10 * sc;
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(w, hgt);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, hgt * 0.5f);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, 0);
        int flags = FLAGS | (h.typed() ? 0 : ImGuiWindowFlags.NoInputs);
        if (ImGui.begin("##la_transform_hud", flags)) {
            ImDrawList dl = ImGui.getWindowDrawList();
            dl.addRectFilled(x, y, x + w, y + hgt, Theme.u32(0x14161a, 0.82f), hgt * 0.5f);
            dl.addRect(x, y, x + w, y + hgt, Theme.u32(0xffffff, 0.10f), hgt * 0.5f);
            float tx = x + pad, ty = y + (hgt - fh) * 0.5f;
            dl.addText(tx, ty, Theme.u32(Theme.FG), h.verb());
            tx += verbW + gap;
            if (h.chips() >= 0) {
                for (int a = 0; a < 3; a++) {
                    boolean lit = (h.chips() & (1 << a)) != 0;
                    float cy = y + (hgt - chip) * 0.5f;
                    dl.addRectFilled(tx, cy, tx + chip, cy + chip, lit ? Theme.u32(AX[a], 0.25f) : Theme.u32(Theme.CHIP), 4 * sc);
                    float lw = ImGui.calcTextSizeX(AX_NAME[a]);
                    dl.addText(tx + (chip - lw) * 0.5f, cy + (chip - fh) * 0.5f, Theme.u32(lit ? AX[a] : Theme.DIM), AX_NAME[a]);
                    if (h.typed()) {
                        ImGui.setCursorScreenPos(tx, cy);
                        if (ImGui.invisibleButton("##hudaxis" + a, chip, chip)) {
                            if (fromBox) s.boxEdit.clickAxis(1 << a);
                            else s.gizmo.clickAxis(1 << a);
                        }
                        if (ImGui.isItemHovered()) ImGui.setTooltip((fromBox ? "Edit side along " : "Move along ") + AX_NAME[a]);
                    }
                    tx += chip + 3 * sc;
                }
                tx += gap - 3 * sc;
            }
            dl.addText(tx, ty, Theme.u32(Theme.ACCENT), h.amount());
            tx += amountW;
            if (h.typed()) {
                boolean on = (System.currentTimeMillis() / 500) % 2 == 0;
                if (on) dl.addRectFilled(tx + 3 * sc, y + (hgt - 14 * sc) * 0.5f, tx + 5 * sc, y + (hgt + 14 * sc) * 0.5f, Theme.u32(Theme.FG));
                tx += caretW;
            }
            if (!tail.isEmpty()) {
                tx += gap;
                dl.addText(tx, ty, Theme.u32(Theme.DIM), tail);
                tx += ImGui.calcTextSizeX(tail);
            }
            if (!hint.isEmpty()) {
                tx += gap;
                dl.addText(tx, ty, Theme.u32(Theme.GUTTER), hint);
                tx += ImGui.calcTextSizeX(hint);
            }
            if (!snap.isEmpty()) {
                tx += gap * 1.5f;
                dl.addText(tx, ty, Theme.u32(Theme.ACCENT), snap);
            }
        }
        ImGui.end();
        ImGui.popStyleColor();
        ImGui.popStyleVar(3);
        ImGui.popFont();
    }
}
