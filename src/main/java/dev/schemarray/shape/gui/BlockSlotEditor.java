package dev.schemarray.shape.gui;

import dev.schemarray.shape.Axis;
import dev.schemarray.shape.BlockEntry;
import dev.schemarray.shape.BlockSlot;
import dev.schemarray.shape.PatternMode;
import dev.schemarray.shape.ShapeSpec;
import dev.schemarray.shape.mc.BlockParsing;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetCheckBox;
import fi.dy.masa.malilib.gui.wrappers.TextFieldType;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * One block slot of the Shape Generator screen (Outline or Fill) as a group of malilib widgets: pattern mode, the
 * block text field with autocomplete and a "Hand" button, and the few fields the chosen mode uses.
 *
 *   Outline  [Mode          v]
 *   [stone or 60%a,40%b   ] [Hand]
 *   2+ blocks: stone,andesite      (grey, multi-block modes only)
 *   Cell X[ ] Y[ ] Z[ ]            (Checker, Brick)
 *   [Axis: Y] Width [ ][+-] [ ] Dither   (Stripes, Gradient, Diagonal)
 *   Seed [      ] [Dice]           (Mix)
 *
 * Its height is what the mode shows (2 rows, +10 for the grey line, +1 row for the mode's fields), so a screen can
 * stack two editors without overlap. The editor keeps its own state (the screen asks slot() and error()) and survives the screen rebuilding its widgets.
 */
final class BlockSlotEditor {
    private static final int RED = 0xFFFF5555, WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA;
    private static final int HINT_H = 10, SUGGESTIONS = 6, SUGGESTION_H = 11;
    private static final String NAMESPACE = "minecraft:";

    private final String title;
    private final String emptyHint;
    private final Runnable onChange;
    private final Consumer<String> message;

    private PatternMode mode;
    private String text;
    private int cellX, cellY, cellZ, width;
    private Axis axis;
    private long seed;
    private boolean dither;

    private ShapeDropDown<PatternMode> modeBox;
    private GuiTextFieldGeneric blockField;
    private ButtonGeneric axisButton;
    private String error;
    private int hintX, hintY;

    private String suggestionKey = "";
    private List<String> suggestions = List.of();
    private Token token;

    /** The part of the text field under the cursor that autocomplete works on: text[nameStart, end). */
    private record Token(int nameStart, int end, String name) {}

    BlockSlotEditor(String title, String emptyHint, BlockSlot initial, Runnable onChange, Consumer<String> message) {
        this.title = title;
        this.emptyHint = emptyHint;
        this.onChange = onChange;
        this.message = message;
        set(initial);
    }

    /** Loads a slot (preset load); the screen rebuilds its widgets afterwards. */
    void set(BlockSlot s) {
        mode = s.mode();
        text = s.entries().isEmpty() ? "" : BlockParsing.formatEntries(s.entries());
        cellX = s.cellX();
        cellY = s.cellY();
        cellZ = s.cellZ();
        axis = s.axis();
        width = s.width();
        seed = s.seed();
        dither = s.dither();
    }

    BlockSlot slot() {
        return new BlockSlot(mode, entries(), cellX, cellY, cellZ, axis, width, seed, dither);
    }

    /** What is wrong with the block field ("Outline: Unknown block ..."), or null. */
    String error() {
        return error;
    }

    /** Whether the mode drop down was changed since the last call (the screen then rebuilds to show the mode's fields). */
    boolean pollMode() {
        PatternMode m = modeBox == null ? null : modeBox.getSelectedEntry();
        if (m == null || m == mode) return false;
        mode = m;
        return true;
    }

    private List<BlockEntry> entries() {
        List<BlockEntry> out = new ArrayList<>();
        for (BlockEntry e : BlockParsing.parseEntries(text)) if (!e.blockState().isEmpty()) out.add(e);
        return out;
    }

    private void validate() {
        error = null;
        for (BlockEntry e : entries()) {
            String m = BlockParsing.error(e.blockState());
            if (m != null) {
                error = title + ": " + m;
                break;
            }
        }
        if (blockField != null) blockField.setTextColor(error == null ? EditBox.DEFAULT_TEXT_COLOR : RED);
    }

    private void textChanged(String value) {
        text = value;
        validate();
        onChange.run();
    }

    private void setText(String value) {
        blockField.setValueWrapper(value);
        textChanged(value);
    }

    /** Adds the widgets at (x, y), w wide and rows of the given height; returns the y just below the last one. */
    int init(ShapeGeneratorScreen g, int x, int y, int w, int row) {
        g.addLabel(x, y + 6, 52, 10, WHITE, title);
        modeBox = new ShapeDropDown<>(x + 52, y, 100, List.of(PatternMode.values()), m -> m.displayName, g.getScreenHeight());
        modeBox.setSelectedEntry(mode);
        g.addDropDown(modeBox);
        y += row;

        int handW = 40;
        blockField = new GuiTextFieldGeneric(x, y + 2, w - handW - 4, 16, g.font);
        blockField.setValueWrapper(text);
        g.addField(blockField, f -> {
            textChanged(f.getValueWrapper());
            return false;
        }, TextFieldType.STRING);
        blockField.setMaxLength(512);
        if (multi()) blockField.setHint(Component.literal(listHint()));
        else if (!emptyHint.isEmpty()) blockField.setHint(Component.literal(emptyHint));
        validate();
        g.addButton(new ButtonGeneric(x + w - handW, y, handW, 20, "Hand", "Use the block in your main hand",
                multi() ? "Appends ,<block> to the list." : "Replaces the field."), (b, mouse) -> hand());
        y += row;
        if (multi()) {   // its own line under the field (which ends 2 px above the next row), then the mode's row
            hintX = x;
            hintY = y - 1;
            g.placeText(hintX, hintY, listHint());
            y += HINT_H;
        }

        switch (mode) {
            case CHECKER, BRICK -> cellRow(g, x, y);
            case STRIPES, GRADIENT, DIAGONAL -> axisRow(g, x, y);
            case MIX -> seedRow(g, x, y);
            default -> {
                return y;
            }
        }
        return y + row;
    }

    private void cellRow(ShapeGeneratorScreen g, int x, int y) {
        g.addLabel(x, y + 6, 26, 10, WHITE, mode == PatternMode.BRICK ? "Brick" : "Cell");
        String[] names = {"X", "Y", "Z"};
        int[] values = {cellX, cellY, cellZ};
        for (int i = 0; i < 3; i++) {
            int gx = x + 30 + i * 42, axisIndex = i;
            g.addLabel(gx, y + 6, 8, 10, WHITE, names[i]);
            g.addIntField(gx + 9, y, 30, values[i], 1, ShapeSpec.MAX_SIZE, v -> {
                switch (axisIndex) {
                    case 0 -> cellX = v;
                    case 1 -> cellY = v;
                    default -> cellZ = v;
                }
                onChange.run();
            });
        }
    }

    private void axisRow(ShapeGeneratorScreen g, int x, int y) {
        axisButton = new ButtonGeneric(x, y, 50, 20, "Axis: " + axis, "Left click: next axis, right click: previous.");
        g.addButton(axisButton, (b, mouse) -> {
            axis = Axis.values()[Math.floorMod(axis.ordinal() + (mouse == 1 ? -1 : 1), 3)];
            axisButton.setDisplayString("Axis: " + axis);
            onChange.run();
        });
        g.addLabel(x + 56, y + 6, 32, 10, WHITE, "Width");
        g.addInt(x + 90, y, 28, width, 1, ShapeSpec.MAX_SIZE, v -> {
            width = v;
            onChange.run();
        }, "Band width in blocks");
        if (mode == PatternMode.GRADIENT) {
            WidgetCheckBox box = new WidgetCheckBox(x + 140, y + 5, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED,
                    "Dither", "Blend the band edges instead of hard steps");
            box.setChecked(dither, false);
            box.setListener(c -> {
                dither = c.isChecked();
                onChange.run();
            });
            g.addWidget(box);
        }
    }

    private void seedRow(ShapeGeneratorScreen g, int x, int y) {
        g.addLabel(x, y + 6, 30, 10, WHITE, "Seed");
        GuiTextFieldGeneric field = new GuiTextFieldGeneric(x + 32, y + 2, 80, 16, g.font);
        field.setValueWrapper(String.valueOf(seed));
        g.addField(field, f -> {
            try {
                seed = Long.parseLong(f.getValueWrapper().trim());
            } catch (NumberFormatException e) {
                return false;   // "-" or empty while typing
            }
            onChange.run();
            return false;
        }, TextFieldType.STRING);
        g.addButton(new ButtonGeneric(x + 116, y, 40, 20, "Dice", "Random seed"), (b, mouse) -> {
            seed = ThreadLocalRandom.current().nextInt(1_000_000);
            field.setValueWrapper(String.valueOf(seed));
            onChange.run();
        });
    }

    private boolean multi() {
        return mode != PatternMode.SINGLE;
    }

    /** What the field takes in the current mode: "2+ blocks: stone,andesite". */
    private String listHint() {
        String example = switch (mode) {
            case MIX -> "60%stone,40%andesite";
            case BRICK -> "brick,mortar";
            case GRADIENT -> "first,...,last";
            default -> "stone,andesite";
        };
        return "2+ blocks: " + example;
    }

    /** The grey syntax line under the block field while it holds a single block in a multi-block mode. */
    void drawHint(GuiContext ctx, ShapeGeneratorScreen g) {
        if (multi() && entries().size() == 1) g.drawString(ctx, listHint(), hintX, hintY, GRAY);
    }

    /** Tooltip of the block field. */
    void drawFieldTooltip(GuiContext ctx, int mouseX, int mouseY) {
        if (blockField == null || !blockField.isMouseOver(mouseX, mouseY) || !suggestions().isEmpty()) return;
        RenderUtils.drawHoverText(ctx, mouseX + 8, mouseY + 4, List.of(
                "Block id, with a state in brackets: oak_log[axis=x].",
                "Several blocks, comma separated: stone,andesite.",
                "Weights with %: 60%stone,40%andesite."));
    }

    private void hand() {
        String s = BlockParsing.fromMainHand();
        if (s == null) {
            message.accept("No block in your main hand");
            return;
        }
        setText(!multi() || text.isBlank() ? s : text + "," + s);
    }

    // ---- autocomplete ----

    /** Up to SUGGESTIONS block ids for the entry under the cursor while the block field has the focus. */
    private List<String> suggestions() {
        if (blockField == null || !blockField.isFocusedWrapper()) return List.of();
        int cursor = Math.min(blockField.getCursorWrapper(), text.length());
        String key = text + '\0' + cursor;
        if (!key.equals(suggestionKey)) {
            suggestionKey = key;
            token = tokenAt(cursor);
            suggestions = List.of();
            if (!token.name().isEmpty() && token.name().indexOf('[') < 0) {
                List<String> found = new ArrayList<>();
                for (String id : BlockParsing.suggest(token.name(), SUGGESTIONS)) {
                    found.add(id.startsWith(NAMESPACE) ? id.substring(NAMESPACE.length()) : id);
                }
                // typed in full already: nothing left to suggest
                if (!(found.size() == 1 && found.get(0).equals(token.name()))) suggestions = found;
            }
        }
        return suggestions;
    }

    /** The entry around the cursor, without its "60%" weight and surrounding blanks. */
    private Token tokenAt(int cursor) {
        int start = cursor == 0 ? 0 : text.lastIndexOf(',', cursor - 1) + 1;
        int end = text.indexOf(',', cursor);
        if (end < 0) end = text.length();
        int i = start;
        while (i < end && text.charAt(i) == ' ') i++;
        int digits = i;
        while (digits < end && Character.isDigit(text.charAt(digits))) digits++;
        if (digits > i && digits < end && text.charAt(digits) == '%') i = digits + 1;
        while (i < end && text.charAt(i) == ' ') i++;
        return new Token(i, end, text.substring(i, end).trim());
    }

    private void accept(int index) {
        List<String> list = suggestions();
        if (index < 0 || index >= list.size()) return;
        String id = list.get(index);
        String value = text.substring(0, token.nameStart()) + id + text.substring(token.end());
        int cursor = token.nameStart() + id.length();
        setText(value);
        blockField.setCursorPosition(cursor);
    }

    /** Tab: takes the first suggestion. */
    boolean acceptFirst() {
        if (suggestions().isEmpty()) return false;
        accept(0);
        return true;
    }

    /** Click on a suggestion row. */
    boolean clickSuggestion(int mouseX, int mouseY) {
        int row = rowAt(mouseX, mouseY);
        if (row < 0) return false;
        accept(row);
        return true;
    }

    private int rowAt(int mouseX, int mouseY) {
        List<String> list = suggestions();
        if (list.isEmpty()) return -1;
        int x = blockField.getX(), y = blockField.getY() + blockField.getHeight();
        if (mouseX < x || mouseX >= x + blockField.getWidth() || mouseY < y + 1) return -1;
        int row = (mouseY - y - 1) / SUGGESTION_H;
        return row < list.size() ? row : -1;
    }

    /** The suggestion list under the block field; drawn on top of everything by the screen. */
    void drawSuggestions(GuiContext ctx, ShapeGeneratorScreen g, int mouseX, int mouseY) {
        List<String> list = suggestions();
        if (list.isEmpty()) return;
        ctx.nextStratum();
        int x = blockField.getX(), y = blockField.getY() + blockField.getHeight(), w = blockField.getWidth();
        int h = list.size() * SUGGESTION_H + 2;
        ctx.fill(x, y, x + w, y + h, 0xF0101010);
        ctx.outline(x, y, w, h, 0xFF808080);
        int hover = rowAt(mouseX, mouseY);
        for (int i = 0; i < list.size(); i++) {
            int ry = y + 1 + i * SUGGESTION_H;
            if (i == hover) ctx.fill(x + 1, ry, x + w - 1, ry + SUGGESTION_H, 0x60FFFFFF);
            g.drawString(ctx, list.get(i), x + 3, ry + 2, i == 0 ? 0xFFFFFF55 : WHITE);
        }
        g.drawString(ctx, "Tab", x + w - g.getStringWidth("Tab") - 3, y + 3, GRAY);
    }
}
