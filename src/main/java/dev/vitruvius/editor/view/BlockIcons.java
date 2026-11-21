package dev.vitruvius.editor.view;

import dev.cg.uihost.UiHost;
import dev.vitruvius.ThumbnailCompat;
import dev.vitruvius.shape.gui.ThumbnailMesh;
import imgui.ImDrawList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;

import java.util.HashMap;
import java.util.Map;

/**
 * Block textures for ImGui: the top face (else any face) of a block's model, taken from the block atlas, drawn as an
 * ImGui image. The atlas is handed to the UI host once as texture id {@link #ATLAS}. Render thread only.
 */
public final class BlockIcons {
    private BlockIcons() {}

    public static final long ATLAS = 2L;

    /** Atlas rectangle {u0, v0, u1, v1} and the tint (ARGB), or null when the block has no textured face. */
    private record Icon(float u0, float v0, float u1, float v1, int tint) {}

    private static final Icon NONE = new Icon(0, 0, 0, 0, 0);
    private static final Map<String, Icon> ICONS = new HashMap<>();
    private static boolean registered;

    private static void register() {
        if (registered) return;
        registered = true;
        UiHost.setTextureLookup(id -> {
            if (id != ATLAS) return null;
            try {
                return Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
            } catch (RuntimeException e) {
                return null;
            }
        });
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
        register();
        Icon i = icon(block);
        if (i == NONE) return false;
        dl.addImage(ATLAS, x0, y0, x1, y1, i.u0, i.v0, i.u1, i.v1, Ui.c(i.tint & 0xFFFFFF));
        return true;
    }
}
