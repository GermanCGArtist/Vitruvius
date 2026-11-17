package dev.vitruvius.shape.gui;

import dev.vitruvius.shape.VoxelResult;
import java.util.function.BooleanSupplier;

/**
 * The cached geometry of the 3D thumbnail: every visible model quad of a shape, grouped by the face direction it
 * is culled with, so the renderer can skip back-facing groups. Pure Java (the Minecraft side supplies the block
 * models as {@link Block}s), built off the render thread because a 256^3 shape has millions of cells to visit.
 */
public final class ThumbnailMesh {
    /** Above this many quads the thumbnail draws flat map colours instead of block textures. */
    public static final int FACE_CAP = 150_000;

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

    /** What the thumbnail needs to know about one palette entry. culled has 6 entries, indexed by direction. */
    public record Block(boolean opaque, int mapColor, Quad[][] culled, Quad[] unculled) {}

    /** Growable parallel arrays: packed cell position and the quad drawn there. */
    public static final class Bucket {
        public int[] pos = new int[256];
        public Quad[] quads = new Quad[256];
        public int size;

        void add(int p, Quad q) {
            if (size == pos.length) {
                pos = java.util.Arrays.copyOf(pos, size * 2);
                quads = java.util.Arrays.copyOf(quads, size * 2);
            }
            pos[size] = p;
            quads[size++] = q;
        }
    }

    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    public final Bucket[] buckets = new Bucket[7];
    public final int quadCount;

    private ThumbnailMesh(int sizeX, int sizeY, int sizeZ, int quadCount) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.quadCount = quadCount;
    }

    /** Half the diagonal of the shape's box: the radius that keeps it inside the view at every angle. */
    public float radius() {
        return 0.5f * (float) Math.sqrt((double) sizeX * sizeX + (double) sizeY * sizeY + (double) sizeZ * sizeZ);
    }

    /** Packs a cell position into one int (10 bits per axis). */
    public static int pack(int x, int y, int z) {
        return x | y << 10 | z << 20;
    }

    /**
     * Collects the quads of every cell that touches a non-opaque neighbour (or the outside). blocks[k] describes
     * palette entry k. Returns null when cancelled.
     */
    public static ThumbnailMesh build(VoxelResult r, Block[] blocks, BooleanSupplier cancelled) {
        int sx = r.sizeX();
        int sy = r.sizeY();
        int sz = r.sizeZ();
        short[] cells = r.cells();
        boolean[] opaque = new boolean[blocks.length + 1];
        for (int i = 0; i < blocks.length; i++) opaque[i + 1] = blocks[i].opaque();
        Bucket[] out = new Bucket[7];
        for (int i = 0; i < 7; i++) out[i] = new Bucket();
        int total = 0;
        int layer = sx * sz;
        for (int y = 0; y < sy; y++) {
            if (cancelled.getAsBoolean()) return null;
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int i = (y * sz + z) * sx + x;
                    int id = cells[i];
                    if (id == 0) continue;
                    Block b = blocks[id - 1];
                    int p = pack(x, y, z);
                    boolean any = false;
                    for (int d = 0; d < 6; d++) {
                        int nx = x + STEP_X[d];
                        int ny = y + STEP_Y[d];
                        int nz = z + STEP_Z[d];
                        int n = nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz ? 0 : cells[i + STEP_X[d] + STEP_Z[d] * sx + STEP_Y[d] * layer];
                        if (n != 0 && opaque[n]) continue;
                        any = true;
                        for (Quad q : b.culled()[d]) {
                            out[d].add(p, q);
                            total++;
                        }
                    }
                    if (any) {
                        for (Quad q : b.unculled()) {
                            out[UNCULLED].add(p, q);
                            total++;
                        }
                    }
                }
            }
        }
        ThumbnailMesh mesh = new ThumbnailMesh(sx, sy, sz, total);
        System.arraycopy(out, 0, mesh.buckets, 0, 7);
        return mesh;
    }

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
