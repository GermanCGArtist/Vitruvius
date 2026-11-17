package dev.schemarray.shape.gui;

import dev.schemarray.api.SchematicArrayApi;
import dev.schemarray.api.ShapeActionContext;
import dev.schemarray.shape.Axis;
import dev.schemarray.shape.GeneratorSettings;
import dev.schemarray.shape.ShapeSpec;
import dev.schemarray.shape.ShapeType;
import dev.schemarray.shape.VoxelResult;
import dev.schemarray.shape.mc.PreviewWorker;
import dev.schemarray.shape.mc.ShapePresets;
import dev.schemarray.shape.mc.ShapePreview;
import dev.schemarray.shape.mc.ShapeSaver;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetLabel;
import fi.dy.masa.malilib.gui.wrappers.TextFieldType;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The Shape Generator screen, opened from Litematica's main menu: controls on the left, the 3D thumbnail on the right,
 * name / folder / presets / Generate at the bottom. Every change rebuilds the settings and asks PreviewWorker for a new
 * preview (thumbnail + ghost in the world). Peek (button held, or G held outside text fields) hides the panel to look
 * at the ghost.
 *
 *   Shape Generator
 *   Shape [Box  v]      Outline [Single v]            +--------------+
 *   X[ ][+-] Y[ ][+-]   [stone         ][Hand]        |              |
 *   Axis [Y]            Fill    [Single v]            |  3D preview  |
 *   Thickness [ ][+-]   [empty = hollow][Hand]        |              |
 *   (Param1/2 [ ][+-])                                +--------------+
 *   [Anchor: Corner][Ghost: On]                        [Reset view]
 *   Name [shape      ]
 *   Folder [Browse...] (schematics)/walls
 *   Presets [v] [name] [Load] [Save] [Delete]
 *   [Generate] [Cancel] [Peek]
 *   status line
 *
 * Layout: three columns computed once in initGui from the screen size, and no widget leaves its column: the left
 * column (LEFT_W wide), the block editor column (EDIT_W) and the thumbnail, which takes what is left of the width (at
 * least MIN_THUMB; the screen needs about 450 GUI pixels in width) and ends above the bottom rows. The bottom block
 * (Name, Folder, Presets, buttons, status line) is anchored to the screen bottom and spans the columns. When the screen
 * is short (GUI scale 4, 480x270) the rows shrink from 22 to 20 pixels and the title gap from 26 to 20, so both
 * columns still end above the bottom block; each editor is as high as the fields its mode shows. After each initGui
 * checkLayout() prints one line to the log if two widgets overlap.
 *
 * Block fields, drop downs and rows are rebuilt by initGui when a shape or mode changes; the values live in the
 * screen's and the editors' fields, so a rebuild (or a window resize) loses nothing. Opening the folder picker keeps
 * the generator running (preview, ghost); only really closing it runs removed()'s cleanup.
 */
public class ShapeGeneratorScreen extends GuiBase {
    private static final int WHITE = 0xFFFFFFFF, RED = 0xFFFF5555, YELLOW = 0xFFFFFF55, GRAY = 0xFFAAAAAA;
    private static final int LEFT = 12, LEFT_W = 176, GAP = 8, EDIT_W = 190, MIN_THUMB = 40;
    private static final int COL_B = LEFT + LEFT_W + GAP, THUMB_X = COL_B + EDIT_W + GAP;

    private ShapeType type;
    private int sizeX, sizeY, sizeZ, thickness, param1, param2;
    private Axis axis;
    private boolean anchorCenter;
    private boolean ghost = ShapePresets.ghost();
    private BlockPos origin;   // where the ghost sits (or sat, with the ghost off)
    private String name;
    private String presetSelected, presetText = "";
    private final BlockSlotEditor outline, fill;

    private final ShapeThumbnail thumbnail = new ShapeThumbnail();
    private VoxelResult result;
    private boolean started;
    private boolean needOrigin = true;   // the next preview picks its spot (first one, and after the Anchor toggle)
    private String message;

    private final List<GuiTextFieldGeneric> fields = new ArrayList<>();
    private final List<ShapeDropDown<?>> dropDowns = new ArrayList<>();
    private ShapeDropDown<ShapeType> typeBox;
    private ShapeDropDown<String> presetBox;
    private ButtonGeneric generate;
    private int row = 22;
    private Path folder = ShapeSaver.resolveFolder(ShapePresets.folder());
    private boolean pickerOpen;   // the folder picker is on top: removed() must not clean up
    private final List<Object> placed = new ArrayList<>();   // widgets and text rects for checkLayout()
    private int thumbX, thumbY, thumbW, thumbH;
    private boolean thumbDrag, peekButton, peekKey;
    private int realMouseX, realMouseY;

    public ShapeGeneratorScreen(Screen parent) {
        this.setParent(parent);
        this.title = "Shape Generator";
        GeneratorSettings s = ShapePresets.last();
        outline = new BlockSlotEditor("Outline", "", s.outline(), this::changed, this::say);
        fill = new BlockSlotEditor("Fill", "empty = hollow", s.fill(), this::changed, this::say);
        apply(s);
        needOrigin = true;
        List<String> presets = ShapePresets.names();
        if (!presets.isEmpty()) presetSelected = presets.get(0);
    }

    private GeneratorSettings settings() {
        ShapeSpec spec = new ShapeSpec(type, sizeX, sizeY, sizeZ, axis, thickness, param1, param2, anchorCenter).clamped();
        return new GeneratorSettings(spec, outline.slot(), fill.slot(), name);
    }

    private void apply(GeneratorSettings s) {
        ShapeSpec spec = s.spec().clamped();
        type = spec.type();
        sizeX = spec.sizeX();
        sizeY = spec.sizeY();
        sizeZ = spec.sizeZ();
        axis = spec.axis();
        thickness = spec.thickness();
        param1 = spec.param1();
        param2 = spec.param2();
        if (anchorCenter != spec.anchorCenter()) needOrigin = true;
        anchorCenter = spec.anchorCenter();
        name = s.name() == null ? "shape" : s.name();
        outline.set(s.outline());
        fill.set(s.fill());
    }

    private void say(String text) {
        message = text;
    }

    private String blockError() {
        return outline.error() != null ? outline.error() : fill.error();
    }

    /** Something that changes the shape: asks for a new preview (not while a block name is invalid). */
    private void changed() {
        message = null;
        boolean ok = blockError() == null;
        if (generate != null) generate.setEnabled(ok);
        if (!ok) {
            PreviewWorker.cancel();
            return;
        }
        GeneratorSettings s = settings();
        PreviewWorker.request(s, r -> {
            if (r == null) return;
            result = r;
            thumbnail.setResult(r);
            showGhost();
        });
    }

    /** The ghost's spot: a new one after the first preview and the Anchor toggle, else where Litematica's tools left it. */
    private BlockPos origin() {
        if (needOrigin || origin == null) origin = ShapePreview.originFor(settings().spec());
        else if (ShapePreview.currentOrigin() != null) origin = ShapePreview.currentOrigin();
        needOrigin = false;
        return origin;
    }

    /** Puts the current result into the world, unless the ghost is off. */
    private void showGhost() {
        BlockPos at = origin();
        if (ghost && result != null) ShapePreview.show(result, at);
    }

    // ---- widgets ----

    @Override
    public void initGui() {
        super.initGui();
        pickerOpen = false;
        fields.clear();
        dropDowns.clear();
        placed.clear();
        int w = getScreenWidth(), h = getScreenHeight();
        row = h >= 320 ? 22 : 20;
        int top = row == 22 ? 26 : 20;
        int bottomY = h - 14 - 4 * row;   // first of the four bottom rows; the status line sits below them

        int y = top;
        addLabel(LEFT, y + 6, 70, 10, WHITE, "Shape");
        typeBox = new ShapeDropDown<>(LEFT + 72, y, 100, List.of(ShapeType.values()), t -> t.displayName, h);
        typeBox.setSelectedEntry(type);
        addDropDown(typeBox);
        y += row;

        sizeRow(y);
        y += row;

        if (type.usesAxis()) {
            addLabel(LEFT, y + 6, 70, 10, WHITE, "Axis");
            ButtonGeneric axisButton = new ButtonGeneric(LEFT + 72, y, 40, 20, axis.name(),
                    "Left click: next axis, right click: previous.");
            addButton(axisButton, (b, mouse) -> {
                axis = Axis.values()[Math.floorMod(axis.ordinal() + (mouse == 1 ? -1 : 1), 3)];
                initGui();
                changed();
            });
            y += row;
        }
        if (type.usesThickness()) {
            addLabel(LEFT, y + 6, 70, 10, WHITE, "Thickness");
            addInt(LEFT + 72, y, 40, thickness, 1, ShapeSpec.MAX_SIZE, v -> {
                thickness = v;
                changed();
            }, "Outline thickness in blocks");
            y += row;
        }
        int min1 = type == ShapeType.CONE || type == ShapeType.PYRAMID ? 0 : 1;
        if (type.param1Label != null) {
            addLabel(LEFT, y + 6, 70, 10, WHITE, type.param1Label);
            addInt(LEFT + 72, y, 40, param1, min1, ShapeSpec.MAX_SIZE, v -> {
                param1 = v;
                changed();
            }, type.param1Label);
            y += row;
        }
        if (type.param2Label != null) {
            addLabel(LEFT, y + 6, 70, 10, WHITE, type.param2Label);
            addInt(LEFT + 72, y, 40, param2, 1, ShapeSpec.MAX_SIZE, v -> {
                param2 = v;
                changed();
            }, type.param2Label);
            y += row;
        }
        int anchorW = 100;
        ButtonGeneric anchor = new ButtonGeneric(LEFT, y, anchorW, 20, anchorLabel(),
                "Where the preview and the placement sit relative to you:", "the min corner of the shape or its center.");
        addButton(anchor, (b, mouse) -> {
            anchorCenter = !anchorCenter;
            needOrigin = true;
            anchor.setDisplayString(anchorLabel());
            changed();
        });
        ButtonGeneric ghostButton = new ButtonGeneric(LEFT + anchorW + 4, y, LEFT_W - anchorW - 4, 20, ghostLabel(),
                "Shows the shape as a ghost in the world.", "Turn it off if the preview slows the game down.");
        addButton(ghostButton, (b, mouse) -> {
            ghost = !ghost;
            ShapePresets.setGhost(ghost);
            ghostButton.setDisplayString(ghostLabel());
            if (ghost) {
                showGhost();
            } else {
                origin();   // remember where it was before it goes
                ShapePreview.clear();
            }
        });

        int by = outline.init(this, COL_B, top, EDIT_W, row);
        fill.init(this, COL_B, by + 4, EDIT_W, row);

        bottomRows(bottomY);

        thumbX = THUMB_X;
        thumbY = top;
        thumbW = Math.max(MIN_THUMB, w - LEFT - thumbX);
        int resetY = bottomY - 24;
        thumbH = Math.max(MIN_THUMB, resetY - 4 - thumbY);
        addButton(new ButtonGeneric(thumbX, resetY, Math.min(80, thumbW), 20, "Reset view"), (b, mouse) -> thumbnail.resetView());

        if (!started) {
            started = true;
            changed();
        } else {
            generate.setEnabled(blockError() == null);
        }
        checkLayout();
    }

    // ---- layout self-check ----

    @Override
    public <T extends ButtonBase> T addButton(T button, IButtonActionListener listener) {
        placed.add(button);
        return super.addButton(button, listener);
    }

    @Override
    public <T extends WidgetBase> T addWidget(T widget) {
        placed.add(widget);
        return super.addWidget(widget);
    }

    @Override
    public WidgetLabel addLabel(int x, int y, int width, int height, int color, List<String> lines) {
        WidgetLabel label = super.addLabel(x, y, width, height, color, lines);
        placed.add(label);
        return label;
    }

    /** Registers a drawn-only text (the grey hint line) for checkLayout(). */
    void placeText(int x, int y, String text) {
        placed.add(new int[] {x, y, getStringWidth(text), 9});
    }

    /** Prints one log line with the overlapping rectangles (x,y wxh), if any. Runs after every initGui. */
    private void checkLayout() {
        List<int[]> rects = new ArrayList<>();
        for (Object o : placed) {
            int[] r = switch (o) {
                case WidgetBase b -> new int[] {b.getX(), b.getY(), b.getWidth(), b.getHeight()};
                case GuiTextFieldGeneric f -> new int[] {f.getX(), f.getY(), f.getWidth(), f.getHeight()};
                case int[] t -> t;
                default -> null;
            };
            if (r != null && r[2] > 0 && r[3] > 0 && rects.stream().noneMatch(e -> e == r)) rects.add(r);
        }
        StringBuilder out = new StringBuilder();
        int found = 0;
        for (int i = 0; i < rects.size(); i++) {
            for (int j = i + 1; j < rects.size(); j++) {
                int[] a = rects.get(i), b = rects.get(j);
                if (a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3] && found++ < 6) {
                    out.append(" [").append(a[0]).append(',').append(a[1]).append(' ').append(a[2]).append('x').append(a[3])
                            .append(" / ").append(b[0]).append(',').append(b[1]).append(' ').append(b[2]).append('x').append(b[3]).append(']');
                }
            }
        }
        if (found > 0) System.out.println("[SchematicArray] Shape Generator layout, " + found + " overlap(s) at "
                + getScreenWidth() + "x" + getScreenHeight() + ":" + out);
    }

    private String ghostLabel() {
        return "Ghost: " + (ghost ? "On" : "Off");
    }

    private String anchorLabel() {
        return "Anchor: " + (anchorCenter ? "Center" : "Corner");
    }

    private void sizeRow(int y) {
        String[] labels = {"X", "Y", "Z"};
        int[] sizes = {sizeX, sizeY, sizeZ};
        for (int i = 0; i < 3; i++) {
            int gx = LEFT + i * 58, index = i;
            addLabel(gx, y + 6, 8, 10, WHITE, labels[i]);
            boolean flat = type == ShapeType.PLANE && axis.ordinal() == i;   // a plane is 1 thick along its axis
            IntControl c = addInt(gx + 10, y, 28, flat ? 1 : sizes[i], 1, ShapeSpec.MAX_SIZE, v -> {
                switch (index) {
                    case 0 -> sizeX = v;
                    case 1 -> sizeY = v;
                    default -> sizeZ = v;
                }
                changed();
            }, "Size along " + labels[i] + ": left click +1, right click -1 (Shift x8, Alt x4).");
            if (flat) c.setEnabled(false);
        }
    }

    private void bottomRows(int y) {
        addLabel(LEFT, y + 6, 44, 10, WHITE, "Name");
        GuiTextFieldGeneric nameField = new GuiTextFieldGeneric(LEFT + 44, y + 2, 150, 16, font);
        nameField.setValueWrapper(name);
        addField(nameField, f -> {
            name = f.getValueWrapper();
            message = null;
            return false;
        }, TextFieldType.STRING);
        addLabel(LEFT + 200, y + 6, 160, 10, GRAY, "File name of the schematic");

        y += row;
        addLabel(LEFT, y + 6, 44, 10, WHITE, "Folder");
        addButton(new ButtonGeneric(LEFT + 44, y, 64, 20, "Browse...", "Choose the folder inside Litematica's",
                "schematics folder to save into."), (b, mouse) -> browse());
        int pathW = LEFT_W + GAP + EDIT_W - 112;
        addLabel(LEFT + 112, y + 6, pathW, 10, WHITE, ellipsize(ShapeSaver.display(folder), pathW));

        y += row;
        addLabel(LEFT, y + 6, 44, 10, WHITE, "Presets");
        List<String> names = ShapePresets.names();
        presetBox = new ShapeDropDown<>(LEFT + 44, y, 110, names.isEmpty() ? List.of("(none)") : names, n -> n, getScreenHeight());
        if (presetSelected != null) presetBox.setSelectedEntry(presetSelected);
        addDropDown(presetBox);
        GuiTextFieldGeneric presetField = new GuiTextFieldGeneric(LEFT + 164, y + 2, 90, 16, font);
        presetField.setValueWrapper(presetText);
        presetField.setHint(Component.literal("preset name"));
        addField(presetField, f -> {
            presetText = f.getValueWrapper();
            return false;
        }, TextFieldType.STRING);
        addButton(new ButtonGeneric(LEFT + 258, y, 40, 20, "Load"), (b, mouse) -> loadPreset());
        addButton(new ButtonGeneric(LEFT + 302, y, 40, 20, "Save", "Saves the current settings under the preset name."),
                (b, mouse) -> savePreset());
        addButton(new ButtonGeneric(LEFT + 346, y, 50, 20, "Delete", "Hold Shift to delete the selected preset."),
                (b, mouse) -> deletePreset());

        y += row;
        generate = new ButtonGeneric(LEFT, y, 70, 20, "Generate", "Writes the schematic and places it.", "Hold Shift to overwrite an existing file.");
        addButton(generate, (b, mouse) -> generate());
        addButton(new ButtonGeneric(LEFT + 74, y, 60, 20, "Cancel"), (b, mouse) -> closeGui(true));
        addButton(new ButtonGeneric(LEFT + 138, y, 60, 20, "Peek", "Hold to hide this panel and look at the preview", "in the world (or hold G)."),
                (b, mouse) -> peekButton = true);

        // buttons that other mods registered (SchematicArrayApi.registerShapeAction), right of Peek
        int ax = LEFT + 202;
        for (SchematicArrayApi.ShapeAction a : SchematicArrayApi.shapeActions()) {
            int w = getStringWidth(a.label()) + 14;
            ButtonGeneric b = a.hover() == null ? new ButtonGeneric(ax, y, w, 20, a.label())
                    : new ButtonGeneric(ax, y, w, 20, a.label(), a.hover());
            addButton(b, (btn, mouse) -> runShapeAction(a));
            ax += w + 4;
        }
    }

    /** Calls a registered shape action with the current preview (name field, origin of the ghost). */
    private void runShapeAction(SchematicArrayApi.ShapeAction a) {
        if (result == null || PreviewWorker.busy()) {
            message = "Wait for the preview to finish";
            return;
        }
        try {
            a.action().accept(new ShapeActionContext(result, name == null ? "" : name.trim(), origin()));
        } catch (Throwable t) {
            message = a.label() + " failed: " + t.getMessage();
            System.out.println("[SchematicArray] shape action failed: " + t);
        }
    }

    /** Text shortened from the left ("...ls/castle") to fit width pixels. */
    private String ellipsize(String text, int width) {
        while (text.length() > 4 && getStringWidth(text) > width) text = "..." + text.substring(4);
        return text;
    }

    /** Opens the folder picker on top; this screen keeps its state, the preview and the ghost meanwhile. */
    private void browse() {
        pickerOpen = true;
        GuiBase.openGui(new FolderPickerScreen(this, ShapeSaver.root(), folder, picked -> {
            folder = picked;
            ShapePresets.setFolder(ShapeSaver.relative(picked));
            message = null;
        }));
    }

    // ---- helpers the editors use too ----

    <T extends GuiTextFieldGeneric> T addField(T field, ITextFieldListener<T> listener, TextFieldType fieldType) {
        addTextField(field, listener, fieldType);
        fields.add(field);
        placed.add(field);
        return field;
    }

    void addDropDown(ShapeDropDown<?> box) {
        addWidget(box);
        dropDowns.add(box);
    }

    /** An integer field (clamped to min..max) and the Litematica-style +- button: left click +1, right click -1, Shift x8, Alt x4. */
    IntControl addInt(int x, int y, int fieldWidth, int value, int min, int max, IntConsumer set, String hover) {
        GuiTextFieldInteger field = addIntField(x, y, fieldWidth, value, min, max, set);
        ButtonGeneric button = new ButtonGeneric(x + fieldWidth + 2, y + 2, Icons.BUTTON_PLUS_MINUS_16, hover);
        addButton(button, (b, mouse) -> {
            int step = (mouse == 1 ? -1 : 1) * (isShiftDown() ? 8 : 1) * (isAltDown() ? 4 : 1);
            int v = Math.max(min, Math.min(max, parse(field.getValueWrapper(), value) + step));
            field.setValueWrapper(String.valueOf(v));
            set.accept(v);
        });
        return new IntControl(field, button);
    }

    /** An integer field without buttons. */
    GuiTextFieldInteger addIntField(int x, int y, int fieldWidth, int value, int min, int max, IntConsumer set) {
        GuiTextFieldInteger field = new GuiTextFieldInteger(x, y + 3, fieldWidth, 14, font);
        field.setValueWrapper(String.valueOf(value));
        addField(field, f -> {
            String t = f.getValueWrapper().trim();
            if (!t.isEmpty() && !t.equals("-")) set.accept(Math.max(min, Math.min(max, parse(t, min))));
            return false;
        }, TextFieldType.INTEGER);
        return field;
    }

    private static int parse(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** An integer field with its +- button, so a row can be greyed out together. */
    record IntControl(GuiTextFieldInteger field, ButtonGeneric button) {
        void setEnabled(boolean on) {
            field.setEditable(on);
            button.setEnabled(on);
        }
    }

    // ---- actions ----

    private void loadPreset() {
        GeneratorSettings s = presetSelected == null ? null : ShapePresets.get(presetSelected);
        if (s == null) {
            message = "No preset selected";
            return;
        }
        apply(s);
        presetText = presetSelected;
        initGui();
        changed();
        message = "Loaded preset " + presetSelected;
    }

    private void savePreset() {
        String n = presetText.trim();
        if (n.isEmpty()) {
            message = "Type a preset name first";
            return;
        }
        ShapePresets.put(n, settings());
        presetSelected = n;
        initGui();
        message = "Saved preset " + n;
    }

    private void deletePreset() {
        if (presetSelected == null) {
            message = "No preset selected";
        } else if (!isShiftDown()) {
            message = "Hold Shift to delete the preset " + presetSelected;
        } else {
            ShapePresets.remove(presetSelected);
            message = "Deleted preset " + presetSelected;
            List<String> names = ShapePresets.names();
            presetSelected = names.isEmpty() ? null : names.get(0);
            initGui();
        }
    }

    private void generate() {
        String error = blockError();
        String file = name.trim();
        if (error != null) {
            message = error;
        } else if (file.isEmpty()) {
            message = "Type a file name first";
        } else if (result == null || PreviewWorker.busy()) {
            message = "Wait for the preview to finish";
        } else if (Files.exists(ShapeSaver.fileFor(folder, file)) && !isShiftDown()) {
            message = "File exists - hold Shift to overwrite";
        } else {
            String failed = ShapeSaver.save(result, folder, file, origin(), isShiftDown());
            if (failed == null) closeGui(true);
            else message = failed;
        }
    }

    // ---- frame, input ----

    private boolean peeking() {
        return peekButton || peekKey;
    }

    private boolean typing() {
        for (GuiTextFieldGeneric f : fields) if (f.isFocusedWrapper()) return true;
        for (ShapeDropDown<?> d : dropDowns) if (d.opened()) return true;
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (typeBox != null && typeBox.getSelectedEntry() != type) {
            type = typeBox.getSelectedEntry();
            initGui();
            changed();
        } else if (outline.pollMode() | fill.pollMode()) {
            initGui();
            changed();
        } else if (presetBox != null && presetBox.getSelectedEntry() != null && !presetBox.getSelectedEntry().equals(presetSelected)
                && ShapePresets.names().contains(presetBox.getSelectedEntry())) {
            presetSelected = presetBox.getSelectedEntry();
            presetText = presetSelected;
            initGui();
        }
    }

    private ShapeDropDown<?> openDropDown() {
        for (ShapeDropDown<?> d : dropDowns) if (d.opened()) return d;
        return null;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (!peeking()) {
            realMouseX = mouseX;
            realMouseY = mouseY;
            // the widgets under an open list get a mouse far away, so none of them highlights or shows a tooltip
            ShapeDropDown<?> open = openDropDown();
            boolean covered = open != null && open.isMouseOver(mouseX, mouseY);
            super.extractRenderState(graphics, covered ? -10000 : mouseX, covered ? -10000 : mouseY, partialTick);
            return;
        }
        String hint = "Peek - release to return";
        graphics.fill(6, 6, 14 + font.width(hint), 20, 0xB0000000);
        graphics.text(font, hint, 10, 9, WHITE);
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTick) {
        super.drawContents(ctx, mouseX, mouseY, partialTick);
        thumbnail.render(ctx, thumbX, thumbY, thumbW, thumbH, mouseX, mouseY);
        outline.drawHint(ctx, this);
        fill.drawHint(ctx, this);

        String text;
        int color = WHITE;
        if (blockError() != null) {
            text = blockError();
            color = RED;
        } else if (message != null) {
            text = message;
            color = YELLOW;
        } else if (PreviewWorker.busy()) {
            text = "Generating...";
            color = GRAY;
        } else if (result != null) {
            text = result.sizeX() + "x" + result.sizeY() + "x" + result.sizeZ() + ", " + result.blockCount() + " blocks";
        } else {
            text = "";
        }
        drawString(ctx, text, LEFT, getScreenHeight() - 18, color);
    }

    /** After the text fields, so the autocomplete list and an open drop down are on top of the controls below them. */
    @Override
    protected void drawHoveredWidget(GuiContext ctx, int mouseX, int mouseY) {
        super.drawHoveredWidget(ctx, mouseX, mouseY);
        outline.drawSuggestions(ctx, this, realMouseX, realMouseY);
        fill.drawSuggestions(ctx, this, realMouseX, realMouseY);
        ShapeDropDown<?> open = openDropDown();
        if (open != null) {
            ctx.nextStratum();
            open.drawList(ctx, realMouseX, realMouseY);
        } else {
            outline.drawFieldTooltip(ctx, realMouseX, realMouseY);
            fill.drawFieldTooltip(ctx, realMouseX, realMouseY);
        }
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (peekKey) return true;
        int mx = (int) event.x(), my = (int) event.y();
        // malilib hands clicks to buttons and fields before widgets: an open list gets them first, and a click
        // outside it only closes it
        ShapeDropDown<?> open = openDropDown();
        if (open != null) {
            if (open.isMouseOver(mx, my)) return open.onMouseClicked(event, doubleClick);
            open.close();
            return true;
        }
        for (ShapeDropDown<?> d : dropDowns) {
            if (!d.isMouseOver(mx, my)) continue;
            for (GuiTextFieldGeneric f : fields) f.setFocusedWrapper(false);
            return d.onMouseClicked(event, doubleClick);
        }
        if (outline.clickSuggestion(mx, my) || fill.clickSuggestion(mx, my)) return true;
        if (event.button() == 0 && inThumbnail(event.x(), event.y()) && thumbnail.mouseClicked(event.x(), event.y(), 0)) {
            thumbDrag = true;
            return true;
        }
        return super.onMouseClicked(event, doubleClick);
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent event) {
        peekButton = false;
        if (thumbDrag) {
            thumbDrag = false;
            thumbnail.mouseReleased(event.x(), event.y(), event.button());
            return true;
        }
        return super.onMouseReleased(event);
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (thumbDrag) return thumbnail.mouseDragged(event.x(), event.y(), dragX, dragY);
        return super.onMouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        ShapeDropDown<?> open = openDropDown();
        if (open != null) {
            if (open.isMouseOver((int) mouseX, (int) mouseY)) open.scroll(vertical);
            return true;
        }
        if (inThumbnail(mouseX, mouseY) && thumbnail.mouseScrolled(mouseX, mouseY, vertical)) return true;
        return super.onMouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private boolean inThumbnail(double mx, double my) {
        return mx >= thumbX && mx < thumbX + thumbW && my >= thumbY && my < thumbY + thumbH;
    }

    @Override
    public boolean onKeyTyped(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_TAB && (outline.acceptFirst() || fill.acceptFirst())) return true;
        if (event.key() == GLFW.GLFW_KEY_G && event.modifiers() == 0 && !typing()) {
            peekKey = true;
            return true;
        }
        return super.onKeyTyped(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_G && peekKey) {
            peekKey = false;
            return true;
        }
        return super.keyReleased(event);
    }

    /**
     * Closing in any way (Cancel, Esc, Generate, leaving the world): stop the worker, remember the settings, drop the
     * ghost. Not when the folder picker opens on top of this screen, which also ends in removed().
     */
    @Override
    public void removed() {
        super.removed();
        if (pickerOpen) return;
        PreviewWorker.cancel();
        ShapePresets.setLast(settings());
        ShapePreview.clear();
    }
}
