package dev.schemarray.mixin;

import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = WidgetListBase.class, remap = false)
public interface WidgetListBaseAccessor {
    @Accessor(value = "browserEntryWidth", remap = false)
    int schemarray$entryWidth();

    @Accessor(value = "browserEntryHeight", remap = false)
    int schemarray$entryHeight();

    @Accessor(value = "posX", remap = false)
    int schemarray$posX();

    @Accessor(value = "posY", remap = false)
    int schemarray$posY();

    @Accessor(value = "browserWidth", remap = false)
    int schemarray$browserWidth();

    @Accessor(value = "browserHeight", remap = false)
    int schemarray$browserHeight();

    @Accessor(value = "browserEntriesStartY", remap = false)
    int schemarray$entriesStartY();

    @Accessor(value = "browserEntriesOffsetY", remap = false)
    int schemarray$entriesOffsetY();

    @Accessor(value = "listWidgets", remap = false)
    List<?> schemarray$listWidgets();

    @Accessor(value = "allowMultiSelection", remap = false)
    void schemarray$setMultiSelection(boolean value);
}
