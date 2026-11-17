package dev.schemarray.editor.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.FolderNode;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipExportTest {
    @TempDir
    Path dir;

    private final Map<String, ZipExport.LinkedFile> links = new HashMap<>();
    private final ZipExport.Resolver resolver = new ZipExport.Resolver() {
        @Override
        public Path file(ObjectNode o, Path base) {
            Path b = base == null ? dir.resolve("proj") : base;
            return b.resolve(o.file());
        }

        @Override
        public ZipExport.LinkedFile link(AssemblyNode a, Path base) {
            return links.get(((AssemblyNode.Link) a.source()).path());
        }
    };

    private Path put(String rel, String content) throws IOException {
        Path f = dir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        return f;
    }

    private static ObjectNode obj(String name, String file) {
        return new ObjectNode(name, file);
    }

    private AssemblyIo.Lasm parse(ZipExport.Plan plan, String zipPath) throws IOException {
        ZipExport.Entry e = plan.find(zipPath);
        assertNotNull(e, zipPath + " is not in " + plan.paths());
        return AssemblyIo.fromJson(new String(e.content(), StandardCharsets.UTF_8));
    }

    @Test
    void theLasmIsTheRootEntryAndObjectFilesGoUnderFiles() throws IOException {
        put("proj/files/a.litematic", "A");
        put("proj/files/sub/b.litematic", "B");
        List<Node> nodes = List.of(obj("a", "files/a.litematic"), obj("b", "files/sub/b.litematic"));
        ZipExport.Plan plan = ZipExport.planNodes("My Tower", nodes, null, resolver);
        assertEquals(List.of("My Tower.lasm", "files/a.litematic", "files/b.litematic"), plan.paths());
        AssemblyIo.Lasm l = parse(plan, "My Tower.lasm");
        assertEquals("files/a.litematic", ((ObjectNode) l.nodes.get(0)).file());
        assertEquals("files/b.litematic", ((ObjectNode) l.nodes.get(1)).file());
        assertTrue(plan.warnings().isEmpty());
        // the model is not touched
        assertEquals("files/sub/b.litematic", ((ObjectNode) nodes.get(1)).file());
    }

    @Test
    void oneFileUsedTwiceIsStoredOnceAndNamesThatClashGetNumbers() throws IOException {
        put("proj/files/a.litematic", "A");
        put("proj/other/a.litematic", "A2");
        List<Node> nodes = List.of(obj("a1", "files/a.litematic"), obj("a2", "files/a.litematic"), obj("a3", "other/a.litematic"));
        ZipExport.Plan plan = ZipExport.planNodes("x", nodes, null, resolver);
        assertEquals(List.of("x.lasm", "files/a.litematic", "files/a 2.litematic"), plan.paths());
        AssemblyIo.Lasm l = parse(plan, "x.lasm");
        assertEquals("files/a.litematic", ((ObjectNode) l.nodes.get(1)).file());
        assertEquals("files/a 2.litematic", ((ObjectNode) l.nodes.get(2)).file());
    }

    @Test
    void aMissingFileIsAWarningAndKeepsItsPath() throws IOException {
        ZipExport.Plan plan = ZipExport.planNodes("x", List.of(obj("gone", "files/gone.litematic")), null, resolver);
        assertEquals(List.of("x.lasm"), plan.paths());
        assertEquals(1, plan.warnings().size());
        assertTrue(plan.warnings().get(0).contains("gone"));
        assertEquals("files/gone.litematic", ((ObjectNode) parse(plan, "x.lasm").nodes.get(0)).file());
    }

    @Test
    void nestedFoldersAndAssembliesKeepTheirStructureAndTransforms() throws IOException {
        put("proj/files/a.litematic", "A");
        put("proj/files/b.litematic", "B");
        FolderNode f = new FolderNode("f");
        AssemblyNode asm = new AssemblyNode("asm");
        asm.addChild(obj("b", "files/b.litematic"));
        f.addChild(obj("a", "files/a.litematic"));
        f.addChild(asm);
        ZipExport.Plan plan = ZipExport.planNodes("f", List.of(f), null, resolver);
        assertEquals(List.of("f.lasm", "files/a.litematic", "files/b.litematic"), plan.paths());
        AssemblyIo.Lasm l = parse(plan, "f.lasm");
        FolderNode cf = (FolderNode) l.nodes.get(0);
        assertEquals("files/b.litematic", ((ObjectNode) cf.children().get(1).children().get(0)).file());
    }

    @Test
    void linkedAssembliesAreIncludedAsTheirOwnLasmWithTheLinkRewritten() throws IOException {
        put("proj/files/a.litematic", "A");
        put("shared/files/p.litematic", "P");
        put("shared/files/q.litematic", "Q");
        // station.lasm (base shared/) links tower.lasm (base shared/) which uses q
        ObjectNode q = obj("q", "files/q.litematic");
        AssemblyNode inner = new AssemblyNode("inner");
        inner.setSource(new AssemblyNode.Link("files/tower.lasm"));
        links.put("../shared/files/station.lasm", new ZipExport.LinkedFile("S", "station", List.of(obj("p", "files/p.litematic"), inner), dir.resolve("shared")));
        links.put("files/tower.lasm", new ZipExport.LinkedFile("T", "tower", List.of(q), dir.resolve("shared")));
        AssemblyNode outer = new AssemblyNode("outer");
        outer.setSource(new AssemblyNode.Link("../shared/files/station.lasm"));

        ZipExport.Plan plan = ZipExport.planNodes("all", List.of(obj("a", "files/a.litematic"), outer), null, resolver);
        assertEquals(List.of("all.lasm", "files/a.litematic", "files/assemblies/station.lasm", "files/p.litematic", "files/assemblies/tower.lasm", "files/q.litematic"),
                plan.paths());
        AssemblyIo.Lasm root = parse(plan, "all.lasm");
        assertEquals("files/assemblies/station.lasm", ((AssemblyNode.Link) ((AssemblyNode) root.nodes.get(1)).source()).path());
        AssemblyIo.Lasm station = parse(plan, "files/assemblies/station.lasm");
        assertEquals("files/p.litematic", ((ObjectNode) station.nodes.get(0)).file());
        assertEquals("files/assemblies/tower.lasm", ((AssemblyNode.Link) ((AssemblyNode) station.nodes.get(1)).source()).path());
        AssemblyIo.Lasm tower = parse(plan, "files/assemblies/tower.lasm");
        assertEquals("files/q.litematic", ((ObjectNode) tower.nodes.get(0)).file());
        assertTrue(plan.warnings().isEmpty(), plan.warnings().toString());
    }


    @Test
    void aLinkThatCannotBeReadIsAWarning() {
        AssemblyNode gone = new AssemblyNode("gone");
        gone.setSource(new AssemblyNode.Link("nowhere.lasm"));
        ZipExport.Plan plan = ZipExport.planNodes("x", List.of(gone), null, resolver);
        assertEquals(1, plan.warnings().size());
        assertEquals(List.of("x.lasm"), plan.paths());
    }

    @Test
    void aLoopBetweenLinkedFilesEnds() {
        AssemblyNode a = new AssemblyNode("a");
        a.setSource(new AssemblyNode.Link("a.lasm"));
        links.put("a.lasm", new ZipExport.LinkedFile("A", "a", List.of(a), dir));
        ZipExport.Plan plan = ZipExport.planNodes("x", List.of(a), null, resolver);
        assertEquals(List.of("x.lasm", "files/assemblies/a.lasm"), plan.paths());
    }

    @Test
    void writingMakesAZipWithTheEntriesAndNeverReplacesOne() throws IOException {
        put("proj/files/a.litematic", "AAA");
        ZipExport.Plan plan = ZipExport.planNodes("tower", List.of(obj("a", "files/a.litematic")), null, resolver);
        Path zip = dir.resolve("exports").resolve(ZipExport.uniqueName(dir.resolve("exports"), "tower", ".zip"));
        ZipExport.write(plan, zip);
        try (ZipFile z = new ZipFile(zip.toFile())) {
            assertEquals(2, z.size());
            ZipEntry e = z.getEntry("files/a.litematic");
            assertEquals("AAA", new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
            assertFalse(new String(z.getInputStream(z.getEntry("tower.lasm")).readAllBytes(), StandardCharsets.UTF_8).isEmpty());
        }
        assertEquals("tower 2.zip", ZipExport.uniqueName(dir.resolve("exports"), "tower", ".zip"));
        assertThrows(FileAlreadyExistsException.class, () -> ZipExport.write(plan, zip));
        assertFalse(Files.exists(dir.resolve("exports").resolve("tower.zip.tmp")));
    }

    @Test
    void theProjectZipHasTheFolderWithoutCacheAndTrash() throws IOException {
        put("Metro/project.json", "{}");
        put("Metro/files/a.litematic", "A");
        put("Metro/files/assemblies/x.lasm", "{}");
        put("Metro/.cache/derived/d.litematic", "D");
        put("Metro/.trash/old.litematic", "T");
        put("Metro/project.json.tmp", "tmp");
        ZipExport.Plan plan = ZipExport.planProject(dir.resolve("Metro"));
        assertEquals(List.of("Metro/files/a.litematic", "Metro/files/assemblies/x.lasm", "Metro/project.json"), plan.paths());
        Path zip = dir.resolve("Metro.zip");
        ZipExport.write(plan, zip);
        try (ZipFile z = new ZipFile(zip.toFile())) {
            assertEquals(3, z.size());
            assertNotNull(z.getEntry("Metro/project.json"));
        }
    }
}
