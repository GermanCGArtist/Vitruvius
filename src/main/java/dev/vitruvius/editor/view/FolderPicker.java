package dev.vitruvius.editor.view;

import dev.cg.uihost.Theme;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A modal "Choose folder" window with a collapsible folder tree, the chosen path on top, a New folder row and
 * Choose / Cancel. It is generic: a node has a label and a key (a relative path or a folder id); the owner builds the
 * tree, says how a folder is created and gets the chosen key. One instance per use; call {@link #draw} every frame
 * at the place where {@link #open} was called from (same ImGui id stack).
 */
final class FolderPicker {
    /** One folder of the tree. */
    static final class Node {
        final String label;
        final String key;
        final List<Node> children = new ArrayList<>();

        Node(String label, String key) {
            this.label = label;
            this.key = key;
        }

        boolean holds(String k) {
            if (key.equals(k)) return true;
            for (Node c : children) if (c.holds(k)) return true;
            return false;
        }

        Node find(String k) {
            if (key.equals(k)) return this;
            for (Node c : children) {
                Node f = c.find(k);
                if (f != null) return f;
            }
            return null;
        }
    }

    /** Makes a folder inside parentKey; returns the new folder's key, or null when it could not be made (see error). */
    interface Creator {
        String create(String parentKey, String name);
    }

    private final String id;
    private Node root;
    private String selected = "";
    private String title = "Choose folder";
    private Creator creator;
    private Consumer<String> onChoose;
    private Supplier<Node> loader;
    private boolean openRequest, expandSelected;
    private String message;
    private final ImString newName = new ImString(64);
    private String bottomHint = "";

    FolderPicker(String id) {
        this.id = id;
    }

    /** Asks for the window; {@code current} is selected and its path opened. */
    void open(String title, String hint, Supplier<Node> loader, String current, Creator creator, Consumer<String> onChoose) {
        this.title = title;
        this.bottomHint = hint;
        this.loader = loader;
        this.root = loader.get();
        this.selected = current != null && root.holds(current) ? current : root.key;
        this.creator = creator;
        this.onChoose = onChoose;
        this.message = null;
        this.newName.set("");
        this.openRequest = true;
        this.expandSelected = true;
    }

    void draw(float sc) {
        String popup = title + "##" + id;
        if (openRequest) {
            openRequest = false;
            ImGui.openPopup(popup);
        }
        ImGui.setNextWindowSize(460 * sc, 540 * sc, ImGuiCond.Appearing);
        ImGui.setNextWindowSizeConstraints(340 * sc, 300 * sc, 900 * sc, 1000 * sc);
        if (!ImGui.beginPopupModal(popup, null, ImGuiWindowFlags.NoSavedSettings)) return;
        Node sel = root.find(selected);
        Ui.smallDim("Selected");
        Ui.colored(Theme.ACCENT, sel == null ? root.label : path(sel));
        ImGui.separator();

        float footer = 118 * sc;
        ImGui.beginChild("##tree" + id, 0, -footer, true);
        drawNode(root, true);
        ImGui.endChild();
        expandSelected = false;

        // new folder inside the selected one
        ImGui.setNextItemWidth(-90 * sc);
        ImGui.inputTextWithHint("##newfolder" + id, "New folder inside the selected one", newName);
        ImGui.sameLine();
        boolean can = creator != null && !newName.get().isBlank();
        if (Ui.button("Create##nf" + id, false, can, 80 * sc, "Type a name first")) {
            String key = creator.create(selected, newName.get().trim());
            if (key == null) {
                message = "That folder could not be made";
            } else {
                root = loader.get();
                selected = root.holds(key) ? key : selected;
                expandSelected = true;
                newName.set("");
                message = null;
            }
        }
        if (message != null) Ui.colored(Theme.ERR, message);
        else if (!bottomHint.isEmpty()) Ui.smallDim(bottomHint);

        if (Ui.button("Choose##pick" + id, true, true, 140 * sc, null)) {
            Consumer<String> c = onChoose;
            String key = selected;
            ImGui.closeCurrentPopup();
            if (c != null) c.accept(key);
        }
        ImGui.sameLine();
        if (ImGui.button("Cancel##pick" + id, 90 * sc, 0) || ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape)) ImGui.closeCurrentPopup();
        ImGui.endPopup();
    }

    private String path(Node n) {
        return n == root ? root.label : n.key.isEmpty() ? n.label : pathOf(root, n, root.label);
    }

    /** "(schematics) / building parts / base" style path for the label line. */
    private static String pathOf(Node from, Node target, String acc) {
        for (Node c : from.children) {
            if (c == target) return acc + " / " + c.label;
            if (c.holds(target.key)) return pathOf(c, target, acc + " / " + c.label);
        }
        return acc;
    }

    private void drawNode(Node n, boolean isRoot) {
        int flags = ImGuiTreeNodeFlags.OpenOnArrow | ImGuiTreeNodeFlags.SpanAvailWidth;
        if (n.children.isEmpty()) flags |= ImGuiTreeNodeFlags.Leaf;
        if (n.key.equals(selected)) flags |= ImGuiTreeNodeFlags.Selected;
        if (isRoot) flags |= ImGuiTreeNodeFlags.DefaultOpen;
        if (expandSelected && !isRoot && n.holds(selected) && !n.key.equals(selected)) ImGui.setNextItemOpen(true);
        boolean open = ImGui.treeNodeEx(n.key + "##n" + id, flags, n.label);
        if (ImGui.isItemClicked() && !ImGui.isItemToggledOpen()) selected = n.key;
        if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0) && !ImGui.isItemToggledOpen() && onChoose != null) {
            Consumer<String> c = onChoose;
            selected = n.key;
            ImGui.closeCurrentPopup();
            c.accept(selected);
        }
        if (open) {
            for (Node c : n.children) drawNode(c, false);
            ImGui.treePop();
        }
    }
}
