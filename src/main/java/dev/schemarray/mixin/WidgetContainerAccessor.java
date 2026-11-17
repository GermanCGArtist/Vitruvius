package dev.schemarray.mixin;

import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(value = WidgetContainer.class, remap = false)
public interface WidgetContainerAccessor {
    @Invoker(value = "addButton", remap = false)
    ButtonBase schemarray$addButton(ButtonBase button, IButtonActionListener listener);

    @Accessor(value = "subWidgets", remap = false)
    List<WidgetBase> schemarray$subWidgets();
}
