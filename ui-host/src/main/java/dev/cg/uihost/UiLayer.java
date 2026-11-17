package dev.cg.uihost;

import imgui.ImGui;

/**
 * One piece of ImGui UI. Register it once with {@link UiHost#register(UiLayer)}; the host calls {@link #draw()}
 * every frame while {@link #visible()} is true, between {@code ImGui.newFrame()} and {@code ImGui.render()}, in
 * ascending {@link #order()}. All methods are called on the render thread.
 */
public interface UiLayer {
    /** Unique, stable id (for logs and de-duplication), e.g. {@code "schemarray.editor"}. */
    String id();

    /** Draw order: lower first, so higher orders are drawn on top. */
    int order();

    /** Whether the layer draws and takes part in input routing this frame. */
    boolean visible();

    /** Issues the ImGui calls for this frame. An exception disables the layer (logged once). */
    void draw();

    /** Whether this layer wants keyboard input. Default: ImGui says it wants it (a text field or focused window). */
    default boolean wantsKeyboard() {
        return UiHost.imguiWantsKeyboard();
    }

    /** Whether this layer wants mouse input. Default: ImGui says the pointer is over one of its windows. */
    default boolean wantsMouse() {
        return UiHost.imguiWantsMouse();
    }

    /**
     * Whether this layer wants this particular key event (so vanilla must not see it). Called for every key event
     * while the layer is visible. Default: {@link #wantsKeyboard()}. A layer that blocks most keys but lets a few
     * through (chat, F-keys) overrides this. Releases always reach ImGui regardless, so no key stays stuck.
     */
    default boolean wantsKey(int key, int scancode, int action, int mods) {
        return wantsKeyboard();
    }

    /** A key event this layer wanted (see {@link #wantsKey}); also called for releases. ImGui has already seen it. */
    default void onKey(int key, int scancode, int action, int mods) {}

    /**
     * A mouse button event, for layers whose {@link #wantsMouse()} is true (releases are always delivered). ImGui sees
     * the event too; use {@link UiHost#imguiWantsMouse()} to tell a click on an ImGui window from one on the viewport.
     */
    default void onMouseButton(int button, int action, int mods) {}

    /** The cursor moved (window coordinates; virtual, unbounded coordinates while the cursor is captured). */
    default void onMouseMove(double x, double y) {}

    /** The wheel turned over a layer that wants the mouse. */
    default void onScroll(double dx, double dy) {}
}
