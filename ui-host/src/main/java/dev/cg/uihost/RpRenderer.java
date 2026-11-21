package dev.cg.uihost;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import imgui.ImDrawData;
import imgui.ImFontAtlas;
import imgui.ImGuiIO;
import imgui.ImVec4;
import imgui.flag.ImGuiBackendFlags;
import imgui.type.ImInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws ImGui draw data through Minecraft's own render API (renderpearl), so it works with both the OpenGL and
 * the Vulkan backend. It reuses the GUI's textured pipeline (position, uv, colour; translucent blend) with
 * ImGui triangle lists, one render pass per frame onto the main render target with a scissor per draw command.
 * Only one texture is used: the font atlas (the white pixel in it draws solid shapes).
 */
final class RpRenderer {
    /** float x y z, float u v, 4 colour bytes (R G B A, the same byte order as ImGui's packed ABGR int). */
    private static final int VERTEX_SIZE = 24;

    /** The GUI pipeline without face culling: ImGui winds its triangles the other way round from Minecraft quads. */
    private static final RenderPipeline PIPELINE = buildPipeline();

    private static RenderPipeline buildPipeline() {
        RenderPipeline g = RenderPipelines.GUI_TEXTURED;
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation("pipeline/cg_uihost_imgui")
                .withVertexShader(g.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX))
                .withFragmentShader(g.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT))
                .withColorTargetState(g.getColorTargetStates().get(0))
                .withDepthStencilState(java.util.Optional.ofNullable(g.getDepthStencilState()))
                .withVertexBinding(0, g.getVertexFormatBinding(0))
                .withPrimitiveTopology(g.getPrimitiveTopology())
                .withPolygonMode(g.getPolygonMode())
                .withCull(false);
        for (var layout : g.getBindGroupLayouts()) b.withBindGroupLayout(layout);
        return b.build();
    }

    private GpuTexture fontTexture;
    private GpuTextureView fontView;
    private final Projection projection = new Projection();
    private ProjectionMatrixBuffer projectionBuffer;
    /** Buffers of the last frames: closed three frames later, so the GPU is done with them whatever the backend. */
    private final ArrayDeque<GpuBuffer[]> retired = new ArrayDeque<>();

    void init(ImGuiIO io) {
        io.setBackendRendererName("cg-uihost-renderpearl");
        io.addBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset);
        projectionBuffer = new ProjectionMatrixBuffer("cg-uihost");
        createFontTexture(io);
    }

    /** (Re)uploads the font atlas; call again after the fonts changed. */
    void createFontTexture(ImGuiIO io) {
        destroyFontTexture();
        ImFontAtlas atlas = io.getFonts();
        ImInt w = new ImInt(), h = new ImInt();
        ByteBuffer px = atlas.getTexDataAsRGBA32(w, h);
        int width = w.get(), height = h.get();
        GpuDevice device = RenderSystem.getDevice();
        fontTexture = device.createTexture("cg-uihost font atlas", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        fontView = device.createTextureView(fontTexture);
        try (NativeImage img = new NativeImage(NativeImage.Format.RGBA, width, height, false)) {
            ByteBuffer src = px.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) img.setPixelABGR(x, y, src.getInt((y * width + x) * 4));
            }
            CommandEncoder upload = device.createCommandEncoder();
            upload.writeToTexture(fontTexture, img);
            upload.submit();
        }
        atlas.setTexID(1L);
        atlas.clearTexData();
    }

    private void destroyFontTexture() {
        if (fontView != null) fontView.close();
        if (fontTexture != null) fontTexture.close();
        fontView = null;
        fontTexture = null;
    }

    void render(Minecraft mc, ImDrawData dd) {
        int lists = dd.getCmdListsCount();
        int totalVtx = dd.getTotalVtxCount(), totalIdx = dd.getTotalIdxCount();
        if (lists == 0 || totalVtx == 0 || totalIdx == 0 || fontView == null) return;
        var window = mc.getWindow();
        int fbW = window.getWidth(), fbH = window.getHeight();
        if (fbW <= 0 || fbH <= 0) return;
        float sx = dd.getFramebufferScaleX(), sy = dd.getFramebufferScaleY();
        if (sx <= 0f) sx = 1f;
        if (sy <= 0f) sy = 1f;
        float ox = dd.getDisplayPosX(), oy = dd.getDisplayPosY();

        // one vertex and one index buffer for all lists; vertices converted to the GUI format, in framebuffer pixels
        ByteBuffer vtx = MemoryUtil.memAlloc(totalVtx * VERTEX_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer idx = MemoryUtil.memAlloc(totalIdx * 2).order(ByteOrder.LITTLE_ENDIAN);
        int[] baseVertex = new int[lists], firstIndex = new int[lists];
        try {
            int vOff = 0, iOff = 0;
            for (int n = 0; n < lists; n++) {
                baseVertex[n] = vOff;
                firstIndex[n] = iOff;
                ByteBuffer v = dd.getCmdListVtxBufferData(n).order(ByteOrder.LITTLE_ENDIAN);
                int vc = dd.getCmdListVtxBufferSize(n);
                for (int i = 0; i < vc; i++) {
                    int p = i * 20;
                    vtx.putFloat((v.getFloat(p) - ox) * sx).putFloat((v.getFloat(p + 4) - oy) * sy).putFloat(0f);
                    vtx.putFloat(v.getFloat(p + 8)).putFloat(v.getFloat(p + 12));
                    vtx.putInt(v.getInt(p + 16));
                }
                ByteBuffer ib = dd.getCmdListIdxBufferData(n).order(ByteOrder.LITTLE_ENDIAN);
                int ic = dd.getCmdListIdxBufferSize(n);
                for (int i = 0; i < ic; i++) idx.putShort(ib.getShort(i * 2));
                vOff += vc;
                iOff += ic;
            }
            vtx.flip();
            idx.flip();

            GpuDevice device = RenderSystem.getDevice();
            GpuBuffer vb = device.createBuffer(() -> "cg-uihost vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vtx);
            GpuBuffer ib = device.createBuffer(() -> "cg-uihost indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, idx);
            retired.addLast(new GpuBuffer[] {vb, ib});
            while (retired.size() > 3) for (GpuBuffer b : retired.removeFirst()) b.close();

            drawPass(mc, dd, lists, vb, ib, baseVertex, firstIndex, fbW, fbH, sx, sy, ox, oy);
        } finally {
            MemoryUtil.memFree(vtx);
            MemoryUtil.memFree(idx);
        }
    }

    private void drawPass(Minecraft mc, ImDrawData dd, int lists, GpuBuffer vb, GpuBuffer ib, int[] baseVertex, int[] firstIndex,
                          int fbW, int fbH, float sx, float sy, float ox, float oy) {
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

        RenderSystem.backupProjectionMatrix();
        try {
            projection.setupOrtho(1000f, 11000f, fbW, fbH, true);
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
            var transform = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f().setTranslation(0f, 0f, -11000f));
            var pipeline = RenderSystem.getCompiledPipeline(PIPELINE);

            try (RenderPass pass = encoder.createRenderPass(() -> "cg-uihost imgui", target.getColorTextureView(), java.util.Optional.empty(),
                    target.hasDepth() ? target.getDepthTextureView() : null, java.util.OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transform);
                pass.setPipeline(pipeline);
                pass.setVertexBuffer(0, vb.slice());
                pass.setIndexBuffer(ib, IndexType.SHORT);
                pass.setUniform("Sampler0", fontView, sampler);
                ImVec4 clip = new ImVec4();
                for (int n = 0; n < lists; n++) {
                    int cmds = dd.getCmdListCmdBufferSize(n);
                    for (int c = 0; c < cmds; c++) {
                        dd.getCmdListCmdBufferClipRect(clip, n, c);
                        int x0 = Math.max(0, (int) ((clip.x - ox) * sx)), y0 = Math.max(0, (int) ((clip.y - oy) * sy));
                        int x1 = Math.min(fbW, (int) Math.ceil((clip.z - ox) * sx)), y1 = Math.min(fbH, (int) Math.ceil((clip.w - oy) * sy));
                        if (x1 <= x0 || y1 <= y0) continue;
                        int count = dd.getCmdListCmdBufferElemCount(n, c);
                        if (count <= 0) continue;
                        pass.enableScissor(x0, fbH - y1, x1 - x0, y1 - y0);
                        pass.drawIndexed(count, 1, firstIndex[n] + dd.getCmdListCmdBufferIdxOffset(n, c),
                                baseVertex[n] + dd.getCmdListCmdBufferVtxOffset(n, c), 0);
                    }
                }
                pass.disableScissor();
            }
        } finally {
            RenderSystem.restoreProjectionMatrix();
        }
    }
}
