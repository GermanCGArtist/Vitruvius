package dev.schemarray.mixin;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = GuiListBase.class, remap = false)
public interface GuiListBaseAccessor {
    @Invoker(value = "getListWidget", remap = false)
    WidgetListBase<?, ?> schemarray$listWidget();
}
