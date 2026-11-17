package dev.schemarray.editor.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.schemarray.editor.core.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Absolute project paths (what the project manager keeps) become port paths when the port root is set. */
class EditContextPortRootTest {
    @Test
    void absoluteProjectPathIsMadeRelativeToThePortRoot() {
        Fx fx = new Fx();
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        fx.ctx.setPortRoot(root);
        fx.p.setPath(root.resolve("Projects").resolve("Metro").toString());
        ObjectNode o = new ObjectNode("a", "files/a.litematic");
        fx.p.addChild(o);
        assertEquals("Projects/Metro", fx.ctx.baseDir(o));
        assertEquals("Projects/Metro/files/a.litematic", fx.ctx.path(o, o.file()));
        fx.p.setPath("Projects/Metro");                      // relative paths are left alone
        assertEquals("Projects/Metro", fx.ctx.baseDir(o));
    }

    @Test
    void addObjectCopiesTheFileIntoTheProject() {
        Fx fx = new Fx();
        fx.fs.put("Tunnels/vent.litematic", "bytes");
        AddObject add = new AddObject(fx.p.id(), -1, null, "Tunnels/vent.litematic", new dev.schemarray.editor.core.Vec3i(1, 2, 3));
        assertEquals(java.util.Optional.empty(), fx.stack.execute(add));
        ObjectNode o = (ObjectNode) fx.scene.byId(add.objectId());
        assertEquals("vent", o.name());
        assertEquals("files/vent.litematic", o.file());
        assertEquals(new dev.schemarray.editor.core.Vec3i(1, 2, 3), o.transform().pos());
        assertEquals("bytes", new String(fx.fs.bytes("Projects/Metro/files/vent.litematic")));
        fx.stack.undo();
        assertEquals(null, fx.scene.byId(add.objectId()));
        assertEquals(false, fx.fs.exists("Projects/Metro/files/vent.litematic"));
        assertEquals(true, fx.fs.exists("Tunnels/vent.litematic"));
        fx.stack.redo();
        assertEquals(true, fx.fs.exists("Projects/Metro/files/vent.litematic"));
        // a missing source is refused
        assertEquals(true, fx.stack.execute(new AddObject(fx.p.id(), -1, null, "nope.litematic", dev.schemarray.editor.core.Vec3i.ZERO)).isPresent());
    }
}
