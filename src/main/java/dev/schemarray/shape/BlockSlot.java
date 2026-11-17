package dev.schemarray.shape;

import java.util.List;

/**
 * The blocks for one part of the shape (Outline or Fill) and how they are patterned. No entries = air (that part
 * is left empty). cellX/Y/Z: CHECKER cell size and BRICK brick size (>= 1). axis + width: STRIPES, GRADIENT,
 * DIAGONAL. seed: MIX. dither: GRADIENT.
 */
public record BlockSlot(PatternMode mode, List<BlockEntry> entries, int cellX, int cellY, int cellZ,
                        Axis axis, int width, long seed, boolean dither) {
    public BlockSlot {
        mode = mode == null ? PatternMode.SINGLE : mode;
        entries = entries == null ? List.of() : List.copyOf(entries);
        cellX = Math.max(1, cellX);
        cellY = Math.max(1, cellY);
        cellZ = Math.max(1, cellZ);
        axis = axis == null ? Axis.Y : axis;
        width = Math.max(1, width);
    }

    public static BlockSlot single(String blockState) {
        return new BlockSlot(PatternMode.SINGLE, List.of(new BlockEntry(blockState, 1)), 1, 1, 1, Axis.Y, 1, 0L, false);
    }

    public static BlockSlot air() {
        return new BlockSlot(PatternMode.SINGLE, List.of(), 1, 1, 1, Axis.Y, 1, 0L, false);
    }

    public boolean isAir() {
        return entries.isEmpty();
    }
}
