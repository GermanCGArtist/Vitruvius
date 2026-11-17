package dev.schemarray;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.schemarray.shape.gui.ThumbnailMesh;
import dev.schemarray.shape.mc.BlockParsing;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Everything the 3D shape thumbnail needs from Minecraft's version-specific rendering: reading block models into
 * {@link ThumbnailMesh.Block}s, and a picture-in-picture GUI renderer (the 26.x way to draw 3D into a GUI
 * rectangle: it renders into its own texture, which the GUI then blits) that draws a mesh with a camera.
 */
public final class ThumbnailCompat {
    private static final int FULL_LIGHT = 0xF000F0;
    private static final int MAGENTA = 0xFFFF00FF;
    private static final Direction[] DIRS = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final float[] DIR_SHADE = {0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f};

    private ThumbnailCompat() {}

    /** Registers the picture-in-picture renderer. Call once from the client initializer. */
    public static void register() {
        PictureInPictureRendererRegistry.register(ctx -> new Renderer(ctx.bufferSource()));
    }

    /** Queues the mesh to be drawn into the GUI rectangle (GUI coordinates) at pxPerBlock GUI pixels per block. */
    public static void submit(GuiContext ctx, ThumbnailMesh mesh, float yawDeg, float pitchDeg, float pxPerBlock, int x0, int y0, int x1, int y1) {
        ScreenRectangle scissor = ctx.peekLastScissor();
        ctx.addSpecialElement(new State(mesh, yawDeg, pitchDeg, pxPerBlock, x0, y0, x1, y1, scissor, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissor)));
    }

    /** The mesh description of a block state string; a magenta cube when it cannot be parsed or modelled. */
    public static ThumbnailMesh.Block resolveBlock(String blockState) {
        try {
            BlockState state = BlockParsing.parse(blockState);
            if (state != null) return fromState(state);
        } catch (Throwable t) {
            System.out.println("[SchematicArray] thumbnail: cannot resolve " + blockState + ": " + t);
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
                float shade = info.shade() ? DIR_SHADE[q.direction().ordinal()] : 1f;
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

    private record State(ThumbnailMesh mesh, float yawDeg, float pitchDeg, float scale, int x0, int y0, int x1, int y1,
            ScreenRectangle scissorArea, ScreenRectangle bounds) implements PictureInPictureRenderState {}

    /** Draws the mesh orthographically; re-renders only when the view changes, otherwise the texture is re-blitted. */
    private static final class Renderer extends PictureInPictureRenderer<State> {
        private State last;

        Renderer(MultiBufferSource.BufferSource bufferSource) {
            super(bufferSource);
        }

        @Override
        public Class<State> getRenderStateClass() {
            return State.class;
        }

        @Override
        protected boolean textureIsReadyToBlit(State state) {
            return state.equals(last);
        }

        @Override
        protected float getTranslateY(int height, int guiScale) {
            return height / 2f;
        }

        @Override
        protected String getTextureLabel() {
            return "schemarray shape thumbnail";
        }

        @Override
        protected void renderToTexture(State state, PoseStack poseStack) {
            last = state;
            ThumbnailMesh mesh = state.mesh();
            // the pose already holds translate(centre of the texture) * scale(s, s, -s), s = texture pixels per block
            Matrix4f m = new Matrix4f(poseStack.last().pose());
            float zk = Math.min(1f, 900f / (m.m00() * mesh.radius()));
            double yaw = Math.toRadians(state.yawDeg());
            double pitch = Math.toRadians(state.pitchDeg());
            // C: from the shape towards the camera. F: viewing direction. R / U: screen right / up in the world.
            float cx = (float) (Math.sin(yaw) * Math.cos(pitch));
            float cy = (float) Math.sin(pitch);
            float cz = (float) (Math.cos(yaw) * Math.cos(pitch));
            float fx = -cx, fy = -cy, fz = -cz;
            float rLen = (float) Math.hypot(fz, fx);
            float rx = -fz / rLen, rz = fx / rLen;
            float ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
            // GUI space: x right, y down, larger z = nearer. Rows R, -U, F (the pose already negates z).
            m.mul(new Matrix4f(new Matrix3f(rx, -ux, zk * fx, 0f, -uy, zk * fy, rz, -uz, zk * fz)));
            m.translate(-mesh.sizeX / 2f, -mesh.sizeY / 2f, -mesh.sizeZ / 2f);
            float[] toCamera = {cx, cy, cz};
            boolean textured = mesh.quadCount <= ThumbnailMesh.FACE_CAP && state.scale() >= 4f;
            if (textured) draw(RenderTypes.text(TextureAtlas.LOCATION_BLOCKS), m, mesh, toCamera, true, true);
            draw(RenderTypes.textBackground(), m, mesh, toCamera, false, textured);
        }

        /**
         * One pass over the camera-facing buckets. The textured pass draws quads with uv; the flat pass draws all
         * quads (flat mode) or only the uv-less stand-ins (textured mode).
         */
        private void draw(RenderType type, Matrix4f m, ThumbnailMesh mesh, float[] toCamera, boolean texturedPass, boolean texturedMode) {
            VertexConsumer buf = null;
            float m00 = m.m00(), m01 = m.m01(), m02 = m.m02(), m10 = m.m10(), m11 = m.m11(), m12 = m.m12();
            float m20 = m.m20(), m21 = m.m21(), m22 = m.m22(), m30 = m.m30(), m31 = m.m31(), m32 = m.m32();
            for (int b = 0; b < 7; b++) {
                if (b < 6 && ThumbnailMesh.STEP_X[b] * toCamera[0] + ThumbnailMesh.STEP_Y[b] * toCamera[1] + ThumbnailMesh.STEP_Z[b] * toCamera[2] <= 0f) continue;
                ThumbnailMesh.Bucket bucket = mesh.buckets[b];
                for (int i = 0; i < bucket.size; i++) {
                    ThumbnailMesh.Quad q = bucket.quads[i];
                    boolean uvless = q.uv() == null;
                    if (texturedPass ? uvless : texturedMode && !uvless) continue;
                    if (buf == null) buf = bufferSource.getBuffer(type);
                    int p = bucket.pos[i];
                    float ox = p & 1023, oy = (p >> 10) & 1023, oz = (p >> 20) & 1023;
                    int color = shaded(texturedPass ? q.tint() : q.flatColor(), q.shade());
                    float[] pos = q.pos();
                    float[] uv = q.uv();
                    for (int v = 0; v < 4; v++) {
                        float x = pos[v * 3] + ox, y = pos[v * 3 + 1] + oy, z = pos[v * 3 + 2] + oz;
                        buf.addVertex(m00 * x + m10 * y + m20 * z + m30, m01 * x + m11 * y + m21 * z + m31, m02 * x + m12 * y + m22 * z + m32).setColor(color);
                        if (texturedPass) buf.setUv(uv[v * 2], uv[v * 2 + 1]);
                        buf.setLight(FULL_LIGHT);
                    }
                }
            }
        }

        private static int shaded(int argb, float shade) {
            int r = (int) (((argb >> 16) & 255) * shade);
            int g = (int) (((argb >> 8) & 255) * shade);
            int bl = (int) ((argb & 255) * shade);
            return 0xFF000000 | r << 16 | g << 8 | bl;
        }
    }
}
