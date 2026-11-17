package dev.schemarray.mixin;

import dev.schemarray.ColorMenu;
import dev.schemarray.DragDrop;
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
    private boolean schemarray$ours() {
        return (Object) this instanceof WidgetListSchematicPlacements;
    }

    private WidgetListBase<?, ?> schemarray$self() {
        return (WidgetListBase<?, ?>) (Object) this;
    }

    /** A left press on a row (not on one of its buttons) does the selection; the click is finished on release. */
    @Inject(method = "onMouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void schemarray$press(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (!schemarray$ours()) return;
        if (ColorMenu.isOpen()) {   // the screen hook normally got it first
            ColorMenu.onClick(event);
            cir.setReturnValue(true);
        } else if (event.input() == 1 ? ColorMenu.tryOpen(schemarray$self(), event) : DragDrop.onPress(schemarray$self(), event)) {
            cir.setReturnValue(true);
        }
    }

    /** The end of a drag is the drop; a release without a drag is the click (and still goes on to the buttons). */
    @Inject(method = "onMouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void schemarray$release(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (schemarray$ours() && DragDrop.onRelease(schemarray$self(), event)) {
            schemarray$self().getScrollbar().setIsDragging(false);
            cir.setReturnValue(true);
        }
    }

    /** Escape cancels a drag (and does not close the screen). */
    @Inject(method = "onKeyTyped(Lnet/minecraft/client/input/KeyEvent;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void schemarray$escape(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.key() == 256 && schemarray$ours() && ColorMenu.isOpen()) {
            ColorMenu.close();
            cir.setReturnValue(true);
        } else if (event.key() == 256 && schemarray$ours() && DragDrop.isDragging(schemarray$self())) {
            DragDrop.cancel();
            cir.setReturnValue(true);
        }
    }

    /** Before drawing: poll the mouse button (drag start / drop / click), and mirror our selection into malilib's selectedEntries, which it highlights. */
    @Inject(method = "drawContents(Lfi/dy/masa/malilib/render/GuiContext;IIF)V", at = @At("HEAD"), remap = false)
    private void schemarray$syncSelection(GuiContext ctx, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!schemarray$ours()) return;
        DragDrop.poll(schemarray$self(), mouseX, mouseY);
        DragDrop.sync(schemarray$self());
    }

    /** After drawing: the drop target and the label at the cursor go on top of the rows. */
    @Inject(method = "drawContents(Lfi/dy/masa/malilib/render/GuiContext;IIF)V", at = @At("TAIL"), remap = false)
    private void schemarray$drawDrag(GuiContext ctx, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!schemarray$ours()) return;
        DragDrop.render(schemarray$self(), ctx, mouseX, mouseY);
        ColorMenu.render(schemarray$self(), ctx, mouseX, mouseY);   // last: above everything the list drew
    }

    /** A scroll closes the color menu without changing anything. */
    @Inject(method = "onMouseScrolled(DDDD)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void schemarray$scroll(double x, double y, double h, double v, CallbackInfoReturnable<Boolean> cir) {
        if (schemarray$ours() && ColorMenu.isOpen()) {
            ColorMenu.close();
            cir.setReturnValue(true);
        }
    }

    /** Rows that are no longer in the list drop out of the selection. */
    @Inject(method = "refreshBrowserEntries()V", at = @At("TAIL"), remap = false)
    private void schemarray$prune(CallbackInfo ci) {
        if (schemarray$ours()) DragDrop.prune(schemarray$self());
    }
}
