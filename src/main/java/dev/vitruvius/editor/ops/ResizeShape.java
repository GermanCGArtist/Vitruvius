package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.ProjectLayout;

import java.util.UUID;

/**
 * Resizing a generated shape: the caller has built the regenerated schematic into a cache file ({@code tempPath}, a
 * port path). It is copied to {@code files/<stem>_<size>.litematic} (a free name), the object points at the copy and
 * moves to {@code newPos} (the low corner moves when a low face was dragged). Undo puts the old file and position
 * back and moves the copy to {@code .trash}. Litematica keeps a loaded schematic until it reloads it, which is why a
 * new file name is used instead of overwriting the old file.
 */
public final class ResizeShape extends BaseCommand {
    private final UUID objectId;
    private final String tempPath;
    private final Vec3i newPos;
    private final String sizeText;

    public ResizeShape(UUID objectId, String tempPath, Vec3i newPos, String sizeText) {
        super("Resize");
        this.objectId = objectId;
        this.tempPath = tempPath;
        this.newPos = newPos;
        this.sizeText = sizeText;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node n = c.scene().byId(objectId);
        if (!(n instanceof ObjectNode)) return new Refusal("Resize works on a schematic object");
        Refusal ro = Nodes.readOnly(n);
        if (ro != null) return ro;
        if (c.scene().projectOf(n) == null) return new Refusal(Nodes.q(n) + " is not in a project: move it into one to resize it");
        if (tempPath == null || !c.fs().exists(tempPath)) return new Refusal("The resized shape is not built");
        return null;
    }

    @Override
    protected void doApply() {
        ObjectNode o = (ObjectNode) ctx.scene().byId(objectId);
        remember(o);
        String stem = FileSystemPort.nameOf(o.file());
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        stem = stem.replaceAll("_[0-9]+x[0-9]+x[0-9]+(_[0-9]+)?$", "");        // not "a_3x3x3_5x5x5_9x9x9"
        String dir = ctx.baseDir(o);
        String rel = FileOps.freeFilesPath(ctx, dir, stem + "_" + sizeText + ProjectLayout.SCHEMATIC_EXT);
        journal().copy(tempPath, FileSystemPort.join(dir, rel));
        o.setFile(rel);
        o.setTransform(o.transform().withPos(newPos));
        setLabel("Resize " + o.name() + " to " + sizeText);
    }
}
