package dev.cg.uihost;

import imgui.flag.ImGuiKey;

/**
 * Minecraft 26.3 delivers SDL input (SDL scancodes, SDL modifier bits, 1-based mouse buttons, press = 1 /
 * release = 0 / repeat = -1). The UiLayer API and the layers built on it speak GLFW codes, so this class
 * translates at the edge, and maps GLFW codes to ImGui keys.
 */
public final class InputMap {
    private InputMap() {}

    public static final int GLFW_RELEASE = 0, GLFW_PRESS = 1, GLFW_REPEAT = 2;
    public static final int GLFW_MOD_SHIFT = 1, GLFW_MOD_CONTROL = 2, GLFW_MOD_ALT = 4, GLFW_MOD_SUPER = 8;

    private static final int[] SDL_TO_GLFW = new int[512];
    private static final int[] GLFW_TO_SDL = new int[512];

    static {
        java.util.Arrays.fill(SDL_TO_GLFW, -1);
        for (int i = 0; i < 26; i++) SDL_TO_GLFW[4 + i] = 65 + i;          // A..Z
        for (int i = 0; i < 9; i++) SDL_TO_GLFW[30 + i] = 49 + i;          // 1..9
        SDL_TO_GLFW[39] = 48;                                              // 0
        SDL_TO_GLFW[40] = 257; SDL_TO_GLFW[41] = 256; SDL_TO_GLFW[42] = 259; SDL_TO_GLFW[43] = 258; SDL_TO_GLFW[44] = 32;
        SDL_TO_GLFW[45] = 45; SDL_TO_GLFW[46] = 61; SDL_TO_GLFW[47] = 91; SDL_TO_GLFW[48] = 93; SDL_TO_GLFW[49] = 92;
        SDL_TO_GLFW[51] = 59; SDL_TO_GLFW[52] = 39; SDL_TO_GLFW[53] = 96; SDL_TO_GLFW[54] = 44; SDL_TO_GLFW[55] = 46;
        SDL_TO_GLFW[56] = 47; SDL_TO_GLFW[57] = 280;
        for (int i = 0; i < 12; i++) SDL_TO_GLFW[58 + i] = 290 + i;        // F1..F12
        SDL_TO_GLFW[70] = 283; SDL_TO_GLFW[71] = 281; SDL_TO_GLFW[72] = 284; SDL_TO_GLFW[73] = 260; SDL_TO_GLFW[74] = 268;
        SDL_TO_GLFW[75] = 266; SDL_TO_GLFW[76] = 261; SDL_TO_GLFW[77] = 269; SDL_TO_GLFW[78] = 267;
        SDL_TO_GLFW[79] = 262; SDL_TO_GLFW[80] = 263; SDL_TO_GLFW[81] = 264; SDL_TO_GLFW[82] = 265;
        SDL_TO_GLFW[83] = 282; SDL_TO_GLFW[84] = 331; SDL_TO_GLFW[85] = 332; SDL_TO_GLFW[86] = 333; SDL_TO_GLFW[87] = 334;
        SDL_TO_GLFW[88] = 335;
        for (int i = 0; i < 9; i++) SDL_TO_GLFW[89 + i] = 321 + i;         // KP 1..9
        SDL_TO_GLFW[98] = 320; SDL_TO_GLFW[99] = 330; SDL_TO_GLFW[101] = 348; SDL_TO_GLFW[103] = 336;
        for (int i = 0; i < 12; i++) SDL_TO_GLFW[104 + i] = 302 + i;       // F13..F24
        SDL_TO_GLFW[224] = 341; SDL_TO_GLFW[225] = 340; SDL_TO_GLFW[226] = 342; SDL_TO_GLFW[227] = 343;
        SDL_TO_GLFW[228] = 345; SDL_TO_GLFW[229] = 344; SDL_TO_GLFW[230] = 346; SDL_TO_GLFW[231] = 347;
        java.util.Arrays.fill(GLFW_TO_SDL, -1);
        for (int i = 0; i < SDL_TO_GLFW.length; i++) if (SDL_TO_GLFW[i] >= 0) GLFW_TO_SDL[SDL_TO_GLFW[i]] = i;
    }

    /** GLFW key code of an SDL scancode, or -1 (GLFW_KEY_UNKNOWN). */
    public static int glfwKey(int sdlScancode) {
        return sdlScancode >= 0 && sdlScancode < SDL_TO_GLFW.length ? SDL_TO_GLFW[sdlScancode] : -1;
    }

    /** The SDL scancode of a GLFW key code, or -1. */
    public static int sdlScancode(int glfwKey) {
        return glfwKey >= 0 && glfwKey < GLFW_TO_SDL.length ? GLFW_TO_SDL[glfwKey] : -1;
    }

    public static int glfwAction(int mcAction) {
        return mcAction == 0 ? GLFW_RELEASE : mcAction < 0 ? GLFW_REPEAT : GLFW_PRESS;
    }

    public static int glfwMods(int sdlMods) {
        int m = 0;
        if ((sdlMods & 0x0003) != 0) m |= GLFW_MOD_SHIFT;
        if ((sdlMods & 0x00C0) != 0) m |= GLFW_MOD_CONTROL;
        if ((sdlMods & 0x0300) != 0) m |= GLFW_MOD_ALT;
        if ((sdlMods & 0x0C00) != 0) m |= GLFW_MOD_SUPER;
        return m;
    }

    /** SDL buttons are 1-based (left, middle, right, x1, x2); GLFW's are 0-based (left, right, middle, 4, 5). */
    public static int glfwButton(int sdlButton) {
        return switch (sdlButton) {
            case 1 -> 0;
            case 2 -> 2;
            case 3 -> 1;
            default -> sdlButton - 1;
        };
    }

    /** ImGui key of a GLFW key code, or ImGuiKey.None. */
    public static int imguiKey(int glfw) {
        if (glfw >= 65 && glfw <= 90) return ImGuiKey.A + (glfw - 65);
        if (glfw >= 48 && glfw <= 57) return ImGuiKey._0 + (glfw - 48);
        if (glfw >= 290 && glfw <= 301) return ImGuiKey.F1 + (glfw - 290);
        if (glfw >= 302 && glfw <= 313) return ImGuiKey.F13 + (glfw - 302);
        if (glfw >= 320 && glfw <= 329) return ImGuiKey.Keypad0 + (glfw - 320);
        return switch (glfw) {
            case 258 -> ImGuiKey.Tab;
            case 263 -> ImGuiKey.LeftArrow;
            case 262 -> ImGuiKey.RightArrow;
            case 265 -> ImGuiKey.UpArrow;
            case 264 -> ImGuiKey.DownArrow;
            case 266 -> ImGuiKey.PageUp;
            case 267 -> ImGuiKey.PageDown;
            case 268 -> ImGuiKey.Home;
            case 269 -> ImGuiKey.End;
            case 260 -> ImGuiKey.Insert;
            case 261 -> ImGuiKey.Delete;
            case 259 -> ImGuiKey.Backspace;
            case 32 -> ImGuiKey.Space;
            case 257 -> ImGuiKey.Enter;
            case 256 -> ImGuiKey.Escape;
            case 39 -> ImGuiKey.Apostrophe;
            case 44 -> ImGuiKey.Comma;
            case 45 -> ImGuiKey.Minus;
            case 46 -> ImGuiKey.Period;
            case 47 -> ImGuiKey.Slash;
            case 59 -> ImGuiKey.Semicolon;
            case 61 -> ImGuiKey.Equal;
            case 91 -> ImGuiKey.LeftBracket;
            case 92 -> ImGuiKey.Backslash;
            case 93 -> ImGuiKey.RightBracket;
            case 96 -> ImGuiKey.GraveAccent;
            case 280 -> ImGuiKey.CapsLock;
            case 281 -> ImGuiKey.ScrollLock;
            case 282 -> ImGuiKey.NumLock;
            case 283 -> ImGuiKey.PrintScreen;
            case 284 -> ImGuiKey.Pause;
            case 330 -> ImGuiKey.KeypadDecimal;
            case 331 -> ImGuiKey.KeypadDivide;
            case 332 -> ImGuiKey.KeypadMultiply;
            case 333 -> ImGuiKey.KeypadSubtract;
            case 334 -> ImGuiKey.KeypadAdd;
            case 335 -> ImGuiKey.KeypadEnter;
            case 336 -> ImGuiKey.KeypadEqual;
            case 340 -> ImGuiKey.LeftShift;
            case 341 -> ImGuiKey.LeftCtrl;
            case 342 -> ImGuiKey.LeftAlt;
            case 343 -> ImGuiKey.LeftSuper;
            case 344 -> ImGuiKey.RightShift;
            case 345 -> ImGuiKey.RightCtrl;
            case 346 -> ImGuiKey.RightAlt;
            case 347 -> ImGuiKey.RightSuper;
            case 348 -> ImGuiKey.Menu;
            default -> ImGuiKey.None;
        };
    }
}
