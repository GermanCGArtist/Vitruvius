package dev.schemarray.mixin;

import dev.schemarray.ArraySync;
import dev.schemarray.Folders;
import dev.schemarray.WidgetFolderRow;
import dev.schemarray.api.ListEntry;
import dev.schemarray.api.SchematicArrayApi;
import dev.schemarray.shape.mc.ShapePreview;
import fi.dy.masa.litematica.gui.GuiSchematicPlacementsList;
import fi.dy.masa.litematica.gui.widgets.WidgetListSchematicPlacements;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Litematica's placement list shows only the originals (their array copies are managed by the original), arranged
 * in folders: the list gets folder rows between the placements, which are indented by their depth. Entries that
 * other mods registered (SchematicArrayApi) are mixed in with the placements. While the search
 * bar has text the list is flat again. The list never sorts itself (shouldSortList stays false), so our order holds.
 */
@Mixin(value = WidgetListSchematicPlacements.class, remap = false)
abstract class WidgetListSchematicPlacementsMixin {
    private static final int INDENT = 12;

    /** malilib draws the rows of its selectedEntries as selected when this is on (DragDrop keeps that set). */
    @Inject(method = "<init>(IIIILfi/dy/masa/litematica/gui/GuiSchematicPlacementsList;)V", at = @At("TAIL"), remap = false)
    private void schemarray$multiSelection(int x, int y, int width, int height, GuiSchematicPlacementsList parent, CallbackInfo ci) {
        ((WidgetListBaseAccessor) (Object) this).schemarray$setMultiSelection(true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "getAllEntries", at = @At("RETURN"), cancellable = true, remap = false)
    private void schemarray$folders(CallbackInfoReturnable<Collection<SchematicPlacement>> cir) {
        Collection<SchematicPlacement> all = cir.getReturnValue();
        if (all == null) return;
        List<Object> out = new ArrayList<>(all.size());
        for (SchematicPlacement p : all) if (!ArraySync.isCopy(p) && !ShapePreview.isPreview(p)) out.add(p);
        boolean removed = out.size() != all.size();
        var entries = SchematicArrayApi.allEntries();
        out.addAll(entries);

        var search = ((WidgetListBase<?, ?>) (Object) this).getSearchBarWidget();
        if (search != null && search.hasFilter()) {
            Folders.rows(List.of());   // forget the indentation, the list is flat
            if (removed || !entries.isEmpty()) {
                out.sort(java.util.Comparator.comparing(o -> Folders.nameOf(o).toLowerCase()));
                cir.setReturnValue((Collection) out);
            }
            return;
        }
        cir.setReturnValue((Collection) Folders.rows(out));
    }

    /** A folder row's own search text is its name. */
    @SuppressWarnings("rawtypes")
    @Inject(method = "getEntryStringsForFilter(Ljava/lang/Object;)Ljava/util/List;", at = @At("HEAD"), cancellable = true, remap = false)
    private void schemarray$filterStrings(Object entry, CallbackInfoReturnable<List> cir) {
        if (entry instanceof Folders.Node n) cir.setReturnValue(List.of(n.name.toLowerCase()));
        else if (entry instanceof ListEntry e) cir.setReturnValue(e.searchStrings());
    }

    /** Folder rows get our widget; placements inside a folder get theirs moved right and narrowed by their depth. */
    @SuppressWarnings("rawtypes")
    @Inject(method = "createListEntryWidget(IIIZLjava/lang/Object;)Lfi/dy/masa/malilib/gui/widgets/WidgetListEntryBase;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void schemarray$createRow(int x, int y, int listIndex, boolean isOdd, Object entry, CallbackInfoReturnable<WidgetListEntryBase> cir) {
        WidgetListSchematicPlacements self = (WidgetListSchematicPlacements) (Object) this;
        WidgetListBaseAccessor acc = (WidgetListBaseAccessor) self;
        if (entry instanceof Folders.Node n) {
            int indent = INDENT * n.depth;
            cir.setReturnValue(new WidgetFolderRow(x + indent, y, acc.schemarray$entryWidth() - indent, acc.schemarray$entryHeight(), isOdd, n, listIndex));
        } else if (entry instanceof ListEntry e) {
            int indent = INDENT * Folders.depthOf(e);
            cir.setReturnValue(e.createWidget(x + indent, y, acc.schemarray$entryWidth() - indent, acc.schemarray$entryHeight(), isOdd, listIndex));
        } else if (entry instanceof SchematicPlacement p) {
            int indent = INDENT * Folders.depthOf(p);
            if (indent > 0) {
                cir.setReturnValue(new WidgetSchematicPlacement(x + indent, y, acc.schemarray$entryWidth() - indent, acc.schemarray$entryHeight(), isOdd, p, listIndex, self));
            }
        }
    }
}
