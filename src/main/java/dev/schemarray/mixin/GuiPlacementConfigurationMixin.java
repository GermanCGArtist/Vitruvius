package dev.schemarray.mixin;

import dev.schemarray.ArrayScreen;
import dev.schemarray.ArraySync;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Litematica's placement configuration (M, Configure placement, Configure): one "Array..." button right under the
 * Rotate button that opens the array screen. Mirror and the buttons below it move down to make room.
 */
@Mixin(value = GuiPlacementConfiguration.class, remap = false)
abstract class GuiPlacementConfigurationMixin {
    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void schemarray$addArrayButton(CallbackInfo ci) {
        GuiBase gui = (GuiBase) (Object) this;
        SchematicPlacement shown = ((GuiPlacementConfiguration) (Object) this).placement;
        if (shown == null) return;
        SchematicPlacement original = ArraySync.original(shown);

        // the right-hand column: Litematica puts it 130 from the right edge, 120 wide
        int colX = gui.getScreenWidth() - 130;
        List<ButtonBase> buttons = ((GuiBaseAccessor) gui).schemarray$buttons();
        List<ButtonBase> col = new ArrayList<>();
        for (ButtonBase b : buttons) if (b.getX() == colX && b.getWidth() == 120) col.add(b);
        col.sort(Comparator.comparingInt(ButtonBase::getY));
        // ... Move to player, Rotate, Mirror, Reset sub-regions: Rotate is the third from the bottom
        if (col.size() < 3) return;
        int rotateY = col.get(col.size() - 3).getY();
        int spacing = col.get(col.size() - 2).getY() - rotateY;
        if (spacing <= 0) spacing = 22;
        for (ButtonBase b : buttons) {
            if (b.getX() >= colX && b.getX() < colX + 120 && b.getY() > rotateY) b.setY(b.getY() + spacing);
        }

        int n = ArraySync.copyCount(original);
        boolean copy = ArraySync.isCopy(shown);
        var font = Minecraft.getInstance().font;
        String mark = copy ? "Array*" : "Array";
        String label = n > 0 ? mark + " (" + n + " copies)..." : mark + "...";
        if (font.width(label) > 112) label = mark + " (" + n + ")...";
        String hover = "Repeats the placement like Blender's Array modifier: a stack of modifiers with count, relative and constant offset.";
        ButtonGeneric button = copy
                ? new ButtonGeneric(colX, rotateY + spacing, 120, 20, label, hover, "This is a copy: the array of its original is edited.")
                : new ButtonGeneric(colX, rotateY + spacing, 120, 20, label, hover);
        Screen self = (Screen) (Object) this;
        gui.addButton(button, (b, mouse) -> GuiBase.openGui(new ArrayScreen(self, original)));
    }
}
