package dev.cg.uihost;

import imgui.ImFont;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;

import java.io.InputStream;
import java.util.Locale;

/**
 * Vintage terminal theme modelled after ModsUndBots: 0px sharp geometry, 1px structural borders, monospace typography
 * (JetBrains Mono primary, Barlow Condensed for large numerals; SIL OFL), and 4 live-switchable palette presets
 * ({@link Preset#SYNTH_TERMINAL}, {@link Preset#GRUVBOX_PIXEL}, {@link Preset#TOKYO_NIGHT},
 * {@link Preset#DRAFTING_PAPER}).
 */
public final class Theme {
    static final Theme INSTANCE = new Theme();
    private Theme() {}

    public enum Preset {
        SYNTH_TERMINAL("synth_terminal", "SYNTH TERMINAL", "Signature Multi-Hue", false),
        GRUVBOX_PIXEL("gruvbox_pixel", "GRUVBOX PIXEL", "Warm Retro Arcade", false),
        TOKYO_NIGHT("tokyo_night", "TOKYO NIGHT", "Crisp Neon Dusk", false),
        DRAFTING_PAPER("drafting_paper", "DRAFTING PAPER", "Clean Light Studio", true);

        public final String id;
        public final String displayName;
        public final String subtitle;
        public final boolean light;

        Preset(String id, String displayName, String subtitle, boolean light) {
            this.id = id;
            this.displayName = displayName;
            this.subtitle = subtitle;
            this.light = light;
        }

        public static Preset fromId(String s) {
            if (s == null) return SYNTH_TERMINAL;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "gruvbox_pixel", "gruvbox" -> GRUVBOX_PIXEL;
                case "tokyo_night", "tokyo" -> TOKYO_NIGHT;
                case "drafting_paper", "paper" -> DRAFTING_PAPER;
                default -> SYNTH_TERMINAL;
            };
        }
    }

    /** Primary UI text (JetBrains Mono 13.5). */ public ImFont ui() { return ui; }
    /** Small UI text (JetBrains Mono 11.5). */ public ImFont small() { return small; }
    /** Code (JetBrains Mono 13.5). */ public ImFont mono() { return mono; }
    /** Small code (JetBrains Mono 11.5): badges, key caps, map labels. */ public ImFont monoS() { return monoS; }
    /** Window titles and section headings (JetBrains Mono 14); falls back to {@link #ui()} if missing. */ public ImFont uiBold() { return uiBold != null ? uiBold : ui; }
    /** Medium numerals (Barlow Condensed 26). */ public ImFont numM() { return numM; }
    /** Large numerals (Barlow Condensed 40). */ public ImFont numL() { return numL; }

    // Live colour tokens (0xRRGGBB), updated by setPreset() — defaults to SYNTH_TERMINAL
    public static int BG = 0x0c0e14, PANEL = 0x11141d, PANEL2 = 0x161b26, SURFACE = 0x161b26;
    public static int LINE = 0x273043, LINE2 = 0x1e2536, BORDER_HI = 0x3b4863, DOT = 0xa5b4d2;
    public static int FG = 0xedf1f8, DIM = 0xa8b3c7, GUTTER = 0x7d8aa1;
    public static int ACCENT = 0x818cf8, ON_ACCENT = 0x0b0e17;
    public static int CTA_BG = 0xedf1f8, CTA_FG = 0x0c0e14;
    public static int OK = 0x22d37e, WARN = 0xf5b731, ERR = 0xf45b69;
    public static int BOT = 0xc084fc, PLAYER = 0x38bdf8, CF = 0xf97316, META = 0xe2bc7a;
    public static int BTN = 0x1d2433, CHIP = 0x161b26, HOVER = 0x252e42, TRACK = 0x273043, SLOT = 0x0c0e14;
    public static int SEL = 0x1d223d, RUNLINE = 0x161f33;

    private static Preset preset = Preset.SYNTH_TERMINAL;
    private static boolean styleDirty = true;

    public static Preset preset() {
        return preset;
    }

    public static void setPreset(Preset next) {
        if (next == null) next = Preset.SYNTH_TERMINAL;
        preset = next;
        switch (next) {
            case SYNTH_TERMINAL -> {
                BG = 0x0c0e14; PANEL = 0x11141d; PANEL2 = 0x161b26; SURFACE = 0x161b26;
                LINE = 0x273043; LINE2 = 0x1e2536; BORDER_HI = 0x3b4863; DOT = 0xa5b4d2;
                FG = 0xedf1f8; DIM = 0xa8b3c7; GUTTER = 0x7d8aa1;
                ACCENT = 0x818cf8; ON_ACCENT = 0x0b0e17;
                CTA_BG = 0xedf1f8; CTA_FG = 0x0c0e14;
                OK = 0x22d37e; WARN = 0xf5b731; ERR = 0xf45b69;
                BOT = 0xc084fc; PLAYER = 0x38bdf8; CF = 0xf97316; META = 0xe2bc7a;
                BTN = 0x1d2433; CHIP = 0x161b26; HOVER = 0x252e42; TRACK = 0x273043; SLOT = 0x0c0e14;
                SEL = 0x1d223d; RUNLINE = 0x161f33;
            }
            case GRUVBOX_PIXEL -> {
                BG = 0x161412; PANEL = 0x1d1b18; PANEL2 = 0x25221e; SURFACE = 0x25221e;
                LINE = 0x3c352e; LINE2 = 0x2e2923; BORDER_HI = 0x574d43; DOT = 0xebdbb2;
                FG = 0xf2e5bc; DIM = 0xc7b899; GUTTER = 0x9b8d76;
                ACCENT = 0x83a598; ON_ACCENT = 0x161412;
                CTA_BG = 0xf2e5bc; CTA_FG = 0x161412;
                OK = 0xa9b665; WARN = 0xfabd2f; ERR = 0xfb4934;
                BOT = 0xd3869b; PLAYER = 0x6cb6c8; CF = 0xfe8019; META = 0xd8a657;
                BTN = 0x302b26; CHIP = 0x25221e; HOVER = 0x3c3630; TRACK = 0x3c352e; SLOT = 0x161412;
                SEL = 0x2b332e; RUNLINE = 0x252c27;
            }
            case TOKYO_NIGHT -> {
                BG = 0x0e111b; PANEL = 0x131724; PANEL2 = 0x181d2e; SURFACE = 0x181d2e;
                LINE = 0x29314a; LINE2 = 0x1f263b; BORDER_HI = 0x3e4a6e; DOT = 0x7aa2f7;
                FG = 0xe4ecfa; DIM = 0xa4b1d0; GUTTER = 0x7a88a8;
                ACCENT = 0x7aa2f7; ON_ACCENT = 0x0b0f1a;
                CTA_BG = 0x7aa2f7; CTA_FG = 0x0b0f1a;
                OK = 0x4fd6be; WARN = 0xe0af68; ERR = 0xf7768e;
                BOT = 0xbb9af7; PLAYER = 0x7dcfff; CF = 0xff9e64; META = 0xcfc9c2;
                BTN = 0x20273d; CHIP = 0x181d2e; HOVER = 0x29324e; TRACK = 0x29314a; SLOT = 0x0e111b;
                SEL = 0x1e2845; RUNLINE = 0x172038;
            }
            case DRAFTING_PAPER -> {
                BG = 0xf3f1ea; PANEL = 0xeae6dc; PANEL2 = 0xfaf8f3; SURFACE = 0xfaf8f3;
                LINE = 0xcec7b7; LINE2 = 0xddd7c8; BORDER_HI = 0xaba28e; DOT = 0x171a21;
                FG = 0x171a21; DIM = 0x444d5c; GUTTER = 0x626b7b;
                ACCENT = 0x3b5bdb; ON_ACCENT = 0xfaf8f3;
                CTA_BG = 0x171a21; CTA_FG = 0xfaf8f3;
                OK = 0x0f8c58; WARN = 0xb06d00; ERR = 0xc92a38;
                BOT = 0x8e3ce0; PLAYER = 0x0284c7; CF = 0xd9531e; META = 0x8a6526;
                BTN = 0xe4dfd3; CHIP = 0xfaf8f3; HOVER = 0xdad4c5; TRACK = 0xcec7b7; SLOT = 0xf3f1ea;
                SEL = 0xdbe2f5; RUNLINE = 0xe4e9f5;
            }
        }
        styleDirty = true;
    }

    static boolean needsStyleApply() {
        return styleDirty;
    }

    private static ImFont ui, small, mono, monoS, uiBold, numM, numL;
    private static float builtScale = -1f;
    private static byte[] monoBytes, barlowBytes;

    /** ImGui packed colour (ABGR) from 0xRRGGBB and alpha. */
    public static int u32(int rgb, float a) {
        int al = Math.max(0, Math.min(255, Math.round(a * 255f)));
        return (al << 24) | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }

    public static int u32(int rgb) {
        return u32(rgb, 1f);
    }

    public static float r(int rgb) { return ((rgb >> 16) & 0xFF) / 255f; }
    public static float g(int rgb) { return ((rgb >> 8) & 0xFF) / 255f; }
    public static float b(int rgb) { return (rgb & 0xFF) / 255f; }

    private static void col(ImGuiStyle s, int idx, int rgb, float a) {
        s.setColor(idx, r(rgb), g(rgb), b(rgb), a);
    }

    /** Coloured text, never treated as a format string (a "%" in a message stays a "%"). */
    public static void textColored(int rgb, String text) {
        pushText(rgb);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
    }

    public static void pushText(int rgb) {
        ImGui.pushStyleColor(ImGuiCol.Text, r(rgb), g(rgb), b(rgb), 1f);
    }

    public static void apply(ImGuiStyle s, float scale) {
        styleDirty = false;
        if (preset.light) ImGui.styleColorsLight(s);
        else ImGui.styleColorsDark(s);
        s.setWindowRounding(0f);
        s.setChildRounding(0f);
        s.setFrameRounding(0f);
        s.setPopupRounding(0f);
        s.setGrabRounding(0f);
        s.setTabRounding(0f);
        s.setScrollbarRounding(0f);
        s.setWindowBorderSize(1f);
        s.setChildBorderSize(1f);
        s.setFrameBorderSize(1f);
        s.setPopupBorderSize(1f);
        s.setWindowPadding(10f * scale, 8f * scale);
        s.setFramePadding(8f * scale, 4.5f * scale);
        s.setItemSpacing(6f * scale, 6f * scale);
        s.setItemInnerSpacing(5f * scale, 4f * scale);
        s.setCellPadding(6f * scale, 4f * scale);
        s.setIndentSpacing(14f * scale);
        s.setScrollbarSize(8f * scale);
        s.setGrabMinSize(10f * scale);

        col(s, ImGuiCol.Text, FG, 1f);
        col(s, ImGuiCol.TextDisabled, GUTTER, 1f);
        col(s, ImGuiCol.WindowBg, PANEL, 0.96f);
        col(s, ImGuiCol.ChildBg, BG, 1f);
        col(s, ImGuiCol.PopupBg, PANEL, 0.98f);
        col(s, ImGuiCol.Border, BORDER_HI, 1f);
        col(s, ImGuiCol.BorderShadow, 0, 0f);
        col(s, ImGuiCol.FrameBg, BG, 1f);
        col(s, ImGuiCol.FrameBgHovered, SURFACE, 1f);
        col(s, ImGuiCol.FrameBgActive, SEL, 1f);
        col(s, ImGuiCol.TitleBg, BG, 1f);
        col(s, ImGuiCol.TitleBgActive, BG, 1f);
        col(s, ImGuiCol.TitleBgCollapsed, BG, 1f);
        col(s, ImGuiCol.MenuBarBg, BG, 1f);
        col(s, ImGuiCol.ScrollbarBg, BG, 1f);
        col(s, ImGuiCol.ScrollbarGrab, BORDER_HI, 1f);
        col(s, ImGuiCol.ScrollbarGrabHovered, GUTTER, 1f);
        col(s, ImGuiCol.ScrollbarGrabActive, DIM, 1f);
        col(s, ImGuiCol.CheckMark, ACCENT, 1f);
        col(s, ImGuiCol.SliderGrab, ACCENT, 1f);
        col(s, ImGuiCol.SliderGrabActive, ACCENT, 1f);
        col(s, ImGuiCol.Button, BTN, 1f);
        col(s, ImGuiCol.ButtonHovered, HOVER, 1f);
        col(s, ImGuiCol.ButtonActive, SEL, 1f);
        col(s, ImGuiCol.Header, SEL, 1f);
        col(s, ImGuiCol.HeaderHovered, SURFACE, 1f);
        col(s, ImGuiCol.HeaderActive, SEL, 1f);
        col(s, ImGuiCol.Separator, LINE, 1f);
        col(s, ImGuiCol.SeparatorHovered, ACCENT, 0.7f);
        col(s, ImGuiCol.SeparatorActive, ACCENT, 1f);
        col(s, ImGuiCol.ResizeGrip, BORDER_HI, 0.35f);
        col(s, ImGuiCol.ResizeGripHovered, ACCENT, 0.7f);
        col(s, ImGuiCol.ResizeGripActive, ACCENT, 1f);
        col(s, ImGuiCol.Tab, BG, 1f);
        col(s, ImGuiCol.TabHovered, SURFACE, 1f);
        col(s, ImGuiCol.TabActive, PANEL, 1f);
        col(s, ImGuiCol.TabUnfocused, BG, 1f);
        col(s, ImGuiCol.TabUnfocusedActive, PANEL, 1f);
        col(s, ImGuiCol.PlotHistogram, ACCENT, 1f);
        col(s, ImGuiCol.TableHeaderBg, BG, 1f);
        col(s, ImGuiCol.TableBorderStrong, BORDER_HI, 1f);
        col(s, ImGuiCol.TableBorderLight, LINE, 1f);
        col(s, ImGuiCol.TableRowBg, PANEL, 0f);
        col(s, ImGuiCol.TableRowBgAlt, SURFACE, 0.5f);
        col(s, ImGuiCol.TextSelectedBg, ACCENT, 0.28f);
        col(s, ImGuiCol.NavHighlight, ACCENT, 1f);
        col(s, ImGuiCol.ModalWindowDimBg, 0, preset.light ? 0.25f : 0.55f);
    }

    /** True when the fonts must be (re)built for this scale. */
    public static boolean needsFonts(float scale) {
        return Math.abs(builtScale - scale) > 0.001f;
    }

    /** Adds the fonts to the atlas at {@code scale}. Call with an empty atlas; returns false if the bundled fonts are missing. */
    public static boolean loadFonts(ImGuiIO io, float scale) {
        builtScale = scale;
        if (monoBytes == null) monoBytes = resource("JetBrainsMono.ttf");
        if (barlowBytes == null) barlowBytes = resource("BarlowCondensed-SemiBold.ttf");
        var atlas = io.getFonts();
        // Latin + punctuation + arrows + box drawing (──) + geometric shapes (■ ▸ ▾ ●)
        short[] ranges = new short[]{
                0x0020, 0x00FF,
                0x2010, 0x2027,
                0x2026, 0x2026,
                0x2190, 0x2193,
                0x2212, 0x2212,
                0x2500, 0x257F,
                0x25A0, 0x25FF,
                0
        };
        ui = small = mono = monoS = uiBold = numM = numL = null;
        if (monoBytes != null) {
            ui = atlas.addFontFromMemoryTTF(monoBytes, 13.5f * scale, cfg(), ranges);
            small = atlas.addFontFromMemoryTTF(monoBytes, 11.5f * scale, cfg(), ranges);
            uiBold = atlas.addFontFromMemoryTTF(monoBytes, 14f * scale, cfg(), ranges);
            mono = ui;
            monoS = small;
        }
        if (barlowBytes != null) {
            numM = atlas.addFontFromMemoryTTF(barlowBytes, 26f * scale, cfg(), ranges);
            numL = atlas.addFontFromMemoryTTF(barlowBytes, 40f * scale, cfg(), ranges);
        }
        if (ui == null) {
            ui = atlas.addFontDefault();
            return false;
        }
        io.setFontDefault(ui);
        return true;
    }

    private static ImFontConfig cfg() {
        ImFontConfig c = new ImFontConfig();
        c.setOversampleH(2);
        c.setOversampleV(1);
        c.setPixelSnapH(true);
        return c;
    }

    private static byte[] resource(String name) {
        try (InputStream in = Theme.class.getClassLoader().getResourceAsStream("assets/cg-uihost/fonts/" + name)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    public static void push(ImFont f) {
        ImGui.pushFont(f != null ? f : ui);
    }
}
