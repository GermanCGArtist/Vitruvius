package dev.vitruvius;

import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldDouble;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.wrappers.TextFieldType;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Blender-style array editor: a stack of modifiers, each with a count, a relative offset (fraction of the bounding
 * box so far) and a constant offset (blocks). Every edit is applied to the placement at once.
 */
public final class ArrayScreen extends GuiBase {
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, RED = 0xFFFF6060, LEFT = 20, TOP = 32;
    private static final int PANEL = 98;   // header + 3 rows of 22 + gap
    private static final String[] AXIS = {"X", "Y", "Z"};

    private final SchematicPlacement original;
    private final List<ArrayStore.Mod> mods;
    private int first;   // index of the first modifier shown (scrolling)
    private boolean refused;
    private int totalX, totalY, totalRoom;

    public ArrayScreen(Screen parent, SchematicPlacement original) {
        this.original = original;
        this.mods = ArraySync.mods(original);
        setParent(parent);
        setTitle("Array: " + original.getName());
    }

    private int visibleCount() {
        return Math.max(1, (footerY() - 6 - TOP) / PANEL);
    }

    private int footerY() {
        return getScreenHeight() - 28;
    }

    @Override
    public void initGui() {
        super.initGui();
        Font font = Minecraft.getInstance().font;
        int fit = visibleCount();
        first = Math.max(0, Math.min(first, mods.size() - fit));

        // shared column widths, measured: the two "use" toggles and the "Count:" / axis labels
        int toggleW = Math.max(font.width("[x] Relative Offset"), font.width("[x] Constant Offset")) + 12;
        int y = TOP;
        for (int i = first; i < Math.min(mods.size(), first + fit); i++) {
            addPanel(font, i, y, toggleW);
            y += PANEL;
        }
        if (mods.isEmpty()) {
            addLabel(LEFT, TOP + 4, 300, 10, GRAY, "No modifiers yet. Add Array makes copies of the placement.");
        }

        int fy = footerY();
        int addW = font.width("Add Array") + 20;
        addButton(new ButtonGeneric(LEFT, fy, addW, 20, "Add Array", "Adds a modifier on top of the stack."), (b, mouse) -> {
            List<ArrayStore.Mod> next = new ArrayList<>(mods);
            next.add(new ArrayStore.Mod());
            if (!ArraySync.allowed(next)) { refused = true; initGui(); return; }
            mods.add(new ArrayStore.Mod());
            first = Integer.MAX_VALUE;   // show it: clamped to the last page
            apply();
            initGui();
        });

        int doneW = font.width("Done") + 20;
        addButton(new ButtonGeneric(getScreenWidth() - LEFT - doneW, fy, doneW, 20, "Done"), (b, mouse) -> GuiBase.openGui(getParent()));

        totalX = LEFT + addW + 10;
        totalY = fy + 6;
        totalRoom = getScreenWidth() - LEFT - doneW - 10 - totalX;
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTick) {
        super.drawContents(ctx, mouseX, mouseY, partialTick);
        Font font = Minecraft.getInstance().font;
        long total = ArraySync.copyCount(mods);
        String text = "Total: " + total + (total == 1 ? " copy" : " copies");
        if (refused || total > ArraySync.MAX_COPIES) text += " (max " + ArraySync.MAX_COPIES + ")";
        int fit = visibleCount();
        if (mods.size() > fit) {
            String more = text + "   Modifiers " + (first + 1) + "-" + Math.min(mods.size(), first + fit) + " of " + mods.size() + " (mouse wheel scrolls)";
            if (font.width(more) <= totalRoom) text = more;
        }
        drawStringWithShadow(ctx, text, totalX, totalY, refused ? RED : WHITE);
    }

    private void addPanel(Font font, int i, int y, int toggleW) {
        ArrayStore.Mod m = mods.get(i);
        int x = LEFT;

        // header: Array n [On] [^] [v] [Remove]
        String head = "Array " + (i + 1);
        addLabel(x, y + 6, font.width(head) + 2, 10, m.enabled ? WHITE : GRAY, head);
        x += font.width(head) + 12;
        int onW = Math.max(font.width("On"), font.width("Off")) + 12;
        addButton(new ButtonGeneric(x, y, onW, 20, m.enabled ? "On" : "Off", "Turns this modifier on or off (off = skipped)."), (b, mouse) -> {
            edit(i, mm -> mm.enabled = !mm.enabled);
            initGui();
        });
        x += onW + 4;
        int arrowW = Math.max(font.width("^"), font.width("v")) + 12;
        ButtonGeneric up = new ButtonGeneric(x, y, arrowW, 20, "^", "Moves this modifier up (applied earlier).");
        up.setEnabled(i > 0);
        addButton(up, (b, mouse) -> { move(i, -1); });
        x += arrowW + 4;
        ButtonGeneric down = new ButtonGeneric(x, y, arrowW, 20, "v", "Moves this modifier down (applied later).");
        down.setEnabled(i < mods.size() - 1);
        addButton(down, (b, mouse) -> { move(i, 1); });
        x += arrowW + 4;
        int remW = font.width("Remove") + 12;
        addButton(new ButtonGeneric(x, y, remW, 20, "Remove", "Deletes this modifier and its copies."), (b, mouse) -> {
            mods.remove(i);
            apply();
            initGui();
        });

        // Count: [ 5 ][+-]
        y += 22;
        x = LEFT;
        addLabel(x, y + 6, font.width("Count:") + 2, 10, WHITE, "Count:");
        x += font.width("Count:") + 6;
        GuiTextFieldInteger count = intField(x, y, 40, m.count);
        addTextField(count, f -> {
            Integer v = parseInt(f.getValueWrapper());
            if (v != null && v >= 1) edit(i, mm -> mm.count = v);
            return false;
        }, TextFieldType.INTEGER);
        addButton(new ButtonGeneric(x + 42, y + 2, Icons.BUTTON_PLUS_MINUS_16, "Copies of the stack so far: left click +1, right click -1 (Shift x8, Alt x4).", "1 = no copies from this modifier."),
                (b, mouse) -> {
                    int v = Math.max(1, mods.get(i).count + step(mouse));
                    if (edit(i, mm -> mm.count = v)) count.setValueWrapper(String.valueOf(mods.get(i).count));
                    else count.setValueWrapper(String.valueOf(mods.get(i).count));
                    updateTotal();
                });

        // [x] Relative Offset  X [1.0] Y [0.0] Z [0.0]
        y += 22;
        addToggle(i, y, toggleW, "Relative Offset", m.useRelative, "Offset as a fraction of the box so far (1 = exactly one box, 0.5 = half, negative = the other way).", true);
        x = LEFT + toggleW + 8;
        for (int a = 0; a < 3; a++) {
            int ax = a;
            addLabel(x, y + 6, font.width(AXIS[a]) + 2, 10, WHITE, AXIS[a]);
            x += font.width(AXIS[a]) + 6;
            GuiTextFieldDouble f = new GuiTextFieldDouble(x, y + 3, 46, 14, font);
            f.setValueWrapper(String.valueOf(m.relative[a]));
            addTextField(f, tf -> {
                Double v = parseDouble(tf.getValueWrapper());
                if (v != null && Math.abs(v) <= 1000) edit(i, mm -> mm.relative[ax] = v);
                return false;
            }, TextFieldType.DOUBLE);
            x += 46 + 10;
        }

        // [x] Constant Offset  X [0][+-] Y [0][+-] Z [0][+-]
        y += 22;
        addToggle(i, y, toggleW, "Constant Offset", m.useConstant, "Offset in blocks (negative = the other way). Added to the relative offset.", false);
        x = LEFT + toggleW + 8;
        for (int a = 0; a < 3; a++) {
            int ax = a;
            addLabel(x, y + 6, font.width(AXIS[a]) + 2, 10, WHITE, AXIS[a]);
            x += font.width(AXIS[a]) + 6;
            GuiTextFieldInteger f = intField(x, y, 40, m.constant[a]);
            addTextField(f, tf -> {
                Integer v = parseInt(tf.getValueWrapper());
                if (v != null && Math.abs(v) <= 10000) edit(i, mm -> mm.constant[ax] = v);
                return false;
            }, TextFieldType.INTEGER);
            addButton(new ButtonGeneric(x + 42, y + 2, Icons.BUTTON_PLUS_MINUS_16, "Blocks along " + AXIS[a] + ": left click +1, right click -1 (Shift x8, Alt x4).", "Turns with the placement."),
                    (b, mouse) -> {
                        int v = Math.max(-10000, Math.min(10000, mods.get(i).constant[ax] + step(mouse)));
                        edit(i, mm -> mm.constant[ax] = v);
                        f.setValueWrapper(String.valueOf(mods.get(i).constant[ax]));
                        updateTotal();
                    });
            x += 42 + 16 + 10;
        }
    }

    private void addToggle(int i, int y, int w, String name, boolean on, String hover, boolean relative) {
        addButton(new ButtonGeneric(LEFT, y, w, 20, (on ? "[x] " : "[ ] ") + name, hover), (b, mouse) -> {
            edit(i, mm -> { if (relative) mm.useRelative = !mm.useRelative; else mm.useConstant = !mm.useConstant; });
            initGui();
        });
    }

    private GuiTextFieldInteger intField(int x, int y, int w, int value) {
        GuiTextFieldInteger f = new GuiTextFieldInteger(x, y + 3, w, 14, Minecraft.getInstance().font);
        f.setValueWrapper(String.valueOf(value));
        return f;
    }

    private static int step(int mouse) {
        return (mouse == 1 ? -1 : 1) * (isShiftDown() ? 8 : 1) * (isAltDown() ? 4 : 1);
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;   // "-" or empty while typing
        }
    }

    private static Double parseDouble(String text) {
        try {
            double v = Double.parseDouble(text.trim());
            return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
        } catch (NumberFormatException e) {
            return null;   // "-", "1." or empty while typing
        }
    }

    /** Changes modifier i; refused (and undone) when the array would get more than MAX_COPIES copies. */
    private boolean edit(int i, Consumer<ArrayStore.Mod> change) {
        ArrayStore.Mod backup = mods.get(i).copy();
        change.accept(mods.get(i));
        if (!ArraySync.allowed(mods)) {
            mods.set(i, backup);
            refused = true;
            updateTotal();
            return false;
        }
        refused = false;
        apply();
        updateTotal();
        return true;
    }

    private void move(int i, int d) {
        int j = i + d;
        if (j < 0 || j >= mods.size()) return;
        ArrayStore.Mod t = mods.get(i);
        mods.set(i, mods.get(j));
        mods.set(j, t);
        apply();
        initGui();
    }

    private void apply() {
        refused = false;
        ArraySync.setMods(original, mods);
    }

    /** The total line is rebuilt with the screen; a rejected edit only needs the red marker, so re-init on that. */
    private void updateTotal() {
        // keeps the focus in the text field while typing: re-init only when the label would change a lot
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int fit = visibleCount();
        if (mods.size() > fit && vertical != 0) {
            int n = Math.max(0, Math.min(mods.size() - fit, first + (vertical < 0 ? 1 : -1)));
            if (n != first) {
                first = n;
                initGui();
            }
            return true;
        }
        return super.onMouseScrolled(mouseX, mouseY, horizontal, vertical);
    }
}
