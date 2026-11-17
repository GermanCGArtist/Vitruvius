package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.io.AssemblyIo;
import dev.vitruvius.editor.io.ProjectLayout;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Save as linked: a local assembly is written out as a {@code .lasm} in the project's {@code files/assemblies/} and
 * becomes a linked instance of it (same id, transform, front number and modifiers; its parts now come from the
 * file). One undoable step: undo puts the parts back and moves the created files to {@code .trash}.
 *
 * <p>The parts keep their ids in the file. Their files stay where they are when they are inside the project; a file
 * that is not (it is found relative to the schematics folder instead, as Loose objects use) is copied to
 * {@code files/assemblies/<name>_files/}, and the {@code .lasm} points at the copy. Paths in the {@code .lasm} are
 * relative to the project folder, as everywhere.
 */
public final class SaveAsLinked extends BaseCommand {
    public static final String DIR = ProjectLayout.FILES_DIR + "/assemblies";

    private final UUID assemblyId;
    private final String fileName;
    private String lasmPath = "";

    /** @param fileName the name for the file (without extension), or null for the assembly's own name */
    public SaveAsLinked(UUID assemblyId, String fileName) {
        super("Save as linked");
        this.assemblyId = assemblyId;
        this.fileName = fileName;
    }

    /** The link path of the new file, relative to the project ({@code files/assemblies/x.lasm}); set once applied. */
    public String lasmPath() {
        return lasmPath;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        if (!(c.scene().byId(assemblyId) instanceof AssemblyNode a)) return new Refusal("Only an assembly can be saved as a .lasm");
        if (a.isLinked()) return new Refusal(Nodes.q(a) + " is linked already");
        Refusal ro = Nodes.readOnly(a);
        if (ro != null) return ro;
        if (c.scene().projectOf(a) == null) return new Refusal("Move " + Nodes.q(a) + " into a project first: the .lasm is saved in the project's files folder");
        if (a.children().isEmpty()) return new Refusal(Nodes.q(a) + " has no parts to save");
        return null;
    }

    @Override
    protected void doApply() throws IOException {
        AssemblyNode a = (AssemblyNode) ctx.scene().byId(assemblyId);
        remember(a);
        String base = ctx.baseDir(a);
        String stem = ProjectLayout.safeName(fileName == null || fileName.isBlank() ? a.name() : fileName.strip());
        String name = ProjectLayout.uniqueName(stem + ProjectLayout.ASSEMBLY_EXT, n -> ctx.fs().exists(FileSystemPort.join(base, DIR + "/" + n)));
        lasmPath = DIR + "/" + name;
        String filesDir = DIR + "/" + name.substring(0, name.length() - ProjectLayout.ASSEMBLY_EXT.length()) + "_files";
        setLabel("Save " + a.name() + " as " + name);

        Map<UUID, UUID> same = new HashMap<>();
        for (Node part : a.children()) for (Node s : part.subtree()) same.put(s.id(), s.id());
        Map<String, String> copied = new HashMap<>();
        Nodes.Copier copier = new Nodes.Copier(same, o -> {
            String f = o.file();
            if (f.isEmpty() || ctx.fs().exists(ctx.path(a, f))) return f;     // inside the project, or missing: as it is
            if (!ctx.fs().exists(f)) return f;
            return copied.computeIfAbsent(f, k -> {
                String rel = filesDir + "/" + ProjectLayout.uniqueName(FileSystemPort.nameOf(k),
                        n -> ctx.fs().exists(FileSystemPort.join(base, filesDir + "/" + n)) || copied.containsValue(filesDir + "/" + n));
                journal().copy(k, FileSystemPort.join(base, rel));
                return rel;
            });
        }, false, new HashMap<>(), n -> {});
        AssemblyIo.Lasm l = new AssemblyIo.Lasm(a.name());
        for (Node part : a.children()) {
            Node copy = copier.copy(part);
            copier.remapBooleans(copy);
            l.nodes.add(copy);
        }
        journal().create(FileSystemPort.join(base, lasmPath), AssemblyIo.toJson(l));

        a.clearChildren();
        a.setSource(new AssemblyNode.Link(lasmPath));
        touch(a);
    }
}
