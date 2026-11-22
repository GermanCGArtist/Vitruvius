package dev.vitruvius.editor.view;

import dev.cg.uihost.Theme;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.ops.AddObject;
import dev.vitruvius.editor.ui.ToastQueue;
import dev.vitruvius.shape.Axis;
import dev.vitruvius.shape.BlockEntry;
import dev.vitruvius.shape.BlockSlot;
import dev.vitruvius.shape.GeneratorSettings;
import dev.vitruvius.shape.PatternMode;
import dev.vitruvius.shape.ShapeSpec;
import dev.vitruvius.shape.ShapeType;
import dev.vitruvius.shape.VoxelResult;
import dev.vitruvius.shape.mc.BlockParsing;
import dev.vitruvius.shape.mc.PreviewWorker;
import dev.vitruvius.shape.mc.ShapePresets;
import dev.vitruvius.shape.mc.ShapeSchematics;
import dev.vitruvius.shape.mc.ShapeSaver;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Shape Generator inside the editor (Add > Shape generator, Windows > Shape generator): the controls of the
 * 1.x screen as an ImGui window. Every change regenerates off-thread (PreviewWorker) and shows the result as a ghost
 * at the camera target, so the shape is judged in the 3D viewport while the camera flies around it. Generate writes
 * the schematic into Litematica's {@code Shapes} folder and adds it as a normal object to the active project (or the
 * folder the Add menu was opened on), where it can be moved, rotated, arrayed and cut like any other.
 */
public final class ShapeWindow {
    private ShapeWindow() {}

    private static final String FOLDER = "Shapes";
    private static final ImBoolean OPEN = new ImBoolean(true);


    // spec
    private static final ShapeType[] TYPES = ShapeType.values();
    private static final String[] TYPE_NAMES = new String[TYPES.length];
    private static final String[] AXIS_NAMES = {"X", "Y", "Z"};
    private static final String[] MODE_NAMES = new String[PatternMode.values().length];

    static {
        for (int i = 0; i < TYPES.length; i++) TYPE_NAMES[i] = TYPES[i].displayName;
        for (int i = 0; i < MODE_NAMES.length; i++) MODE_NAMES[i] = PatternMode.values()[i].displayName;
    }

    private static final ImInt type = new ImInt(0);
    private static final ImInt sx = new ImInt(9), sy = new ImInt(9), sz = new ImInt(9);
    private static final ImInt axis = new ImInt(1);
    private static final ImInt thickness = new ImInt(1), param1 = new ImInt(3), param2 = new ImInt(1);
    private static final ImInt depth = new ImInt(4);        // Custom 2D: how far the drawing is extruded
    private static final ImString name = new ImString("shape", 64);
    private static final SlotState outline = new SlotState(), fill = new SlotState();

    // the bound shape object and what the controls and the object's file have in common
    private static boolean initialised;
    private static UUID boundId;
    private static String syncedJson = "";      // the settings the controls had when a regeneration was last asked for
    private static String storedJson = "";      // the settings the object's file holds (as far as we know)
    private static String lastFileRaw = "";     // the file's settings as last read back
    private static boolean expectApply;         // our own apply is about to change the file: do not reload the controls
    private static String requestedJson = "";
    private static VoxelResult result;          // the newest generated result and the settings it is for
    private static String resultJson = "";
    private static String error;
    private static final ImString folder = new ImString("Shapes", 128);
    private static final ImInt output = new ImInt(0);      // 0 = schematic, n = shapeOutputs().get(n - 1)
    private static final ImInt option = new ImInt(0);
    private static String outputStatus;
    private static int activeOutput = -1;                  // the output whose ghost is up (index into shapeOutputs()), or -1
    private static String lastOutKey = "";
    private static String listFolder;                      // chosen 1.x list folder id, null = top level

    /** One Outline/Fill editor: pattern mode, the block text and the mode's extra numbers. */
    private static final class SlotState {
        final ImInt mode = new ImInt(0);
        final ImString blocks = new ImString("", 512);
        final ImInt cellX = new ImInt(1), cellY = new ImInt(1), cellZ = new ImInt(1);
        final ImInt axis = new ImInt(1), width = new ImInt(1);
        final ImInt seed = new ImInt(0);
        boolean dither;

        void load(BlockSlot s) {
            mode.set(s.mode().ordinal());
            blocks.set(s.entries().isEmpty() ? "" : BlockParsing.formatEntries(s.entries()));
            cellX.set(s.cellX());
            cellY.set(s.cellY());
            cellZ.set(s.cellZ());
            axis.set(s.axis().ordinal());
            width.set(s.width());
            seed.set((int) s.seed());
            dither = s.dither();
        }

        BlockSlot slot() {
            List<BlockEntry> entries = BlockParsing.parseEntries(blocks.get());
            return new BlockSlot(PatternMode.values()[mode.get()], entries, cellX.get(), cellY.get(), cellZ.get(),
                    Axis.values()[axis.get()], width.get(), seed.get(), dither);
        }

        /** The first invalid block of the field, or null. */
        String error() {
            for (BlockEntry e : BlockParsing.parseEntries(blocks.get())) {
                String m = BlockParsing.error(e.blockState());
                if (m != null) return m;
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ open / close

    /**
     * Add > Shape generator (parentId = where the object goes) makes a new shape object at the camera target and binds
     * the window to it; the Windows menu (parentId null) just shows the window, bound to the selected generated shape.
     */
    public static void open(EditorSession s, UUID parentId) {
        s.showShape = true;
        s.windowsHidden = false;
        init();
        if (parentId != null) createDraft(s, parentId);
    }

    private static void init() {
        if (initialised) return;
        initialised = true;
        GeneratorSettings g = ShapePresets.last();
        loadControls(g);
        name.set(g.name() == null || g.name().isBlank() ? "shape" : g.name());
        String f = ShapePresets.folder();
        folder.set(f == null || f.isBlank() ? FOLDER : f);
    }

    /** Removes the output ghost and drops pending work; safe to call at any time (editor exit, window closed). */
    public static void close() {
        PreviewWorker.cancel();
        SketchPad.close();
        setHiddenShape(null);
        clearOutputGhost();
        boundId = null;
        result = null;
        resultJson = "";
        requestedJson = "";
    }

    private static UUID hiddenShape;

    /** The shape whose blocks are not drawn right now (null = none): only the viewport, nothing is saved. */
    private static void setHiddenShape(UUID id) {
        if (java.util.Objects.equals(id, hiddenShape)) return;
        if (hiddenShape != null) dev.vitruvius.editor.mc.ReconcileCore.suppress(hiddenShape, false);
        hiddenShape = id;
        if (id != null) dev.vitruvius.editor.mc.ReconcileCore.suppress(id, true);
    }

    private static void clearOutputGhost() {
        var outs = dev.vitruvius.api.VitruviusApi.shapeOutputs();
        if (activeOutput >= 0 && activeOutput < outs.size()) {
            try {
                outs.get(activeOutput).preview(null, 0);
            } catch (Throwable ignored) {
                // the other mod's ghost is its own problem
            }
        }
        activeOutput = -1;
        lastOutKey = "";
        outputStatus = null;
    }

    private static void loadControls(GeneratorSettings g) {
        ShapeSpec sp = g.spec();
        type.set(sp.type().ordinal());
        sx.set(sp.sizeX());
        sy.set(sp.sizeY());
        sz.set(sp.sizeZ());
        axis.set(sp.axis().ordinal());
        thickness.set(sp.thickness());
        param1.set(sp.param1());
        param2.set(sp.param2());
        outline.load(g.outline());
        fill.load(g.fill());
        if (g.sketch() != null) depth.set(g.sketch().depth());
        SketchPad.load(g.sketch());
    }

    private static ObjectNode bound(EditorSession s) {
        Node n = boundId == null ? null : s.scene().byId(boundId);
        return n instanceof ObjectNode o ? o : null;
    }

    /** Makes the new shape object from the current controls, at the camera target (centered on X and Z, standing on Y). */
    private static void createDraft(EditorSession s, UUID parentId) {
        GeneratorSettings g = settings(TYPES[type.get()]);
        if (outline.error() != null || fill.error() != null) {
            s.toast(ToastQueue.Level.WARN, "Fix the blocks first: " + (outline.error() != null ? outline.error() : fill.error()));
            return;
        }
        VoxelResult r = dev.vitruvius.shape.ShapeGenerator.generate(g, () -> false);
        if (r == null || r.blockCount() == 0) {
            if (g.spec().type() == ShapeType.CUSTOM) {
                s.toast(ToastQueue.Level.WARN, "Draw something in the sketchpad first");
                SketchPad.open();
                return;
            }
            s.toast(ToastQueue.Level.WARN, "That makes an empty shape");
            return;
        }
        // where the player stands (the camera target when there is no player), centered on X and Z, standing on Y
        BlockPos at = dev.vitruvius.Compat.playerBlockPos();
        if (at == null) {
            var t = dev.vitruvius.editor.cam.EditorCamera.get().target();
            at = new BlockPos((int) Math.floor(t.x), (int) Math.floor(t.y), (int) Math.floor(t.z));
        }
        ShapeSpec sp = g.spec();
        Vec3i pos = new Vec3i(at.getX() - sp.sizeX() / 2, at.getY(), at.getZ() - sp.sizeZ() / 2);
        String base = name.get().isBlank() ? "shape" : name.get().trim();
        String tmpName = Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt());
        Path dir = s.schematicsDir().resolve(dev.vitruvius.editor.ops.ResizeShape.CACHE_DIR).normalize();
        try {
            Files.createDirectories(dir);
            if (!ShapeSchematics.build(r, base, ShapePresets.toJson(g)).writeToFile(dir, tmpName, true)) {
                s.toast(ToastQueue.Level.WARN, "Could not write the shape");
                return;
            }
        } catch (java.io.IOException | RuntimeException e) {
            s.toast(ToastQueue.Level.WARN, "Could not write the shape: " + e.getMessage());
            return;
        }
        UUID parent = parentId != null && s.scene().byId(parentId) != null ? parentId
                : (s.scene().activeProject() != null ? s.scene().activeProject().id() : s.scene().loose().id());
        Node parentNode = s.scene().byId(parent);
        String baseDir = parentNode == null ? "" : s.undoStack().context().baseDir(parentNode);
        if (baseDir.equals("..") || baseDir.startsWith("../")) {
            // a project that lives outside this profile's schematics folder (opened from another profile) cannot take files
            s.toast(ToastQueue.Level.WARN, "The project \"" + (s.scene().projectOf(parentNode) == null ? "" : s.scene().projectOf(parentNode).name())
                    + "\" is outside this profile's schematics folder, so the shape was added to Loose. Move the project folder into schematics/Projects to use it.");
            parent = s.scene().loose().id();
        }
        AddObject cmd = new AddObject(parent, -1, base, dev.vitruvius.editor.ops.ResizeShape.CACHE_DIR + "/" + tmpName + ".litematic", pos);
        if (!s.actions.execute(cmd)) return;
        s.selection.set(cmd.objectId());
        Node n = s.scene().byId(cmd.objectId());
        if (n != null) s.tree.reveal(n);
        s.scrollToRow = cmd.objectId();
        boundId = cmd.objectId();
        String json = ShapePresets.toJson(g);
        syncedJson = storedJson = resultJson = json;
        result = r;
        lastFileRaw = "";
        expectApply = true;       // the first read of the file is what the controls already show
        ShapePresets.setLast(g);
    }

    // ------------------------------------------------------------------ window

    public static void draw(EditorSession s) {
        float sc = Ui.s();
        var vp = ImGui.getMainViewport();
        OPEN.set(s.showShape);
        ImGui.setNextWindowPos(vp.getPosX() + vp.getSizeX() - 340 * sc - 12 * sc, vp.getPosY() + 56 * sc, s.resetLayout ? ImGuiCond.Always : ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(330 * sc, Math.max(300 * sc, vp.getSizeY() * 0.7f), s.resetLayout ? ImGuiCond.Always : ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSizeConstraints(280 * sc, 200 * sc, 4000, 4000);
        boolean visible = Ui.beginWindow("05 SHAPE GENERATOR###Shape generator", OPEN, 0, 0.96f);
        if (visible) {
            init();
            sync(s);
            drawContent(s, sc);
        }
        Ui.endWindow();
        if (!OPEN.get()) s.showShape = false;        // the X button; the Close button sets showShape itself
        if (s.showShape) {
            SketchPad.draw(result == null ? "" : result.sizeX() + " x " + result.sizeY() + " x " + result.sizeZ() + " blocks");
        } else {
            close();
        }
    }

    /** Follows the selection (a generated shape rebinds the window) and picks up changes made outside (handles, Properties). */
    private static void sync(EditorSession s) {
        List<UUID> ids = s.selection.ids();
        if (ids.size() == 1 && !ids.get(0).equals(boundId)) {
            Node n = s.scene().byId(ids.get(0));
            if (n instanceof ObjectNode o && dev.vitruvius.editor.world.ShapeHandles.settingsOf(s, o) != null) {
                boundId = o.id();
                lastFileRaw = "";
                expectApply = false;
                result = null;
                resultJson = "";
            }
        }
        ObjectNode o = bound(s);
        if (o == null) {
            boundId = null;
            return;
        }
        GeneratorSettings fg = dev.vitruvius.editor.world.ShapeHandles.settingsOf(s, o);
        if (fg == null) {
            boundId = null;
            return;
        }
        String raw = ShapePresets.toJson(fg);
        if (!raw.equals(lastFileRaw)) {
            lastFileRaw = raw;
            if (expectApply) {
                expectApply = false;
            } else {
                loadControls(fg);
                name.set(o.name());
                String json = ShapePresets.toJson(settings(TYPES[type.get()]));
                syncedJson = storedJson = json;
                result = null;
                resultJson = "";
                requestedJson = "";
            }
        }
    }

    private static void drawContent(EditorSession s, float sc) {
        ShapeType t = TYPES[type.get()];
        float w = -1;
        ObjectNode o = bound(s);

        if (o == null) {
            Ui.smallDim("Select a generated shape to edit it, or make a new one.");
            if (Ui.button("New shape", true, true, 120 * sc, null)) createDraft(s, null);
        } else {
            Ui.smallDim("Editing \"" + o.name() + "\": move it with the gizmo, drag its round handles to resize.");
        }

        Ui.section("Shape");
        boolean custom = TYPES[type.get()] == ShapeType.CUSTOM;
        ImGui.setNextItemWidth(custom ? -128 * sc : w);
        ImGui.combo("##shapetype", type, TYPE_NAMES);
        t = TYPES[type.get()];
        custom = t == ShapeType.CUSTOM;
        if (custom) {
            ImGui.sameLine();
            if (ImGui.button(SketchPad.isOpen() ? "Sketchpad ✓" : "Open sketchpad", 120 * sc, 0)) {
                if (SketchPad.isOpen()) SketchPad.close();
                else SketchPad.open();
            }
            Ui.tooltip("Draw the shape pixel by pixel; it is extruded along the axis below");
            Ui.smallDim(result == null ? "Draw it in the sketchpad, then it is extruded to a 3D shape."
                    : "Size " + result.sizeX() + " x " + result.sizeY() + " x " + result.sizeZ() + " (from the drawing)");
            ImGui.setNextItemWidth(80 * sc);
            ImGui.combo("Extrude along", axis, AXIS_NAMES);
            number("Depth", depth, 1, ShapeSpec.MAX_SIZE, sc);
        } else {
            size("X", sx, sc);
            size("Y", sy, sc);
            size("Z", sz, sc);
            if (t.usesAxis()) {
                ImGui.setNextItemWidth(80 * sc);
                ImGui.combo("Axis", axis, AXIS_NAMES);
            }
        }
        number("Thickness", thickness, 1, ShapeSpec.MAX_SIZE, sc);
        if (t.param1Label != null) number(t.param1Label, param1, 0, ShapeSpec.MAX_SIZE, sc);
        if (t.param2Label != null) number(t.param2Label, param2, 0, ShapeSpec.MAX_SIZE, sc);

        Ui.section("Outline");
        slot("outline", outline, "stone, or 60%stone,40%andesite", sc);
        Ui.section("Fill");
        slot("fill", fill, "empty = hollow", sc);

        var outs = dev.vitruvius.api.VitruviusApi.shapeOutputs();
        if (output.get() > outs.size()) output.set(0);
        dev.vitruvius.api.ShapeOutput out = output.get() == 0 ? null : outs.get(output.get() - 1);
        if (!outs.isEmpty()) {
            Ui.section("Output");
            String[] names = new String[outs.size() + 1];
            names[0] = "Schematic";
            for (int i = 0; i < outs.size(); i++) names[i + 1] = outs.get(i).label();
            ImGui.setNextItemWidth(130 * sc);
            if (ImGui.combo("##shapeoutput", output, names)) {
                option.set(0);
                clearOutputGhost();
            }
            out = output.get() == 0 ? null : outs.get(output.get() - 1);
            if (out != null) {
                var opts = out.options();
                if (option.get() >= opts.size()) option.set(0);
                ImGui.sameLine();
                ImGui.setNextItemWidth(-1);
                ImGui.combo("##shapeoption", option, opts.toArray(new String[0]));
            }
        }

        // while an output (Heavymatic) shows the shape, the shape's own blocks stay out of the way
        setHiddenShape(out != null && o != null ? o.id() : null);

        // the controls changed: regenerate off-thread, then the object takes the new file
        String blockError = outline.error() != null ? outline.error() : fill.error();
        GeneratorSettings g = settings(t);
        String json = blockError == null ? ShapePresets.toJson(g) : null;
        error = blockError;
        if (json != null && o != null) {
            boolean changed = !json.equals(syncedJson);
            boolean needResult = out != null && !json.equals(resultJson);
            if ((changed || needResult) && !json.equals(requestedJson)) {
                requestedJson = json;
                syncedJson = json;
                PreviewWorker.request(g, r -> onResult(s, g, json, r));
            }
        }

        // the ghost of an output (Heavymatic wireframes), at the object's position
        if (out != null && o != null && result != null && json != null && json.equals(resultJson)) {
            Vec3i p = o.transform().pos();
            String key = System.identityHashCode(result) + "/" + p + "/" + output.get() + "/" + option.get();
            if (!key.equals(lastOutKey)) {
                lastOutKey = key;
                int idx = output.get() - 1;
                if (activeOutput >= 0 && activeOutput != idx) clearOutputGhost();
                activeOutput = idx;
                lastOutKey = key;
                try {
                    outputStatus = out.preview(new dev.vitruvius.api.ShapeActionContext(result, name.get().trim(), new BlockPos(p.x(), p.y(), p.z())), option.get());
                } catch (Throwable ex) {
                    outputStatus = out.label() + " preview failed: " + ex.getMessage();
                }
            }
        }

        Ui.section(out == null ? "Save to the library" : "Create");
        ImGui.setNextItemWidth(w);
        ImGui.inputText("##shapename", name);
        if (out == null || out.usesSchematicFolder()) drawFolder(sc);
        if (out != null && out.usesListFolders()) drawListFolder(sc);
        boolean busy = PreviewWorker.busy();
        if (error != null) Ui.colored(Theme.ERR, error);
        else if (busy) Ui.smallDim("Generating…");
        else if (out != null && outputStatus != null) Ui.smallDim(outputStatus);
        else if (result != null && o != null) Ui.smallDim(result.blockCount() + " blocks, " + result.sizeX() + " x " + result.sizeY() + " x " + result.sizeZ());
        boolean ready = error == null && !busy && o != null && result != null && json != null && json.equals(resultJson) && result.blockCount() > 0;
        boolean can = ready && !name.get().isBlank();
        String why = error != null ? error : o == null ? "Select or make a shape first" : !ready ? "Still generating" : "Give it a name";
        String create = out == null ? "Save schematic file" : "Create " + out.label().toLowerCase(java.util.Locale.ROOT);
        if (Ui.button(create, true, can, 170 * sc, why)) {
            if (out == null) saveToLibrary(s, g, o);
            else createOutput(s, out, o);
        }
        if (out != null && out.hover() != null && can) Ui.tooltip(out.hover());
        ImGui.sameLine();
        if (ImGui.button("Close", 80 * sc, 0)) s.showShape = false;
        if (out == null) Ui.smallDim("The shape itself is already in the scene. This also saves a copy as a .litematic file for the library.");
    }

    /** A generated result arrived: remember it, and when it differs from the object's file, apply it as one undo step. */
    private static void onResult(EditorSession s, GeneratorSettings g, String json, VoxelResult r) {
        if (r == null) return;
        result = r;
        resultJson = json;
        ObjectNode o = bound(s);
        if (o == null || json.equals(storedJson)) return;
        if (dev.vitruvius.editor.world.ShapeHandles.regenerate(s, o, g, r, o.transform().pos())) {
            storedJson = json;
            expectApply = true;
            ShapePresets.setLast(g);
        }
    }

    private static void createOutput(EditorSession s, dev.vitruvius.api.ShapeOutput out, ObjectNode o) {
        String err;
        try {
            String folderId = out.usesListFolders() ? listFolder : null;
            Path dir = null;
            if (out.usesSchematicFolder()) {
                dir = schematicFolder(s);
                if (dir == null) return;
            }
            Vec3i p = o.transform().pos();
            err = out.create(new dev.vitruvius.api.ShapeActionContext(result, name.get().trim(), new BlockPos(p.x(), p.y(), p.z())), option.get(), folderId, dir);
        } catch (Throwable ex) {
            err = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        }
        activeOutput = -1;           // create() removed its own ghost
        lastOutKey = "";             // a new ghost for the next tweak
        String what = out.label().toLowerCase(java.util.Locale.ROOT);
        if (err != null) s.toast(ToastQueue.Level.WARN, "Not created: " + err);
        else s.toast(ToastQueue.Level.INFO, "Created " + what + " \"" + name.get().trim() + "\" at the shape's position.");
    }

    /** The schematics subfolder typed or chosen (made when missing), or null with a toast when it is not valid. */
    private static Path schematicFolder(EditorSession s) {
        Path root = ShapeSaver.root();
        Path dir;
        try {
            dir = root.resolve(folder.get().trim()).normalize();
        } catch (RuntimeException e) {
            s.toast(ToastQueue.Level.WARN, "That is not a valid folder name");
            return null;
        }
        if (!dir.startsWith(root)) {
            s.toast(ToastQueue.Level.WARN, "The folder must be inside the schematics folder");
            return null;
        }
        return dir;
    }

    private static final FolderPicker SCHEM_PICKER = new FolderPicker("schem");
    private static final FolderPicker LIST_PICKER = new FolderPicker("list");

    /** The list folder row: shows the chosen placement-list folder; Browse opens the folder window. */
    private static void drawListFolder(float sc) {
        var folders = dev.vitruvius.api.VitruviusApi.listFolders();
        String shown = "(top level)";
        for (var f : folders) if (f.id().equals(listFolder)) shown = f.name();
        if (listFolder != null && shown.equals("(top level)")) listFolder = null;     // the folder was deleted
        Ui.smallDim("Placement list folder");
        ImGui.setNextItemWidth(-90 * sc);
        ImGui.inputText("##listfolder", new ImString(shown, 128), imgui.flag.ImGuiInputTextFlags.ReadOnly);
        ImGui.sameLine();
        if (ImGui.button("Browse…##listfolder", 80 * sc, 0)) {
            LIST_PICKER.open("Choose list folder", "The new entry appears in this folder of the placement list.",
                    ShapeWindow::listTree, listFolder == null ? "" : listFolder,
                    (parentKey, name) -> dev.vitruvius.api.VitruviusApi.createFolder(name, parentKey.isEmpty() ? null : parentKey),
                    key -> listFolder = key.isEmpty() ? null : key);
        }
        LIST_PICKER.draw(sc);
    }

    private static FolderPicker.Node listTree() {
        FolderPicker.Node root = new FolderPicker.Node("(top level)", "");
        java.util.ArrayDeque<FolderPicker.Node> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        for (var f : dev.vitruvius.api.VitruviusApi.listFolders()) {        // flattened tree: depth says where it belongs
            while (stack.size() > f.depth() + 1) stack.pop();
            FolderPicker.Node n = new FolderPicker.Node(f.name(), f.id());
            stack.peek().children.add(n);
            stack.push(n);
        }
        return root;
    }

    /** The folder row: where the schematic file goes (inside Litematica's schematics folder). */
    private static void drawFolder(float sc) {
        Ui.smallDim("Save the schematic in");
        ImGui.setNextItemWidth(-90 * sc);
        ImGui.inputTextWithHint("##shapefolder", "(schematics folder)", folder);
        Ui.tooltip("A path inside Litematica's schematics folder. Type a new name to make a new folder, or use Browse.");
        ImGui.sameLine();
        if (ImGui.button("Browse…##shapefolder", 80 * sc, 0)) {
            SCHEM_PICKER.open("Choose schematic folder", "Schematics are saved as .litematic files in the selected folder.",
                    ShapeWindow::schematicTree, folder.get().trim().replace('\\', '/'),
                    ShapeWindow::createSchematicFolder, key -> folder.set(key));
        }
        SCHEM_PICKER.draw(sc);
    }

    private static FolderPicker.Node schematicTree() {
        Path root = ShapeSaver.root();
        FolderPicker.Node top = new FolderPicker.Node("(schematics folder)", "");
        addDirs(root, root, top, 0);
        return top;
    }

    private static void addDirs(Path root, Path dir, FolderPicker.Node into, int depth) {
        if (depth > 8) return;
        List<Path> subs = new ArrayList<>();
        try (var st = Files.list(dir)) {
            st.filter(Files::isDirectory).forEach(subs::add);
        } catch (java.io.IOException ignored) {
            return;
        }
        subs.sort(java.util.Comparator.comparing(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)));
        for (Path p : subs) {
            String n = p.getFileName().toString();
            if (n.startsWith(".") || (depth == 0 && n.equals("Projects"))) continue;
            FolderPicker.Node node = new FolderPicker.Node(n, root.relativize(p).toString().replace(java.io.File.separatorChar, '/'));
            into.children.add(node);
            addDirs(root, p, node, depth + 1);
        }
    }

    private static String createSchematicFolder(String parentKey, String name) {
        String clean = name.trim();
        if (clean.isEmpty() || clean.contains("/") || clean.contains("\\") || clean.equals(".") || clean.equals("..")
                || clean.matches(".*[<>:\"|?*].*")) return null;
        Path root = ShapeSaver.root();
        try {
            Path dir = root.resolve(parentKey).resolve(clean).normalize();
            if (!dir.startsWith(root)) return null;
            Files.createDirectories(dir);
            return root.relativize(dir).toString().replace(java.io.File.separatorChar, '/');
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private static GeneratorSettings settings(ShapeType t) {
        ShapeSpec spec = new ShapeSpec(t, sx.get(), sy.get(), sz.get(), Axis.values()[axis.get()], thickness.get(),
                param1.get(), param2.get(), false).clamped();
        if (t == ShapeType.CUSTOM) {
            return new GeneratorSettings(spec, outline.slot(), fill.slot(), name.get(), SketchPad.sketch(depth.get())).withSketchSize();
        }
        return new GeneratorSettings(spec, outline.slot(), fill.slot(), name.get());
    }

    // ------------------------------------------------------------------ controls

    private static void size(String label, ImInt v, float sc) {
        number(label, v, 1, ShapeSpec.MAX_SIZE, sc);
    }

    private static void number(String label, ImInt v, int lo, int hi, float sc) {
        ImGui.setNextItemWidth(130 * sc);
        if (ImGui.inputInt(label, v, 1, 8)) v.set(Math.max(lo, Math.min(hi, v.get())));
    }

    private static void slot(String id, SlotState st, String hint, float sc) {
        ImGui.setNextItemWidth(110 * sc);
        ImGui.combo("##" + id + "mode", st.mode, MODE_NAMES);
        PatternMode mode = PatternMode.values()[st.mode.get()];
        ImGui.sameLine();
        if (ImGui.button("Hand##" + id)) {
            String held = BlockParsing.fromMainHand();
            if (held != null) st.blocks.set(held);
        }
        Ui.tooltip("Use the block in your main hand");
        ImGui.setNextItemWidth(-1);
        ImGui.inputTextWithHint("##" + id + "blocks", mode == PatternMode.SINGLE ? hint : "2+ blocks: stone,andesite", st.blocks);
        switch (mode) {
            case CHECKER, BRICK -> {
                ImGui.setNextItemWidth(60 * sc);
                if (ImGui.inputInt("##" + id + "cx", st.cellX, 0, 0)) st.cellX.set(Math.max(1, st.cellX.get()));
                ImGui.sameLine();
                ImGui.setNextItemWidth(60 * sc);
                if (ImGui.inputInt("##" + id + "cy", st.cellY, 0, 0)) st.cellY.set(Math.max(1, st.cellY.get()));
                ImGui.sameLine();
                ImGui.setNextItemWidth(60 * sc);
                if (ImGui.inputInt("##" + id + "cz", st.cellZ, 0, 0)) st.cellZ.set(Math.max(1, st.cellZ.get()));
                ImGui.sameLine();
                Ui.smallDim(mode == PatternMode.BRICK ? "brick X Y Z" : "cell X Y Z");
            }
            case STRIPES, GRADIENT, DIAGONAL -> {
                ImGui.setNextItemWidth(60 * sc);
                ImGui.combo("##" + id + "axis", st.axis, AXIS_NAMES);
                ImGui.sameLine();
                ImGui.setNextItemWidth(80 * sc);
                if (ImGui.inputInt("width##" + id, st.width, 1, 4)) st.width.set(Math.max(1, st.width.get()));
                if (mode == PatternMode.GRADIENT && ImGui.checkbox("Dither##" + id, st.dither)) st.dither = !st.dither;
            }
            case MIX -> {
                ImGui.setNextItemWidth(110 * sc);
                ImGui.inputInt("Seed##" + id, st.seed, 1, 100);
                ImGui.sameLine();
                if (ImGui.button("Dice##" + id)) st.seed.set(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ library

    /** Writes the current shape as a .litematic file into the chosen schematics folder (a copy; the object stays). */
    private static void saveToLibrary(EditorSession s, GeneratorSettings g, ObjectNode o) {
        Path dir = schematicFolder(s);
        if (dir == null) return;
        ShapePresets.setFolder(ShapeSaver.relative(dir));
        String base = name.get().trim();
        String unique = base;
        for (int i = 2; Files.exists(ShapeSaver.fileFor(dir, unique)); i++) unique = base + " " + i;
        String err = ShapeSaver.write(result, dir, unique, ShapePresets.toJson(g));
        if (err != null) s.toast(ToastQueue.Level.WARN, "Not saved: " + err);
        else s.toast(ToastQueue.Level.INFO, "Saved \"" + unique + "\" in " + ShapeSaver.display(dir));
    }
}
