package dev.vitruvius.shape.gui;

import dev.vitruvius.ThumbnailCompat;
import dev.vitruvius.shape.VoxelResult;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The rotatable 3D view of the shape inside the generator screen. The screen owns one instance, tells it the area
 * it covers each frame and forwards mouse events that land in it. The mesh (visible block-model quads) is built
 * on a worker thread when the result changes; drawing goes through {@link ThumbnailCompat} (picture-in-picture
 * renderer) and only re-renders its texture when the angle, zoom or size changes.
 */
public final class ShapeThumbnail {
    private static final float DEFAULT_YAW = 45f;
    private static final float DEFAULT_PITCH = 30f;
    private static final float MAX_PITCH = 89f;
    private static final float MIN_ZOOM = 0.25f;
    private static final float MAX_ZOOM = 8f;
    private static final float DEGREES_PER_PIXEL = 0.5f;
    private static final int BACKGROUND = 0xFF14161A;
    private static final int BORDER = 0xFF5A5F69;

    private static final ExecutorService BUILDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vitruvius-thumbnail");
        t.setDaemon(true);
        return t;
    });

    private volatile ThumbnailMesh mesh;
    private volatile int generation;
    private float yaw = DEFAULT_YAW;
    private float pitch = DEFAULT_PITCH;
    private float zoom = 1f;
    private boolean dragging;
    private int areaX;
    private int areaY;
    private int areaW;
    private int areaH;

    /** New shape to show (null = empty). Rebuilds the cached mesh; cheap to call only when the result changes. */
    public void setResult(VoxelResult result) {
        int gen = ++generation;
        if (result == null || result.blockCount() == 0) {
            mesh = null;
            return;
        }
        ThumbnailMesh.Block[] blocks = new ThumbnailMesh.Block[result.palette().size()];
        for (int i = 0; i < blocks.length; i++) blocks[i] = resolve(result.palette().get(i));
        BUILDER.execute(() -> {
            try {
                ThumbnailMesh built = ThumbnailMesh.build(result, blocks, () -> gen != generation);
                if (built != null && gen == generation) mesh = built;
            } catch (Throwable t) {
                System.out.println("[Vitruvius] thumbnail mesh failed: " + t);
            }
        });
    }

    /** One palette entry as mesh data; kept separate so the mesh builder can be exercised without Minecraft. */
    private static ThumbnailMesh.Block resolve(String blockState) {
        return ThumbnailCompat.resolveBlock(blockState);
    }

    /** Draws the view into the rectangle (screen coordinates), with a dark background and a 1px border. */
    public void render(GuiContext ctx, int x, int y, int width, int height, int mouseX, int mouseY) {
        areaX = x;
        areaY = y;
        areaW = width;
        areaH = height;
        ctx.fill(x, y, x + width, y + height, BACKGROUND);
        ctx.fill(x, y, x + width, y + 1, BORDER);
        ctx.fill(x, y + height - 1, x + width, y + height, BORDER);
        ctx.fill(x, y + 1, x + 1, y + height - 1, BORDER);
        ctx.fill(x + width - 1, y + 1, x + width, y + height - 1, BORDER);
        ThumbnailMesh m = mesh;
        if (m == null || width < 4 || height < 4) return;
        float pxPerBlock = 0.46f * Math.min(width - 2, height - 2) / m.radius() * zoom;
        ThumbnailCompat.submit(ctx, m, yaw, pitch, pxPerBlock, x + 1, y + 1, x + width - 1, y + height - 1);
    }

    /** Left button pressed inside the view: starts orbiting. Returns true if taken. */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !inside(mouseX, mouseY)) return false;
        dragging = true;
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, double dragX, double dragY) {
        if (!dragging) return false;
        yaw = (yaw - (float) dragX * DEGREES_PER_PIXEL) % 360f;
        pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch + (float) dragY * DEGREES_PER_PIXEL));
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!dragging || button != 0) return false;
        dragging = false;
        return true;
    }

    /** Wheel inside the view: zoom. */
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!inside(mouseX, mouseY)) return false;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * (float) Math.pow(1.15, amount)));
        return true;
    }

    /** Back to the default angle and zoom (fit the whole shape). */
    public void resetView() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom = 1f;
    }

    private boolean inside(double mouseX, double mouseY) {
        return mouseX >= areaX && mouseX < areaX + areaW && mouseY >= areaY && mouseY < areaY + areaH;
    }
}
