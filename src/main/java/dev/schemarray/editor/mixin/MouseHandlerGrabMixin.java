package dev.schemarray.editor.mixin;

import dev.schemarray.editor.EditorMode;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Closing chat (or regaining window focus) calls {@code grabMouse()}; in editor mode the mouse stays free. Leaving
 * the editor clears the flag first, so its own grab goes through.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerGrabMixin {
    @Inject(method = "grabMouse()V", at = @At("HEAD"), cancellable = true)
    private void schemarray$keepMouseFree(CallbackInfo ci) {
        if (EditorMode.isActive()) ci.cancel();
    }
}
