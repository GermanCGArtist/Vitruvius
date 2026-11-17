package dev.schemarray.editor.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectLayoutTest {
    @Test
    void paths() {
        Path schematics = Path.of("schematics");
        Path dir = ProjectLayout.projectDir(schematics, "Castle");
        assertEquals(Path.of("schematics", "Projects", "Castle"), dir);
        assertEquals(dir.resolve("project.json"), ProjectLayout.projectFile(dir));
        assertEquals(dir.resolve("files"), ProjectLayout.filesDir(dir));
        assertEquals(dir.resolve(".cache").resolve("derived"), ProjectLayout.derivedDir(dir));
        assertEquals(dir.resolve(".trash"), ProjectLayout.trashDir(dir));
        UUID id = new UUID(0, 5);
        assertEquals(dir.resolve(".cache").resolve("derived").resolve(id + "-ab12.litematic"), ProjectLayout.derivedFile(dir, id, "ab12"));
        assertEquals(Path.of("schematics", "Projects"), ProjectLayout.projectsRoot(schematics));
    }

    @Test
    void forbiddenCharactersAreStripped() {
        assertEquals("abc", ProjectLayout.safeName("a<b>c"));
        assertEquals("ab", ProjectLayout.safeName("a:\"/\\|?*b"));
        assertEquals("ab", ProjectLayout.safeName("a\u0000\u001fb"));
        assertEquals("My Castle", ProjectLayout.safeName("  My Castle  "));
        assertEquals("keep \u00fc \u65e5\u672c", ProjectLayout.safeName("keep \u00fc \u65e5\u672c"));
        assertEquals("name", ProjectLayout.safeName("name. . "));
        assertEquals("a.b", ProjectLayout.safeName("a.b"));
    }

    @Test
    void emptyResultsGetAPlaceholder() {
        assertEquals("unnamed", ProjectLayout.safeName(""));
        assertEquals("unnamed", ProjectLayout.safeName(null));
        assertEquals("unnamed", ProjectLayout.safeName("???"));
        assertEquals("unnamed", ProjectLayout.safeName(".."));
        assertEquals("unnamed", ProjectLayout.safeName("   "));
    }

    @Test
    void reservedNamesAreAvoided() {
        for (String r : Set.of("CON", "con", "PRN", "AUX", "NUL", "COM1", "com9", "LPT1", "LPT9")) {
            assertEquals("_" + r, ProjectLayout.safeName(r));
        }
        assertEquals("_CON.litematic", ProjectLayout.safeName("CON.litematic"));
        assertEquals("_nul.txt", ProjectLayout.safeName("nul.txt"));
        assertEquals("CONSOLE", ProjectLayout.safeName("CONSOLE"));
        assertEquals("COM10", ProjectLayout.safeName("COM10"));
        assertEquals("_CON", ProjectLayout.safeName("CON "));
        assertEquals("_CON", ProjectLayout.safeName("C?ON"));
    }

    @Test
    void longNamesAreCapped() {
        String s = ProjectLayout.safeName("x".repeat(500));
        assertEquals(120, s.length());
    }

    @Test
    void uniqueNamesAddANumber() {
        Set<String> taken = Set.of("tower.litematic", "tower 2.litematic", "Castle");
        assertEquals("tower 3.litematic", ProjectLayout.uniqueName("tower.litematic", taken::contains));
        assertEquals("free.litematic", ProjectLayout.uniqueName("free.litematic", taken::contains));
        assertEquals("Castle 2", ProjectLayout.uniqueName("Castle", taken::contains));
        assertEquals("ab", ProjectLayout.uniqueName("a/b", s -> false));
    }

    @Test
    void resolveInsideRefusesEscapes() {
        Path base = Path.of("proj").toAbsolutePath();
        assertEquals(base.resolve("files").resolve("a.litematic"), ProjectLayout.resolveInside(base, "files/a.litematic"));
        assertEquals(base.resolve("b"), ProjectLayout.resolveInside(base, "files/../b"));
        assertThrows(IllegalArgumentException.class, () -> ProjectLayout.resolveInside(base, "../x"));
        assertThrows(IllegalArgumentException.class, () -> ProjectLayout.resolveInside(base, "files/../../x"));
        assertThrows(IllegalArgumentException.class, () -> ProjectLayout.resolveInside(base, "/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> ProjectLayout.resolveInside(base, "C:/Windows"));
        assertThrows(IllegalArgumentException.class, () -> ProjectLayout.resolveInside(base, "\\x"));
        assertThrows(IllegalArgumentException.class, () -> ProjectLayout.resolveInside(base, ""));
        assertFalse(ProjectLayout.resolveInside(base, "x").equals(base));
        assertTrue(true);
    }
}
