package dev.vitruvius.editor.view;

import dev.cg.uihost.Theme;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.core.Node;
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
import dev.vitruvius.shape.mc.ShapePreview;
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

    private static boolean initialised;
    private static UUID parent;

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
    private static boolean anchorCenter;
    private static final ImString name = new ImString("shape", 64);
    private static final SlotState outline = new SlotState(), fill = new SlotState();

    // preview
    private static BlockPos target = BlockPos.ZERO;     // the point the shape is anchored at
    private static GeneratorSettings requested;
    private static VoxelResult result;
    private static String error;
    private static String lastShownKey = "";
    private static final ImString folder = new ImString("Shapes", 128);
    private static final ImInt output = new ImInt(0);      // 0 = schematic, n = shapeOutputs().get(n - 1)
    private static final ImInt option = new ImInt(0);
    private static String outputStatus;
    private static int activeOutput = -1;                  // the output whose ghost is up (index into shapeOutputs()), or -1
    private static boolean blockGhost;
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

    /** Opens the window; the Add menu passes the parent the shape should end up in. */
    public static void open(EditorSession s, UUID parentId) {
        parent = parentId;
        s.showShape = true;
        s.windowsHidden = false;
        if (!initialised) {
            initialised = true;
            GeneratorSettings g = ShapePresets.last();
            ShapeSpec sp = g.spec();
            type.set(sp.type().ordinal());
            sx.set(sp.sizeX());
            sy.set(sp.sizeY());
            sz.set(sp.sizeZ());
            axis.set(sp.axis().ordinal());
            thickness.set(sp.thickness());
            param1.set(sp.param1());
            param2.set(sp.param2());
            anchorCenter = sp.anchorCenter();
            outline.load(g.outline());
            fill.load(g.fill());
            String f = ShapePresets.folder();
            folder.set(f == null || f.isBlank() ? FOLDER : f);
            name.set(g.name() == null || g.name().isBlank() ? "shape" : g.name());
        }
        moveHere();
        requested = null;     // force a fresh preview
    }

    /** Removes the ghost and drops pending work; safe to call at any time (editor exit, window closed). */
    public static void close() {
        PreviewWorker.cancel();
        ShapePreview.clear();
        blockGhost = false;
        clearOutputGhost();
        requested = null;
        result = null;
        lastShownKey = "";
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
        outputStatus = null;
    }

    private static void moveHere() {
        var t = dev.vitruvius.editor.cam.EditorCamera.get().target();
        target = new BlockPos((int) Math.floor(t.x), (int) Math.floor(t.y), (int) Math.floor(t.z));
        lastShownKey = "";
    }

    // ------------------------------------------------------------------ window

    public static void draw(EditorSession s) {
        float sc = Ui.s();
        var vp = ImGui.getMainViewport();
        OPEN.set(s.showShape);
        ImGui.setNextWindowPos(vp.getPosX() + vp.getSizeX() - 340 * sc - 12 * sc, vp.getPosY() + 56 * sc, s.resetLayout ? ImGuiCond.Always : ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(330 * sc, Math.max(300 * sc, vp.getSizeY() * 0.7f), s.resetLayout ? ImGuiCond.Always : ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSizeConstraints(280 * sc, 200 * sc, 4000, 4000);
        boolean visible = Ui.beginWindow("Shape generator", OPEN, 0, 0.96f);
        if (visible) drawContent(s, sc);
        Ui.endWindow();
        s.showShape = OPEN.get();
        if (!s.showShape) close();
    }

    private static void drawContent(EditorSession s, float sc) {
        ShapeType t = TYPES[type.get()];
        float w = -1;

        Ui.section("Shape");
        ImGui.setNextItemWidth(w);
        ImGui.combo("##shapetype", type, TYPE_NAMES);
        t = TYPES[type.get()];
        size("X", sx, sc);
        size("Y", sy, sc);
        size("Z", sz, sc);
        if (t.usesAxis()) {
            ImGui.setNextItemWidth(80 * sc);
            ImGui.combo("Axis", axis, AXIS_NAMES);
        }
        number("Thickness", thickness, 1, ShapeSpec.MAX_SIZE, sc);
        if (t.param1Label != null) number(t.param1Label, param1, 0, ShapeSpec.MAX_SIZE, sc);
        if (t.param2Label != null) number(t.param2Label, param2, 0, ShapeSpec.MAX_SIZE, sc);

        Ui.section("Outline");
        slot("outline", outline, "stone, or 60%stone,40%andesite", sc);
        Ui.section("Fill");
        slot("fill", fill, "empty = hollow", sc);

        Ui.section("Placement");
        if (ImGui.checkbox("Anchor at the center", anchorCenter)) anchorCenter = !anchorCenter;
        Ui.tooltip("Off: the camera target is the shape's low corner. On: it is the middle of the box.");
        if (ImGui.button("Move to camera target")) moveHere();
        Ui.tooltip("Orbit or fly the camera, then click: the shape jumps to what the camera looks at.");
        ImGui.sameLine();
        Ui.smallDim(target.getX() + ", " + target.getY() + ", " + target.getZ());

        var outs = dev.vitruvius.api.VitruviusApi.shapeOutputs();
        if (output.get() > outs.size()) output.set(0);
        dev.vitruvius.api.ShapeOutput out = output.get() == 0 ? null : outs.get(output.get() - 1);
        if (!outs.isEmpty()) {
            Ui.section("Output");
            String[] names = new String[outs.size() + 1];
            names[0] = "Schematic";
            for (int i = 0; i < outs.size(); i++) names[i + 1] = outs.get(i).label();
            ImGui.setNextItemWidth(130 * sc);
            if (ImGui.combo("##shapeoutput", output, names)) option.set(0);
            out = output.get() == 0 ? null : outs.get(output.get() - 1);
            if (out != null) {
                var opts = out.options();
                if (option.get() >= opts.size()) option.set(0);
                ImGui.sameLine();
                ImGui.setNextItemWidth(-1);
                ImGui.combo("##shapeoption", option, opts.toArray(new String[0]));
            }
        }

        // build the settings and (re)generate when something changed
        String blockError = outline.error() != null ? outline.error() : fill.error();
        GeneratorSettings g = settings(t);
        BlockPos origin = originFor(g.spec());
        if (blockError == null) {
            if (!g.equals(requested)) {
                requested = g;
                PreviewWorker.request(g, r -> {
                    result = r;
                    lastShownKey = "";      // show it below with the current origin
                });
            }
            String key = origin.toString() + (result == null ? 0 : System.identityHashCode(result)) + "/" + output.get() + "/" + option.get();
            if (result != null && !key.equals(lastShownKey)) {
                lastShownKey = key;
                if (out == null) {
                    clearOutputGhost();
                    ShapePreview.show(result, origin);
                    blockGhost = true;
                } else {
                    if (blockGhost) {
                        ShapePreview.clear();
                        blockGhost = false;
                    }
                    int idx = output.get() - 1;
                    if (activeOutput >= 0 && activeOutput != idx) clearOutputGhost();
                    activeOutput = idx;
                    try {
                        outputStatus = out.preview(new dev.vitruvius.api.ShapeActionContext(result, name.get().trim(), origin), option.get());
                    } catch (Throwable ex) {
                        outputStatus = out.label() + " preview failed: " + ex.getMessage();
                    }
                }
            }
        }
        error = blockError;

        Ui.section("Create");
        ImGui.setNextItemWidth(w);
        ImGui.inputText("##shapename", name);
        if (out == null) drawFolder(sc);
        else if (out.usesListFolders()) drawListFolder(sc);
        boolean busy = PreviewWorker.busy();
        if (error != null) Ui.colored(Theme.ERR, error);
        else if (busy) Ui.smallDim("Generating…");
        else if (out != null && outputStatus != null) Ui.smallDim(outputStatus);
        else if (result != null) Ui.smallDim(result.blockCount() + " blocks, " + result.sizeX() + " x " + result.sizeY() + " x " + result.sizeZ()
                + "  at " + origin.getX() + ", " + origin.getY() + ", " + origin.getZ());
        boolean can = error == null && !busy && result != null && result.blockCount() > 0 && !name.get().isBlank();
        String why = error != null ? error : busy ? "Still generating" : result == null || result.blockCount() == 0 ? "The shape is empty" : "Give it a name";
        String create = out == null ? "Generate" : "Create " + out.label().toLowerCase(java.util.Locale.ROOT);
        if (Ui.button(create, true, can, 150 * sc, why)) {
            if (out == null) generate(s, g, origin);
            else createOutput(s, out, origin);
        }
        if (out != null && out.hover() != null && can) Ui.tooltip(out.hover());
        ImGui.sameLine();
        if (ImGui.button("Close", 80 * sc, 0)) s.showShape = false;

        // old-style buttons other mods registered (only when no output of theirs is there)
        var actions = dev.vitruvius.api.VitruviusApi.shapeActions();
        if (!actions.isEmpty() && outs.isEmpty()) {
            Ui.section("Send the shape to");
            for (var a : actions) {
                if (Ui.button(a.label() + "##shapeaction", false, can, 0, why)) runAction(s, a, g, origin);
                if (a.hover() != null && can) Ui.tooltip(a.hover());
                ImGui.sameLine();
            }
            ImGui.newLine();
            Ui.smallDim("These open their own dialog, which leaves the editor (press F6 to come back).");
        }
    }

    private static void createOutput(EditorSession s, dev.vitruvius.api.ShapeOutput out, BlockPos origin) {
        String err;
        try {
            String folderId = out.usesListFolders() ? listFolder : null;
            err = out.create(new dev.vitruvius.api.ShapeActionContext(result, name.get().trim(), origin), option.get(), folderId);
        } catch (Throwable ex) {
            err = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        }
        activeOutput = -1;           // create() removed its own ghost
        lastShownKey = "";           // a new ghost for the next tweak
        String what = out.label().toLowerCase(java.util.Locale.ROOT);
        if (err != null) s.toast(ToastQueue.Level.WARN, "Not created: " + err);
        else s.toast(ToastQueue.Level.INFO, "Created " + what + " \"" + name.get().trim() + "\". Change the settings to make another.");
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

    private static void runAction(EditorSession s, dev.vitruvius.api.VitruviusApi.ShapeAction a, GeneratorSettings g, BlockPos origin) {
        try {
            a.action().accept(new dev.vitruvius.api.ShapeActionContext(result, name.get().trim(), origin));
        } catch (Throwable t) {
            s.toast(ToastQueue.Level.WARN, a.label() + " failed: " + t.getMessage());
        }
    }

    private static GeneratorSettings settings(ShapeType t) {
        ShapeSpec spec = new ShapeSpec(t, sx.get(), sy.get(), sz.get(), Axis.values()[axis.get()], thickness.get(),
                param1.get(), param2.get(), anchorCenter).clamped();
        return new GeneratorSettings(spec, outline.slot(), fill.slot(), name.get());
    }

    private static BlockPos originFor(ShapeSpec spec) {
        if (!spec.anchorCenter()) return target;
        return target.offset(-(spec.sizeX() / 2), -(spec.sizeY() / 2), -(spec.sizeZ() / 2));
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

    // ------------------------------------------------------------------ generate

    private static void generate(EditorSession s, GeneratorSettings g, BlockPos origin) {
        Path root = ShapeSaver.root();
        Path folder;
        try {
            folder = root.resolve(ShapeWindow.folder.get().trim()).normalize();
        } catch (RuntimeException e) {
            s.toast(ToastQueue.Level.WARN, "That is not a valid folder name");
            return;
        }
        if (!folder.startsWith(root)) {
            s.toast(ToastQueue.Level.WARN, "The folder must be inside the schematics folder");
            return;
        }
        ShapePresets.setFolder(ShapeSaver.relative(folder));
        String base = name.get().trim();
        String unique = base;
        for (int i = 2; Files.exists(ShapeSaver.fileFor(folder, unique)); i++) unique = base + " " + i;
        String err = ShapeSaver.write(result, folder, unique);
        if (err != null) {
            s.toast(ToastQueue.Level.WARN, "Shape not saved: " + err);
            return;
        }
        ShapePresets.setLast(new GeneratorSettings(g.spec(), g.outline(), g.fill(), base));
        String rel = ShapeSaver.root().relativize(ShapeSaver.fileFor(folder, unique)).toString().replace(java.io.File.separatorChar, '/');
        UUID target = parent != null && s.scene().byId(parent) != null ? parent
                : (s.scene().activeProject() != null ? s.scene().activeProject().id() : s.scene().loose().id());
        close();      // the ghost goes first: the new object takes its place
        AddObject cmd = new AddObject(target, -1, unique, rel, new Vec3i(origin.getX(), origin.getY(), origin.getZ()));
        if (s.actions.execute(cmd)) {
            s.selection.set(cmd.objectId());
            Node n = s.scene().byId(cmd.objectId());
            if (n != null) s.tree.reveal(n);
            s.scrollToRow = cmd.objectId();
            s.toast(ToastQueue.Level.INFO, "Added \"" + unique + "\". Change the settings to make another.");
        }
        requested = null;
    }
}
