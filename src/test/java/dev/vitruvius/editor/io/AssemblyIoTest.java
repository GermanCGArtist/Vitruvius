package dev.vitruvius.editor.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssemblyIoTest {
    /** The parts of the sample project's gate and folder, as a .lasm. */
    private static AssemblyIo.Lasm sample() {
        ProjectNode p = ProjectIoTest.sampleAfterSave();
        AssemblyIo.Lasm l = new AssemblyIo.Lasm("tower");
        for (Node n : p.children()) l.nodes.add(n);
        return l;
    }

    @Test
    void roundTripAndStableBytes(@TempDir Path dir) throws IOException {
        AssemblyIo.Lasm l = sample();
        String text = AssemblyIo.toJson(l);
        AssemblyIo.Lasm back = AssemblyIo.fromJson(text);
        assertTrue(l.structurallyEquals(back));
        assertEquals(text, AssemblyIo.toJson(back));

        Path f = dir.resolve("files").resolve("tower.lasm");
        AssemblyIo.write(l, f);
        AssemblyIo.Lasm read = AssemblyIo.read(f);
        assertTrue(l.structurallyEquals(read));
        Path g = dir.resolve("again.lasm");
        AssemblyIo.write(read, g);
        assertTrue(java.util.Arrays.equals(Files.readAllBytes(f), Files.readAllBytes(g)));
    }

    @Test
    void hasNoWorldOrDim() {
        String text = AssemblyIo.toJson(sample());
        assertFalse(text.contains("\"world\""));
        assertFalse(text.contains("\"dim\""));
        assertFalse(text.contains("\"origin\""));
        assertTrue(text.startsWith("{\n  \"format\": 1,\n  \"name\": \"tower\",\n  \"nodes\": ["));
    }

    @Test
    void ofAssemblyTakesTheParts() throws IOException {
        AssemblyNode a = new AssemblyNode("gate");
        a.addChild(new ObjectNode("door", "files/door.litematic"));
        AssemblyIo.Lasm l = AssemblyIo.Lasm.of(a);
        assertEquals("gate", l.name);
        assertEquals(1, l.nodes.size());
        AssemblyIo.Lasm back = AssemblyIo.fromJson(AssemblyIo.toJson(l));
        assertTrue(l.structurallyEquals(back));
        // the parts can be attached to a linked assembly
        AssemblyNode inst = new AssemblyNode("inst");
        inst.setSource(new AssemblyNode.Link("gate.lasm"));
        for (Node n : back.nodes) inst.addChild(n);
        assertEquals(1, inst.children().size());
    }

    @Test
    void unknownFieldsAndFormat() throws IOException {
        AssemblyIo.Lasm l = AssemblyIo.fromJson("{\"format\":1,\"name\":\"x\",\"author\":\"me\",\"nodes\":[]}");
        assertEquals("me", l.extra.get("author").getAsString());
        assertTrue(AssemblyIo.toJson(l).contains("\"author\": \"me\""));
        assertThrows(FormatException.class, () -> AssemblyIo.fromJson("{\"format\":9}"));
        assertThrows(FormatException.class, () -> AssemblyIo.fromJson("3"));
    }

    @Test
    void nestedLinkedAssemblyKeepsItsLinkOnly() throws IOException {
        AssemblyNode outer = new AssemblyNode("outer");
        AssemblyNode in = new AssemblyNode("in");
        in.setSource(new AssemblyNode.Link("other.lasm"));
        in.addChild(new ObjectNode("ignored", "x"));
        outer.addChild(in);
        AssemblyIo.Lasm back = AssemblyIo.fromJson(AssemblyIo.toJson(lasmOf(outer)));
        AssemblyNode inBack = (AssemblyNode) back.nodes.get(0).children().get(0);
        assertTrue(inBack.isLinked());
        assertTrue(inBack.children().isEmpty());
    }

    private static AssemblyIo.Lasm lasmOf(AssemblyNode outer) {
        AssemblyIo.Lasm l = new AssemblyIo.Lasm("n");
        l.nodes.add(outer);
        return l;
    }
}
