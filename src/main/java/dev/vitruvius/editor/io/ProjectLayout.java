package dev.vitruvius.editor.io;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Where things live: {@code schematics/Projects/<name>/project.json}, {@code files/}, {@code .cache/derived/},
 * {@code .trash/}; and file names that are safe on Windows (and so everywhere).
 */
public final class ProjectLayout {
    private ProjectLayout() {}

    public static final String PROJECTS_DIR = "Projects";
    public static final String PROJECT_FILE = "project.json";
    public static final String FILES_DIR = "files";
    public static final String DERIVED_DIR = ".cache/derived";
    public static final String TRASH_DIR = ".trash";
    public static final String ASSEMBLY_EXT = ".lasm";
    public static final String SCHEMATIC_EXT = ".litematic";

    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final int MAX_NAME = 120;

    /** {@code <schematics>/Projects}. */
    public static Path projectsRoot(Path schematicsDir) {
        return schematicsDir.resolve(PROJECTS_DIR);
    }

    /** {@code <schematics>/Projects/<safe name>}. */
    public static Path projectDir(Path schematicsDir, String projectName) {
        return projectsRoot(schematicsDir).resolve(safeName(projectName));
    }

    public static Path projectFile(Path projectDir) {
        return projectDir.resolve(PROJECT_FILE);
    }

    public static Path filesDir(Path projectDir) {
        return projectDir.resolve(FILES_DIR);
    }

    public static Path derivedDir(Path projectDir) {
        return projectDir.resolve(".cache").resolve("derived");
    }

    public static Path trashDir(Path projectDir) {
        return projectDir.resolve(TRASH_DIR);
    }

    /** {@code .cache/derived/<objectId>-<hash>.litematic}. */
    public static Path derivedFile(Path projectDir, java.util.UUID objectId, String hash) {
        return derivedDir(projectDir).resolve(objectId + "-" + safeName(hash) + SCHEMATIC_EXT);
    }

    /**
     * Resolves a file path stored in the model (relative to the project folder, '/' separators) and refuses anything
     * that would leave the folder (absolute paths, {@code ..}).
     *
     * @throws IllegalArgumentException if the path is absolute or escapes {@code base}
     */
    public static Path resolveInside(Path base, String relative) {
        if (relative.isEmpty() || relative.startsWith("/") || relative.startsWith("\\") || relative.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("not a relative path: " + relative);
        }
        Path b = base.toAbsolutePath().normalize();
        Path r = b.resolve(relative).normalize();
        if (!r.startsWith(b) || r.equals(b)) throw new IllegalArgumentException("path leaves the folder: " + relative);
        return r;
    }

    /**
     * A name that is safe as one file or folder name: characters Windows does not allow ({@code < > : " / \ | ? *}
     * and controls) are removed, trailing dots and spaces dropped, reserved device names ({@code CON}, {@code NUL},
     * {@code COM1}, ...; also with an extension) get a leading underscore, an empty result becomes "unnamed", and the
     * length is capped.
     */
    public static String safeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < (name == null ? 0 : name.length()); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127 || "<>:\"/\\|?*".indexOf(c) >= 0) continue;
            sb.append(c);
        }
        String s = sb.toString().strip();
        if (s.length() > MAX_NAME) {
            int lastDot = s.lastIndexOf('.');
            if (lastDot > 0 && s.length() - lastDot <= 16) {
                String ext = s.substring(lastDot);
                int maxStem = Math.max(1, MAX_NAME - ext.length());
                s = s.substring(0, Math.min(lastDot, maxStem)).strip() + ext;
            } else {
                s = s.substring(0, MAX_NAME).strip();
            }
        }
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return "unnamed";
        int dot = s.indexOf('.');
        String stem = dot < 0 ? s : s.substring(0, dot);
        if (RESERVED.contains(stem.strip().toUpperCase(Locale.ROOT))) s = "_" + s;
        return s;
    }

    /** {@code base}, or {@code base 2}, {@code base 3}, ... until {@code exists} says no. The extension, if any, stays last. */
    public static String uniqueName(String base, Predicate<String> exists) {
        String name = safeName(base);
        if (!exists.test(name)) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            String c = stem + " " + n + ext;
            if (!exists.test(c)) return c;
        }
    }
}
