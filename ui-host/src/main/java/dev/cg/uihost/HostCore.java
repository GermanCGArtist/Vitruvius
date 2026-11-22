package dev.cg.uihost;

import com.mojang.blaze3d.platform.Window;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiMouseCursor;
import imgui.internal.ImGuiContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLMouse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Internal: ImGui lifecycle, frame loop and input routing. Called by the mixins in {@code dev.cg.uihost.mixin}
 * and by {@link UiHost}; not API. Init is lazy (first frame with a visible layer); any failure disables the host
 * with one warning and never crashes the game.
 */
public final class HostCore {
    private static final Logger LOG = LoggerFactory.getLogger("cg-uihost");

    private static final List<UiLayer> LAYERS = new CopyOnWriteArrayList<>();
    private static final Set<String> FAILED_LAYERS = new HashSet<>();

    private static boolean initialized;
    private static boolean disabled;
    private static float scale = 1.0f;
    private static ImGuiContext ctx;
    private static long lastFrameNanos;
    private static boolean textInputOn;
    private static RpRenderer renderer;

    private HostCore() {}

    // ------------------------------------------------------------------ layers

    static void register(UiLayer layer) {
        if (layer == null) return;
        LAYERS.removeIf(l -> l.id().equals(layer.id()));
        LAYERS.add(layer);
        LAYERS.sort((a, b) -> Integer.compare(a.order(), b.order()));
    }

    /** The visible, not failed layers in draw order. */
    private static List<UiLayer> visibleLayers() {
        List<UiLayer> out = null;
        for (UiLayer l : LAYERS) {
            boolean vis;
            try {
                vis = l.visible() && !FAILED_LAYERS.contains(l.id());
            } catch (Throwable t) {
                fail(l, t);
                continue;
            }
            if (vis) {
                if (out == null) out = new ArrayList<>(4);
                out.add(l);
            }
        }
        return out == null ? List.of() : out;
    }

    private static void fail(UiLayer l, Throwable t) {
        if (FAILED_LAYERS.add(l.id())) LOG.warn("[cg-uihost] layer '{}' disabled after an error", l.id(), t);
    }

    // ------------------------------------------------------------------ state

    static float scale() {
        return scale;
    }

    static void setScale(float s) {
        scale = Math.max(0.5f, Math.min(4f, s));
    }

    static boolean isInitialized() {
        return initialized;
    }

    static boolean imguiWantsKeyboard() {
        if (!initialized) return false;
        ImGui.setCurrentContext(ctx);
        return ImGui.getIO().getWantCaptureKeyboard();
    }

    static boolean imguiWantsMouse() {
        if (!initialized) return false;
        ImGui.setCurrentContext(ctx);
        return ImGui.getIO().getWantCaptureMouse();
    }

    static boolean imguiWantsTextInput() {
        if (!initialized) return false;
        ImGui.setCurrentContext(ctx);
        return ImGui.getIO().getWantTextInput();
    }

    // ------------------------------------------------------------------ cursor

    private static boolean cursorCaptured;
    private static UiHost.Cursor cursorRequest;
    private static final long[] STD_CURSORS = new long[UiHost.Cursor.values().length];

    static void setCursor(UiHost.Cursor c) {
        cursorRequest = c;
    }

    static boolean isCursorCaptured() {
        return cursorCaptured;
    }

    /** SDL relative mouse mode: hidden cursor, raw motion (delivered as deltas; see {@link #onMouseMove}). */
    static void captureCursor(boolean on) {
        if (on == cursorCaptured) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return;
        cursorCaptured = on;
        if (on) {
            virtualX = mouseX;
            virtualY = mouseY;
        }
        SDLMouse.SDL_SetWindowRelativeMouseMode(mc.getWindow().handle(), on);
    }

    private static int sdlCursorId(UiHost.Cursor c) {
        return switch (c) {
            case TEXT -> SDLMouse.SDL_SYSTEM_CURSOR_TEXT;
            case CROSSHAIR -> SDLMouse.SDL_SYSTEM_CURSOR_CROSSHAIR;
            case HAND -> SDLMouse.SDL_SYSTEM_CURSOR_POINTER;
            case RESIZE_EW -> SDLMouse.SDL_SYSTEM_CURSOR_EW_RESIZE;
            case RESIZE_NS -> SDLMouse.SDL_SYSTEM_CURSOR_NS_RESIZE;
            case RESIZE_ALL -> SDLMouse.SDL_SYSTEM_CURSOR_MOVE;
            case NOT_ALLOWED -> SDLMouse.SDL_SYSTEM_CURSOR_NOT_ALLOWED;
            default -> SDLMouse.SDL_SYSTEM_CURSOR_DEFAULT;
        };
    }

    private static long standardCursor(UiHost.Cursor c) {
        int i = c.ordinal();
        if (STD_CURSORS[i] == 0L) {
            long h = SDLMouse.SDL_CreateSystemCursor(sdlCursorId(c));
            if (h == 0L) h = SDLMouse.SDL_CreateSystemCursor(SDLMouse.SDL_SYSTEM_CURSOR_DEFAULT);
            STD_CURSORS[i] = h;
        }
        return STD_CURSORS[i];
    }

    private static UiHost.Cursor fromImGui(int cursor) {
        return switch (cursor) {
            case ImGuiMouseCursor.TextInput -> UiHost.Cursor.TEXT;
            case ImGuiMouseCursor.ResizeAll -> UiHost.Cursor.RESIZE_ALL;
            case ImGuiMouseCursor.ResizeNS -> UiHost.Cursor.RESIZE_NS;
            case ImGuiMouseCursor.ResizeEW -> UiHost.Cursor.RESIZE_EW;
            case ImGuiMouseCursor.Hand -> UiHost.Cursor.HAND;
            case ImGuiMouseCursor.NotAllowed -> UiHost.Cursor.NOT_ALLOWED;
            default -> UiHost.Cursor.ARROW;
        };
    }

    // ------------------------------------------------------------------ polled input state (GLFW codes)

    private static final boolean[] SDL_KEYS_DOWN = new boolean[512];
    private static final boolean[] BUTTONS_DOWN = new boolean[8];
    private static double mouseX, mouseY;
    private static double virtualX, virtualY;

    /** Whether the key (a GLFW key code) is held, from the key events seen so far. */
    static boolean isKeyDown(int glfwKey) {
        int sc = InputMap.sdlScancode(glfwKey);
        return sc >= 0 && SDL_KEYS_DOWN[sc];
    }

    /** Whether the mouse button (GLFW numbering: 0 left, 1 right, 2 middle) is held. */
    static boolean isMouseDown(int glfwButton) {
        return glfwButton >= 0 && glfwButton < BUTTONS_DOWN.length && BUTTONS_DOWN[glfwButton];
    }

    /** The cursor position in window coordinates; virtual and unbounded while the cursor is captured. */
    static double cursorX() {
        return cursorCaptured ? virtualX : mouseX;
    }

    static double cursorY() {
        return cursorCaptured ? virtualY : mouseY;
    }

    /** Whether the host can draw with the running graphics backend: it draws through Minecraft's render API, so always. */
    static boolean backendSupported() {
        return true;
    }


    // ------------------------------------------------------------------ frame (render thread)

    /** Called at the end of the GUI renderer's render: the GUI is in the main render target, the window blit follows. */
    public static void renderFrame() {
        if (disabled || LAYERS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Window win = mc.getWindow();
        if (win == null || win.isIconified() || win.getWidth() <= 0 || win.getHeight() <= 0) return;
        List<UiLayer> layers = visibleLayers();
        if (layers.isEmpty()) return;
        try {
            if (!initialized && !init(win.handle())) return;
            render(mc, layers);
        } catch (Throwable t) {
            disabled = true;
            LOG.warn("[cg-uihost] ImGui host disabled after an error", t);
        }
    }

    private static boolean init(long windowHandle) {
        try {
            ImGuiNatives.load(FabricLoader.getInstance().getConfigDir().resolve("cg-uihost").resolve("natives"),
                    HostCore.class.getClassLoader());

            ctx = ImGui.createContext();
            ImGui.setCurrentContext(ctx);
            ImGuiIO io = ImGui.getIO();
            io.setIniFilename(null);
            io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard | ImGuiConfigFlags.DockingEnable);
            io.setConfigDockingWithShift(true);   // windows float; hold Shift while dragging to dock

            Theme.apply(ImGui.getStyle(), scale);
            try {
                Theme.loadFonts(io, scale);
            } catch (Throwable t) {
                LOG.warn("[cg-uihost] bundled fonts failed to load, using ImGui's default", t);
                io.getFonts().addFontDefault();
            }

            io.setBackendPlatformName("cg-uihost-sdl");

            renderer = new RpRenderer();
            renderer.init(io);
            initialized = true;
            return true;
        } catch (Throwable t) {
            disabled = true;
            LOG.warn("[cg-uihost] ImGui host disabled: {}", t.toString());
            LOG.debug("[cg-uihost] init failure", t);
            return false;
        }
    }

    private static void refreshScale() {
        if (Theme.needsFonts(scale)) {
            ImGuiIO io = ImGui.getIO();
            io.getFonts().clear();
            Theme.loadFonts(io, scale);
            renderer.createFontTexture(io);
            Theme.apply(ImGui.getStyle(), scale);
        } else if (Theme.needsStyleApply()) {
            Theme.apply(ImGui.getStyle(), scale);
        }
    }

    private static void platformNewFrame(Minecraft mc, ImGuiIO io) {
        Window win = mc.getWindow();
        int sw = Math.max(1, win.getScreenWidth()), sh = Math.max(1, win.getScreenHeight());
        io.setDisplaySize(sw, sh);
        io.setDisplayFramebufferScale((float) win.getWidth() / sw, (float) win.getHeight() / sh);
        long now = System.nanoTime();
        io.setDeltaTime(lastFrameNanos == 0 ? 1f / 60f : Math.max(1e-4f, (now - lastFrameNanos) / 1e9f));
        lastFrameNanos = now;
        // text fields need SDL's text input (it produces the character events)
        boolean wantText = io.getWantTextInput();
        if (wantText != textInputOn) {
            textInputOn = wantText;
            if (wantText) SDLKeyboard.SDL_StartTextInput(win.handle());
            else SDLKeyboard.SDL_StopTextInput(win.handle());
        }
        // ImGui's own cursor, unless a layer asked for a shape or the cursor is captured
        if ((io.getConfigFlags() & ImGuiConfigFlags.NoMouseCursorChange) == 0 && io.getWantCaptureMouse()) {
            SDLMouse.SDL_SetCursor(standardCursor(fromImGui(ImGui.getMouseCursor())));
        }
    }

    private static void render(Minecraft mc, List<UiLayer> layers) {
        ImGui.setCurrentContext(ctx);
        refreshScale();
        ImGuiIO io = ImGui.getIO();
        UiHost.Cursor wanted = cursorRequest;
        cursorRequest = null;
        if (mc.mouseHandler.isMouseGrabbed() || cursorCaptured) {
            // Mouse look / orbit / fly: ImGui must neither see the pointer nor touch the cursor mode.
            io.addConfigFlags(ImGuiConfigFlags.NoMouse | ImGuiConfigFlags.NoMouseCursorChange);
            io.addMousePosEvent(-Float.MAX_VALUE, -Float.MAX_VALUE);
        } else {
            io.removeConfigFlags(ImGuiConfigFlags.NoMouse);
            if (wanted != null && wanted != UiHost.Cursor.AUTO) {
                io.addConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
                SDLMouse.SDL_SetCursor(standardCursor(wanted));
            } else {
                io.removeConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
            }
        }
        io.setWantSetMousePos(false);
        platformNewFrame(mc, io);
        ImGui.newFrame();

        for (UiLayer l : layers) {
            if (FAILED_LAYERS.contains(l.id())) continue;
            try {
                l.draw();
            } catch (Throwable t) {
                fail(l, t);
            }
        }

        ImGui.render();
        renderer.render(mc, ImGui.getDrawData());
    }

    // ------------------------------------------------------------------ input routing
    // The mixins pass Minecraft 26.3's SDL values; layers get GLFW codes (see InputMap).

    private static boolean routing(long window, Minecraft mc) {
        return initialized && !disabled && mc != null && mc.getWindow() != null && window == mc.getWindow().handle()
                && !visibleLayers().isEmpty();
    }

    private static boolean anyWantsKeyboard() {
        for (UiLayer l : visibleLayers()) {
            try {
                if (l.wantsKeyboard()) return true;
            } catch (Throwable t) {
                fail(l, t);
            }
        }
        return false;
    }

    private static boolean anyWantsKey(int key, int scancode, int action, int mods) {
        for (UiLayer l : visibleLayers()) {
            try {
                if (l.wantsKey(key, scancode, action, mods)) return true;
            } catch (Throwable t) {
                fail(l, t);
            }
        }
        return false;
    }

    /** Returns true when vanilla must not see the event. Releases always reach ImGui so no key stays stuck. */
    public static boolean onKey(long window, int sdlScancode, int mcAction, int sdlMods) {
        if (sdlScancode >= 0 && sdlScancode < SDL_KEYS_DOWN.length) SDL_KEYS_DOWN[sdlScancode] = mcAction != 0;
        Minecraft mc = Minecraft.getInstance();
        if (!routing(window, mc)) return false;
        int key = InputMap.glfwKey(sdlScancode), action = InputMap.glfwAction(mcAction), mods = InputMap.glfwMods(sdlMods);
        boolean wants = anyWantsKey(key, sdlScancode, action, mods);
        ImGui.setCurrentContext(ctx);
        if (wants || action == InputMap.GLFW_RELEASE) {
            int ik = InputMap.imguiKey(key);
            if (ik != 0 && action != InputMap.GLFW_REPEAT) ImGui.getIO().addKeyEvent(ik, action == InputMap.GLFW_PRESS);
            for (UiLayer l : visibleLayers()) {
                try {
                    l.onKey(key, sdlScancode, action, mods);
                } catch (Throwable t) {
                    fail(l, t);
                }
            }
        }
        return wants;
    }

    public static boolean onChar(long window, int codepoint) {
        Minecraft mc = Minecraft.getInstance();
        if (!routing(window, mc)) return false;
        if (!anyWantsKeyboard()) return false;
        ImGui.setCurrentContext(ctx);
        ImGui.getIO().addInputCharacter(codepoint);
        return true;
    }

    public static boolean onMouseMove(long window, double x, double y, double dx, double dy) {
        mouseX = x;
        mouseY = y;
        if (cursorCaptured) {
            virtualX += dx;
            virtualY += dy;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!routing(window, mc) || mc.mouseHandler.isMouseGrabbed()) return false;
        ImGui.setCurrentContext(ctx);
        if (!cursorCaptured) ImGui.getIO().addMousePosEvent((float) x, (float) y);
        boolean wants = false;
        double lx = cursorX(), ly = cursorY();
        for (UiLayer l : visibleLayers()) {
            try {
                if (l.wantsMouse() || cursorCaptured) {
                    wants = true;
                    l.onMouseMove(lx, ly);
                }
            } catch (Throwable t) {
                fail(l, t);
            }
        }
        return wants;
    }

    public static boolean onMouseButton(long window, int sdlButton, int mcAction, int sdlMods) {
        int button = InputMap.glfwButton(sdlButton), action = InputMap.glfwAction(mcAction), mods = InputMap.glfwMods(sdlMods);
        if (button >= 0 && button < BUTTONS_DOWN.length) BUTTONS_DOWN[button] = action != InputMap.GLFW_RELEASE;
        Minecraft mc = Minecraft.getInstance();
        if (!routing(window, mc) || mc.mouseHandler.isMouseGrabbed()) return false;
        boolean wants = false;
        for (UiLayer l : visibleLayers()) {
            try {
                if (l.wantsMouse()) wants = true;
            } catch (Throwable t) {
                fail(l, t);
            }
        }
        ImGui.setCurrentContext(ctx);
        if (wants || action == InputMap.GLFW_RELEASE) {
            // ImGui orders the buttons left, right, middle like GLFW
            ImGui.getIO().addMouseButtonEvent(button, action == InputMap.GLFW_PRESS);
            for (UiLayer l : visibleLayers()) {
                try {
                    if (action == InputMap.GLFW_RELEASE || l.wantsMouse()) l.onMouseButton(button, action, mods);
                } catch (Throwable t) {
                    fail(l, t);
                }
            }
        }
        return wants;
    }

    public static boolean onMouseScroll(long window, double dx, double dy) {
        Minecraft mc = Minecraft.getInstance();
        if (!routing(window, mc) || mc.mouseHandler.isMouseGrabbed()) return false;
        boolean wants = false;
        for (UiLayer l : visibleLayers()) {
            try {
                if (l.wantsMouse()) {
                    wants = true;
                    l.onScroll(dx, dy);
                }
            } catch (Throwable t) {
                fail(l, t);
            }
        }
        if (wants) {
            ImGui.setCurrentContext(ctx);
            ImGui.getIO().addMouseWheelEvent((float) dx, (float) dy);
        }
        return wants;
    }
}
