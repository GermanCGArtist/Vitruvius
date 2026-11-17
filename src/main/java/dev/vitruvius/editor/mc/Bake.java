package dev.vitruvius.editor.mc;

import dev.vitruvius.editor.core.BakeParts;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.BakeLayout;
import dev.vitruvius.shape.mc.ShapeSaver;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * "Bake to one .litematic": merges parts into one {@link LitematicaSchematic} with one sub-region per source
 * sub-region of every placed part (array copies included), placed by the part's world transform and relative to the
 * bake origin (the minimum corner of everything, see {@link BakeLayout}).
 *
 * <p>Rotation and mirror are baked into the blocks: each block moves with Litematica's own
 * {@code PositionUtils.getTransformedBlockPos(pos, mirror, rotation)} (mirror first, then turn; the same rule as the
 * placements and {@code Transform.apply}) and its state goes through {@code BlockState.mirror(..).rotate(..)}, the
 * order {@code placeBlocksToWorld} uses. Each sub-region is the axis-aligned box of the transformed source region.
 * Block entities are copied with their positions moved (their NBT is not rotated); entities and scheduled ticks are
 * not baked.
 *
 * <p>Heavy for big selections: {@link #bake} touches no game state, so call it from a worker thread.
 */
public final class Bake {
    private Bake() {}

    /** The outcome: {@code file} is set on success, {@code error} otherwise. */
    public record Result(Path file, int regions, long blocks, int skippedParts, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private record Src(BakeParts.Part part, LitematicaSchematic schematic, SchematicInfo.Region region, String name) {}

    /**
     * @param fileOf the file of a part's object (null or missing: the part is skipped and counted)
     * @param stem   the file name without extension; the caller makes sure the file does not exist yet
     */
    public static Result bake(List<BakeParts.Part> parts, Function<BakeParts.Part, Path> fileOf, Path outDir, String stem, String author) {
        Map<Path, LitematicaSchematic> loaded = new HashMap<>();
        List<Src> srcs = new ArrayList<>();
        int skipped = 0;
        for (BakeParts.Part part : parts) {
            Path f = fileOf.apply(part);
            if (f == null || !Files.isRegularFile(f)) {
                skipped++;
                continue;
            }
            Path key = f.toAbsolutePath().normalize();
            LitematicaSchematic s = loaded.computeIfAbsent(key, k -> {
                Path dir = k.getParent();
                return dir == null ? null : LitematicaSchematic.createFromFile(dir, k.getFileName().toString());
            });
            if (s == null) {
                skipped++;
                continue;
            }
            List<SchematicInfo.Region> regions = SchematicInfo.regionsOf(s);
            for (SchematicInfo.Region r : regions) {
                String name = part.label() + (regions.size() > 1 ? "/" + r.name() : "");
                srcs.add(new Src(part, s, r, name));
            }
        }
        if (srcs.isEmpty()) return new Result(null, 0, 0, skipped, "Nothing to bake: no readable schematic in the selection");

        List<BakeLayout.Source> sources = new ArrayList<>();
        for (Src s : srcs) sources.add(new BakeLayout.Source(s.name, s.region.box().transformed(s.part.world())));
        BakeLayout.Plan plan = BakeLayout.plan(sources);

        AreaSelection area = new AreaSelection();
        area.setName(stem);
        for (BakeLayout.Region r : plan.regions()) {
            BlockPos p1 = new BlockPos(r.pos().x(), r.pos().y(), r.pos().z());
            BlockPos p2 = new BlockPos(r.pos().x() + r.size().x() - 1, r.pos().y() + r.size().y() - 1, r.pos().z() + r.size().z() - 1);
            area.addSubRegionBox(new fi.dy.masa.litematica.selection.Box(p1, p2, r.name()), false);
        }
        area.setExplicitOrigin(BlockPos.ZERO);
        LitematicaSchematic out = LitematicaSchematic.createEmptySchematic(area, author);
        if (out == null) return new Result(null, 0, 0, skipped, "Could not create the schematic");

        long blocks = 0;
        for (BakeLayout.Region r : plan.regions()) {
            Src s = srcs.get(r.source());
            blocks += fill(s, out, r, plan.origin());
        }
        long now = System.currentTimeMillis();
        out.getMetadata().setTotalBlocks((int) Math.min(Integer.MAX_VALUE, blocks));
        out.getMetadata().setTimeCreated(now);
        out.getMetadata().setTimeModified(now);
        out.getMetadata().setName(stem);
        try {
            if (!out.writeToFile(outDir, stem, false)) return new Result(null, plan.regions().size(), blocks, skipped, "Writing the file failed");
        } catch (RuntimeException e) {
            return new Result(null, plan.regions().size(), blocks, skipped, "Writing the file failed: " + e.getMessage());
        }
        return new Result(ShapeSaver.fileFor(outDir, stem), plan.regions().size(), blocks, skipped, null);
    }

    private static long fill(Src s, LitematicaSchematic out, BakeLayout.Region r, Vec3i origin) {
        LitematicaBlockStateContainer from = s.schematic.getSubRegionContainer(s.region.name());
        LitematicaBlockStateContainer to = out.getSubRegionContainer(r.name());
        if (from == null || to == null) return 0;
        Box lo = s.region.box();
        var t = s.part.world();
        Rotation rot = LitematicaAdapter.toMc(t.rot());
        net.minecraft.world.level.block.Mirror mirror = LitematicaAdapter.toMc(t.mirror());
        net.minecraft.core.Vec3i sz = from.getSize(), dsz = to.getSize();
        long count = 0;
        for (int y = 0; y < Math.abs(sz.getY()); y++) {
            for (int z = 0; z < Math.abs(sz.getZ()); z++) {
                for (int x = 0; x < Math.abs(sz.getX()); x++) {
                    BlockState st = from.get(x, y, z);
                    if (st == null || st.isAir()) continue;
                    BlockPos local = new BlockPos(lo.min().x() + x, lo.min().y() + y, lo.min().z() + z);
                    BlockPos moved = PositionUtils.getTransformedBlockPos(local, mirror, rot);
                    int dx = moved.getX() + t.pos().x() - origin.x() - r.pos().x();
                    int dy = moved.getY() + t.pos().y() - origin.y() - r.pos().y();
                    int dz = moved.getZ() + t.pos().z() - origin.z() - r.pos().z();
                    if (dx < 0 || dy < 0 || dz < 0 || dx >= dsz.getX() || dy >= dsz.getY() || dz >= dsz.getZ()) continue;
                    to.set(dx, dy, dz, st.mirror(mirror).rotate(rot));
                    count++;
                }
            }
        }
        copyBlockEntities(s, out, r, origin, lo, rot, mirror);
        return count;
    }

    private static void copyBlockEntities(Src s, LitematicaSchematic out, BakeLayout.Region r, Vec3i origin, Box lo, Rotation rot,
                                          net.minecraft.world.level.block.Mirror mirror) {
        try {
            Map<BlockPos, CompoundData> src = s.schematic.getBlockEntityMapForRegion(s.region.name());
            Map<BlockPos, CompoundData> dst = out.getBlockEntityMapForRegion(r.name());
            if (src == null || dst == null) return;
            var t = s.part.world();
            for (Map.Entry<BlockPos, CompoundData> e : src.entrySet()) {
                BlockPos local = new BlockPos(lo.min().x() + e.getKey().getX(), lo.min().y() + e.getKey().getY(), lo.min().z() + e.getKey().getZ());
                BlockPos m = PositionUtils.getTransformedBlockPos(local, mirror, rot);
                dst.put(new BlockPos(m.getX() + t.pos().x() - origin.x() - r.pos().x(), m.getY() + t.pos().y() - origin.y() - r.pos().y(),
                        m.getZ() + t.pos().z() - origin.z() - r.pos().z()), e.getValue());
            }
        } catch (UnsupportedOperationException ignored) {
            // an immutable map: the blocks are baked without their block entities
        }
    }
}
