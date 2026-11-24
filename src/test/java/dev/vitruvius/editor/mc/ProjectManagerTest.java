package dev.vitruvius.editor.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.Migration1x;
import dev.vitruvius.editor.io.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectManagerTest {
    @TempDir
    Path root;

    Path config, schematics;
    ProjectManager.Env env;

    @BeforeEach
    void setUp() throws IOException {
        config = root.resolve("config");
        schematics = root.resolve("schematics");
        Files.createDirectories(config.resolve("vitruvius"));
        Files.createDirectories(schematics);
        env = new ProjectManager.Env() {
            @Override
            public Path configDir() {
                return config.resolve("vitruvius");
            }

            @Override
            public Path schematicsDir() {
                return schematics;
            }

            @Override
            public Vec3i playerPos() {
                return new Vec3i(3, 64, -5);
            }
        };
    }

    private ProjectManager manager(String world) {
        ProjectManager pm = new ProjectManager(env);
        pm.enterWorld(world, "minecraft:overworld", List.of(), id -> null);
        return pm;
    }

    @Test
    void newProjectCreatesTheFolderAndOpensIt() throws IOException {
        ProjectManager pm = manager("sp:w");
        ProjectNode p = pm.newProject("Castle");
        Path dir = schematics.resolve("Projects").resolve("Castle");
        assertTrue(Files.exists(ProjectLayout.projectFile(dir)));
        assertTrue(Files.isDirectory(ProjectLayout.filesDir(dir)));
        assertEquals(dir.toAbsolutePath().normalize().toString(), p.path());
        assertEquals("sp:w", p.world());
        assertEquals("minecraft:overworld", p.dim());
        assertEquals(new Vec3i(3, 64, -5), p.origin());
        assertEquals(List.of(p), pm.projects());
        assertSame(p, pm.scene().activeProject());

        ProjectNode second = pm.newProject("Castle");
        assertEquals("Castle", second.name());
        assertTrue(Files.isDirectory(schematics.resolve("Projects").resolve("Castle 2")), "a taken folder name is numbered");
        assertEquals(2, pm.projects().size());
    }

    @Test
    void notInAWorldNothingCanBeOpened() {
        ProjectManager pm = new ProjectManager(env);
        assertThrows(IllegalStateException.class, () -> pm.newProject("x"));
    }

    @Test
    void saveWritesAndCloseSavesFirstAndCallsTheHook() throws IOException {
        ProjectManager pm = manager("sp:w");
        ProjectNode p = pm.newProject("A");
        List<ProjectNode> released = new ArrayList<>();
        pm.setCloseHook(released::add);
        ObjectNode o = new ObjectNode("o", "files/o.litematic");
        p.addChild(o);
        assertTrue(pm.isDirty(p.id()));
        assertTrue(pm.close(p.id()));
        assertEquals(List.of(p), released);
        assertTrue(pm.projects().isEmpty());
        assertNull(pm.scene().activeProject());
        assertFalse(pm.close(p.id()));

        ProjectNode back = pm.open(Path.of(p.path()));
        assertEquals(1, back.children().size());
        assertEquals("o", back.children().get(0).name());
        assertSame(back, pm.open(Path.of(p.path()).resolve("project.json")), "opening an open project returns it");
        assertEquals(1, pm.projects().size());
    }

    @Test
    void autosaveWritesChangedProjectsEvery30Seconds() throws IOException {
        ProjectManager pm = manager("sp:w");
        ProjectNode p = pm.newProject("A");
        Path file = ProjectLayout.projectFile(Path.of(p.path()));
        long before = Files.getLastModifiedTime(file).toMillis();
        p.addChild(new FolderNode("f"));
        for (int i = 0; i < ProjectManager.AUTOSAVE_TICKS - 1; i++) pm.tick();
        assertTrue(pm.isDirty(p.id()), "not yet");
        pm.tick();
        assertFalse(pm.isDirty(p.id()));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"f\""));
        assertTrue(Files.getLastModifiedTime(file).toMillis() >= before);
    }

    @Test
    void leavingTheWorldSavesEverything() throws IOException {
        ProjectManager pm = manager("sp:w");
        ProjectNode p = pm.newProject("A");
        p.addChild(new FolderNode("added"));
        pm.leaveWorld();
        assertTrue(Files.readString(ProjectLayout.projectFile(Path.of(p.path())), StandardCharsets.UTF_8).contains("added"));
        assertFalse(pm.inWorld());
        assertTrue(pm.projects().isEmpty());
    }

    @Test
    void theOpenProjectsComeBackWhenTheWorldIsJoinedAgain() {
        ProjectManager pm = manager("sp:w");
        ProjectNode a = pm.newProject("A"), b = pm.newProject("B");
        pm.setActive(b.id());
        pm.leaveWorld();

        ProjectManager again = manager("sp:w");
        assertEquals(List.of("A", "B"), again.projects().stream().map(ProjectNode::name).toList());
        assertEquals(b.id(), again.scene().activeProject().id());

        ProjectManager other = manager("sp:other");
        assertTrue(other.projects().isEmpty(), "remembered per world");
        assertNotNull(a);
    }

    @Test
    void aClosedProjectIsNotReopened() {
        ProjectManager pm = manager("sp:w");
        ProjectNode a = pm.newProject("A");
        pm.newProject("B");
        pm.close(a.id());
        pm.leaveWorld();
        assertEquals(List.of("B"), manager("sp:w").projects().stream().map(ProjectNode::name).toList());
    }

    @Test
    void aProjectWithoutAWorldIsBoundToTheCurrentOne() throws IOException {
        ProjectManager pm = manager("sp:w");
        ProjectNode p = pm.newProject("A");
        p.setWorld("");
        pm.save(p.id());
        pm.close(p.id());
        ProjectNode back = pm.open(Path.of(p.path()));
        assertEquals("sp:w", back.world());
        assertTrue(pm.isDirty(back.id()), "the binding is saved by the next autosave");
    }

    @Test
    void firstEntryMigratesThe1xPlacementsIntoLooseAndRemembersIt() throws IOException {
        Path folders = config.resolve("vitruvius").resolve("folders.json");
        String foldersText = "{\"folders\":{},\"placements\":{},\"hiddenByFolder\":[],\"placementColors\":{}}";
        Files.writeString(folders, foldersText);
        String id = "11111111-2222-3333-4444-555555555555";
        List<String> asked = new ArrayList<>();
        ProjectManager pm = new ProjectManager(env);
        pm.enterWorld("sp:w", "minecraft:overworld", List.of(id), pid -> {
            asked.add(pid);
            return new Migration1x.PlacementInfo("Tower", "C:/schem/tower.litematic", new Vec3i(1, 2, 3), Rot.CW_90, Mirror.NONE);
        });
        assertTrue(pm.migrated());
        assertEquals(1, pm.scene().loose().children().size());
        ObjectNode o = (ObjectNode) pm.scene().loose().children().get(0);
        assertEquals("Tower", o.name());
        assertEquals(id, o.placementIds().get(ObjectNode.BASE_CELL));
        assertEquals(new Vec3i(1, 2, 3), o.transform().pos());
        assertFalse(o.hidden());
        assertEquals(foldersText, Files.readString(folders), "the 1.x files stay as they were");

        // a later session: the flag is set, Loose comes from its own file, the placements are not asked again
        pm.leaveWorld();
        asked.clear();
        ProjectManager later = new ProjectManager(env);
        later.enterWorld("sp:w", "minecraft:overworld", List.of(id), pid -> {
            asked.add(pid);
            return null;
        });
        assertTrue(asked.isEmpty());
        assertEquals(1, later.scene().loose().children().size());
        ObjectNode back = (ObjectNode) later.scene().loose().children().get(0);
        assertEquals(o.id(), back.id());
        assertEquals(id, back.placementIds().get(ObjectNode.BASE_CELL));
        assertTrue(o.transform().equals(back.transform()));
    }

    @Test
    void hiddenFoldersOf1xDoNotHideTheMigratedNodes() throws IOException {
        Files.writeString(config.resolve("vitruvius").resolve("folders.json"),
                "{\"folders\":{\"f1\":{\"name\":\"F\",\"hidden\":true,\"open\":true}},\"placements\":{\"p1\":\"f1\"}}");
        ProjectManager pm = new ProjectManager(env);
        pm.enterWorld("sp:w", "d", List.of("p1"),
                pid -> new Migration1x.PlacementInfo("P", "C:/x.litematic", Vec3i.ZERO, Rot.NONE, Mirror.NONE));
        assertEquals(1, pm.scene().loose().children().size());
        assertFalse(pm.scene().loose().children().get(0).hidden());
        assertFalse(pm.scene().allNodes().stream().anyMatch(n -> n.hidden()));
    }

    @Test
    void aBrokenSessionFileIsIgnored() throws IOException {
        Files.writeString(config.resolve("vitruvius").resolve("editor-session.json"), "{ not json");
        ProjectManager pm = manager("sp:w");
        assertTrue(pm.projects().isEmpty());
        pm.newProject("A");
        Map<?, ?> parsed = new com.google.gson.Gson().fromJson(Files.readString(config.resolve("vitruvius").resolve("editor-session.json")), Map.class);
        assertNotNull(parsed.get("worlds"));
    }

    @Test
    void anOldProjectJsonIsReadAndSavedAsVproj() throws IOException {
        ProjectManager pm = manager("sp:w");
        ProjectNode made = pm.newProject("Old");
        Path dir = Path.of(made.path());
        pm.close(made.id());
        // as an older Vitruvius left it: only project.json
        Files.move(dir.resolve("project.vproj"), dir.resolve("project.json"));

        ProjectNode p = pm.open(dir);
        assertEquals("Old", p.name());
        p.addChild(new FolderNode("f"));
        pm.saveAll();
        assertTrue(Files.isRegularFile(dir.resolve("project.vproj")), "saved under the new name");
        assertFalse(Files.exists(dir.resolve("project.json")), "the stale old file is gone from the folder");
        assertTrue(Files.isRegularFile(dir.resolve(".trash").resolve("project.json")), "and kept in .trash");
        assertTrue(Files.readString(dir.resolve("project.vproj")).contains("\"f\""));
    }

    @Test
    void reopenedProjectsGoThroughTheEnvRemap() throws IOException {
        ProjectManager pm = manager("sp:w");
        ProjectNode p = pm.newProject("Moved");
        Path real = Path.of(p.path());
        pm.leaveWorld();
        // the session remembers an old location; the env knows where it went
        Path old = root.resolve("old-schematics").resolve("Projects").resolve("Moved");
        Path session = config.resolve("vitruvius").resolve("editor-session.json");
        Files.writeString(session, Files.readString(session).replace(
                real.toString().replace("\\", "\\\\"), old.toString().replace("\\", "\\\\")));
        ProjectManager.Env moved = new ProjectManager.Env() {
            @Override public Path configDir() { return config.resolve("vitruvius"); }
            @Override public Path schematicsDir() { return schematics; }
            @Override public Path remap(Path path) { return path.equals(old) ? real : path; }
        };
        ProjectManager again = new ProjectManager(moved);
        again.enterWorld("sp:w", "minecraft:overworld", List.of(), id -> null);
        assertEquals(1, again.projects().size());
        assertEquals(real.toString(), again.projects().get(0).path());
        assertSame(again.projects().get(0), again.scene().activeProject());
    }

    @Test
    void theHistoryHookHearsAutosavesAndNamedSavesButNotQuickSaves() throws IOException {
        ProjectManager pm = manager("sp:w");
        List<String> heard = new ArrayList<>();
        pm.setHistoryHook((p, dir, explicit) -> heard.add(p.name() + (explicit ? ":named" : ":auto")));
        ProjectNode p = pm.newProject("A");
        p.addChild(new FolderNode("f"));
        pm.saveChanged();
        assertEquals(List.of(), heard, "the save a second after an edit records nothing");
        p.addChild(new FolderNode("g"));
        for (int i = 0; i < ProjectManager.AUTOSAVE_TICKS; i++) pm.tick();
        assertEquals(List.of("A:auto"), heard);
        pm.checkpoint();
        assertEquals(List.of("A:auto", "A:named"), heard);
        pm.close(p.id());
        assertEquals(List.of("A:auto", "A:named", "A:auto"), heard);
    }

    @Test
    void revertToDiskDropsUnsavedStateAndReadsTheFileAgain() throws IOException {
        ProjectManager pm = manager("sp:w");
        ProjectNode p = pm.newProject("A");
        p.addChild(new FolderNode("kept"));
        pm.saveAll();
        p.addChild(new FolderNode("unsaved"));
        Path file = ProjectLayout.projectFile(Path.of(p.path()));
        String onDisk = Files.readString(file);

        ProjectNode fresh = pm.revertToDisk(p.id());
        assertNotNull(fresh);
        assertEquals(p.id(), fresh.id());
        assertEquals(List.of("kept"), fresh.children().stream().map(n -> n.name()).toList());
        assertSame(fresh, pm.scene().activeProject(), "stays active");
        assertEquals(1, pm.projects().size());
        assertFalse(pm.isDirty(p.id()));
        assertEquals(onDisk, Files.readString(file), "nothing was written over the file");
    }
}
