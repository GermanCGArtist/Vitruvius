package dev.vitruvius.editor;

import dev.cg.uihost.UiHost;
import dev.cg.uihost.UiLayer;
import dev.vitruvius.editor.cam.EditorCamera;
import dev.vitruvius.editor.mc.Picking;
import dev.vitruvius.editor.view.ContextMenus;
import dev.vitruvius.editor.view.EditorChrome;
import dev.vitruvius.editor.view.PropertiesWindow;
import dev.vitruvius.editor.view.SceneWindow;
import dev.vitruvius.editor.view.ShapeWindow;
import dev.vitruvius.editor.view.TopViewWindow;
import dev.vitruvius.editor.view.Toasts;
import dev.vitruvius.editor.view.TransformHud;
import dev.vitruvius.editor.world.WorldOverlay;
import imgui.ImGui;
import imgui.flag.ImGuiDockNodeFlags;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The editor's cg-uihost layer: draws the editor UI and routes input while editor mode is on.
 * <ul>
 *   <li>Drawing: a dock space over the main viewport with a transparent, pass-through central node (the game shows
 *       through and the mouse there belongs to the viewport), the Scene and Properties windows, the toolbar and
 *       status bar, context menus, toasts and the name tags of the selection.</li>
 *   <li>Mouse: always ours (so a click can never re-grab the mouse or attack), except while a screen such as chat
 *       is open and the pointer is not over an ImGui window. Presses that land on an ImGui window belong to
 *       ImGui; presses on the viewport drive picking and the {@link EditorCamera}.</li>
 *   <li>Keyboard: everything is blocked from vanilla except F1 to F12, the editor toggle, chat and command. Keys
 *       not typed into a text field run the editor shortcuts of {@link EditorSession#onKey}.</li>
 * </ul>
 */
public final class EditorInputLayer implements UiLayer {
    private static final Logger LOG = LoggerFactory.getLogger("vitruvius-editor");

    private int frame;
    private boolean uiFailed;

    @Override public String id() { return "vitruvius.editor"; }
    @Override public int order() { return 100; }
    @Override public boolean visible() { return EditorMode.isActive(); }

    @Override
    public void draw() {
        EditorSession s = null;
        if (!uiFailed) {
            try {
                s = EditorSession.get();
            } catch (Throwable t) {
                uiFailed = true;
                LOG.warn("[vitruvius] editor UI disabled", t);
            }
        }
        if (s == null) {
            EditorChrome.draw(null);
            return;
        }
        try {
            drawUi(s);
        } catch (RuntimeException | LinkageError e) {
            uiFailed = true;
            LOG.warn("[vitruvius] editor UI failed", e);
            throw e;
        }
    }

    private void drawUi(EditorSession s) {
        Minecraft mc = Minecraft.getInstance();
        ImGui.dockSpaceOverViewport(0, ImGui.getMainViewport(), ImGuiDockNodeFlags.PassthruCentralNode | ImGuiDockNodeFlags.NoDockingOverCentralNode);

        if (!s.windowsHidden) {
            if (s.showScene) SceneWindow.draw(s);
            if (s.showProperties) PropertiesWindow.draw(s);
            if (s.showTopView) TopViewWindow.draw(s);
            if (s.showShape) ShapeWindow.draw(s);
        }
        s.resetLayout = false;
        EditorChrome.draw(s);
        s.gizmo.frame(mc);
        TransformHud.draw(s);

        // viewport right click: a short RMB press opens the menu for what is under the pointer
        EditorCamera cam = EditorCamera.get();
        if (cam.consumeRightClick() && mc.gui.screen() == null) {
            boolean hit = Picking.onRightClick(mc, cam.rightClickX(), cam.rightClickY());
            ContextMenus.openForSelection(s, !hit);
        }
        if (s.addMenuRequest) {
            s.addMenuRequest = false;
            ContextMenus.openAdd(s);
        }
        ContextMenus.draw(s);

        if ((++frame & 1) == 0) Picking.updateHover(mc, UiHost.imguiWantsMouse() || s.gizmo.owningMouse());
        WorldOverlay.drawTags();
        WorldOverlay.drawOverlapLabels();
        Toasts.draw(s);
        s.popupOpen = ImGui.isPopupOpen("", imgui.flag.ImGuiPopupFlags.AnyPopupId | imgui.flag.ImGuiPopupFlags.AnyPopupLevel);
    }

    // ------------------------------------------------------------------ keyboard

    @Override
    public boolean wantsKey(int key, int scancode, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) return UiHost.imguiWantsKeyboard();       // chat: it gets the keys
        if (UiHost.imguiWantsTextInput()) return true;                    // a text field is active
        return !EditorMode.passesToVanilla(mc, key, scancode, mods);
    }

    @Override
    public void onKey(int key, int scancode, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null || mc.player == null) return;
        if (action != GLFW.GLFW_PRESS && !(action == GLFW.GLFW_REPEAT && key == GLFW.GLFW_KEY_BACKSPACE)) return;   // Backspace repeats in a typed transform
        if (UiHost.imguiWantsTextInput()) return;                         // typing in a field: no shortcut fires
        if (EditorCamera.get().isFlying()) return;                        // fly keys only
        if (EditorMode.passesToVanilla(mc, key, scancode, mods)) return;  // F-keys, chat, the toggle
        EditorSession s = null;
        try {
            s = EditorSession.get();
        } catch (Throwable ignored) {
            // no session: no shortcuts
        }
        if (s == null) {
            if (key == GLFW.GLFW_KEY_F || key == GLFW.GLFW_KEY_KP_DECIMAL) EditorCamera.get().frame(mc.player.getBoundingBox());
            return;
        }
        if (s.popupOpen) return;                                          // menus own arrows, Enter and Esc
        if (action == GLFW.GLFW_REPEAT && !s.gizmo.typedActive()) return;
        s.onKey(key, mods);
    }

    // ------------------------------------------------------------------ mouse

    @Override
    public boolean wantsMouse() {
        // with chat open the mouse belongs to chat, unless the pointer is over an editor window
        return Minecraft.getInstance().gui.screen() == null || UiHost.imguiWantsMouse();
    }

    @Override
    public void onMouseButton(int button, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null && action == GLFW.GLFW_PRESS) return;
        boolean overUi = UiHost.imguiWantsMouse();
        EditorSession s = null;
        try {
            s = EditorSession.get();
        } catch (Throwable ignored) {
            // no session: plain navigation only
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (action == GLFW.GLFW_PRESS) {
                if (!overUi && !EditorCamera.get().isNavigating()) {
                    if (s == null || !s.gizmo.onLeftPress(mc)) {           // the gizmo, the ring and box select come first
                        Picking.onLeftPress(mc);
                        if (s != null) s.gizmo.onEmptyPress(mc);
                    }
                }
            } else if (action == GLFW.GLFW_RELEASE) {
                if (s != null && s.gizmo.onLeftRelease(mc, mods)) Picking.cancelPress();
                else Picking.onLeftRelease(mc, mods);
            }
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && action == GLFW.GLFW_PRESS && s != null && s.gizmo.onRightPress()) {
            return;                                                         // a right click cancels the gesture and never starts a fly
        }
        EditorCamera.get().onMouseButton(mc, button, action, overUi);
    }

    @Override
    public void onMouseMove(double x, double y) {
        EditorCamera.get().onMouseMove(Minecraft.getInstance(), x, y);
    }

    @Override
    public void onScroll(double dx, double dy) {
        if (UiHost.imguiWantsMouse()) return;                              // over a window: it scrolls
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) return;
        EditorCamera.get().onScroll(mc, dy);
    }
}
