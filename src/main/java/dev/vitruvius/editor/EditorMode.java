package dev.vitruvius.editor;

import com.mojang.blaze3d.platform.InputConstants;
import dev.liskov.ui.host.LiskovUiHost;
import dev.vitruvius.editor.cam.EditorCamera;
import dev.vitruvius.editor.cam.SpectatorFollow;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Editor mode (F6): the mouse is released, vanilla controls are blocked and the camera is the editor camera
 * ({@link EditorCamera}) instead of the player's eyes.
 * <ul>
 *   <li>Enter: release the mouse, start the camera at the player's eye and rotation, switch to a detached third
 *       person camera so the player's own model is drawn (and no hand).</li>
 *   <li>Exit: restore the camera type and grab the mouse again.</li>
 *   <li>Auto-exit when the connection ends, the level (dimension) changes or a screen other than chat opens.</li>
 * </ul>
 * Only F6 leaves; Esc does nothing here (the input layer keeps it from vanilla). All state is on the client thread.
 */
public final class EditorMode {
    private static final Logger LOG = LoggerFactory.getLogger("vitruvius-editor");

    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("vitruvius", "main"));
    /** F6 by default, rebindable under "Vitruvius". */
    public static final KeyMapping TOGGLE = new KeyMapping("key.vitruvius.toggle_editor",
            InputConstants.KEY_F6, CATEGORY);

    private static boolean active;
    private static ClientLevel level;
    private static CameraType savedCameraType = CameraType.FIRST_PERSON;

    private EditorMode() {}

    public static boolean isActive() {
        return active;
    }

    /** Registers the key, the tick hook, the auto-exit hook and the UI layer. Call once from the client entrypoint. */
    public static void init() {
        KeyMappingHelper.registerKeyMapping(TOGGLE);
        ClientTickEvents.END_CLIENT_TICK.register(EditorMode::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (active) exit(client, false);
        });
        dev.liskov.ui.host.LiskovUiHost.register(new EditorInputLayer());
    }

    // ------------------------------------------------------------------ enter / exit

    public static void toggle(Minecraft mc) {
        if (active) exit(mc, true);
        else enter(mc);
    }

    public static void enter(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (active || player == null || mc.level == null || mc.gui.screen() != null) return;
        active = true;
        level = mc.level;
        savedCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        EditorCamera.get().reset(player.getEyePosition(), player.getYRot(), player.getXRot());
        releaseGameplayKeys(mc);
        mc.mouseHandler.releaseMouse();
        LOG.debug("editor mode on");
    }

    /**
     * Leaves editor mode.
     *
     * @param regrab grab the mouse again; false when leaving because a screen opened or the world went away
     */
    public static void exit(Minecraft mc, boolean regrab) {
        if (!active) return;
        active = false;                       // first: the layer stops blocking grabMouse() (Liskov) from here on
        level = null;
        EditorCamera.get().cancelGesture();
        try {
            EditorSession s = EditorSession.get();
            if (s != null) {
                s.gizmo.cancel();                            // a running drag or typed transform ends like Esc
                s.showShape = false;
            }
            dev.vitruvius.editor.view.ShapeWindow.close();   // no ghost is left behind
        } catch (Throwable ignored) {
            // no session (Litematica missing)
        }
        LiskovUiHost.captureCursor(false);
        mc.options.setCameraType(savedCameraType);
        releaseGameplayKeys(mc);
        if (regrab && mc.gui.screen() == null && mc.player != null) mc.mouseHandler.grabMouse();
        LOG.debug("editor mode off");
    }

    // ------------------------------------------------------------------ tick

    private static void tick(Minecraft mc) {
        while (TOGGLE.consumeClick()) toggle(mc);
        if (!active) return;

        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.level != level) {
            exit(mc, false);                  // disconnect, respawn into a new level, dimension change
            return;
        }
        if (mc.gui.screen() != null) {
            if (!(mc.gui.screen() instanceof ChatScreen)) {
                exit(mc, false);              // pause menu, inventory, ... the screen owns the mouse now
                return;
            }
            EditorCamera.get().cancelGesture();
        }

        // F5 (or anything else) must not bring the first-person camera back
        if (mc.options.getCameraType().isFirstPerson()) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        // keys held when a screen closed, KeyMapping.setAll() and friends can re-arm vanilla controls
        releaseGameplayKeys(mc);
        if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();

        SpectatorFollow.tick(mc, player, EditorCamera.get());
    }

    // ------------------------------------------------------------------ input rules

    /**
     * True for the keys vanilla may still see in editor mode: F1 to F12, the toggle key, chat and command.
     * {@code scancode} is the SDL scancode (what Minecraft 26.3's key mappings and {@link InputConstants} use).
     */
    public static boolean passesToVanilla(Minecraft mc, int key, int scancode, int mods) {
        if (scancode >= InputConstants.KEY_F1 && scancode <= InputConstants.KEY_F12) return true;
        var event = new net.minecraft.client.input.KeyEvent(scancode, 0, 0);
        if (TOGGLE.matches(event)) return true;
        // with the editor's session the chat keys open the chat window instead (see chatKeyKind)
        return !chatInWindow() && (mc.options.keyChat.matches(event) || mc.options.keyCommand.matches(event));
    }

    private static boolean chatInWindow() {
        try {
            return EditorSession.get() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 1 for the chat key, 2 for the command key, else 0; only when the chat goes to the editor's window. */
    public static int chatKeyKind(Minecraft mc, int scancode) {
        if (!chatInWindow()) return 0;
        var event = new net.minecraft.client.input.KeyEvent(scancode, 0, 0);
        if (mc.options.keyChat.matches(event)) return 1;
        return mc.options.keyCommand.matches(event) ? 2 : 0;
    }

    /** Lets go of every vanilla key mapping that is blocked in editor mode and drops its pending clicks. */
    private static void releaseGameplayKeys(Minecraft mc) {
        for (KeyMapping m : mc.options.keyMappings) {
            if (m == TOGGLE || m == mc.options.keyChat || m == mc.options.keyCommand || isFunctionKey(m)) continue;
            m.setDown(false);
            while (m.consumeClick()) { /* drain */ }
        }
    }

    private static boolean isFunctionKey(KeyMapping m) {
        String s = m.saveString();                       // "key.keyboard.f6"
        if (!s.startsWith("key.keyboard.f") || s.length() > "key.keyboard.f12".length()) return false;
        try {
            int n = Integer.parseInt(s.substring("key.keyboard.f".length()));
            return n >= 1 && n <= 12;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
