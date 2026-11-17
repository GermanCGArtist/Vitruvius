package dev.schemarray.shape.mc;

import dev.schemarray.Compat;
import dev.schemarray.shape.ShapeSpec;
import dev.schemarray.shape.VoxelResult;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import net.minecraft.core.BlockPos;

import java.util.Arrays;

/**
 * The in-world ghost: at most one temporary Litematica placement of the current VoxelResult. Render thread only.
 * It is marked "do not save", so Litematica never writes it to its placement file, and the placement code of this
 * addon skips it (isPreview).
 */
public final class ShapePreview {
    private ShapePreview() {}

    private static SchematicPlacement placement;
    private static VoxelResult shown;

    /**
     * Shows result at origin (the placement's min corner; see originFor). Replaces the previous ghost: identical
     * voxels at the same spot change nothing, a result of the same size is written into the existing schematic
     * (no placement removed and added again), anything else makes a new placement. A null result just removes it.
     */
    public static void show(VoxelResult result, BlockPos origin) {
        if (result == null) {
            clear();
            return;
        }
        if (placement != null && shown != null && sameSize(shown, result)) {
            boolean same = Arrays.equals(shown.cells(), result.cells()) && shown.palette().equals(result.palette());
            try {
                if (!same) {
                    ShapeSchematics.refill(placement.getSchematic(), result);
                    DataManager.getSchematicPlacementManager().markAllPlacementsOfSchematicForRebuild(placement.getSchematic());
                }
                if (!origin.equals(placement.getOrigin())) placement.setOrigin(origin, null);
                shown = result;
                return;
            } catch (RuntimeException e) {
                // an invalid block, or a placement Litematica no longer knows: make a fresh one below
            }
        }
        clear();
        LitematicaSchematic schematic;
        try {
            schematic = ShapeSchematics.build(result, "Shape preview");
        } catch (IllegalArgumentException e) {
            return;   // an invalid block: the screen shows the reason, there is just no ghost
        }
        SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
        SchematicPlacement previous = mgr.getSelectedSchematicPlacement();
        SchematicPlacement p = SchematicPlacement.createTemporary(schematic, origin);
        p.setName("Shape preview");
        p.setShouldBeSaved(false);
        mgr.addSchematicPlacement(p, false);
        // adding selects the new placement when none was selected; the ghost must not take the selection
        if (mgr.getSelectedSchematicPlacement() != previous) mgr.setSelectedSchematicPlacement(previous);
        placement = p;
        shown = result;
    }

    private static boolean sameSize(VoxelResult a, VoxelResult b) {
        return a.sizeX() == b.sizeX() && a.sizeY() == b.sizeY() && a.sizeZ() == b.sizeZ();
    }

    /** Where the ghost goes for this spec: the player's block position as min corner, or as box center when spec.anchorCenter(). */
    public static BlockPos originFor(ShapeSpec spec) {
        BlockPos pos = Compat.playerBlockPos();
        if (pos == null) pos = BlockPos.ZERO;
        if (!spec.anchorCenter()) return pos;
        ShapeSpec s = spec.clamped();
        return pos.offset(-(s.sizeX() / 2), -(s.sizeY() / 2), -(s.sizeZ() / 2));
    }

    /** The current origin of the ghost (the player may have moved it with Litematica's tools), or null. */
    public static BlockPos currentOrigin() {
        return placement == null ? null : placement.getOrigin();
    }

    /** Removes the ghost (Cancel, Esc, world leave). Safe to call when there is none. */
    public static void clear() {
        SchematicPlacement p = placement;
        placement = null;
        shown = null;
        if (p == null) return;
        try {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(p, false);
        } catch (RuntimeException e) {
            System.out.println("[SchematicArray] cannot remove the shape preview: " + e);
        }
    }

    /** Whether p is the ghost placement (Folders/ArraySync/placement list skip it). */
    public static boolean isPreview(SchematicPlacement p) {
        return p != null && p == placement;
    }
}
