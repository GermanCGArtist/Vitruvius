package dev.cg.uihost;

/** Public entry point of cg-uihost: register layers, read the theme and the UI scale. */
public final class UiHost {
    private UiHost() {}

    /** Adds a layer. A layer with the same id replaces the earlier one. Safe to call before ImGui is initialised. */
    public static void register(UiLayer layer) {
        HostCore.register(layer);
    }

    /** The shared colour tokens and fonts. */
    public static Theme theme() {
        return Theme.INSTANCE;
    }

    /** The UI scale (1.0 = 100 %). */
    public static float scale() {
        return HostCore.scale();
    }

    /** Sets the UI scale (clamped to 0.5..4); fonts and style are rebuilt on the next frame. */
    public static void setScale(float scale) {
        HostCore.setScale(scale);
    }

    /** Whether ImGui is up (natives loaded, context created). False until the first frame with a visible layer. */
    public static boolean isInitialized() {
        return HostCore.isInitialized();
    }

    /** ImGui's own keyboard-capture flag, for the default {@link UiLayer#wantsKeyboard()}. */
    public static boolean imguiWantsKeyboard() {
        return HostCore.imguiWantsKeyboard();
    }

    /** ImGui's own mouse-capture flag, for the default {@link UiLayer#wantsMouse()}. */
    public static boolean imguiWantsMouse() {
        return HostCore.imguiWantsMouse();
    }

    /** ImGui's text-input flag: true while a text field is active. */
    public static boolean imguiWantsTextInput() {
        return HostCore.imguiWantsTextInput();
    }

    /** Mouse cursors a layer can ask for (GLFW standard cursors). {@link #AUTO} leaves the choice to ImGui. */
    public enum Cursor { AUTO, ARROW, TEXT, CROSSHAIR, HAND, RESIZE_EW, RESIZE_NS, RESIZE_ALL, NOT_ALLOWED }

    /**
     * Asks for a cursor shape for the next frame. Call it every frame the shape should stay (typically from
     * {@link UiLayer#draw()}); when a frame passes without a call, ImGui controls the cursor again. Has no effect
     * while the cursor is captured.
     */
    public static void setCursor(Cursor cursor) {
        HostCore.setCursor(cursor);
    }

    /**
     * Hides the cursor and delivers raw relative motion to the layers ({@link UiLayer#onMouseMove}) while true: the
     * mode for orbit and fly. Turning it off restores the cursor at the spot where it was captured. ImGui sees no
     * mouse while captured. Does not touch Minecraft's own mouse-grab state, so vanilla mouse look stays off.
     */
    public static void captureCursor(boolean capture) {
        HostCore.captureCursor(capture);
    }

    /** Whether {@link #captureCursor} is on. */
    public static boolean isCursorCaptured() {
        return HostCore.isCursorCaptured();
    }
}
