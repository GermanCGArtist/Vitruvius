package dev.vitruvius;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.liskov.core.LiskovDrives;
import dev.liskov.core.vcs.VcsCommit;
import dev.liskov.core.vcs.VcsStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiskovHistoryTest {
    @TempDir
    Path root;

    private Path local() {
        return root.resolve("liskov/local");
    }

    private Path project(String name) throws IOException {
        Path d = local().resolve("schematics/Projects").resolve(name);
        Files.createDirectories(d.resolve("files/sub"));
        Files.createDirectories(d.resolve(".cache/derived"));
        Files.writeString(d.resolve("project.vproj"), "{\"format\":1,\"name\":\"" + name + "\"}");
        Files.writeString(d.resolve("files/a.litematic"), "A");
        Files.writeString(d.resolve("files/sub/b.lasm"), "B");
        Files.writeString(d.resolve("files/sub/b.lasm.tmp"), "half");
        Files.writeString(d.resolve(".cache/derived/x.litematic"), "derived");
        return d;
    }

    @Test
    void theRepoPathIsTheVprojOnTheLocalDrive() throws IOException {
        Path p = project("Castle");
        assertEquals("schematics/Projects/Castle/project.vproj", LiskovHistory.repoPath(local(), p));
        assertNull(LiskovHistory.repoPath(local(), root.resolve("elsewhere/Castle")), "outside the drive: no history");
    }

    @Test
    void aSnapshotHoldsTheProjectFileAndItsFilesButNotTheCaches() throws IOException {
        Path p = project("Castle");
        VcsStore vcs = new VcsStore(root.resolve("liskov/.liskov-vcs"));
        VcsCommit c = LiskovHistory.snapshot(vcs, local(), p, "u", "Tester", "Castle", true);
        assertNotNull(c);
        assertEquals(List.of("project.vproj", "files/a.litematic", "files/sub/b.lasm"), List.copyOf(c.tree.keySet()));
        assertFalse(c.autoSave);
        assertEquals("Save Castle", c.message);

        String repoId = VcsStore.repoIdFor("schematics/Projects/Castle/project.vproj");
        Map<String, byte[]> back = vcs.readCommitFiles(LiskovDrives.LOCAL_DRIVE_ID, repoId, c.commitId);
        assertEquals("A", new String(back.get("files/a.litematic")));
    }

    @Test
    void unchangedSavesAddNoVersions() throws IOException {
        Path p = project("Castle");
        VcsStore vcs = new VcsStore(root.resolve("liskov/.liskov-vcs"));
        String repoId = VcsStore.repoIdFor("schematics/Projects/Castle/project.vproj");
        assertNotNull(LiskovHistory.snapshot(vcs, local(), p, "u", "T", "Castle", true));
        assertNull(LiskovHistory.snapshot(vcs, local(), p, "u", "T", "Castle", true), "a second Ctrl S without changes");
        LiskovHistory.snapshot(vcs, local(), p, "u", "T", "Castle", false);   // Liskov folds an identical autosave
        assertEquals(1, vcs.loadCommits(LiskovDrives.LOCAL_DRIVE_ID, repoId).size());

        Files.writeString(p.resolve("files/a.litematic"), "A2");
        VcsCommit auto = LiskovHistory.snapshot(vcs, local(), p, "u", "T", "Castle", false);
        assertTrue(auto.autoSave);
        assertEquals("Autosave", auto.message);
        assertEquals(2, vcs.loadCommits(LiskovDrives.LOCAL_DRIVE_ID, repoId).size());
    }

    @Test
    void aFolderWithoutAVprojIsNotRecorded() throws IOException {
        Path p = project("Old");
        Files.move(p.resolve("project.vproj"), p.resolve("project.json"));
        assertNull(LiskovHistory.snapshot(new VcsStore(root.resolve("vcs")), local(), p, "u", "T", "Old", true));
    }
}
