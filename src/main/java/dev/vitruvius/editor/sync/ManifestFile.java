package dev.vitruvius.editor.sync;

import dev.vitruvius.shape.mc.ShapeSaver;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.SchematicMetadata;
import fi.dy.masa.litematica.selection.AreaSelection;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;

/**
 * The manifest as a file: a {@code .litematic} of one air block whose metadata description holds the encoded project
 * (see {@link SyncManifest}). Written and read with Litematica's own classes.
 */
public final class ManifestFile {
    private ManifestFile() {}

    /**
     * Writes {@code dir/<stem>.litematic}.
     *
     * @return the file, or null when Litematica could not write it
     */
    public static Path write(Path dir, String stem, String placementName, String description, String author) {
        AreaSelection area = new AreaSelection();
        area.setName(stem);
        area.addSubRegionBox(new fi.dy.masa.litematica.selection.Box(BlockPos.ZERO, BlockPos.ZERO, "manifest"), false);
        area.setExplicitOrigin(BlockPos.ZERO);
        LitematicaSchematic s = LitematicaSchematic.createEmptySchematic(area, author);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        SchematicMetadata m = s.getMetadata();
        m.setName(placementName);
        m.setDescription(description);
        m.setTotalBlocks(0);
        m.setTimeCreated(now);
        m.setTimeModified(now);
        try {
            if (!s.writeToFile(dir, stem, true)) return null;
        } catch (RuntimeException e) {
            return null;
        }
        Path f = ShapeSaver.fileFor(dir, stem);
        return f != null && java.nio.file.Files.isRegularFile(f) ? f : null;
    }

    /** The description of a manifest file, or null when it cannot be read. */
    public static String readDescription(Path file) {
        Path dir = file.getParent();
        if (dir == null) return null;
        try {
            SchematicMetadata m = LitematicaSchematic.readMetadataFromFile(dir, file.getFileName().toString());
            return m == null ? null : m.getDescription();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
