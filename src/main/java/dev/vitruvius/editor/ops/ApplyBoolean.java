package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.BooleanModifier;
import dev.vitruvius.editor.core.Modifier;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;

import java.util.List;
import java.util.UUID;

/**
 * Apply Boolean: bakes the cut into a file. The caller passes the derived file ({@code derivedPath}, a port path such
 * as {@code Projects/Metro/.cache/derived/<id>-<hash>.litematic}). It is copied to
 * {@code files/<name>_bool.litematic} (a free name; the derived file stays in the cache), the object points at the
 * copy, and the Boolean modifier is removed. Undo removes the copy (to {@code .trash}), restores the old file and
 * brings the modifier back.
 */
public final class ApplyBoolean extends BaseCommand {
    private final UUID objectId;
    private final int modifierIndex;
    private final String derivedPath;

    public ApplyBoolean(UUID objectId, int modifierIndex, String derivedPath) {
        super("Apply Boolean");
        this.objectId = objectId;
        this.modifierIndex = modifierIndex;
        this.derivedPath = derivedPath;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node n = c.scene().byId(objectId);
        if (!(n instanceof ObjectNode o)) return new Refusal("Apply works on a schematic object");
        Refusal ro = Nodes.readOnly(n);
        if (ro != null) return ro;
        if (c.scene().projectOf(n) == null) return new Refusal(Nodes.q(n) + " is not in a project, so there is no files folder for the result");
        List<Modifier> mods = o.mods();
        if (modifierIndex < 0 || modifierIndex >= mods.size() || !(mods.get(modifierIndex) instanceof BooleanModifier)) {
            return new Refusal("That modifier is not a Boolean");
        }
        if (derivedPath == null || !c.fs().exists(derivedPath)) return new Refusal("The result of the Boolean is not built yet");
        return null;
    }

    @Override
    protected void doApply() {
        ObjectNode o = (ObjectNode) ctx.scene().byId(objectId);
        remember(o);
        String stem = FileSystemPort.nameOf(o.file());
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        String dir = ctx.baseDir(o);
        String rel = FileOps.freeFilesPath(ctx, dir, stem + "_bool" + dev.vitruvius.editor.io.ProjectLayout.SCHEMATIC_EXT);
        journal().copy(derivedPath, FileSystemPort.join(dir, rel));
        o.setFile(rel);
        o.mods().remove(modifierIndex);
        setLabel("Apply Boolean to " + o.name());
    }
}
