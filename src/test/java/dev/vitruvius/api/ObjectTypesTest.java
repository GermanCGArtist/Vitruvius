package dev.vitruvius.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.ProjectIo;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ObjectTypesTest {
    /** An area of w x h x d blocks. */
    private static final class AreaType implements SceneObjectType {
        @Override
        public String id() {
            return "test:area";
        }

        @Override
        public String displayName() {
            return "Test area";
        }

        @Override
        public JsonObject create() {
            JsonObject o = new JsonObject();
            o.addProperty("w", 4);
            o.addProperty("h", 2);
            o.addProperty("d", 6);
            return o;
        }

        @Override
        public Box bounds(JsonObject data) {
            return Box.ofSize(new Vec3i(data.get("w").getAsInt(), data.get("h").getAsInt(), data.get("d").getAsInt()));
        }

        @Override
        public void renderOutline(JsonObject data, Transform world, OverlayDrawer d, boolean selected) {}

        @Override
        public void drawProperties(JsonObject data, PropertyUi ui) {}
    }

    private final Scene scene = new Scene();
    private final ProjectNode proj = new ProjectNode("p");

    @BeforeEach
    void setUp() {
        ObjectTypes.clear();
        ObjectTypes.register(new AreaType());
        scene.openProject(proj);
    }

    @AfterEach
    void tearDown() {
        ObjectTypes.clear();
    }

    private ExternalNode area(String name, int x, int y, int z) {
        ExternalNode e = new ExternalNode(name, "test:area");
        e.setData(new AreaType().create());
        e.setTransform(Transform.IDENTITY.withPos(new Vec3i(x, y, z)));
        proj.addChild(e);
        return e;
    }

    @Test
    void registeredTypesAreFoundById() {
        assertNotNull(ObjectTypes.get("test:area"));
        assertNull(ObjectTypes.get("other:thing"));
        assertEquals(1, ObjectTypes.all().size());
        VitruviusApi.registerObjectType(new AreaType());     // the public entry point replaces, not duplicates
        assertEquals(1, ObjectTypes.all().size());
        assertEquals(2, VitruviusApi.API_VERSION);
    }

    @Test
    void boundsFollowTheNodesTransform() {
        ExternalNode e = area("a", 10, 5, 20);
        assertEquals(new Box(new Vec3i(10, 5, 20), new Vec3i(14, 7, 26)), ObjectTypes.worldBoxes(scene, e).get(0));
        e.setTransform(new Transform(new Vec3i(10, 5, 20), Rot.CW_90, Mirror.NONE));
        Box b = ObjectTypes.worldBoxes(scene, e).get(0);
        assertEquals(new Vec3i(6, 2, 4), b.size(), "a quarter turn swaps the footprint");
    }

    @Test
    void anArrayRepeatsTheBounds() {
        ExternalNode e = area("a", 0, 0, 0);
        ArrayModifier am = new ArrayModifier();
        am.count = 3;
        am.useRelative = true;
        am.relative = new double[] {1, 0, 0};
        e.mods().add(am);
        List<Box> boxes = ObjectTypes.worldBoxes(scene, e);
        assertEquals(3, boxes.size());
        assertEquals(new Vec3i(4, 0, 0), boxes.get(1).min());
        assertEquals(new Vec3i(8, 0, 0), boxes.get(2).min());
    }

    @Test
    void aMissingTypeIsOneBlockAndKeepsItsData() {
        ExternalNode e = new ExternalNode("x", "gone:thing");
        JsonObject data = new JsonObject();
        data.addProperty("secret", 42);
        e.setData(data);
        proj.addChild(e);
        assertEquals(new Vec3i(1, 1, 1), ObjectTypes.sizeOf(e));
        assertEquals(1, ObjectTypes.worldBoxes(scene, e).size());
        assertEquals(42, e.data().get("secret").getAsInt());
    }

    @Test
    void aBrokenTypeFallsBackToOneBlock() {
        ExternalNode e = area("a", 0, 0, 0);
        e.setData(new JsonObject());     // the type throws on missing fields
        assertEquals(new Vec3i(1, 1, 1), ObjectTypes.sizeOf(e));
    }

    @Test
    void viewsListEveryCopyWithItsWorldTransform() {
        ExternalNode e = area("a", 1, 2, 3);
        ArrayModifier am = new ArrayModifier();
        am.count = 2;
        am.useRelative = false;
        am.useConstant = true;
        am.constant = new int[] {10, 0, 0};
        e.mods().add(am);
        area("b", 50, 0, 0).setHidden(true);
        List<SceneObjectView> v = ObjectTypes.viewsIn(scene, "test:area");
        assertEquals(3, v.size());
        assertEquals("base", v.get(0).cell());
        assertEquals(new Vec3i(1, 2, 3), v.get(0).world().pos());
        assertEquals(new Vec3i(11, 2, 3), v.get(1).world().pos());
        assertTrue(v.get(2).hidden());
        assertFalse(v.get(0).hidden());
        assertEquals(4, v.get(0).data().get("w").getAsInt());
        v.get(0).data().addProperty("w", 99);
        assertEquals(4, e.data().get("w").getAsInt(), "views hold copies");
        assertTrue(ObjectTypes.viewsIn(scene, "nothing").isEmpty());
    }

    @Test
    void viewsOfUsesTheBoundScene() {
        area("a", 0, 0, 0);
        assertTrue(VitruviusApi.objectsOfType("test:area").isEmpty(), "no scene bound yet");
        ObjectTypes.setSceneSource(() -> scene);
        assertEquals(1, VitruviusApi.objectsOfType("test:area").size());
        ObjectTypes.setSceneSource(null);
    }

    @Test
    void dataAndArrayRoundTripThroughProjectJson() throws Exception {
        ExternalNode e = area("a", 1, 2, 3);
        ArrayModifier am = new ArrayModifier();
        am.count = 4;
        e.mods().add(am);
        ProjectNode back0 = ProjectIo.fromJson(ProjectIo.toJson(proj));
        ExternalNode back = (ExternalNode) back0.children().get(0);
        assertTrue(e.structurallyEquals(back));
        assertEquals(1, back.mods().size());
        assertEquals(6, back.data().get("d").getAsInt());
    }

    @Test
    void viewsUnderASelectedFolderIncludeArrayCopiesAndLeaveOutTheRest() {
        dev.vitruvius.editor.core.FolderNode folder = new dev.vitruvius.editor.core.FolderNode("f");
        proj.addChild(folder);
        ExternalNode inside = new ExternalNode("tunnel", "test:area");
        inside.setData(new AreaType().create());
        inside.setTransform(Transform.IDENTITY.withPos(new Vec3i(100, 50, 0)));
        ArrayModifier am = new ArrayModifier();
        am.count = 5;
        inside.mods().add(am);
        folder.addChild(inside);
        area("outside", 0, 0, 0);

        List<SceneObjectView> v = ObjectTypes.viewsUnder(scene, "test:area", null, List.of(folder.id()));
        assertEquals(5, v.size());
        // one copy is one area wide (4), not the whole array: 100, 104, 108, ...
        for (int i = 0; i < 5; i++) assertEquals(100 + 4 * i, v.get(i).world().pos().x());
        assertEquals("base", v.get(0).cell());
        assertTrue(ObjectTypes.viewsUnder(scene, "test:area", null, List.of()).isEmpty());
        assertEquals(1, ObjectTypes.viewsUnder(scene, "test:area", null, List.of(proj.id())).stream().filter(x -> x.name().equals("outside")).count());
    }
}
