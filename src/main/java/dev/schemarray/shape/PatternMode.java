package dev.schemarray.shape;

/**
 * How a BlockSlot spreads its blocks over the cells it covers (coordinates are the shape's own box coordinates).
 *
 * <ul>
 *   <li>SINGLE: always entries[0].</li>
 *   <li>MIX: weighted random by BlockEntry.weight, deterministic per (seed, x, y, z).</li>
 *   <li>CHECKER: cell (x/cellX + y/cellY + z/cellZ) mod n -> entry; 2 blocks + cell 1 = chessboard.</li>
 *   <li>STRIPES: (coord along axis / width) mod n -> entry.</li>
 *   <li>GRADIENT: entries in order from the low to the high end of axis, equal bands; dither = blend band edges
 *       with ordered (Bayer) dithering instead of hard steps.</li>
 *   <li>DIAGONAL: ((x + z) / width) mod n when axis = Y (the two axes normal to axis are summed).</li>
 *   <li>BRICK: bricks cellX long, cellY high, cellZ deep; every other row (along Y) is shifted by half a brick;
 *       entries[0] = brick, entries[1] (if present) = mortar line of width 1 between bricks, else no mortar and
 *       bricks alternate through the entries.</li>
 * </ul>
 */
public enum PatternMode {
    SINGLE("Single"),
    MIX("Mix"),
    CHECKER("Checker"),
    STRIPES("Stripes"),
    GRADIENT("Gradient"),
    DIAGONAL("Diagonal"),
    BRICK("Brick");

    public final String displayName;

    PatternMode(String displayName) {
        this.displayName = displayName;
    }
}
