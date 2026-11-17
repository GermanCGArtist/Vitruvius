package dev.schemarray.editor.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.schemarray.editor.io.ProjectLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Saves the runtime placements of a world ({@link ReconcileCore#runtimeIds()}) in {@code editor-runtime/<world>.json}. */
public final class RuntimeStore {
    private RuntimeStore() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path file(Path configSchemarrayDir, String worldKey) {
        return configSchemarrayDir.resolve("editor-runtime")
                .resolve(ProjectLayout.safeName(worldKey) + "-" + Integer.toHexString(worldKey.hashCode()) + ".json");
    }

    public static Map<String, Map<String, String>> read(Path file) {
        try {
            if (!Files.isRegularFile(file)) return Map.of();
            Map<String, Map<String, String>> m = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<TreeMap<String, TreeMap<String, String>>>() {}.getType());
            return m == null ? Map.of() : m;
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    public static void write(Path file, Map<String, Map<String, String>> ids) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(ids), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            System.out.println("[SchematicArray] cannot save the runtime placements: " + e.getMessage());
        }
    }
}
