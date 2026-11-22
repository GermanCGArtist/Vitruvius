package dev.vitruvius.editor.view;

import dev.cg.uihost.Theme;
import dev.cg.uihost.UiHost;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImBoolean;

/** Small drawing and widget helpers shared by the editor windows (vintage terminal aesthetic). Render thread only. */
public final class Ui {
    private Ui() {}

    /** Semantic editor tokens synced with the active {@link Theme.Preset}. */
    public static int AX_X = Theme.ERR, AX_Y = Theme.OK, AX_Z = Theme.PLAYER, BOOL = Theme.BOT, LINK = Theme.PLAYER,
            T_FOLDER = Theme.META, T_OBJECT = Theme.PLAYER, DIM_ON_SEL = Theme.DIM, AREA = Theme.CF, OVERLAP = Theme.ERR;

    /** Syncs editor semantic tokens with the active {@link Theme} palette. */
    public static void syncTheme() {
        AX_X = Theme.ERR;
        AX_Y = Theme.OK;
        AX_Z = Theme.PLAYER;
        BOOL = Theme.BOT;
        LINK = Theme.PLAYER;
        T_FOLDER = Theme.META;
        T_OBJECT = Theme.PLAYER;
        DIM_ON_SEL = Theme.DIM;
        AREA = Theme.CF;
        OVERLAP = Theme.ERR;
    }

    public static float s() {
        return UiHost.scale();
    }

    public static int c(int rgb) {
        return Theme.u32(rgb);
    }

    public static int c(int rgb, float a) {
        return Theme.u32(rgb, a);
    }

    // ------------------------------------------------------------------ windows

    private static int styleVars, styleColors;
    private static boolean drawFocusFrame;

    /**
     * Begins an editor window with 0px sharp terminal geometry and 1px border. Always pair with {@link #endWindow()},
     * also when this returns false.
     */
    public static boolean beginWindow(String title, ImBoolean open, int flags, float alpha) {
        float s = s();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 10 * s, 8 * s);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 6 * s, 6 * s);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemInnerSpacing, 5 * s, 4 * s);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ScrollbarSize, 8 * s);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8 * s, 5.5f * s);
        styleVars = 9;
        ImGui.pushStyleColor(ImGuiCol.WindowBg, c(Theme.PANEL, alpha));
        ImGui.pushStyleColor(ImGuiCol.Border, c(Theme.BORDER_HI));
        ImGui.pushStyleColor(ImGuiCol.TitleBg, c(Theme.BG));
        ImGui.pushStyleColor(ImGuiCol.TitleBgActive, c(Theme.BG));
        ImGui.pushStyleColor(ImGuiCol.TitleBgCollapsed, c(Theme.BG));
        styleColors = 5;
        Theme.push(UiHost.theme().uiBold());
        boolean visible = ImGui.begin(title, open, flags | imgui.flag.ImGuiWindowFlags.NoCollapse);
        ImGui.popFont();
        ImGui.popStyleVar();                       // the taller title frame padding
        styleVars--;
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8 * s, 4.5f * s);
        styleVars++;
        Theme.push(UiHost.theme().ui());
        drawFocusFrame = visible && ImGui.isWindowFocused(imgui.flag.ImGuiFocusedFlags.RootAndChildWindows);
        return visible;
    }

    public static void endWindow() {
        if (drawFocusFrame) {
            ImDrawList dl = ImGui.getWindowDrawList();
            float x = ImGui.getWindowPosX(), y = ImGui.getWindowPosY(), w = ImGui.getWindowWidth(), h = ImGui.getWindowHeight();
            dl.pushClipRectFullScreen();
            dl.addRect(x, y, x + w, y + h, c(Theme.ACCENT), 0f, 0, 1f);
            dl.popClipRect();
        }
        drawFocusFrame = false;
        ImGui.popFont();
        ImGui.end();
        ImGui.popStyleColor(styleColors);
        ImGui.popStyleVar(styleVars);
    }

    // ------------------------------------------------------------------ text and rules

    /** An upper-case terminal section heading (`── TITLE`). */
    public static void label(String text) {
        Theme.push(UiHost.theme().small());
        ImGui.pushStyleColor(ImGuiCol.Text, c(Theme.GUTTER));
        ImGui.text("── " + text.toUpperCase(java.util.Locale.ROOT));
        ImGui.popStyleColor();
        ImGui.popFont();
    }

    /** A section start: a 1 px LINE rule, then the `── HEADING`. */
    public static void section(String heading) {
        float s = s();
        ImGui.dummy(0, 3 * s);
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getWindowPosX() + 1, w = ImGui.getWindowWidth() - 2, y = ImGui.getCursorScreenPosY();
        dl.addLine(x, y, x + w, y, c(Theme.LINE), 1f);
        ImGui.dummy(0, 5 * s);
        label(heading);
    }

    public static void dim(String text) {
        ImGui.pushStyleColor(ImGuiCol.Text, c(Theme.DIM));
        ImGui.textWrapped(text);
        ImGui.popStyleColor();
    }

    public static void smallDim(String text) {
        Theme.push(UiHost.theme().small());
        ImGui.pushStyleColor(ImGuiCol.Text, c(Theme.DIM));
        ImGui.textWrapped(text);
        ImGui.popStyleColor();
        ImGui.popFont();
    }

    public static void colored(int rgb, String text) {
        ImGui.pushStyleColor(ImGuiCol.Text, c(rgb));
        ImGui.textWrapped(text);
        ImGui.popStyleColor();
    }

    /** Shortens text in the middle with an ellipsis so it fits {@code maxW} pixels. */
    public static String fitMiddle(String text, float maxW) {
        if (ImGui.calcTextSizeX(text) <= maxW) return text;
        int keep = text.length();
        while (keep > 4) {
            keep--;
            int head = keep / 2, tail = keep - head;
            String t = text.substring(0, head) + "…" + text.substring(text.length() - tail);
            if (ImGui.calcTextSizeX(t) <= maxW) return t;
        }
        return "…";
    }

    /** Shortens text at the end. */
    public static String fitEnd(String text, float maxW) {
        if (ImGui.calcTextSizeX(text) <= maxW) return text;
        int n = text.length();
        while (n > 1 && ImGui.calcTextSizeX(text.substring(0, n) + "…") > maxW) n--;
        return text.substring(0, n) + "…";
    }

    public static void tooltip(String text) {
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled | ImGuiHoveredFlags.DelayShort)) ImGui.setTooltip(text);
    }

    // ------------------------------------------------------------------ widgets

    /** A sharp 0px key cap (17 high, BG fill, BORDER_HI border, monoS). Returns its right edge. */
    public static float keyCap(ImDrawList dl, float x, float y, String text, int textRgb) {
        float s = s();
        Theme.push(UiHost.theme().monoS());
        float w = ImGui.calcTextSizeX(text) + 10 * s, h = 17 * s, fh = ImGui.getFontSize();
        dl.addRectFilled(x, y, x + w, y + h, c(Theme.BG), 0f);
        dl.addRect(x, y, x + w, y + h, c(Theme.BORDER_HI), 0f);
        dl.addText(x + 5 * s, y + (h - fh) * 0.5f, c(textRgb), text);
        ImGui.popFont();
        return x + w;
    }

    public static float capWidth(String text) {
        Theme.push(UiHost.theme().monoS());
        float w = ImGui.calcTextSizeX(text) + 10 * s();
        ImGui.popFont();
        return w;
    }

    /** A sharp 0px bordered badge with text (17 high). Returns the width drawn. */
    public static float badge(ImDrawList dl, float x, float y, String text, int fillRgb, float fillAlpha, int textRgb) {
        float s = s();
        Theme.push(UiHost.theme().monoS());
        float w = ImGui.calcTextSizeX(text) + 10 * s, h = 17 * s, fh = ImGui.getFontSize();
        dl.addRectFilled(x, y, x + w, y + h, c(fillRgb, fillAlpha), 0f);
        dl.addRect(x, y, x + w, y + h, c(textRgb, 0.7f), 0f);
        dl.addText(x + 5 * s, y + (h - fh) * 0.5f, c(textRgb), text);
        ImGui.popFont();
        return w;
    }

    /**
     * A sharp 0px terminal button. {@code primary}: ModsUndBots CTA fill. A disabled button shows its reason as a tooltip.
     */
    public static boolean button(String label, boolean primary, boolean enabled, float w, String disabledTip) {
        if (primary) {
            ImGui.pushStyleColor(ImGuiCol.Button, c(Theme.CTA_BG));
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, c(Theme.CTA_BG, 0.86f));
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, c(Theme.CTA_BG, 0.74f));
            ImGui.pushStyleColor(ImGuiCol.Text, c(Theme.CTA_FG));
            ImGui.pushStyleColor(ImGuiCol.Border, c(Theme.CTA_BG));
        }
        if (!enabled) ImGui.beginDisabled();
        boolean clicked = ImGui.button(label, w, 0);
        if (!enabled) {
            ImGui.endDisabled();
            if (disabledTip != null && !disabledTip.isEmpty()) tooltip(disabledTip);
        }
        if (primary) ImGui.popStyleColor(5);
        return clicked && enabled;
    }

    /** Sharp 0px segmented terminal control. Returns the clicked segment or -1; {@code active} -1 lights none. */
    public static int segmented(String id, String[] labels, int active, float totalW, boolean enabled) {
        float s = s();
        float h = 24 * s;
        float segW = totalW / labels.length;
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY();
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(x0, y0, x0 + totalW, y0 + h, c(Theme.BG), 0f);
        dl.addRect(x0, y0, x0 + totalW, y0 + h, c(Theme.BORDER_HI), 0f);
        int clicked = -1;
        for (int i = 0; i < labels.length; i++) {
            float sx = x0 + i * segW;
            ImGui.setCursorScreenPos(sx, y0);
            boolean hit = ImGui.invisibleButton(id + i, segW, h);
            boolean hot = ImGui.isItemHovered() && enabled;
            boolean on = i == active;
            if (on) {
                dl.addRectFilled(sx + 1, y0 + 1, sx + segW - 1, y0 + h - 1, c(Theme.SEL), 0f);
                dl.addRect(sx, y0, sx + segW, y0 + h, c(Theme.ACCENT), 0f);
            } else if (hot) {
                dl.addRectFilled(sx + 1, y0 + 1, sx + segW - 1, y0 + h - 1, c(Theme.SURFACE), 0f);
            }
            if (i > 0 && !on && (i - 1) != active) {
                dl.addLine(sx, y0 + 1, sx, y0 + h - 1, c(Theme.LINE), 1f);
            }
            float tw = ImGui.calcTextSizeX(labels[i]), fh = ImGui.getFontSize();
            int rgb = !enabled ? Theme.GUTTER : on ? Theme.ACCENT : hot ? Theme.FG : Theme.DIM;
            dl.addText(sx + (segW - tw) * 0.5f, y0 + (h - fh) * 0.5f, c(rgb), labels[i]);
            if (hit && enabled) clicked = i;
        }
        ImGui.setCursorScreenPos(x0, y0 + h);
        ImGui.dummy(totalW, 0);
        return clicked;
    }

    /** Crisp terminal `[×]` / `[ ]` toggle matching ModsUndBots `term_toggle`. Returns true when clicked. */
    public static boolean toggle(String id, boolean on) {
        float s = s();
        Theme.push(UiHost.theme().monoS());
        String txt = on ? "[×]" : "[ ]";
        float w = Math.max(28 * s, ImGui.calcTextSizeX(txt) + 10 * s), h = 20 * s;
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, w, h);
        boolean hot = ImGui.isItemHovered();
        ImDrawList dl = ImGui.getWindowDrawList();
        if (on) {
            dl.addRectFilled(x, y, x + w, y + h, c(Theme.SEL), 0f);
            dl.addRect(x, y, x + w, y + h, c(Theme.ACCENT), 0f);
        } else {
            if (hot) dl.addRectFilled(x, y, x + w, y + h, c(Theme.SURFACE), 0f);
            dl.addRect(x, y, x + w, y + h, c(hot ? Theme.BORDER_HI : Theme.LINE), 0f);
        }
        float tw = ImGui.calcTextSizeX(txt), fh = ImGui.getFontSize();
        int rgb = on ? Theme.ACCENT : hot ? Theme.FG : Theme.GUTTER;
        dl.addText(x + (w - tw) * 0.5f, y + (h - fh) * 0.5f, c(rgb), txt);
        ImGui.popFont();
        return clicked;
    }

    /** A sharp 0px icon-only terminal button of {@code size}. Returns true when clicked. */
    public static boolean iconButton(String id, String icon, float size, boolean active, boolean enabled, String tip) {
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, size, size);
        boolean hot = ImGui.isItemHovered() && enabled;
        ImDrawList dl = ImGui.getWindowDrawList();
        if (active) {
            dl.addRectFilled(x, y, x + size, y + size, c(Theme.SEL), 0f);
            dl.addRect(x, y, x + size, y + size, c(Theme.ACCENT), 0f);
        } else if (hot) {
            dl.addRectFilled(x, y, x + size, y + size, c(Theme.SURFACE), 0f);
            dl.addRect(x, y, x + size, y + size, c(Theme.BORDER_HI), 0f);
        }
        int rgb = !enabled ? Theme.GUTTER : active ? Theme.ACCENT : hot ? Theme.FG : Theme.DIM;
        EditorIcons.draw(dl, icon, x + size * 0.5f, y + size * 0.5f, size * 0.30f, c(rgb));
        if (tip != null) tooltip(tip);
        return clicked && enabled;
    }
}
