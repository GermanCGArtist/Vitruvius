package dev.schemarray.editor.io;

import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The zip exports (no Minecraft types). Planning is separate from writing, so the path rewriting is unit-tested
 * without touching a zip.
 *
 * <ul>
 *   <li>{@link #planNodes}: a {@code .lasm} of the given nodes plus every file they use. Object files go to
 *       {@code files/<name>} (a name that is taken gets a number; one source file is stored once), the {@code .lasm}
 *       is the zip's root entry, and every linked assembly inside is included as its own {@code .lasm} under
 *       {@code files/assemblies/}, rewritten the same way (recursively), with the link pointing at it. All paths in
 *       the zip are relative to its root, which is also what they are relative to once it is unpacked into a project.
 *   <li>{@link #planProject}: the project folder without {@code .cache} and {@code .trash}.
 *   <li>{@link #write}: writes a plan to a new zip file (never replaces one).
 * </ul>
 */
public final class ZipExport {
    private ZipExport() {}

    public static final String EXPORTS_DIR = "exports";
    public static final String FILES = ProjectLayout.FILES_DIR;
    public static final String ASSEMBLIES = ProjectLayout.FILES_DIR + "/assemblies";

    /** One entry of the zip: a file copied from {@code source}, or text content. */
    public record Entry(String zipPath, Path source, byte[] content) {
        public static Entry file(String zipPath, Path source) {
            return new Entry(zipPath, source, null);
        }

        public static Entry text(String zipPath, String text) {
            return new Entry(zipPath, null, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    public record Plan(List<Entry> entries, List<String> warnings) {
        public List<String> paths() {
            List<String> out = new ArrayList<>();
            for (Entry e : entries) out.add(e.zipPath());
            return out;
        }

        public Entry find(String zipPath) {
            for (Entry e : entries) if (e.zipPath().equals(zipPath)) return e;
            return null;
        }
    }

    /** A linked file as the planner needs it: a name for the copy, its parts and what their files are relative to. */
    public record LinkedFile(String key, String name, List<Node> parts, Path base) {}

    public interface Resolver {
        /** The file of an object whose files are relative to {@code base} (null base: the object is in the model). Null when unusable. */
        Path file(ObjectNode o, Path base);

        /** The target of a linked assembly seen from {@code base} (null base: the assembly is in the model); null when unreadable. */
        LinkedFile link(AssemblyNode a, Path base);
    }

    // ------------------------------------------------------------------------------------------ assemblies and folders

    /**
     * The plan for a {@code .lasm} named {@code rootName} holding these nodes (their transforms as they are) and all
     * the files they need. The nodes are not changed.
     *
     * @param base what the object files of {@code nodes} are relative to, or null when they belong to the model
     */
    public static Plan planNodes(String rootName, List<Node> nodes, Path base, Resolver r) {
        Builder b = new Builder(r);
        String root = ProjectLayout.safeName(rootName) + ProjectLayout.ASSEMBLY_EXT;
        b.used.add(root.toLowerCase(Locale.ROOT));
        AssemblyIo.Lasm l = new AssemblyIo.Lasm(rootName);
        l.nodes.addAll(nodes);
        int slot = b.entries.size();
        b.entries.add(null);                                  // the root comes first, whatever the files below add
        b.entries.set(slot, Entry.text(root, b.rewrite(l, base, 0)));
        return new Plan(b.entries, b.warnings);
    }

    private static final class Builder {
        final Resolver r;
        final List<Entry> entries = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Set<String> used = new HashSet<>();
        final Map<Path, String> files = new HashMap<>();
        final Map<String, String> lasms = new HashMap<>();

        Builder(Resolver r) {
            this.r = r;
        }

        private String unique(String dir, String name) {
            String n = ProjectLayout.uniqueName(name, c -> used.contains((dir + "/" + c).toLowerCase(Locale.ROOT)));
            used.add((dir + "/" + n).toLowerCase(Locale.ROOT));
            return dir + "/" + n;
        }

        /** The .lasm text for these nodes with the paths rewritten; adds the files and nested .lasm entries. */
        String rewrite(AssemblyIo.Lasm lasm, Path base, int depth) {
            AssemblyIo.Lasm clone;
            try {
                clone = AssemblyIo.fromJson(AssemblyIo.toJson(lasm));
            } catch (IOException e) {
                throw new IllegalStateException("cannot copy the nodes: " + e.getMessage(), e);
            }
            for (int i = 0; i < lasm.nodes.size(); i++) {
                List<Node> from = lasm.nodes.get(i).subtree(), to = clone.nodes.get(i).subtree();
                for (int k = 0; k < from.size(); k++) {
                    Node a = from.get(k), c = to.get(k);
                    if (a instanceof ObjectNode o && c instanceof ObjectNode co) {
                        if (!o.file().isEmpty()) co.setFile(addFile(o, base));
                    } else if (a instanceof AssemblyNode la && la.isLinked() && c instanceof AssemblyNode ca) {
                        ca.setSource(new AssemblyNode.Link(addLasm(la, base, depth)));
                    }
                }
            }
            return AssemblyIo.toJson(clone);
        }

        private String addFile(ObjectNode o, Path base) {
            Path src = r.file(o, base);
            if (src == null || !Files.isRegularFile(src)) {
                warnings.add("The file of \"" + o.name() + "\" (" + o.file() + ") is missing and is not in the zip");
                return o.file();
            }
            Path key = src.toAbsolutePath().normalize();
            String hit = files.get(key);
            if (hit != null) return hit;
            String zipPath = unique(FILES, src.getFileName().toString());
            files.put(key, zipPath);
            entries.add(Entry.file(zipPath, src));
            return zipPath;
        }

        private String addLasm(AssemblyNode a, Path base, int depth) {
            LinkedFile lf = depth > 12 ? null : r.link(a, base);
            String path = ((AssemblyNode.Link) a.source()).path();
            if (lf == null) {
                warnings.add("The linked file of \"" + a.name() + "\" (" + path + ") cannot be read and is not in the zip");
                return path;
            }
            String hit = lasms.get(lf.key());
            if (hit != null) return hit;
            String zipPath = unique(ASSEMBLIES, ProjectLayout.safeName(lf.name()) + ProjectLayout.ASSEMBLY_EXT);
            lasms.put(lf.key(), zipPath);       // before the recursion: a loop in the files ends here
            int slot = entries.size();
            entries.add(null);
            AssemblyIo.Lasm inner = new AssemblyIo.Lasm(lf.name());
            inner.nodes.addAll(lf.parts());
            entries.set(slot, Entry.text(zipPath, rewrite(inner, lf.base(), depth + 1)));
            return zipPath;
        }
    }

    // ------------------------------------------------------------------------------------------ projects

    /** The project folder's files below {@code <folder name>/}, without {@code .cache}, {@code .trash} and temporary files. */
    public static Plan planProject(Path projectDir) throws IOException {
        Path dir = projectDir.toAbsolutePath().normalize();
        String top = dir.getFileName().toString();
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path p : all) {
                Path rel = dir.relativize(p);
                String first = rel.getName(0).toString();
                if (rel.getNameCount() > 1 && (first.equals(".cache") || first.equals(ProjectLayout.TRASH_DIR))) continue;
                if (p.getFileName().toString().endsWith(".tmp")) continue;
                entries.add(Entry.file(top + "/" + rel.toString().replace('\\', '/'), p));
            }
        }
        return new Plan(entries, new ArrayList<>());
    }

    // ------------------------------------------------------------------------------------------ writing

    /** A name for a new export in {@code dir}: {@code base.ext}, else {@code base 2.ext}, and so on. */
    public static String uniqueName(Path dir, String base, String ext) {
        return ProjectLayout.uniqueName(ProjectLayout.safeName(base) + ext, n -> Files.exists(dir.resolve(n)));
    }

    /** Writes the plan to {@code zip} (a new file: an existing one is never replaced). */
    public static void write(Plan plan, Path zip) throws IOException {
        Path abs = zip.toAbsolutePath();
        Files.createDirectories(abs.getParent());
        if (Files.exists(abs)) throw new java.nio.file.FileAlreadyExistsException(abs.toString());
        Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Entry e : plan.entries()) {
                ZipEntry ze = new ZipEntry(e.zipPath());
                if (e.source() != null) {
                    ze.setTime(Files.getLastModifiedTime(e.source()).toMillis());
                    z.putNextEntry(ze);
                    Files.copy(e.source(), z);
                } else {
                    z.putNextEntry(ze);
                    z.write(e.content());
                }
                z.closeEntry();
            }
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
        Files.move(tmp, abs);
    }
}
