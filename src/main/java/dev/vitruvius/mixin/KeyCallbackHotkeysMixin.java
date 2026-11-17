package dev.vitruvius.mixin;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.tool.ToolMode;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Litematica's Grid Paste tool mode has no handler. The execute-operation hotkey runs, for PASTE_SCHEMATIC, when
 * (tool item in hand and rendering and tool item enabled) or EXECUTE_REQUIRE_TOOL is off, and no schematic project is
 * open; then it calls pasteCurrentPlacementToWorld. We repeat exactly those checks for GRID_PASTE. The class is a
 * package-private record, hence the string target; a rename fails at launch (require = 1).
 */
@Mixin(targets = "fi.dy.masa.litematica.event.KeyCallbacks$KeyCallbackHotkeys", remap = false)
abstract class KeyCallbackHotkeysMixin {
    @Shadow(remap = false) @Final private Minecraft mc;

    @Inject(method = "onKeyAction(Lfi/dy/masa/malilib/hotkeys/KeyAction;Lfi/dy/masa/malilib/hotkeys/IKeybind;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void vitruvius$gridPaste(KeyAction action, IKeybind key, CallbackInfoReturnable<Boolean> cir) {
        if (mc.player == null || mc.level == null) return;
        if (key != Hotkeys.EXECUTE_OPERATION.getKeybind() || DataManager.getToolMode() != ToolMode.GRID_PASTE) return;
        boolean toolEnabled = Configs.Visuals.ENABLE_RENDERING.getBooleanValue() && Configs.Generic.TOOL_ITEM_ENABLED.getBooleanValue();
        boolean hasTool = EntityUtils.hasToolItem(mc.player);
        if (!((hasTool && toolEnabled) || !Configs.Generic.EXECUTE_REQUIRE_TOOL.getBooleanValue())) return;
        // with a schematic project open Litematica pastes the project version; leave that to it
        if (DataManager.getSchematicProjectsManager().hasProjectOpen()) return;
        DataManager.getSchematicPlacementManager().pasteCurrentPlacementToWorld(mc);
        cir.setReturnValue(true);
    }
}
