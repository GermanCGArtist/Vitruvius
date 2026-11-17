package dev.vitruvius.editor.mixin;

import dev.vitruvius.editor.EditorMode;
import dev.vitruvius.editor.cam.EditorCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.1.1 has no {@code Camera.setup}: {@code Camera.update(DeltaTracker)} calls the private
 * {@code alignWithEntity(float)}, which positions and rotates the camera from the entity and applies third person.
 * While editor mode is on we replace that step with the editor camera and mark the camera detached, which makes the
 * level renderer draw the player's own model and skips the hand, exactly like third person.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private boolean detached;

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "alignWithEntity(F)V", at = @At("HEAD"), cancellable = true)
    private void vitruvius$editorCamera(float partialTicks, CallbackInfo ci) {
        if (!EditorMode.isActive()) return;
        EditorCamera cam = EditorCamera.get();
        cam.update(Minecraft.getInstance());
        Vec3 pos = cam.position();
        this.setRotation(cam.yaw(), cam.pitch());
        this.setPosition(pos.x, pos.y, pos.z);
        this.detached = true;
        ci.cancel();
    }
}
