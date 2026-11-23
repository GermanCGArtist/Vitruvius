package dev.vitruvius.editor.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Vec3i;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildJobTextTest {
    private static BuildJobText.Entry entry(String name, String cell, int x, boolean enabled, Map<String, Long> blocks) {
        return new BuildJobText.Entry(name, "tower", cell, "Town", "files/tower.litematic", "NONE", "NONE", enabled,
                new Box(new Vec3i(x, 64, 0), new Vec3i(x + 10, 74, 5)), blocks);
    }

    @Test
    void onePlacementPerArrayCopyWithBlocksAndABuildLine() {
        String t = BuildJobText.build("Town", List.of(
                entry("tower", "base", 0, true, Map.of("stone", 100L, "oak_planks", 20L)),
                entry("tower #1,0,0", "1,0,0", 10, true, Map.of("stone", 100L, "oak_planks", 20L)),
                entry("tower #2,0,0", "2,0,0", 20, false, Map.of("stone", 100L))));
        assertTrue(t.startsWith("// Vitruvius build job Town: 2 Litematica placements, world bounds (0, 64, 0)-(19, 73, 4), 240 blocks\n"), t);
        assertTrue(t.contains("// all blocks: stone 200, oak_planks 40\n"), t);
        assertTrue(t.contains("// Placement \"tower #1,0,0\": object tower, array copy 1,0,0, project Town\n"), t);
        assertTrue(t.contains("size 10 x 10 x 5"), t);
        assertTrue(t.contains("schem_build(\"tower\")\n"), t);
        assertTrue(t.contains("schem_build(\"tower #1,0,0\")\n"), t);
        assertFalse(t.contains("tower #2,0,0\")"), t);
        assertTrue(t.contains("1 placement is disabled"), t);
        // every line is a comment or a schem_build call, so the text stays a valid Manhattan script
        for (String line : t.split("\n")) assertTrue(line.startsWith("//") || line.startsWith("schem_build(\""), line);
    }

    @Test
    void quotesInNamesAreEscapedAndUnreadableFilesSaySo() {
        String t = BuildJobText.build("x", List.of(entry("say \"hi\"", "base", 0, true, null)));
        assertTrue(t.contains("schem_build(\"say \\\"hi\\\"\")"), t);
        assertTrue(t.contains("// blocks: the schematic file could not be read"), t);
        assertEquals(1, t.lines().filter(l -> l.startsWith("schem_build")).count());
    }
}
