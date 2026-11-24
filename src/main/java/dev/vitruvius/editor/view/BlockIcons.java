package dev.vitruvius.editor.view;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.liskov.ui.host.LiskovUiHost;
import dev.vitruvius.ThumbnailCompat;
import dev.vitruvius.shape.gui.ThumbnailMesh;
import imgui.ImDrawList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;

import java.util.HashMap;
import java.util.Map;

/**
 * Block textures for ImGui: the top face (else any face) of a block's model, taken from the block atlas, drawn as an
 * ImGui image. The atlas view is registered with Liskov's UI host (a keyed texture id, so other mods' textures are not
 * clobbered) and registered again whenever the atlas is rebuilt (resource reload). Render thread only.
 */
public final class BlockIcons {
    private BlockIcons() {}


    /** Atlas rectangle {u0, v0, u1, v1} and the tint (ARGB), or null when the block has no textured face. */
    private record Icon(float u0, float v0, float u1, float v1, int tint) {}

    private static final Icon NONE = new Icon(0, 0, 0, 0, 0);
    private static final Map<String, Icon> ICONS = new HashMap<>();
    private static GpuTextureView atlasView;
    private static long atlasId;

    /** The ImGui texture id of the block atlas, or 0 when it is not available. */
    private static long atlas() {
        GpuTextureView view;
        try {
            view = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        } catch (RuntimeException e) {
            view = null;
        }
        if (view != atlasView) {
            if (atlasId != 0L) LiskovUiHost.unregisterTexture(atlasId);
            atlasView = view;
            atlasId = view == null ? 0L : LiskovUiHost.registerTexture(view);
            ICONS.clear();   // a rebuilt atlas moves the sprites
        }
        return atlasId;
    }

    private static Icon icon(String block) {
        return ICONS.computeIfAbsent(block, b -> {
            try {
                ThumbnailMesh.Block mb = ThumbnailCompat.resolveBlock(b);
                ThumbnailMesh.Quad q = null;
                if (mb.culled()[1].length > 0 && mb.culled()[1][0].uv() != null) q = mb.culled()[1][0];     // UP
                for (int d = 0; d < 6 && q == null; d++) {
                    for (ThumbnailMesh.Quad c : mb.culled()[d]) if (c.uv() != null) { q = c; break; }
                }
                for (int i = 0; i < mb.unculled().length && q == null; i++) if (mb.unculled()[i].uv() != null) q = mb.unculled()[i];
                if (q == null) return NONE;
                float u0 = 2, v0 = 2, u1 = -1, v1 = -1;
                for (int v = 0; v < 4; v++) {
                    u0 = Math.min(u0, q.uv()[v * 2]);
                    u1 = Math.max(u1, q.uv()[v * 2]);
                    v0 = Math.min(v0, q.uv()[v * 2 + 1]);
                    v1 = Math.max(v1, q.uv()[v * 2 + 1]);
                }
                return new Icon(u0, v0, u1, v1, q.tint());
            } catch (Throwable t) {
                return NONE;
            }
        });
    }

    /** Draws the block's texture in the rectangle; returns false (nothing drawn) when it has none. */
    public static boolean draw(ImDrawList dl, String block, float x0, float y0, float x1, float y1) {
        long tex = atlas();
        if (tex == 0L) return false;
        Icon i = icon(block);
        if (i == NONE) return false;
        dl.addImage(tex, x0, y0, x1, y1, i.u0, i.v0, i.u1, i.v1, Ui.c(i.tint & 0xFFFFFF));
        return true;
    }
}
