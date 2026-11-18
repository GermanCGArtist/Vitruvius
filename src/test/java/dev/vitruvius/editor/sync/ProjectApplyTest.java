package dev.vitruvius.editor.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Ids;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.FormatException;
import dev.vitruvius.editor.io.ProjectIo;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectApplyTest {
    private static ProjectNode project() {
        ProjectNode p = new ProjectNode(Ids.newId(), "P");
        p.setWorld("w");
        return p;
    }

    private static ProjectNode copyOf(ProjectNode p) throws FormatException {
        return ProjectIo.fromJson(ProjectIo.toJson(p));
    }

    @Test
    void anEditedNodeKeepsItsIdentityAndItsPlacementIds() throws Exception {
        ProjectNode live = project();
        ObjectNode a = new ObjectNode("a", "files/a.litematic");
        a.setPlacementIds(Map.of("base", "pid"));
        live.addChild(a);
        ProjectNode theirs = copyOf(live);
        ObjectNode ta = (ObjectNode) theirs.children().get(0);
        ta.setTransform(new Transform(new Vec3i(4, 5, 6), Rot.NONE, Mirror.NONE));
        ArrayModifier arr = new ArrayModifier();
        arr.count = 3;
        ta.mods().add(arr);

        Set<UUID> affected = ProjectApply.apply(live, theirs);

        assertSame(a, live.children().get(0));
        assertEquals(new Vec3i(4, 5, 6), a.transform().pos());
        assertEquals(1, a.mods().size());
        assertEquals("pid", a.placementIds().get("base"));
        assertTrue(affected.contains(a.id()));
        assertEquals("w", live.world());
    }

    @Test
    void newNodesAppearAndGoneNodesAreDropped() throws Exception {
        ProjectNode live = project();
        ObjectNode a = new ObjectNode("a", "files/a.litematic");
        ObjectNode b = new ObjectNode("b", "files/b.litematic");
        live.addChild(a);
        live.addChild(b);
        ProjectNode theirs = copyOf(live);
        theirs.removeChild(theirs.children().get(1));
        ObjectNode c = new ObjectNode("c", "files/c.litematic");
        theirs.addChild(c);

        ProjectApply.apply(live, theirs);

        assertEquals(2, live.children().size());
        assertSame(a, live.children().get(0));
        assertEquals(c.id(), live.children().get(1).id());
        assertNull(b.parent());
    }

    @Test
    void anObjectMovedIntoANewFolderIsTheSameNode() throws Exception {
        ProjectNode live = project();
        ObjectNode a = new ObjectNode("a", "files/a.litematic");
        live.addChild(a);
        ProjectNode theirs = copyOf(live);
        Node ta = theirs.children().get(0);
        FolderNode f = new FolderNode("f");
        theirs.removeChild(ta);
        theirs.addChild(f);
        f.addChild(ta);

        ProjectApply.apply(live, theirs);

        assertEquals(1, live.children().size());
        assertEquals(f.id(), live.children().get(0).id());
        assertSame(a, live.children().get(0).children().get(0));
    }

    @Test
    void applyingTheSameStateChangesNothing() throws Exception {
        ProjectNode live = project();
        live.addChild(new ObjectNode("a", "files/a.litematic"));
        Set<UUID> affected = ProjectApply.apply(live, copyOf(live));
        assertTrue(affected.isEmpty(), affected.toString());
        assertFalse(live.children().isEmpty());
    }
}
