package dev.vitruvius;

import dev.vitruvius.shape.gui.ThumbnailMesh;
import dev.vitruvius.shape.mc.BlockParsing;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads block models into {@link ThumbnailMesh.Block}s (for the editor's block icons); the version-specific part of
 * Minecraft's model API.
 */
public final class ThumbnailCompat {
    private static final int MAGENTA = 0xFFFF00FF;
    private static final Direction[] DIRS = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final float[] DIR_SHADE = {0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f};

    private ThumbnailCompat() {}

    /** The mesh description of a block state string; a magenta cube when it cannot be parsed or modelled. */
    public static ThumbnailMesh.Block resolveBlock(String blockState) {
        try {
            BlockState state = BlockParsing.parse(blockState);
            if (state != null) return fromState(state);
        } catch (Throwable t) {
            System.out.println("[Vitruvius] thumbnail: cannot resolve " + blockState + ": " + t);
        }
        return ThumbnailMesh.cubeBlock(MAGENTA, true);
    }

    private static ThumbnailMesh.Block fromState(BlockState state) {
        int col = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        int mapColor = 0xFF000000 | (col == 0 ? 0x9A9A9A : col);
        boolean opaque = state.isSolidRender();
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        ThumbnailMesh.Quad[][] culled = new ThumbnailMesh.Quad[6][];
        int count = 0;
        for (int d = 0; d < 6; d++) {
            culled[d] = convert(parts, DIRS[d], name, mapColor);
            count += culled[d].length;
        }
        ThumbnailMesh.Quad[] unculled = convert(parts, null, name, mapColor);
        // fluids and other blocks without a baked model: show their map colour as a cube
        if (count + unculled.length == 0) return ThumbnailMesh.cubeBlock(mapColor, false);
        return new ThumbnailMesh.Block(opaque, mapColor, culled, unculled);
    }

    private static ThumbnailMesh.Quad[] convert(List<BlockStateModelPart> parts, Direction side, String blockName, int mapColor) {
        List<ThumbnailMesh.Quad> out = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (BakedQuad q : part.getQuads(side)) {
                float[] pos = new float[12];
                float[] uv = new float[8];
                for (int v = 0; v < 4; v++) {
                    pos[v * 3] = q.position(v).x();
                    pos[v * 3 + 1] = q.position(v).y();
                    pos[v * 3 + 2] = q.position(v).z();
                    long packed = q.packedUV(v);
                    uv[v * 2] = UVPair.unpackU(packed);
                    uv[v * 2 + 1] = UVPair.unpackV(packed);
                }
                BakedQuad.MaterialInfo info = q.materialInfo();
                Direction lit = info.shadeDirectionOverride() != null ? info.shadeDirectionOverride() : q.direction();
                float shade = DIR_SHADE[lit.ordinal()];
                out.add(new ThumbnailMesh.Quad(pos, uv, info.isTinted() ? tintFor(blockName) : 0xFFFFFFFF, shade, mapColor));
            }
        }
        return out.toArray(new ThumbnailMesh.Quad[0]);
    }

    /** A fixed biome-less tint for grass, foliage and water textures (they are grey in the atlas). */
    private static int tintFor(String blockName) {
        if (blockName.contains("leaves") || blockName.contains("vine")) return 0xFF48B518;
        if (blockName.contains("water") || blockName.contains("cauldron")) return 0xFF3F76E4;
        return 0xFF91BD59;
    }

}
