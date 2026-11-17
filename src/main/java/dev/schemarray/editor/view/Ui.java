package dev.schemarray.editor.view;

import dev.cg.uihost.Theme;
import dev.cg.uihost.UiHost;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImBoolean;

/** Small drawing and widget helpers shared by the editor windows (DESIGN 3 and 15.2). Render thread only. */
public final class Ui {
    private Ui() {}

    /** Tokens that DESIGN 2.2 adds on top of the host theme. */
    public static final int AX_X = 0xf0605e, AX_Y = 0x8ad27e, AX_Z = 0x5b9cf0, BOOL = 0xb48cf0, LINK = 0x7fc8f0,
            T_FOLDER = 0xc9b27a, T_OBJECT = 0x9fb4c9, DIM_ON_SEL = 0xa8afb8, AREA = 0xf2b35e, OVERLAP = 0xf07470;

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

    /**
     * Begins an editor window with the style of DESIGN 3.1. Always pair with {@link #endWindow()}, also when this
     * returns false.
     */
    public static boolean beginWindow(String title, ImBoolean open, int flags, float alpha) {
        float s = s();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 8 * s);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 10 * s, 8 * s);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8 * s, 6 * s);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemInnerSpacing, 6 * s, 5 * s);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 5 * s);
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, 7 * s);
        ImGui.pushStyleVar(ImGuiStyleVar.ScrollbarSize, 10 * s);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 9 * s, 6.5f * s);
        styleVars = 9;
        ImGui.pushStyleColor(ImGuiCol.WindowBg, c(Theme.PANEL, alpha));
        ImGui.pushStyleColor(ImGuiCol.Border, c(Theme.LINE));
        ImGui.pushStyleColor(ImGuiCol.TitleBg, c(Theme.PANEL2));
        ImGui.pushStyleColor(ImGuiCol.TitleBgActive, c(Theme.PANEL2));
        ImGui.pushStyleColor(ImGuiCol.TitleBgCollapsed, c(Theme.PANEL2));
        styleColors = 5;
        Theme.push(UiHost.theme().uiBold());
        boolean visible = ImGui.begin(title, open, flags | imgui.flag.ImGuiWindowFlags.NoCollapse);
        ImGui.popFont();
        ImGui.popStyleVar();                       // the taller title frame padding
        styleVars--;
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 9 * s, 5 * s);
        styleVars++;
        Theme.push(UiHost.theme().ui());
        return visible;
    }

    public static void endWindow() {
        ImGui.popFont();
        ImGui.end();
        ImGui.popStyleColor(styleColors);
        ImGui.popStyleVar(styleVars);
    }

    // ------------------------------------------------------------------ text and rules

    /** An upper-case small DIM heading (Widgets.label). */
    public static void label(String text) {
        Theme.push(UiHost.theme().small());
        ImGui.pushStyleColor(ImGuiCol.Text, c(Theme.DIM));
        ImGui.text(text.toUpperCase(java.util.Locale.ROOT));
        ImGui.popStyleColor();
        ImGui.popFont();
    }

    /** A section start: a 1 px LINE2 rule, then the heading. */
    public static void section(String heading) {
        float s = s();
        ImGui.dummy(0, 3 * s);
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getWindowPosX() + 1, w = ImGui.getWindowWidth() - 2, y = ImGui.getCursorScreenPosY();
        dl.addLine(x, y, x + w, y, c(Theme.LINE2), 1f);
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

    /** A key cap (18 high, CHIP fill, LINE border, monoS). Returns its right edge. */
    public static float keyCap(ImDrawList dl, float x, float y, String text, int textRgb) {
        float s = s();
        Theme.push(UiHost.theme().monoS());
        float w = ImGui.calcTextSizeX(text) + 10 * s, h = 18 * s, fh = ImGui.getFontSize();
        dl.addRectFilled(x, y, x + w, y + h, c(Theme.CHIP), 4 * s);
        dl.addRect(x, y, x + w, y + h, c(Theme.LINE), 4 * s);
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

    /** A pill/badge with text (17 high). Returns the width drawn. */
    public static float badge(ImDrawList dl, float x, float y, String text, int fillRgb, float fillAlpha, int textRgb) {
        float s = s();
        Theme.push(UiHost.theme().monoS());
        float w = ImGui.calcTextSizeX(text) + 10 * s, h = 17 * s, fh = ImGui.getFontSize();
        dl.addRectFilled(x, y, x + w, y + h, c(fillRgb, fillAlpha), 4 * s);
        dl.addText(x + 5 * s, y + (h - fh) * 0.5f, c(textRgb), text);
        ImGui.popFont();
        return w;
    }

    /**
     * A normal 25 px button. {@code primary}: ACCENT fill. A disabled button shows its reason as a tooltip.
     */
    public static boolean button(String label, boolean primary, boolean enabled, float w, String disabledTip) {
        if (primary) {
            ImGui.pushStyleColor(ImGuiCol.Button, c(Theme.ACCENT));
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, c(Theme.ACCENT, 0.85f));
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, c(Theme.ACCENT, 0.7f));
            ImGui.pushStyleColor(ImGuiCol.Text, c(Theme.ON_ACCENT));
        }
        if (!enabled) ImGui.beginDisabled();
        boolean clicked = ImGui.button(label, w, 0);
        if (!enabled) {
            ImGui.endDisabled();
            if (disabledTip != null && !disabledTip.isEmpty()) tooltip(disabledTip);
        }
        if (primary) ImGui.popStyleColor(4);
        return clicked && enabled;
    }

    /** The segmented control of DESIGN 8.2. Returns the clicked segment or -1; {@code active} -1 lights none. */
    public static int segmented(String id, String[] labels, int active, float totalW, boolean enabled) {
        float s = s();
        float gap = 2 * s, pad = 2 * s, h = 25 * s;
        float segW = (totalW - pad * 2 - gap * (labels.length - 1)) / labels.length;
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY();
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(x0, y0, x0 + totalW, y0 + h, c(Theme.PANEL2), 5 * s);
        dl.addRect(x0, y0, x0 + totalW, y0 + h, c(Theme.LINE), 5 * s);
        int clicked = -1;
        for (int i = 0; i < labels.length; i++) {
            float sx = x0 + pad + i * (segW + gap);
            ImGui.setCursorScreenPos(sx, y0 + pad);
            boolean hit = ImGui.invisibleButton(id + i, segW, h - pad * 2);
            boolean hot = ImGui.isItemHovered() && enabled;
            boolean on = i == active;
            if (on) {
                dl.addRectFilled(sx, y0 + pad, sx + segW, y0 + h - pad, c(Theme.BTN), 4 * s);
                dl.addRect(sx, y0 + pad, sx + segW, y0 + h - pad, c(Theme.LINE), 4 * s);
            } else if (hot) {
                dl.addRectFilled(sx, y0 + pad, sx + segW, y0 + h - pad, c(Theme.CHIP), 4 * s);
            }
            float tw = ImGui.calcTextSizeX(labels[i]), fh = ImGui.getFontSize();
            int rgb = !enabled ? Theme.GUTTER : on ? Theme.FG : Theme.DIM;
            dl.addText(sx + (segW - tw) * 0.5f, y0 + (h - fh) * 0.5f, c(rgb), labels[i]);
            if (hit && enabled) clicked = i;
        }
        ImGui.setCursorScreenPos(x0, y0 + h);
        ImGui.dummy(totalW, 0);
        return clicked;
    }

    /** The 26 x 14 enable switch of DESIGN 8.11. Returns true when clicked. */
    public static boolean toggle(String id, boolean on) {
        float s = s();
        float w = 26 * s, h = 14 * s;
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, w, h);
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(x, y, x + w, y + h, c(on ? Theme.ACCENT : Theme.TRACK), h * 0.5f);
        float kr = 5 * s, kx = on ? x + w - h * 0.5f : x + h * 0.5f;
        dl.addCircleFilled(kx, y + h * 0.5f, kr, c(on ? Theme.ON_ACCENT : Theme.FG), 16);
        return clicked;
    }

    /** An icon-only button of {@code size}; draws the icon in the given colour states. Returns true when clicked. */
    public static boolean iconButton(String id, String icon, float size, boolean active, boolean enabled, String tip) {
        float s = s();
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, size, size);
        boolean hot = ImGui.isItemHovered() && enabled;
        ImDrawList dl = ImGui.getWindowDrawList();
        if (active) dl.addRectFilled(x, y, x + size, y + size, c(Theme.BTN), 5 * s);
        else if (hot) dl.addRectFilled(x, y, x + size, y + size, c(Theme.BTN), 5 * s);
        int rgb = !enabled ? Theme.GUTTER : active ? Theme.ACCENT : hot ? Theme.FG : Theme.DIM;
        EditorIcons.draw(dl, icon, x + size * 0.5f, y + size * 0.5f, size * 0.30f, c(rgb));
        if (tip != null) tooltip(tip);
        return clicked && enabled;
    }
}
