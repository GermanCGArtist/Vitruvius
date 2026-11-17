package dev.vitruvius.editor.mc;

import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Vec3i;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.ILitematicaBlockStatePalette;
import fi.dy.masa.litematica.schematic.container.LitematicaBitArray;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the UI and the overlap checks need to know about a {@code .litematic}: the enclosing size, the box of every
 * sub-region and the number of non-air blocks. Cached per file; the cache is checked against the modification time
 * at most once a second per file, so asking every tick is cheap. Render thread only.
 */
public final class SchematicInfo implements SizeSource {
    /** A sub-region as a box in the schematic's own coordinates (before the placement's transform). */
    public record Region(String name, Box box) {}

    public static final class Info {
        private final LitematicaAdapter.Handle handle;
        private final Vec3i size;
        private final List<Region> regions;
        private volatile long blocks = -1;
        private final java.util.concurrent.atomic.AtomicBoolean counting = new java.util.concurrent.atomic.AtomicBoolean();

        private Info(LitematicaAdapter.Handle handle, Vec3i size, List<Region> regions) {
            this.handle = handle;
            this.size = size;
            this.regions = regions;
        }

        /** The enclosing size (the box of the whole schematic is {@code [0, size)}). */
        public Vec3i size() {
            return size;
        }

        public List<Region> regions() {
            return regions;
        }

        /**
         * Non-air blocks in all sub-regions, or -1 while they are still being counted. The first call starts the count
         * (a pass over every block) on a worker thread, so the render thread never walks the blocks; ask again next
         * frame. The schematic was loaded privately for this info and is only read.
         */
        public long blockCount() {
            long b = blocks;
            if (b < 0 && counting.compareAndSet(false, true)) {
                POOL.execute(() -> {
                    try {
                        blocks = count(handle.schematic);
                    } catch (RuntimeException | LinkageError e) {
                        blocks = 0;
                    }
                });
            }
            return b;
        }
    }

    private record Entry(long mtime, Info info, long checkedAtMillis) {}

    private static final long RECHECK_MILLIS = 1000;
    /** One worker for the block counts (daemon, so it never keeps the game from exiting). */
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vitruvius-blockcount");
        t.setDaemon(true);
        return t;
    });
    private static final SchematicInfo SHARED = new SchematicInfo(LitematicaAdapter.shared());

    public static SchematicInfo shared() {
        return SHARED;
    }

    private final LitematicaAdapter adapter;
    private final Map<Path, Entry> cache = new HashMap<>();

    public SchematicInfo(LitematicaAdapter adapter) {
        this.adapter = adapter;
    }

    /** The info of this file, or null when it is missing or cannot be read. */
    public Info of(Path file) {
        Path f = file.toAbsolutePath().normalize();
        long now = System.currentTimeMillis();
        Entry e = cache.get(f);
        if (e != null && now - e.checkedAtMillis < RECHECK_MILLIS) return e.info;
        LitematicaAdapter.Handle h = adapter.loadPrivate(f);
        if (h == null) {
            cache.remove(f);
            return null;
        }
        if (e != null && e.mtime == h.mtime()) {
            cache.put(f, new Entry(e.mtime, e.info, now));
            return e.info;
        }
        Info info = new Info(h, adapter.size(h), regionsOf(h.schematic));
        cache.put(f, new Entry(h.mtime(), info, now));
        return info;
    }

    @Override
    public Vec3i size(Path file) {
        Info i = of(file);
        return i == null ? null : i.size();
    }

    public void clear() {
        cache.clear();
    }

    static List<Region> regionsOf(LitematicaSchematic s) {
        List<Region> out = new ArrayList<>();
        Map<String, BlockPos> sizes = s.getAreaSizes();
        for (var a : s.getAreaPositions().entrySet()) {
            BlockPos p = a.getValue(), sz = sizes.get(a.getKey());
            if (sz == null) continue;
            int[] pp = {p.getX(), p.getY(), p.getZ()}, ss = {sz.getX(), sz.getY(), sz.getZ()};
            int[] lo = new int[3], hi = new int[3];
            for (int i = 0; i < 3; i++) {
                int end = ss[i] >= 0 ? pp[i] + ss[i] - 1 : pp[i] + ss[i] + 1;   // the last block, as in ArraySync.size
                lo[i] = Math.min(pp[i], end);
                hi[i] = Math.max(pp[i], end) + 1;
            }
            out.add(new Region(a.getKey(), new Box(new Vec3i(lo[0], lo[1], lo[2]), new Vec3i(hi[0], hi[1], hi[2]))));
        }
        return out;
    }

    private static long count(LitematicaSchematic s) {
        long total = 0;
        for (String name : s.getAreaPositions().keySet()) {
            LitematicaBlockStateContainer c = s.getSubRegionContainer(name);
            if (c == null) continue;
            net.minecraft.core.Vec3i sz = c.getSize();
            long volume = (long) sz.getX() * sz.getY() * sz.getZ();
            LitematicaBitArray arr = c.getArray();
            ILitematicaBlockStatePalette pal = c.getPalette();
            byte[] air = new byte[Math.max(pal.getPaletteSize(), 1) + 1];   // 0 = unknown, 1 = air, 2 = block
            for (long i = 0; i < volume; i++) {
                int id = arr.getAt(i);
                if (id < 0 || id >= air.length) {
                    total++;
                    continue;
                }
                if (air[id] == 0) {
                    BlockState b = pal.getBlockState(id);
                    air[id] = (byte) (b == null || b.isAir() ? 1 : 2);
                }
                if (air[id] == 2) total++;
            }
        }
        return total;
    }
}
