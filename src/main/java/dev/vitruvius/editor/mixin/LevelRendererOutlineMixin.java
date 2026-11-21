package dev.vitruvius.editor.mixin;

import dev.vitruvius.editor.EditorMode;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No block outline for the block the player is looking at while editor mode is on. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererOutlineMixin {
    @Inject(method = "submitBlockOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void vitruvius$hideOutline(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, LevelRenderState state, CallbackInfo ci) {
        if (EditorMode.isActive()) ci.cancel();
    }
}
