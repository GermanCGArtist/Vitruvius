package dev.vitruvius;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VitruviusPathsTest {
    @TempDir
    Path root;

    @Test
    void oldSchematicsPathsGoToTheLiskovCopy() throws IOException {
        Path old = root.resolve("schematics"), now = root.resolve("liskov/local/schematics");
        Path oldProject = old.resolve("Projects/Castle"), newProject = now.resolve("Projects/Castle");
        Files.createDirectories(oldProject.resolve("files"));
        Files.createDirectories(newProject.resolve("files"));
        Files.writeString(oldProject.resolve("project.json"), "{}");
        Files.writeString(newProject.resolve("project.vproj"), "{}");
        Files.writeString(old.resolve("loose.litematic"), "x");
        Files.writeString(now.resolve("loose.litematic"), "x");

        assertEquals(newProject, VitruviusPaths.remap(oldProject, old, now), "a project folder");
        assertEquals(newProject.resolve("project.vproj"), VitruviusPaths.remap(oldProject.resolve("project.json"), old, now),
                "project.json becomes project.vproj, as Liskov renamed it");
        assertEquals(now.resolve("loose.litematic"), VitruviusPaths.remap(old.resolve("loose.litematic"), old, now));
    }

    @Test
    void pathsWithoutACopyOrOutsideStayAsTheyAre() throws IOException {
        Path old = root.resolve("schematics"), now = root.resolve("liskov/local/schematics");
        Files.createDirectories(now);
        Path missing = old.resolve("Projects/Gone");
        assertEquals(missing, VitruviusPaths.remap(missing, old, now), "no copy: the old place");
        Path elsewhere = root.resolve("other/x.litematic");
        assertEquals(elsewhere, VitruviusPaths.remap(elsewhere, old, now));
        assertEquals(Path.of("Projects/Castle"), VitruviusPaths.remap(Path.of("Projects/Castle"), old, now), "relative paths are not touched");
        assertEquals(old.resolve("a"), VitruviusPaths.remap(old.resolve("a"), old, old), "nothing to do when the folders are the same");
    }
}
