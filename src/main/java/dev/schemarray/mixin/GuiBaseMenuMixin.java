package dev.schemarray.mixin;

import dev.schemarray.ColorMenu;
import fi.dy.masa.litematica.gui.GuiSchematicPlacementsList;
import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GuiListBase.onMouseClicked gives the screen's own buttons the click before the list sees it, so while the color menu
 * is open the click is taken here, at the first stop: nothing underneath gets it.
 */
@Mixin(value = GuiBase.class, remap = false)
abstract class GuiBaseMenuMixin {
    @Inject(method = "onMouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void schemarray$menuClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (ColorMenu.isOpen() && (Object) this instanceof GuiSchematicPlacementsList) {
            ColorMenu.onClick(event);
            cir.setReturnValue(true);
        }
    }
}
