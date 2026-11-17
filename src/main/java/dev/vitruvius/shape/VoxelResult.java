package dev.vitruvius.shape;

import java.util.List;

/**
 * A generated shape: one palette index per cell. cells[index(x, y, z)] = 0 for air, k for palette.get(k - 1).
 * The palette holds each distinct block state string once (outline and fill entries merged). blockCount = number
 * of non-air cells.
 */
public record VoxelResult(int sizeX, int sizeY, int sizeZ, short[] cells, List<String> palette, int blockCount) {
    public int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    /** Palette index at (x, y, z), 0 = air; outside the box counts as air. */
    public int get(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return 0;
        return cells[index(x, y, z)];
    }
}
