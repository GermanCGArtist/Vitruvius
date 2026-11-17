package dev.vitruvius.editor.mixin;

import dev.vitruvius.editor.EditorMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The editor camera is a detached camera, which makes the level renderer draw the player's own model (and its blob
 * shadow). The renderer asks {@code shouldRender} for every entity before it extracts its render state, so saying no
 * for the local player removes the model and the shadow while editor mode is on.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
            at = @At("HEAD"), cancellable = true)
    private void vitruvius$hideLocalPlayer(Entity entity, Frustum frustum, double camX, double camY, double camZ,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (EditorMode.isActive() && entity instanceof LocalPlayer) cir.setReturnValue(false);
    }
}
