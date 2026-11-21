package dev.vitruvius.shape.mc;

import dev.vitruvius.Compat;
import dev.vitruvius.shape.VoxelResult;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** VoxelResult -> LitematicaSchematic: one region "Shape" from 0,0,0 to size-1, author = the player. Render thread only. */
public final class ShapeSchematics {
    private ShapeSchematics() {}

    static final String REGION = "Shape";

    /** Builds the schematic; throws IllegalArgumentException naming the block when a palette entry does not parse. */
    public static LitematicaSchematic build(VoxelResult r, String name) {
        return build(r, name, null);
    }

    /** Like build, and stores the shape's settings in the description (see ShapeMeta). */
    public static LitematicaSchematic build(VoxelResult r, String name, String description) {
        BlockState[] states = states(r);

        AreaSelection area = new AreaSelection();
        area.setName(name);
        area.addSubRegionBox(new Box(BlockPos.ZERO, new BlockPos(r.sizeX() - 1, r.sizeY() - 1, r.sizeZ() - 1), REGION), false);
        area.setExplicitOrigin(BlockPos.ZERO);
        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, Compat.playerName());
        if (schematic == null) throw new IllegalArgumentException("Could not create the schematic");

        fill(schematic, r, states, false);
        long now = System.currentTimeMillis();
        schematic.getMetadata().setTimeCreated(now);
        schematic.getMetadata().setTimeModified(now);
        if (description != null) schematic.getMetadata().setDescription(description);
        return schematic;
    }

    private static BlockState[] states(VoxelResult r) {
        BlockState[] states = new BlockState[r.palette().size()];
        for (int i = 0; i < states.length; i++) {
            String entry = r.palette().get(i);
            states[i] = BlockParsing.parse(entry);
            if (states[i] == null) throw new IllegalArgumentException("Invalid block: " + entry);
        }
        return states;
    }

    private static void fill(LitematicaSchematic schematic, VoxelResult r, BlockState[] states, boolean clearAir) {
        LitematicaBlockStateContainer container = schematic.getSubRegionContainer(REGION);
        BlockState air = Blocks.AIR.defaultBlockState();
        short[] cells = r.cells();
        int i = 0;
        for (int y = 0; y < r.sizeY(); y++) {
            for (int z = 0; z < r.sizeZ(); z++) {
                for (int x = 0; x < r.sizeX(); x++, i++) {
                    if (cells[i] != 0) container.set(x, y, z, states[cells[i] - 1]);
                    else if (clearAir) container.set(x, y, z, air);
                }
            }
        }
        schematic.getMetadata().setTotalBlocks(r.blockCount());
    }
}
