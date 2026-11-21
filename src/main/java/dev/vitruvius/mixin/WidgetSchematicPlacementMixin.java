package dev.vitruvius.mixin;

import dev.vitruvius.Compat;
import dev.vitruvius.DragDrop;
import dev.vitruvius.Folders;
import dev.vitruvius.MoveScreen;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** A Move button on every placement row, left of Configure: opens the screen that picks the folder (for the whole selection if this row is selected). */
@Mixin(value = WidgetSchematicPlacement.class, remap = false)
abstract class WidgetSchematicPlacementMixin {
    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void vitruvius$addMove(CallbackInfo ci) {
        WidgetSchematicPlacement w = (WidgetSchematicPlacement) (Object) this;
        if (Folders.isCopy(w.placement)) return;
        ButtonGeneric move = new ButtonGeneric(w.buttonsStartX, w.getY() + 1, -1, true, "Move");
        ((WidgetContainerAccessor) w).vitruvius$addButton(move, (b, mouse) -> {
            // a row that is part of the selection moves the whole selection
            List<Object> moving = new ArrayList<>();
            if (DragDrop.selection().contains(w.placement)) {
                for (Object p : DragDrop.selection()) if (!(p instanceof SchematicPlacement sp && Folders.isCopy(sp))) moving.add(p);
            }
            if (moving.isEmpty()) moving.add(w.placement);
            GuiBase.openGui(new MoveScreen(Compat.screen(), moving));
        });
        w.buttonsStartX = move.getX() - 1;

    }

    /** The row's color: a 3 px bar at its left edge (own color, or the nearest colored folder's) and a light tint for an own color. */
    @Inject(method = "render(Lfi/dy/masa/malilib/render/GuiContext;IIZ)V",
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/schematic/placement/SchematicPlacement;getName()Ljava/lang/String;", ordinal = 0),
            remap = false)
    private void vitruvius$color(GuiContext ctx, int mouseX, int mouseY, boolean selected, CallbackInfo ci) {
        WidgetSchematicPlacement w = (WidgetSchematicPlacement) (Object) this;
        int c = Folders.effectiveColor(w.placement);
        if (c < 0) return;
        int rgb = Folders.COLORS[c];
        if (Folders.ownColor(w.placement) >= 0) RenderUtils.drawRect(ctx, w.getX(), w.getY(), w.getWidth(), w.getHeight(), 0x28000000 | rgb);
        RenderUtils.drawRect(ctx, w.getX(), w.getY(), 3, w.getHeight(), 0xFF000000 | rgb);
    }
}
