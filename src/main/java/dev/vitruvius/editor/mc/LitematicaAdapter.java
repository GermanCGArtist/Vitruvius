package dev.vitruvius.editor.mc;

import dev.vitruvius.ArraySync;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.shape.mc.ShapePreview;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.interfaces.IMessageConsumer;
import fi.dy.masa.malilib.interfaces.IStringConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The only class of the editor that touches Litematica's placement classes. It turns the model's types
 * ({@code core.Rot}, {@code core.Mirror}, {@code Vec3i}) into Litematica's and back, and keeps loaded schematics per
 * file and modification time. Render thread only.
 *
 * <p>Rotation and mirror map one to one (CLOCKWISE_90 is CW_90, and so on; LEFT_RIGHT and FRONT_BACK keep their
 * names), with the rule of {@code ArraySync.transform}: mirror first, then turn.
 */
public final class LitematicaAdapter implements PlacementPort {
    /** A loaded schematic. Opaque to everything outside this class and {@link SchematicInfo}. */
    public static final class Handle {
        final LitematicaSchematic schematic;
        final Path file;
        final long mtime;

        private Handle(LitematicaSchematic schematic, Path file, long mtime) {
            this.schematic = schematic;
            this.file = file;
            this.mtime = mtime;
        }

        public Path file() {
            return file;
        }

        public long mtime() {
            return mtime;
        }
    }

    private static final IMessageConsumer QUIET = new IMessageConsumer() {
        @Override
        public void addMessage(Message.MessageType type, String key, Object... args) {}

        @Override
        public void addMessage(Message.MessageType type, int lifeTime, String key, Object... args) {}
    };
    private static final IStringConsumer QUIET_STR = s -> {};

    private static final LitematicaAdapter SHARED = new LitematicaAdapter();

    public static LitematicaAdapter shared() {
        return SHARED;
    }

    private final Map<Path, Handle> loaded = new HashMap<>();
    private final Map<String, SchematicPlacement> byId = new HashMap<>();

    // ------------------------------------------------------------------------------------------ schematics

    /**
     * The schematic of this file as placements use it: loaded through Litematica's holder, so placements of the same
     * file share it. The holder is only ever asked to load; nothing is removed from it (that would drop the
     * player's own placements). Known limitation: if the file is edited while Litematica holds a copy, Litematica
     * keeps showing the old copy until it reloads it itself.
     *
     * @return null when the file is missing or cannot be read
     */
    public Handle loadSchematic(Path file) {
        Path f = file.toAbsolutePath().normalize();
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return null;
        }
        LitematicaSchematic s = SchematicHolder.getInstance().getOrLoad(f);
        return s == null ? null : new Handle(s, f, mtime);
    }

    /**
     * A private copy of the file for reading (sizes, regions, block counts), loaded with Litematica's own file
     * loader and kept outside the holder: no placement can see it and none is affected. Cached per file and
     * modification time.
     *
     * @return null when the file is missing or cannot be read
     */
    public Handle loadPrivate(Path file) {
        Path f = file.toAbsolutePath().normalize();
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            loaded.remove(f);
            return null;
        }
        Handle h = loaded.get(f);
        if (h != null && h.mtime == mtime) return h;
        Path dir = f.getParent();
        LitematicaSchematic s = dir == null ? null : LitematicaSchematic.createFromFile(dir, f.getFileName().toString());
        if (s == null) {
            loaded.remove(f);
            return null;
        }
        h = new Handle(s, f, mtime);
        loaded.put(f, h);
        return h;
    }

    /** The description stored in the file's metadata ("" when none), read through the private cache; null when unreadable. */
    public String description(Path file) {
        Handle h = loadPrivate(file);
        if (h == null) return null;
        String d = h.schematic.getMetadata().getDescription();
        return d == null ? "" : d;
    }

    /** The enclosing size of the schematic (same rule as the 1.x arrays). */
    public Vec3i size(Handle h) {
        net.minecraft.core.Vec3i s = ArraySync.size(h.schematic);
        return new Vec3i(s.getX(), s.getY(), s.getZ());
    }

    // ------------------------------------------------------------------------------------------ placements

    @Override
    public String create(Path file, Vec3i origin, Rot rot, Mirror mirror, String name, boolean enabled, boolean render, String preferredId) {
        Handle h = loadSchematic(file);
        if (h == null) return null;
        UUID id = null;
        if (preferredId != null) {
            try {
                UUID u = UUID.fromString(preferredId);
                if (!byId.containsKey(u.toString()) && find(u.toString()) == null) id = u;
            } catch (IllegalArgumentException e) {
                // not a UUID: a fresh one is used
            }
        }
        if (id == null) id = UUID.randomUUID();
        SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
        SchematicPlacement p = SchematicPlacement.createFor(h.schematic, new BlockPos(origin.x(), origin.y(), origin.z()), name, enabled, render, id);
        p.setRotation(toMc(rot), QUIET);
        p.setMirror(toMc(mirror), QUIET);
        SchematicPlacement selected = mgr.getSelectedSchematicPlacement();
        mgr.addSchematicPlacement(p, false);
        // adding selects the new placement when none was selected; the editor must not take the selection
        if (mgr.getSelectedSchematicPlacement() != selected) mgr.setSelectedSchematicPlacement(selected);
        byId.put(id.toString(), p);
        return id.toString();
    }

    /** The Litematica {@code SchematicPlacement} behind a hash id (opaque for callers that drive other mods by reflection), or null. */
    public Object placementObject(String hashId) {
        return find(hashId);
    }

    @Override
    public void update(String hashId, Vec3i origin, Rot rot, Mirror mirror, boolean enabled, boolean render) {
        SchematicPlacement p = find(hashId);
        if (p == null) return;
        Rotation r = toMc(rot);
        net.minecraft.world.level.block.Mirror m = toMc(mirror);
        BlockPos at = new BlockPos(origin.x(), origin.y(), origin.z());
        // Litematica ignores a move or turn of a locked placement. Syncmatica locks every placement it shares or renders, so
        // after a share the editor's moves would silently do nothing and the object would jump back. The lock is lifted for
        // the change and put back (Syncmatica's own updates do the same).
        boolean locked = p.isLocked();
        if (locked) p.toggleLocked();
        try {
            if (p.getRotation() != r) p.setRotation(r, QUIET);
            if (p.getMirror() != m) p.setMirror(m, QUIET);
            if (!p.getOrigin().equals(at)) p.setOrigin(at, QUIET_STR);
        } finally {
            if (locked && !p.isLocked()) p.toggleLocked();
        }
        if (p.isEnabled() != enabled) p.setEnabled(enabled);
        if (p.isRenderingEnabled() != render) p.setRenderSchematic(render);
    }

    @Override
    public void remove(String hashId) {
        SchematicPlacement p = byId.remove(hashId);
        if (p == null) p = find(hashId);
        if (p == null) return;
        DataManager.getSchematicPlacementManager().removeSchematicPlacement(p, false);
    }

    @Override
    public PlacementState read(String hashId) {
        SchematicPlacement p = find(hashId);
        if (p == null) return null;
        BlockPos o = p.getOrigin();
        Path file = p.getSchematicFile();
        return new PlacementState(file == null ? null : file.toAbsolutePath().normalize(), new Vec3i(o.getX(), o.getY(), o.getZ()),
                fromMc(p.getRotation()), fromMc(p.getMirror()), p.isEnabled(), p.isRenderingEnabled(), p.getName());
    }

    @Override
    public Set<String> allHashIds() {
        byId.clear();
        Set<String> out = new HashSet<>();
        for (SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (ShapePreview.isPreview(p)) continue;
            String id = p.getHashId().toString();
            byId.put(id, p);
            out.add(id);
        }
        return out;
    }

    @Override
    public void markRebuild(String hashId) {
        SchematicPlacement p = find(hashId);
        if (p != null) DataManager.getSchematicPlacementManager().markChunksForRebuild(p);
    }

    @Override
    public void copySubRegions(String fromId, String toId) {
        SchematicPlacement from = find(fromId), to = find(toId);
        if (from == null || to == null) return;
        Map<String, Boolean> states = new HashMap<>();
        for (SubRegionPlacement s : from.getAllSubRegionsPlacements()) states.put(s.getName(), s.isEnabled());
        for (SubRegionPlacement s : to.getAllSubRegionsPlacements()) {
            Boolean on = states.get(s.getName());
            if (on != null && on != s.isEnabled()) to.toggleSubRegionEnabled(s.getName(), QUIET);
        }
    }

    private SchematicPlacement find(String hashId) {
        SchematicPlacement p = byId.get(hashId);
        if (p != null) return p;
        for (SchematicPlacement q : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (q.getHashId().toString().equals(hashId) && !ShapePreview.isPreview(q)) {
                byId.put(hashId, q);
                return q;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------ mapping

    static Rotation toMc(Rot r) {
        return switch (r) {
            case NONE -> Rotation.NONE;
            case CW_90 -> Rotation.CLOCKWISE_90;
            case CW_180 -> Rotation.CLOCKWISE_180;
            case CCW_90 -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    static Rot fromMc(Rotation r) {
        return switch (r) {
            case NONE -> Rot.NONE;
            case CLOCKWISE_90 -> Rot.CW_90;
            case CLOCKWISE_180 -> Rot.CW_180;
            case COUNTERCLOCKWISE_90 -> Rot.CCW_90;
        };
    }

    static net.minecraft.world.level.block.Mirror toMc(Mirror m) {
        return switch (m) {
            case NONE -> net.minecraft.world.level.block.Mirror.NONE;
            case LEFT_RIGHT -> net.minecraft.world.level.block.Mirror.LEFT_RIGHT;
            case FRONT_BACK -> net.minecraft.world.level.block.Mirror.FRONT_BACK;
        };
    }

    static Mirror fromMc(net.minecraft.world.level.block.Mirror m) {
        return switch (m) {
            case NONE -> Mirror.NONE;
            case LEFT_RIGHT -> Mirror.LEFT_RIGHT;
            case FRONT_BACK -> Mirror.FRONT_BACK;
        };
    }
}
