package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.ProjectLayout;

import java.util.Objects;
import java.util.UUID;

/**
 * Regenerating a generated shape (a resize, or a changed setting): the caller has built the new schematic into a cache
 * file ({@code tempPath}, a port path). In a project it is copied to {@code files/<stem>_<size>.litematic} (a free
 * name); a Loose object just points at the cache file. The object moves to {@code newPos}. The file it used before is
 * moved to {@code .trash} when {@code trashOld} says it was made by the generator and no other object uses it. Undo
 * puts the old file and position back. Litematica keeps a loaded schematic until it reloads it, which is why a new
 * file name is used instead of overwriting the old file.
 */
public final class ResizeShape extends BaseCommand {
    /** Where Loose shapes are written: hidden, below the schematics folder. */
    public static final String CACHE_DIR = ".cache/shape";

    private final UUID objectId;
    private final String tempPath;
    private final Vec3i newPos;
    private final String sizeText;
    private final boolean trashOld;

    public ResizeShape(UUID objectId, String tempPath, Vec3i newPos, String sizeText, boolean trashOld) {
        super("Resize");
        this.objectId = objectId;
        this.tempPath = tempPath;
        this.newPos = newPos;
        this.sizeText = sizeText;
        this.trashOld = trashOld;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node n = c.scene().byId(objectId);
        if (!(n instanceof ObjectNode)) return new Refusal("Resize works on a schematic object");
        Refusal ro = Nodes.readOnly(n);
        if (ro != null) return ro;
        if (tempPath == null || !c.fs().exists(tempPath)) return new Refusal("The new shape is not built");
        return null;
    }

    @Override
    protected void doApply() {
        ObjectNode o = (ObjectNode) ctx.scene().byId(objectId);
        remember(o);
        String oldFile = o.file();
        boolean inProject = ctx.scene().projectOf(o) != null;
        String newRel;
        if (inProject) {
            String stem = FileSystemPort.nameOf(oldFile);
            int dot = stem.lastIndexOf('.');
            if (dot > 0) stem = stem.substring(0, dot);
            stem = stem.replaceAll("_[0-9]+x[0-9]+x[0-9]+(_[0-9]+)?$", "");        // not "a_3x3x3_5x5x5_9x9x9"
            String dir = ctx.baseDir(o);
            newRel = FileOps.freeFilesPath(ctx, dir, stem + "_" + sizeText + ProjectLayout.SCHEMATIC_EXT);
            journal().copy(tempPath, FileSystemPort.join(dir, newRel));
        } else {
            String loose = ctx.looseDir();
            newRel = !loose.isEmpty() && tempPath.startsWith(loose + "/") ? tempPath.substring(loose.length() + 1) : tempPath;
        }
        o.setFile(newRel);
        o.setTransform(o.transform().withPos(newPos));
        if (trashOld && !oldFile.isEmpty() && !oldFile.equals(newRel)) {
            boolean madeHere = inProject || oldFile.startsWith(CACHE_DIR + "/");
            boolean shared = ctx.scene().allNodes().stream().anyMatch(x -> x != o && x instanceof ObjectNode y
                    && y.file().equals(oldFile) && Objects.equals(ctx.scene().projectOf(y), ctx.scene().projectOf(o)));
            String oldPort = ctx.path(o, oldFile);
            if (madeHere && !shared && ctx.fs().exists(oldPort)) journal().trash(oldPort);
        }
        setLabel("Resize " + o.name() + " to " + sizeText);
    }
}
