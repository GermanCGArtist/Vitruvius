package dev.vitruvius.editor.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.vitruvius.editor.core.Ids;
import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.AssemblyIo;
import dev.vitruvius.editor.io.Migration1x;
import dev.vitruvius.editor.io.ProjectIo;
import dev.vitruvius.editor.io.ProjectLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The open projects of the world the player is in, and the Loose node. No Minecraft types: the folders come from an
 * {@link Env}, so it is tested against temporary folders.
 *
 * <ul>
 *   <li>{@link #newProject}, {@link #open}, {@link #close}, {@link #save}, {@link #saveAll}, {@link #setActive}.
 *   <li>Autosave: {@link #tick} saves the projects (and Loose) that changed every {@link #AUTOSAVE_TICKS} ticks (30 s);
 *       "changed" means the saved text differs from what is on disk, so no command has to flag anything.
 *   <li>The open projects are remembered per world in {@code <config>/editor-session.json} and opened again
 *       by {@link #enterWorld} ({@code <config>} is {@link Env#configDir()}: Liskov's {@code liskov/local/vitruvius/config}).
 *   <li>Loose is not part of a project: it is kept per world in {@code <config>/editor-loose/<world>.lasm}.
 *       The first time a world is entered, it is built from folders.json and the live placements
 *       ({@link Migration1x}); the file is left as it is.
 * </ul>
 */
public final class ProjectManager {
    /** 30 seconds of ticks. */
    public static final int AUTOSAVE_TICKS = 600;

    public interface Env {
        /** Vitruvius's own config folder (editor-session.json, editor-loose/, folders.json). */
        Path configDir();

        /** The schematics folder (projects live in {@code Projects/} in it). */
        Path schematicsDir();

        /**
         * Maps a remembered path that may point into the folders used before Liskov (the old {@code schematics/})
         * to where that project is now. Default: unchanged.
         */
        default Path remap(Path path) {
            return path;
        }

        /** Where new projects are anchored: the player's block position. */
        default Vec3i playerPos() {
            return Vec3i.ZERO;
        }
    }

    private final Env env;
    private Scene scene = new Scene();
    private String world;
    private String dim = "minecraft:overworld";
    private final Map<UUID, String> saved = new HashMap<>();
    private String savedLoose;
    private Consumer<ProjectNode> closeHook = p -> {};
    private Consumer<ProjectNode> saveHook = p -> {};
    private HistoryHook historyHook = (p, dir, explicit) -> {};
    private int ticks;

    public ProjectManager(Env env) {
        this.env = env;
    }

    public Scene scene() {
        return scene;
    }

    /** The key of the world entered, or null when in none. */
    public String world() {
        return world;
    }

    public String dim() {
        return dim;
    }

    public boolean inWorld() {
        return world != null;
    }

    public void setDimension(String dim) {
        this.dim = dim;
    }

    /** Called after a project was written (derived-file cache cleanup). */
    public void setSaveHook(Consumer<ProjectNode> hook) {
        this.saveHook = hook == null ? p -> {} : hook;
    }

    /**
     * Told after a project was written, for version history: {@code explicit} for a save the player asked for
     * ({@link #checkpoint}), false for autosave, close and world changes. The quick saves a second after each edit
     * ({@link #saveChanged}) are not reported.
     */
    public interface HistoryHook {
        void saved(ProjectNode project, Path projectDir, boolean explicit);
    }

    public void setHistoryHook(HistoryHook hook) {
        this.historyHook = hook == null ? (p, dir, explicit) -> {} : hook;
    }

    /** Called with a project just before it is closed (the reconciler removes its placements). */
    public void setCloseHook(Consumer<ProjectNode> hook) {
        this.closeHook = hook == null ? p -> {} : hook;
    }

    // ------------------------------------------------------------------------------------------ world

    /**
     * Enters a world: builds the Loose node (migrating the 1.x configs the first time) and opens the projects this
     * world had open last time. Call it once the placements of the world are loaded.
     *
     * @param existingIds the hash ids of the current placements, for the migration
     * @param lookup      hash id to what the placement holds (null for an unknown id)
     */
    public void enterWorld(String worldKey, String dimension, Collection<String> existingIds, Function<String, Migration1x.PlacementInfo> lookup) {
        saveAll();
        this.world = worldKey;
        this.dim = dimension;
        saved.clear();
        savedLoose = null;
        LooseNode loose = loadLoose(existingIds, lookup);
        scene = new Scene(loose);
        savedLoose = looseText(loose);

        JsonObject w = worldEntry(false);
        if (w != null && w.has("open") && w.get("open").isJsonArray()) {
            for (JsonElement e : w.getAsJsonArray("open")) {
                if (!e.isJsonPrimitive()) continue;
                try {
                    openInternal(env.remap(Path.of(e.getAsString())), false);
                } catch (IOException | RuntimeException ex) {
                    System.out.println("[Vitruvius] cannot reopen project " + e + ": " + ex.getMessage());
                }
            }
            String active = w.has("active") && w.get("active").isJsonPrimitive() ? w.get("active").getAsString() : null;
            if (active != null) {
                for (ProjectNode p : scene.projects()) {
                    if (samePath(Path.of(p.path()), env.remap(Path.of(active)))) scene.setActiveProject(p);
                }
            }
        }
    }

    /** Leaves the world: saves everything and drops the scene (placements are left to Litematica). */
    public void leaveWorld() {
        if (world != null) saveAll();
        scene = new Scene();
        world = null;
        saved.clear();
        savedLoose = null;
    }

    // ------------------------------------------------------------------------------------------ projects

    /** Creates {@code Projects/<name>} (a numbered name if taken), anchored at the player, and opens it. */
    public ProjectNode newProject(String name) {
        return newProject(name, env.playerPos());
    }

    public ProjectNode newProject(String name, Vec3i origin) {
        requireWorld();
        Path root = ProjectLayout.projectsRoot(env.schematicsDir());
        String folder = ProjectLayout.uniqueName(name, n -> Files.exists(root.resolve(n)));
        Path dir = root.resolve(folder).toAbsolutePath().normalize();
        ProjectNode p = new ProjectNode(name == null || name.isBlank() ? folder : name.strip());
        p.setPath(dir.toString());
        p.setWorld(world);
        p.setDim(dim);
        p.setOrigin(origin);
        try {
            Files.createDirectories(ProjectLayout.filesDir(dir));
            ProjectIo.write(p, ProjectLayout.saveFile(dir));
        } catch (IOException e) {
            throw new IllegalStateException("cannot create project " + dir + ": " + e.getMessage(), e);
        }
        saved.put(p.id(), ProjectIo.toJson(p));
        scene.openProject(p);
        remember();
        return p;
    }

    /**
     * Opens a project from its folder or its project file (project.vproj, or an old project.json). Opening one that is open already returns it. A project
     * that belongs to no world yet is bound to the current one.
     */
    public ProjectNode open(Path path) throws IOException {
        requireWorld();
        return openInternal(path, true);
    }

    private ProjectNode openInternal(Path path, boolean remember) throws IOException {
        Path file = Files.isDirectory(path) ? ProjectLayout.projectFile(path) : ProjectLayout.existingProjectFile(path);
        ProjectNode p = ProjectIo.read(file);
        for (ProjectNode o : scene.projects()) if (o.id().equals(p.id())) return o;
        String text = ProjectIo.toJson(p);
        if (p.world().isEmpty()) {
            p.setWorld(world);
            p.setDim(dim);
        }
        saved.put(p.id(), text);   // the binding above is a change that autosave writes
        scene.openProject(p);
        if (remember) remember();
        return p;
    }

    /** Saves and closes a project; its placements are removed (see {@link #setCloseHook}). */
    public boolean close(UUID id) {
        ProjectNode p = find(id);
        if (p == null) return false;
        save(p, Snap.AUTO);
        closeHook.accept(p);
        scene.closeProject(p);
        saved.remove(id);
        remember();
        return true;
    }

    public boolean save(UUID id) {
        ProjectNode p = find(id);
        return p != null && save(p, Snap.NONE);
    }

    /** What a save tells the {@link HistoryHook}. */
    private enum Snap { NONE, AUTO, EXPLICIT }

    /**
     * Once a project is saved as project.vproj, an old project.json next to it is stale: it is moved to the project's
     * .trash (not deleted), so only one project file is left and the explorer shows one project.
     */
    private static void retireLegacyFile(Path dir) {
        Path legacy = dir.resolve(ProjectLayout.LEGACY_PROJECT_FILE);
        if (!Files.isRegularFile(legacy) || !Files.isRegularFile(dir.resolve(ProjectLayout.PROJECT_FILE))) return;
        try {
            Path trash = ProjectLayout.trashDir(dir);
            Files.createDirectories(trash);
            String name = ProjectLayout.uniqueName(ProjectLayout.LEGACY_PROJECT_FILE, n -> Files.exists(trash.resolve(n)));
            Files.move(legacy, trash.resolve(name));
        } catch (IOException e) {
            System.out.println("[Vitruvius] cannot move the old " + legacy + " to .trash: " + e.getMessage());
        }
    }

    private boolean save(ProjectNode p, Snap snap) {
        if (p.path().isEmpty()) return false;
        try {
            Path dir = dirOf(p);
            ProjectIo.write(p, ProjectLayout.saveFile(dir));
            retireLegacyFile(dir);
            saved.put(p.id(), ProjectIo.toJson(p));
            try {
                saveHook.accept(p);
            } catch (RuntimeException e) {
                System.out.println("[Vitruvius] derived cache cleanup failed: " + e);
            }
            if (snap != Snap.NONE) {
                try {
                    historyHook.saved(p, dir, snap == Snap.EXPLICIT);
                } catch (RuntimeException e) {
                    System.out.println("[Vitruvius] version snapshot failed: " + e);
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            System.out.println("[Vitruvius] cannot save project " + p.name() + ": " + e.getMessage());
            return false;
        }
    }

    /** Saves every open project and the Loose node, changed or not. */
    public void saveAll() {
        for (ProjectNode p : scene.projects()) save(p, Snap.AUTO);
        if (world != null) saveLoose();
    }

    /** Saves everything, as {@link #saveAll}, and records a named version of each open project (Ctrl S). */
    public void checkpoint() {
        for (ProjectNode p : scene.projects()) save(p, Snap.EXPLICIT);
        if (world != null) saveLoose();
    }

    /** Saves only what changed since it was last written. */
    public void saveChanged() {
        saveChanged(Snap.NONE);
    }

    private void saveChanged(Snap snap) {
        for (ProjectNode p : scene.projects()) {
            if (!ProjectIo.toJson(p).equals(saved.get(p.id()))) save(p, snap);
        }
        if (world != null && !looseText(scene.loose()).equals(savedLoose)) saveLoose();
    }

    /** Whether the project differs from what is saved. */
    public boolean isDirty(UUID id) {
        ProjectNode p = find(id);
        return p != null && !ProjectIo.toJson(p).equals(saved.get(id));
    }

    /** Call once per client tick; autosaves every {@link #AUTOSAVE_TICKS} ticks. */
    public void tick() {
        if (world == null) return;
        if (++ticks >= AUTOSAVE_TICKS) {
            ticks = 0;
            saveChanged(Snap.AUTO);
        }
    }

    public boolean setActive(UUID id) {
        ProjectNode p = find(id);
        if (p == null) return false;
        scene.setActiveProject(p);
        remember();
        return true;
    }

    /** The folder of a project: its path, made absolute against the schematics folder when it is relative. */
    private Path dirOf(ProjectNode p) {
        Path x = Path.of(p.path());
        return x.isAbsolute() ? x : env.schematicsDir().resolve(x).toAbsolutePath().normalize();
    }

    /**
     * Throws away the open project's state and reads it again from its folder, without saving first: for when its
     * file was replaced on disk (a version restored in Liskov's explorer). The project stays active if it
     * was (it moves to the end of the list). Returns the new node, or null when it is not open or cannot be read (then nothing changes).
     */
    public ProjectNode revertToDisk(UUID id) {
        ProjectNode old = find(id);
        if (old == null || old.path().isEmpty()) return null;
        Path dir = dirOf(old);
        ProjectNode fresh;
        try {
            fresh = ProjectIo.read(ProjectLayout.projectFile(dir));
        } catch (IOException | RuntimeException e) {
            System.out.println("[Vitruvius] cannot read " + dir + " again: " + e.getMessage());
            return null;
        }
        boolean wasActive = scene.activeProject() == old;
        closeHook.accept(old);
        scene.closeProject(old);
        saved.remove(id);
        saved.put(fresh.id(), ProjectIo.toJson(fresh));
        scene.openProject(fresh);
        if (wasActive) scene.setActiveProject(fresh);
        remember();
        return fresh;
    }

    /** The folder of an open project (absolute). */
    public Path projectDir(ProjectNode p) {
        return dirOf(p);
    }

    public ProjectNode find(UUID id) {
        for (ProjectNode p : scene.projects()) if (p.id().equals(id)) return p;
        return null;
    }

    private void requireWorld() {
        if (world == null) throw new IllegalStateException("not in a world");
    }

    // ------------------------------------------------------------------------------------------ Loose and migration

    private LooseNode loadLoose(Collection<String> existingIds, Function<String, Migration1x.PlacementInfo> lookup) {
        JsonObject w = worldEntry(false);
        boolean migrated = w != null && w.has("migrated") && w.get("migrated").isJsonPrimitive() && w.get("migrated").getAsBoolean();
        Path file = looseFile();
        if (migrated) {
            LooseNode loose = new LooseNode(Ids.derived("la1:loose"));
            if (Files.exists(file)) {
                try {
                    for (Node n : AssemblyIo.read(file).nodes) loose.addChild(n);
                } catch (IOException | RuntimeException e) {
                    System.out.println("[Vitruvius] cannot read " + file + ": " + e.getMessage());
                }
            }
            return loose;
        }
        Path cfg = env.configDir();
        try {
            Migration1x.Result r = Migration1x.migrate(cfg.resolve("folders.json"), existingIds, lookup);
            LooseNode loose = r.loose();
            // 1.x hides a folder by switching rendering off (Folders.tick keeps doing that); a hidden node here would
            // remove the placements instead
            for (Node n : loose.subtree()) n.setHidden(false);
            worldEntry(true).addProperty("migrated", true);
            writeSession();
            savedLoose = null;
            this.scene = new Scene(loose);
            saveLoose();
            if (!r.skipped().isEmpty()) System.out.println("[Vitruvius] 1.x migration skipped " + r.skipped().size() + " unknown placement(s)");
            return loose;
        } catch (IOException | RuntimeException e) {
            System.out.println("[Vitruvius] 1.x migration failed, will try again next time: " + e.getMessage());
            return new LooseNode(Ids.derived("la1:loose"));
        }
    }

    private Path looseFile() {
        String key = world == null ? "" : world;
        return env.configDir().resolve("editor-loose")
                .resolve(ProjectLayout.safeName(key) + "-" + Integer.toHexString(key.hashCode()) + ".lasm");
    }

    private static String looseText(LooseNode loose) {
        AssemblyIo.Lasm l = new AssemblyIo.Lasm("Loose");
        l.nodes.addAll(loose.children());
        return AssemblyIo.toJson(l);
    }

    private void saveLoose() {
        try {
            String text = looseText(scene.loose());
            AssemblyIo.Lasm l = new AssemblyIo.Lasm("Loose");
            l.nodes.addAll(scene.loose().children());
            AssemblyIo.write(l, looseFile());
            savedLoose = text;
        } catch (IOException | RuntimeException e) {
            System.out.println("[Vitruvius] cannot save Loose: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------ session file

    private Path sessionFile() {
        return env.configDir().resolve("editor-session.json");
    }

    private JsonObject readSession() {
        Path f = sessionFile();
        if (Files.exists(f)) {
            try {
                JsonElement e = com.google.gson.JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
                if (e.isJsonObject()) return e.getAsJsonObject();
            } catch (IOException | RuntimeException ex) {
                System.out.println("[Vitruvius] cannot read " + f + ": " + ex.getMessage());
            }
        }
        return new JsonObject();
    }

    private JsonObject session;

    private JsonObject session() {
        if (session == null) session = readSession();
        return session;
    }

    private JsonObject worldEntry(boolean create) {
        if (world == null) return null;
        JsonObject s = session();
        JsonObject worlds = s.has("worlds") && s.get("worlds").isJsonObject() ? s.getAsJsonObject("worlds") : null;
        if (worlds == null) {
            if (!create) return null;
            worlds = new JsonObject();
            s.add("worlds", worlds);
        }
        JsonObject w = worlds.has(world) && worlds.get(world).isJsonObject() ? worlds.getAsJsonObject(world) : null;
        if (w == null && create) {
            w = new JsonObject();
            worlds.add(world, w);
        }
        return w;
    }

    /** Writes the open projects of this world (and the active one) to the session file. */
    private void remember() {
        JsonObject w = worldEntry(true);
        if (w == null) return;
        JsonArray open = new JsonArray();
        for (ProjectNode p : scene.projects()) open.add(dirOf(p).toString());
        w.add("open", open);
        ProjectNode a = scene.activeProject();
        if (a != null) w.addProperty("active", dirOf(a).toString());
        else w.remove("active");
        writeSession();
    }

    private void writeSession() {
        Path f = sessionFile();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(session()), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[Vitruvius] cannot write " + f + ": " + e.getMessage());
        }
    }

    /** Whether this world has been migrated from the 1.x configs already. */
    public boolean migrated() {
        JsonObject w = worldEntry(false);
        return w != null && w.has("migrated") && w.get("migrated").isJsonPrimitive() && w.get("migrated").getAsBoolean();
    }

    private static boolean samePath(Path a, Path b) {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    /** The open projects, in order. */
    public List<ProjectNode> projects() {
        return scene.projects();
    }
}
