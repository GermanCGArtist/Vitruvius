package dev.vitruvius.editor.view;

import dev.cg.uihost.Theme;
import dev.cg.uihost.UiHost;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.ui.ToastQueue;
import dev.vitruvius.editor.ui.ToastQueue.Toast;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiViewport;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the toasts (DESIGN 12.6): bottom right, 12 px from the edge and above the status bar, stacked upwards, a
 * level-coloured bar at the left, an optional action as a text button. A hovered toast keeps its timer.
 */
public final class Toasts {
    private Toasts() {}

    public static void draw(EditorSession s) {
        ToastQueue q = s.toasts;
        float sc = Ui.s();
        List<Toast> list = new ArrayList<>(q.toasts());
        Toast hovered = null;
        ImGuiViewport vp = ImGui.getMainViewport();
        ImDrawList dl = ImGui.getForegroundDrawList();
        float maxW = 380 * sc, padX = 12 * sc, padY = 8 * sc, gap = 6 * sc;
        float right = vp.getPosX() + vp.getSizeX() - 12 * sc;
        float y = vp.getPosY() + vp.getSizeY() - 24 * sc - 12 * sc;
        float mx = ImGui.getMousePosX(), my = ImGui.getMousePosY();
        Theme.push(UiHost.theme().ui());
        Toast clicked = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            Toast t = list.get(i);
            float actW = t.hasAction() ? ImGui.calcTextSizeX(t.actionLabel) + 14 * sc : 0;
            float textW = Math.min(maxW - 2 * padX - 6 * sc - actW, ImGui.calcTextSizeX(t.text));
            float w = Math.min(maxW, textW + 2 * padX + 6 * sc + actW + (t.hasAction() ? 8 * sc : 0));
            // wrap: height in lines
            float lineH = ImGui.getFontSize();
            float wrapW = w - 2 * padX - 6 * sc - actW;
            int lines = Math.max(1, (int) Math.ceil(ImGui.calcTextSizeX(t.text) / Math.max(1, wrapW)));
            float h = lines * lineH + 2 * padY;
            float x0 = right - w, y0 = y - h;
            boolean over = mx >= x0 && mx <= right && my >= y0 && my <= y;
            if (over) hovered = t;
            float a = t.alpha();
            int bar = switch (t.level) {
                case INFO -> Theme.ACCENT;
                case WARN -> Theme.WARN;
                case ERROR -> Theme.ERR;
            };
            float borderA = switch (t.level) {
                case INFO -> 1f;
                case WARN -> 0.5f;
                case ERROR -> 0.7f;
            };
            int border = switch (t.level) {
                case INFO -> Theme.BORDER_HI;
                case WARN -> Theme.WARN;
                case ERROR -> Theme.ERR;
            };
            dl.addRectFilled(x0, y0, right, y, Theme.u32(Theme.BG, 0.96f * a), 0f);
            dl.addRect(x0, y0, right, y, Theme.u32(border, borderA * a), 0f);
            dl.addRectFilled(x0, y0, x0 + 3 * sc, y, Theme.u32(bar, a), 0f);
            drawWrapped(dl, t.text, x0 + padX + 4 * sc, y0 + padY, wrapW, Theme.u32(Theme.FG, a));
            if (t.hasAction()) {
                float bx = right - actW - 6 * sc, by = y0 + (h - lineH) * 0.5f;
                boolean hotBtn = over && mx >= bx - 4 * sc;
                dl.addRectFilled(bx - 4 * sc, by - 2 * sc, right - 6 * sc, by + lineH + 2 * sc, Theme.u32(Theme.SEL, (hotBtn ? 1f : 0.7f) * a), 0f);
                dl.addRect(bx - 4 * sc, by - 2 * sc, right - 6 * sc, by + lineH + 2 * sc, Theme.u32(Theme.ACCENT, a), 0f);
                dl.addText(bx, by, Theme.u32(Theme.ACCENT, a), t.actionLabel.toUpperCase(java.util.Locale.ROOT));
            }
            if (over && ImGui.isMouseClicked(0)) clicked = t;
            y = y0 - gap;
        }
        ImGui.popFont();
        q.tick((long) (ImGui.getTime() * 1000.0), hovered);
        if (clicked != null) {
            if (clicked.hasAction() && mx >= right - 110 * sc) q.click(clicked);
            else q.dismiss(clicked);
        }
    }

    /** Draws text wrapped at {@code wrapW} pixels, word by word. */
    private static void drawWrapped(ImDrawList dl, String text, float x, float y, float wrapW, int col) {
        float lineH = ImGui.getFontSize();
        StringBuilder line = new StringBuilder();
        float cy = y;
        for (String word : text.split(" ")) {
            String test = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && ImGui.calcTextSizeX(test) > wrapW) {
                dl.addText(x, cy, col, line.toString());
                cy += lineH;
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(test);
            }
        }
        if (!line.isEmpty()) dl.addText(x, cy, col, line.toString());
    }
}
