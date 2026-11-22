package dev.vitruvius.editor.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NioFileSystemPortTest {
    @TempDir
    Path dir;

    @Test
    void basicOperationsTrashAndRestore() throws Exception {
        NioFileSystemPort fs = new NioFileSystemPort(dir);
        fs.writeString("Projects/P/files/a.txt", "hello");
        assertTrue(fs.exists("Projects/P/files/a.txt"));
        assertEquals("hello", fs.readString("Projects/P/files/a.txt"));
        fs.copy("Projects/P/files/a.txt", "Projects/P/files/b.txt");
        assertThrows(FileAlreadyExistsException.class, () -> fs.copy("Projects/P/files/a.txt", "Projects/P/files/b.txt"));
        fs.move("Projects/P/files/b.txt", "Projects/P/files/sub/c.txt");
        assertEquals(List.of("Projects/P/files/a.txt", "Projects/P/files/sub"), fs.list("Projects/P/files"));

        String token = fs.moveToTrash("Projects/P/files/a.txt");
        assertFalse(fs.exists("Projects/P/files/a.txt"));
        List<String> trash = fs.list("Projects/P/.trash");
        assertEquals(1, trash.size());
        assertTrue(Files.exists(dir.resolve(trash.get(0))));   // still on disk: nothing is hard-deleted
        fs.restoreFromTrash(token);
        assertEquals("hello", fs.readString("Projects/P/files/a.txt"));
        assertTrue(fs.list("Projects/P/.trash").isEmpty());
    }

    @Test
    void pathsOutsideTheRootAreRefused() {
        NioFileSystemPort fs = new NioFileSystemPort(dir);
        assertThrows(IllegalArgumentException.class, () -> fs.exists("../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> fs.writeString("Projects/../../x.txt", "no"));
        assertThrows(IllegalArgumentException.class, () -> fs.copy("a", "/etc/x"));
        assertThrows(IllegalArgumentException.class, () -> fs.readString("C:/Windows/win.ini"));
        assertFalse(Files.exists(dir.getParent().resolve("x.txt")));
    }

    @Test
    void aCommandRunsAndUndoesOnTheRealFileSystem() throws Exception {
        NioFileSystemPort fs = new NioFileSystemPort(dir);
        Scene scene = new Scene();
        ProjectNode p = new ProjectNode("P");
        p.setPath("Projects/P");
        scene.openProject(p);
        ObjectNode o = new ObjectNode("o", "files/o.litematic");
        o.setTransform(Transform.IDENTITY.withPos(new Vec3i(1, 2, 3)));
        p.addChild(o);
        fs.writeString("Projects/P/files/o.litematic", "blocks");
        UndoStack stack = new UndoStack(new EditContext(scene, fs, null));

        Duplicate dup = new Duplicate(List.of(o.id()), new Vec3i(5, 0, 0));
        assertTrue(stack.execute(dup).isEmpty());
        assertEquals("blocks", Files.readString(dir.resolve("Projects/P/files/o 2.litematic")));
        stack.undo();
        assertFalse(Files.exists(dir.resolve("Projects/P/files/o 2.litematic")));
        try (var s = Files.list(dir.resolve("Projects/P/.trash"))) {
            assertEquals(1, s.count());
        }
        assertEquals(1, p.children().size());
        stack.redo();
        assertEquals("blocks", Files.readString(dir.resolve("Projects/P/files/o 2.litematic")));
        assertEquals(2, p.children().size());
    }

    @Test
    void openProjectOutsideProfileSchematicsFolderWorksAndStillBlocksUnrelatedPaths() throws Exception {
        Path currentSchem = dir.resolve("profile-26.3").resolve("schematics");
        Path otherProject = dir.resolve("Bestes_projekt_1.26.0").resolve("schematics").resolve("Projects").resolve("New project 3");
        Files.createDirectories(currentSchem);
        Files.createDirectories(otherProject.resolve("files"));
        Files.writeString(otherProject.resolve("files").resolve("bamboo_farm.litematic"), "bamboo");

        Scene scene = new Scene();
        ProjectNode p = new ProjectNode("New project 3");
        p.setPath(otherProject.toAbsolutePath().normalize().toString());
        scene.openProject(p);
        ObjectNode o = new ObjectNode("bamboo_farm", "files/bamboo_farm.litematic");
        o.setTransform(Transform.IDENTITY.withPos(new Vec3i(1, 2, 3)));
        p.addChild(o);

        NioFileSystemPort fs = new NioFileSystemPort(currentSchem, () -> List.of(otherProject));
        EditContext ctx = new EditContext(scene, fs, null).setPortRoot(currentSchem);
        UndoStack stack = new UndoStack(ctx);

        Duplicate dup = new Duplicate(List.of(o.id()), new Vec3i(4, 0, 0));
        assertTrue(stack.execute(dup).isEmpty());
        assertEquals("bamboo", Files.readString(otherProject.resolve("files").resolve("bamboo_farm 2.litematic")));

        stack.undo();
        assertFalse(Files.exists(otherProject.resolve("files").resolve("bamboo_farm 2.litematic")));
        try (var s = Files.list(otherProject.resolve(".trash"))) {
            assertEquals(1, s.count());
        }

        // Unrelated paths outside both root and allowed open projects are still refused
        assertThrows(IllegalArgumentException.class, () -> fs.exists("../../unrelated/secret.txt"));
    }

    @Test
    void resizeShapeDoesNotGrowFilenameAcrossRepeatedEdits() throws Exception {
        NioFileSystemPort fs = new NioFileSystemPort(dir);
        Scene scene = new Scene();
        ProjectNode p = new ProjectNode("P");
        p.setPath("Projects/P");
        scene.openProject(p);
        ObjectNode o = new ObjectNode("shape", "files/a61fc7d3_40x7x14 2_40x8x14 2_40x9x14.lit.litematic");
        p.addChild(o);
        fs.writeString("Projects/P/files/a61fc7d3_40x7x14 2_40x8x14 2_40x9x14.lit.litematic", "v0");
        UndoStack stack = new UndoStack(new EditContext(scene, fs, null));

        for (int i = 1; i <= 15; i++) {
            String tmp = ResizeShape.CACHE_DIR + "/tmp" + i + ".litematic";
            fs.writeString(tmp, "v" + i);
            // keep a collision file around on the same size so uniqueName appends " 2"
            if (i == 2) fs.writeString("Projects/P/files/a61fc7d3_40x9x14.litematic", "collision");
            assertTrue(stack.execute(new ResizeShape(o.id(), tmp, Vec3i.ZERO, "40x9x14", true)).isEmpty());
            assertTrue(o.file().length() < 40, "filename grew unexpectedly: " + o.file());
            assertTrue(o.file().endsWith(".litematic"));
            assertFalse(o.file().contains(".lit.litematic"));
        }
    }
}
