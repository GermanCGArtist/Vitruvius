package dev.vitruvius.shape.mc;

import dev.vitruvius.shape.VoxelResult;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.malilib.util.FileNameUtils;
import net.minecraft.core.BlockPos;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Generate: writes the .litematic and turns the ghost into a normal placement. Render thread only. */
public final class ShapeSaver {
    private ShapeSaver() {}

    private static final String EXTENSION = ".litematic";

    /** Litematica's schematics folder, the root every save folder lives in. */
    public static Path root() {
        return DataManager.getSchematicsBaseDirectory().toAbsolutePath().normalize();
    }

    /** The folder for a relative path from shapes.json: an existing folder inside the root, else the root. */
    public static Path resolveFolder(String relative) {
        Path root = root();
        if (relative == null || relative.isBlank()) return root;
        try {
            Path p = root.resolve(relative).normalize();
            return p.startsWith(root) && Files.isDirectory(p) ? p : root;
        } catch (RuntimeException e) {   // InvalidPathException
            return root;
        }
    }

    /** folder relative to the root, "/" separated, "" for the root. */
    public static String relative(Path folder) {
        Path root = root();
        Path f = folder.toAbsolutePath().normalize();
        return f.startsWith(root) ? root.relativize(f).toString().replace(File.separatorChar, '/') : "";
    }

    /** folder for display: "(schematics)" or "(schematics)/sub/dir". */
    public static String display(Path folder) {
        String rel = relative(folder);
        return rel.isEmpty() ? "(schematics)" : "(schematics)/" + rel;
    }

    /** The file the name would be written to in the schematics folder (".litematic" added). */
    public static Path fileFor(String name) {
        return fileFor(root(), name);
    }

    /** The file the name would be written to in folder (".litematic" added). */
    public static Path fileFor(Path folder, String name) {
        // the same steps LitematicaSchematic.writeToFile takes, so this is exactly the file it writes
        String file = FileNameUtils.generateSimpleUnicodeSafeFileName(name.trim());
        if (!file.endsWith(EXTENSION)) file += EXTENSION;
        return folder.resolve(FileNameUtils.generateSafeFileName(file)).normalize();
    }

    /**
     * Writes result as fileFor(name) (overwrite only when allowed), loads it, adds a normal placement at origin
     * named name, removes the ghost and selects the new placement. Returns null on success, else an error message
     * for the screen (e.g. "File exists", invalid block, write failed).
     */
    public static String save(VoxelResult result, String name, BlockPos origin, boolean overwrite) {
        return save(result, root(), name, origin, overwrite);
    }

    /** Like save(result, name, origin, overwrite), into folder (inside the schematics folder; created when missing). */
    public static String save(VoxelResult result, Path folder, String name, BlockPos origin, boolean overwrite) {
        if (name == null || name.isBlank()) return "Name is empty";
        Path dir = folder.toAbsolutePath().normalize();
        if (!dir.startsWith(root())) return "Folder is outside the schematics folder";
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return "Cannot create folder: " + e.getMessage();
        }
        Path file = fileFor(dir, name);
        if (Files.exists(file) && !overwrite) return "File exists";

        LitematicaSchematic schematic;
        try {
            schematic = ShapeSchematics.build(result, name.trim());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        try {
            if (!schematic.writeToFile(dir, name.trim(), overwrite)) return "Write failed";
        } catch (RuntimeException e) {
            return "Write failed: " + e.getMessage();
        }

        // an older copy of this file may still be loaded (and would be handed back instead of the new file);
        // removing it also removes the placements that used it
        SchematicHolder holder = SchematicHolder.getInstance();
        for (LitematicaSchematic old : new ArrayList<>(holder.getAllSchematics())) {
            if (file.equals(old.getFile())) holder.removeSchematic(old);
        }
        LitematicaSchematic loaded = holder.getOrLoad(file);
        if (loaded == null) return "Could not load " + file.getFileName();

        ShapePreview.clear();
        SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
        SchematicPlacement placement = SchematicPlacement.createFor(loaded, origin, name.trim(), true, true);
        mgr.addSchematicPlacement(placement, false);
        mgr.setSelectedSchematicPlacement(placement);
        return null;
    }
}
