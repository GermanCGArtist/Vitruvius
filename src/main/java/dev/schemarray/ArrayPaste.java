package dev.schemarray;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;

import java.util.ArrayList;
import java.util.List;

/** Which placements a Litematica paste of the given placement should cover: an array original pastes its enabled copies too. */
public final class ArrayPaste {
    private ArrayPaste() {}

    /** True while copies are being pasted one by one (Servux path), so they do not trigger an array paste themselves. */
    public static boolean reentrant;

    /** The original plus its existing, enabled copies; just the placement itself for copies and plain placements. */
    public static List<SchematicPlacement> placementsFor(SchematicPlacement p) {
        List<SchematicPlacement> out = new ArrayList<>();
        out.add(p);
        if (reentrant || ArraySync.isCopy(p)) return out;
        List<String> ids = ArraySync.copyIds(p);
        if (ids.isEmpty()) return out;
        for (SchematicPlacement o : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (o != p && o.isEnabled() && ids.contains(o.getHashId().toString())) out.add(o);
        }
        return out;
    }

    /** Copies only (without the original); empty when nothing array-related applies. */
    public static List<SchematicPlacement> copiesOf(SchematicPlacement p) {
        List<SchematicPlacement> all = placementsFor(p);
        return all.size() <= 1 ? List.of() : all.subList(1, all.size());
    }

    public static void announce(int count) {
        if (count > 1) InfoUtils.showGuiOrActionBarMessage(MessageType.INFO, "Pasting array: " + count + " placements");
    }
}
