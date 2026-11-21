package dev.vitruvius.shape.gui;


/**
 * Block model geometry for block icons: the quads of a block, grouped by the face direction they are culled with.
 * Pure Java; the Minecraft side ({@code ThumbnailCompat}) supplies them.
 */
public final class ThumbnailMesh {
    private ThumbnailMesh() {}

    /** Direction indices (Direction ordinals): DOWN, UP, NORTH, SOUTH, WEST, EAST; bucket 6 = never culled. */
    public static final int[] STEP_X = {0, 0, 0, 0, -1, 1};
    public static final int[] STEP_Y = {-1, 1, 0, 0, 0, 0};
    public static final int[] STEP_Z = {0, 0, -1, 1, 0, 0};
    public static final int UNCULLED = 6;

    /**
     * One model quad in block-local coordinates (0..1). uv is null for a flat-coloured quad, otherwise 4 atlas
     * (u, v) pairs. tint and flatColor are ARGB; shade is the directional brightness factor.
     */
    public record Quad(float[] pos, float[] uv, int tint, float shade, int flatColor) {}

    /** What an icon needs to know about one block. culled has 6 entries, indexed by direction. */
    public record Block(boolean opaque, int mapColor, Quad[][] culled, Quad[] unculled) {}

    /** A plain full-cube block in one flat colour (ARGB): the stand-in for unparsable or model-less blocks. */
    public static Block cubeBlock(int argb, boolean opaque) {
        float[] shade = {0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f};
        Quad[][] culled = new Quad[6][];
        for (int d = 0; d < 6; d++) {
            int axis = d < 2 ? 1 : d < 4 ? 2 : 0;
            float layer = STEP_X[d] + STEP_Y[d] + STEP_Z[d] > 0 ? 1f : 0f;
            float[] pos = new float[12];
            float[][] corner = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int v = 0; v < 4; v++) {
                int a = (axis + 1) % 3;
                int b = (axis + 2) % 3;
                pos[v * 3 + axis] = layer;
                pos[v * 3 + a] = corner[v][0];
                pos[v * 3 + b] = corner[v][1];
            }
            culled[d] = new Quad[] {new Quad(pos, null, 0xFFFFFFFF, shade[d], argb)};
        }
        return new Block(opaque, argb, culled, new Quad[0]);
    }
}
