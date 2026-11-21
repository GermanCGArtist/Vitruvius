package dev.cg.uihost.mixin;

import dev.cg.uihost.HostCore;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ImGui draws after the GUI, into the same render target, with either graphics backend. */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void cguihost$renderImGui(CallbackInfo ci) {
        HostCore.renderFrame();
    }
}
