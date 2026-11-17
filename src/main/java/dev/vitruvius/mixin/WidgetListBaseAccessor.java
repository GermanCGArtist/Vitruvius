package dev.vitruvius.mixin;

import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = WidgetListBase.class, remap = false)
public interface WidgetListBaseAccessor {
    @Accessor(value = "browserEntryWidth", remap = false)
    int vitruvius$entryWidth();

    @Accessor(value = "browserEntryHeight", remap = false)
    int vitruvius$entryHeight();

    @Accessor(value = "posX", remap = false)
    int vitruvius$posX();

    @Accessor(value = "posY", remap = false)
    int vitruvius$posY();

    @Accessor(value = "browserWidth", remap = false)
    int vitruvius$browserWidth();

    @Accessor(value = "browserHeight", remap = false)
    int vitruvius$browserHeight();

    @Accessor(value = "browserEntriesStartY", remap = false)
    int vitruvius$entriesStartY();

    @Accessor(value = "browserEntriesOffsetY", remap = false)
    int vitruvius$entriesOffsetY();

    @Accessor(value = "listWidgets", remap = false)
    List<?> vitruvius$listWidgets();

    @Accessor(value = "allowMultiSelection", remap = false)
    void vitruvius$setMultiSelection(boolean value);
}
