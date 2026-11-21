package dev.cg.uihost.mixin;

import dev.cg.uihost.HostCore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The OpenGL backend's present swaps the window's buffers: ImGui draws into the back buffer just before. */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlSurface")
public abstract class GlSurfaceMixin {
    @Inject(method = "present", at = @At("HEAD"))
    private void cguihost$renderImGui(CallbackInfo ci) {
        HostCore.renderFrame();
    }
}
