package dev.schemarray.editor.ui;

import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.Box;
import dev.schemarray.editor.core.FolderNode;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.ProjectNode;
import dev.schemarray.editor.core.Scene;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;
import dev.schemarray.editor.ops.ChangeSink;
import dev.schemarray.editor.ops.EditContext;
import dev.schemarray.editor.ops.MemoryFileSystemPort;
import dev.schemarray.editor.ops.UndoStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Test fixture: a scene with project "Metro" (world "w"), an in-memory file system, an undo stack and a fake host. */
final class UiFx {
    final Scene scene = new Scene();
    final MemoryFileSystemPort fs = new MemoryFileSystemPort();
    final EditContext ctx = new EditContext(scene, fs, ChangeSink.NONE);
    final UndoStack stack = new UndoStack(ctx);
    final SelectionModel sel = new SelectionModel();
    final ProjectNode metro;
    final List<String> toasts = new ArrayList<>();
    final List<UUID> renames = new ArrayList<>();
    Box frameBox;
    boolean framed;
    final EditorActions actions;

    UiFx() {
        metro = project("Metro", "w");
        actions = new EditorActions(() -> stack, sel, new EditorActions.Host() {
            @Override
            public Vec3i sizeOf(ObjectNode o) {
                return new Vec3i(4, 3, 2);
            }

            @Override
            public Box boxOf(List<UUID> ids) {
                Box u = null;
                for (UUID id : ids) {
                    for (Node n : scene.byId(id).subtree()) {
                        if (n instanceof ObjectNode o) {
                            Box b = Box.ofSize(new Vec3i(4, 3, 2)).transformed(scene.worldTransform(o));
                            u = u == null ? b : u.union(b);
                        }
                    }
                }
                return u;
            }

            @Override
            public void frame(Box box) {
                frameBox = box;
                framed = true;
            }

            @Override
            public void toast(ToastQueue.Level level, String text, String actionLabel, Runnable action) {
                toasts.add(level + ":" + text);
            }

            @Override
            public void beginRename(UUID id) {
                renames.add(id);
            }

            @Override
            public void requestAddSchematic(UUID parentId) {}

            @Override
            public void focusFilter() {}

            @Override
            public void setActive(ProjectNode p) {
                scene.setActiveProject(p);
            }

            @Override
            public boolean closeProject(ProjectNode p) {
                return scene.closeProject(p);
            }

            @Override
            public void showInExplorer(ProjectNode p) {}

            @Override
            public void saveAll() {}

            @Override
            public void toggleWindows() {}

            @Override
            public void showInTree(UUID id) {}
        });
    }

    ProjectNode project(String name, String world) {
        ProjectNode n = new ProjectNode(name);
        n.setPath("Projects/" + name);
        n.setWorld(world);
        scene.openProject(n);
        fs.put("Projects/" + name + "/project.json", "{}");
        return n;
    }

    static Transform at(int x, int y, int z) {
        return Transform.IDENTITY.withPos(new Vec3i(x, y, z));
    }

    ObjectNode obj(Node parent, String name, int x, int y, int z) {
        ObjectNode o = new ObjectNode(name, "files/" + name + ".litematic");
        o.setTransform(at(x, y, z));
        parent.addChild(o);
        fs.put(ctx.path(o, o.file()), "data of " + name);
        return o;
    }

    FolderNode folder(Node parent, String name) {
        FolderNode f = new FolderNode(name);
        parent.addChild(f);
        return f;
    }

    AssemblyNode asm(Node parent, String name, int x, int y, int z) {
        AssemblyNode a = new AssemblyNode(name);
        a.setTransform(at(x, y, z));
        parent.addChild(a);
        return a;
    }

    Vec3i pos(Node n) {
        return n.localTransform().pos();
    }

    MenuModel.Ctx menuCtx(Node... selected) {
        return new MenuModel.Ctx(scene, List.of(selected), null, null, false, false);
    }
}
