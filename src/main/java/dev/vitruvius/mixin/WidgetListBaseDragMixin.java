package dev.vitruvius.mixin;

import dev.vitruvius.ColorMenu;
import dev.vitruvius.DragDrop;
import fi.dy.masa.litematica.gui.widgets.WidgetListSchematicPlacements;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Selection and drag and drop of the placement list (see DragDrop). The list's methods are inherited from
 * WidgetListBase (WidgetListSchematicPlacements overrides none of them), so this hooks WidgetListBase and does
 * nothing for any other list.
 */
@Mixin(value = WidgetListBase.class, remap = false)
abstract class WidgetListBaseDragMixin {
    private boolean vitruvius$ours() {
        return (Object) this instanceof WidgetListSchematicPlacements;
    }

    private WidgetListBase<?, ?> vitruvius$self() {
        return (WidgetListBase<?, ?>) (Object) this;
    }

    /** A left press on a row (not on one of its buttons) does the selection; the click is finished on release. */
    @Inject(method = "onMouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void vitruvius$press(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (!vitruvius$ours()) return;
        if (ColorMenu.isOpen()) {   // the screen hook normally got it first
            ColorMenu.onClick(event);
            cir.setReturnValue(true);
        } else if (event.input() == 1 ? ColorMenu.tryOpen(vitruvius$self(), event) : DragDrop.onPress(vitruvius$self(), event)) {
            cir.setReturnValue(true);
        }
    }

    /** The end of a drag is the drop; a release without a drag is the click (and still goes on to the buttons). */
    @Inject(method = "onMouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void vitruvius$release(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (vitruvius$ours() && DragDrop.onRelease(vitruvius$self(), event)) {
            vitruvius$self().getScrollbar().setIsDragging(false);
            cir.setReturnValue(true);
        }
    }

    /** Escape cancels a drag (and does not close the screen). */
    @Inject(method = "onKeyTyped(Lnet/minecraft/client/input/KeyEvent;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void vitruvius$escape(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.key() == 256 && vitruvius$ours() && ColorMenu.isOpen()) {
            ColorMenu.close();
            cir.setReturnValue(true);
        } else if (event.key() == 256 && vitruvius$ours() && DragDrop.isDragging(vitruvius$self())) {
            DragDrop.cancel();
            cir.setReturnValue(true);
        }
    }

    /** Before drawing: poll the mouse button (drag start / drop / click), and mirror our selection into malilib's selectedEntries, which it highlights. */
    @Inject(method = "drawContents(Lfi/dy/masa/malilib/render/GuiContext;IIF)V", at = @At("HEAD"), remap = false)
    private void vitruvius$syncSelection(GuiContext ctx, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!vitruvius$ours()) return;
        DragDrop.poll(vitruvius$self(), mouseX, mouseY);
        DragDrop.sync(vitruvius$self());
    }

    /** After drawing: the drop target and the label at the cursor go on top of the rows. */
    @Inject(method = "drawContents(Lfi/dy/masa/malilib/render/GuiContext;IIF)V", at = @At("TAIL"), remap = false)
    private void vitruvius$drawDrag(GuiContext ctx, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!vitruvius$ours()) return;
        DragDrop.render(vitruvius$self(), ctx, mouseX, mouseY);
        ColorMenu.render(vitruvius$self(), ctx, mouseX, mouseY);   // last: above everything the list drew
    }

    /** A scroll closes the color menu without changing anything. */
    @Inject(method = "onMouseScrolled(DDDD)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void vitruvius$scroll(double x, double y, double h, double v, CallbackInfoReturnable<Boolean> cir) {
        if (vitruvius$ours() && ColorMenu.isOpen()) {
            ColorMenu.close();
            cir.setReturnValue(true);
        }
    }

    /** Rows that are no longer in the list drop out of the selection. */
    @Inject(method = "refreshBrowserEntries()V", at = @At("TAIL"), remap = false)
    private void vitruvius$prune(CallbackInfo ci) {
        if (vitruvius$ours()) DragDrop.prune(vitruvius$self());
    }
}
