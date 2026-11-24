package dev.vitruvius.editor.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.CycleCheck;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.io.AssemblyIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinkPathsTest {
    @TempDir
    Path root;

    private Path project(String name) throws IOException {
        Path d = root.resolve("Projects").resolve(name);
        Files.createDirectories(d.resolve("files"));
        Files.writeString(d.resolve("project.json"), "{\"format\":1,\"nodes\":[]}");
        return d;
    }

    private static AssemblyNode link(String path) {
        AssemblyNode a = new AssemblyNode("l");
        a.setSource(new AssemblyNode.Link(path));
        return a;
    }

    private static AssemblyIo.Lasm lasmOf(AssemblyNode... links) {
        AssemblyIo.Lasm l = new AssemblyIo.Lasm("x");
        for (AssemblyNode a : links) l.nodes.add(a);
        l.nodes.add(new ObjectNode("o", "files/o.litematic"));
        return l;
    }

    @Test
    void aFileInsideTheLinkingProjectUsesTheProjectAsBase() throws IOException {
        Path p = project("Metro");
        Path f = p.resolve("files/assemblies/x.lasm");
        assertEquals(f, LinkPaths.target(p, "files/assemblies/x.lasm"));
        assertEquals(p, LinkPaths.baseOf(p, f));
    }

    @Test
    void aFileOfAnotherProjectUsesThatProjectAsBase() throws IOException {
        Path p = project("Metro"), q = project("Depot");
        Path f = q.resolve("files/assemblies/y.lasm");
        assertEquals(f, LinkPaths.target(p, "../Depot/files/assemblies/y.lasm"));
        assertEquals(q, LinkPaths.baseOf(p, f));
        assertEquals(q, LinkPaths.baseOf(p, q.resolve("project.json")));
    }

    @Test
    void aFileInTheSchematicsFolderUsesItsOwnFolderAsBase() throws IOException {
        Path p = project("Metro");
        Path f = root.resolve("places/z.lasm");
        assertEquals(f, LinkPaths.target(p, "../../places/z.lasm"));
        assertEquals(root.resolve("places"), LinkPaths.baseOf(p, f));
    }

    @Test
    void relativeLinksRoundTrip() throws IOException {
        Path p = project("Metro"), q = project("Depot");
        for (Path target : new Path[] {p.resolve("files/a.lasm"), q.resolve("project.json"), root.resolve("z.lasm")}) {
            String link = LinkPaths.relativeLink(p, target);
            assertEquals(target, LinkPaths.target(p, link), link);
        }
        assertEquals("../Depot/project.json", LinkPaths.relativeLink(p, q.resolve("project.json")));
    }

    @Test
    void aProjectIsIdentifiedByItsFolder() throws IOException {
        Path q = project("Depot");
        assertEquals(q.toString(), LinkPaths.identity(q.resolve("project.json")));
        assertEquals(q.resolve("files/a.lasm").toString(), LinkPaths.identity(q.resolve("files/a.lasm")));
        assertEquals(q.toString(), LinkPaths.projectIdentity(q.toString()));
    }

    @Test
    void linksOfListsWhatAFileLinksAndTheCycleCheckUsesIt() throws IOException {
        Path a = project("A"), b = project("B");
        // A links B; B links a .lasm that links A back
        Files.writeString(a.resolve("project.json"), AssemblyIo.toJson(lasmOf(link("../B/project.json"))));
        AssemblyIo.write(lasmOf(link("../A/project.json")), b.resolve("files/loop.lasm"));
        Files.writeString(b.resolve("project.json"), AssemblyIo.toJson(lasmOf(link("files/loop.lasm"))));

        assertEquals(List.of(b.toString()), List.copyOf(LinkPaths.linksOf(a.toString())));
        assertEquals(List.of(b.resolve("files/loop.lasm").toString()), List.copyOf(LinkPaths.linksOf(b.toString())));
        assertEquals(List.of(a.toString()), List.copyOf(LinkPaths.linksOf(b.resolve("files/loop.lasm").toString())));
        // linking B into A would close the loop A -> B -> loop.lasm -> A
        assertTrue(CycleCheck.wouldCreateCycle(a.toString(), b.toString(), LinkPaths::linksOf));
        // a project that nothing links back is fine
        Path c = project("C");
        assertFalse(CycleCheck.wouldCreateCycle(c.toString(), a.toString(), LinkPaths::linksOf));
        // and a missing file links nothing
        assertTrue(LinkPaths.linksOf(root.resolve("nope.lasm").toString()).isEmpty());
    }

    @Test
    void anOldLinkToProjectJsonFindsTheVproj() throws IOException {
        Path a = project("A"), b = project("B");
        Files.move(b.resolve("project.json"), b.resolve("project.vproj"));   // as Liskov's migration leaves it
        Path t = LinkPaths.target(a, "../B/project.json");
        assertEquals(b.resolve("project.vproj").toAbsolutePath().normalize(), t);
        assertEquals(b.toAbsolutePath().normalize().toString(), LinkPaths.identity(t));
        assertEquals(b.resolve("project.vproj").toAbsolutePath().normalize(), LinkPaths.fileOf(LinkPaths.identity(t)).toAbsolutePath().normalize());
        assertEquals(b.toAbsolutePath().normalize(), LinkPaths.ownerBase(b.resolve("files/x.litematic")));
    }
}
