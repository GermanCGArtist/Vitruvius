package dev.vitruvius;

import dev.liskov.core.LiskovFs;
import dev.liskov.core.LiskovMigration;
import dev.vitruvius.editor.io.ProjectLayout;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where Vitruvius keeps its files, on Liskov's Drives filesystem:
 * <ul>
 *   <li>schematics and projects: {@code <game>/liskov/local/schematics/} (projects in {@code Projects/<name>/project.vproj});
 *       Liskov points Litematica at the same folder.
 *   <li>Vitruvius's own settings (editor-session.json, editor-loose/, folders.json, shapes.json, ...):
 *       {@code <game>/liskov/local/vitruvius/config/}.
 * </ul>
 * Liskov copies the old {@code <game>/schematics/} and {@code config/vitruvius/} there once (and renames each
 * {@code project.json} to {@code project.vproj}); the old folders stay as they were, as a backup.
 * {@link #remapLegacy} sends paths remembered before the move (open projects, Loose files) to the copies.
 */
public final class VitruviusPaths {
    private VitruviusPaths() {}

    /**
     * Liskov's filesystem, set up (and the one-time copy of the old folders done) if Liskov's own client entrypoint
     * has not run yet; the entrypoint order of mods is not fixed, and Vitruvius reads its settings at start.
     */
    public static synchronized LiskovFs fs() {
        if (LiskovFs.isInitialized()) return LiskovFs.get();
        try {
            LiskovFs fs = LiskovFs.init(FabricLoader.getInstance().getGameDir(), FabricLoader.getInstance().getConfigDir());
            LiskovMigration.migrateLegacyOnce(fs);
            return fs;
        } catch (IOException e) {
            throw new IllegalStateException("cannot set up Liskov's filesystem: " + e.getMessage(), e);
        }
    }

    /** The schematics folder: {@code <game>/liskov/local/schematics}. */
    public static Path schematicsDir() {
        return fs().schematicsDir().toAbsolutePath().normalize();
    }

    /** Vitruvius's settings folder: {@code <game>/liskov/local/vitruvius/config}. */
    public static Path configDir() {
        return fs().vitruviusDir().resolve("config").toAbsolutePath().normalize();
    }

    /** The schematics folder used before Liskov: {@code <game>/schematics}. */
    public static Path legacySchematicsDir() {
        return fs().gameDir().resolve("schematics").toAbsolutePath().normalize();
    }

    /**
     * A remembered absolute path into the old {@code <game>/schematics} folder, sent to the same place in the
     * Liskov schematics folder when that exists there (a project folder, a project file under either name, a
     * schematic). Anything else, and paths whose copy does not exist, are returned unchanged.
     */
    public static Path remapLegacy(Path path) {
        return remap(path, legacySchematicsDir(), schematicsDir());
    }

    /** {@link #remapLegacy} with the folders given (for tests). */
    public static Path remap(Path path, Path legacyRoot, Path newRoot) {
        if (path == null || !path.isAbsolute()) return path;
        Path p = path.normalize();
        Path from = legacyRoot.toAbsolutePath().normalize(), to = newRoot.toAbsolutePath().normalize();
        if (from.equals(to) || !p.startsWith(from)) return path;
        Path moved = ProjectLayout.existingProjectFile(to.resolve(from.relativize(p).toString()).normalize());
        return Files.exists(moved) ? moved : path;
    }
}
