package dev.schemarray.mixin;

import dev.schemarray.Folders;
import fi.dy.masa.litematica.gui.GuiSchematicPlacementsList;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The New folder button next to the first button at the bottom of the placements screen; folder rows are never selected. */
@Mixin(value = GuiSchematicPlacementsList.class, remap = false)
abstract class GuiSchematicPlacementsListMixin {
    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void schemarray$addNewFolder(CallbackInfo ci) {
        GuiBase gui = (GuiBase) (Object) this;
        int y = gui.getScreenHeight() - 26;
        // right of the first button (Litematica puts it at x 12), whatever its width
        int x = 12;
        for (ButtonBase b : ((GuiBaseAccessor) gui).schemarray$buttons()) {
            if (b.getX() == 12 && b.getY() == y) x = b.getX() + b.getWidth();
        }
        var font = Minecraft.getInstance().font;
        gui.addButton(new ButtonGeneric(x + 4, y, font.width("New folder") + 20, 20, "New folder"), (b, mouse) ->
                GuiBase.openGui(new GuiTextInput(64, "New folder", "", gui, s -> {
                    String name = s.trim();
                    if (!name.isEmpty()) Folders.create(name, null);
                })));
    }

    /** Folder rows are not placements; they are never clicked into selection, this just makes sure. */
    @Inject(method = "onSelectionChange(Ljava/lang/Object;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void schemarray$ignoreFolders(Object entry, CallbackInfo ci) {
        if (!(entry instanceof SchematicPlacement)) ci.cancel();
    }
}
