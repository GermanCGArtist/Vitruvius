package dev.vitruvius;

import dev.vitruvius.mixin.GuiListBaseAccessor;
import fi.dy.masa.litematica.gui.GuiSchematicPlacementsList;
import net.minecraft.client.Minecraft;

/** Rebuilds the rows of the placement list after a folder change (on the next tick, never in the middle of a click). */
public final class ListRefresh {
    private ListRefresh() {}

    public static void later() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (Compat.screen() instanceof GuiSchematicPlacementsList gui) {
                var list = ((GuiListBaseAccessor) gui).vitruvius$listWidget();
                if (list != null) list.refreshEntries();
            }
        });
    }
}
