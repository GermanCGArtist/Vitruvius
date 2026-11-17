package dev.vitruvius.mixin;

import dev.vitruvius.shape.gui.ShapeGeneratorScreen;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Adds the "Shape Generator" button to Litematica's main menu, at the end of the right-hand button column. Litematica
 * lays the menu out in two columns with fixed x positions, so the column is found from the buttons it created: the
 * rightmost x is the right column, and the new button goes one row (22 px) below its lowest button, same x and width.
 */
@Mixin(value = GuiMainMenu.class, remap = false)
abstract class GuiMainMenuMixin {
    private static final int ROW = 22;
    private static final int HEIGHT = 20;

    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void vitruvius$shapeButton(CallbackInfo ci) {
        GuiBase self = (GuiBase) (Object) this;
        List<ButtonBase> buttons = ((GuiBaseAccessor) (Object) this).vitruvius$buttons();
        if (buttons.isEmpty()) return;
        int x = Integer.MIN_VALUE;
        for (ButtonBase b : buttons) x = Math.max(x, b.getX());
        int y = 0, width = 0;
        for (ButtonBase b : buttons) {
            if (b.getX() == x && b.getY() + ROW > y) {
                y = b.getY() + ROW;
                width = b.getWidth();
            }
        }
        Screen screen = self;
        self.addButton(new ButtonGeneric(x, y, width, HEIGHT, "Shape Generator"),
                (button, mouseButton) -> GuiBase.openGui(new ShapeGeneratorScreen(screen)));
    }
}
