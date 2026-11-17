package dev.schemarray.shape.gui;

import dev.schemarray.shape.mc.ShapeSaver;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.wrappers.TextFieldType;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.FileNameUtils;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Small folder picker for the Shape Generator: the subfolders of the current folder as a scrolling list of buttons
 * (wheel to scroll), ".." to go up (never above root), a New folder field, Select and Cancel. The parent screen is
 * shown again on Select, Cancel and Esc; it keeps its state.
 *
 *   Folder: (schematics)/walls          3-8 of 12
 *   [..]
 *   [castle]
 *   [towers]
 *   New folder [name      ] [Create]
 *   [Select] [Cancel]
 */
final class FolderPickerScreen extends GuiBase {
    private static final int WHITE = 0xFFFFFFFF, RED = 0xFFFF5555, GRAY = 0xFFAAAAAA;
    private static final int M = 12, ROW = 20, LIST_TOP = 38, FOOTER = 72;

    private final Path root;
    private final Consumer<Path> onSelect;
    private Path current;
    private int scroll;
    private String newName = "";
    private String error;

    FolderPickerScreen(Screen parent, Path root, Path start, Consumer<Path> onSelect) {
        this.setParent(parent);
        this.title = "Choose save folder";
        this.root = root;
        this.current = start.startsWith(root) ? start : root;
        this.onSelect = onSelect;
    }

    private List<Path> subfolders() {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(current)) {
            s.filter(Files::isDirectory).forEach(out::add);
        } catch (IOException e) {
            error = "Cannot list the folder";
        }
        out.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return out;
    }

    private void enter(Path p) {
        current = p;
        scroll = 0;
        error = null;
        initGui();
    }

    @Override
    public void initGui() {
        super.initGui();
        int w = getScreenWidth(), h = getScreenHeight();
        int listW = Math.min(300, w - 2 * M);
        List<Path> folders = subfolders();
        List<Path> entries = new ArrayList<>();
        if (!current.equals(root)) entries.add(current.getParent());
        entries.addAll(folders);

        int visible = Math.max(2, (h - FOOTER - LIST_TOP) / ROW);
        scroll = Math.max(0, Math.min(scroll, entries.size() - visible));
        String path = ShapeSaver.display(current);
        while (path.length() > 4 && getStringWidth(path) > listW - 70) path = "..." + path.substring(4);
        addLabel(M, 24, listW - 70, 10, WHITE, path);
        if (entries.size() > visible) {
            String range = (scroll + 1) + "-" + Math.min(entries.size(), scroll + visible) + " of " + entries.size();
            addLabel(M + listW - 66, 24, 66, 10, GRAY, range);
        }
        for (int i = 0; i < visible && scroll + i < entries.size(); i++) {
            Path p = entries.get(scroll + i);
            boolean up = !current.equals(root) && scroll + i == 0;
            addButton(new ButtonGeneric(M, LIST_TOP + i * ROW, listW, ROW, up ? ".." : p.getFileName().toString()),
                    (b, mouse) -> enter(p));
        }
        if (entries.isEmpty()) addLabel(M + 4, LIST_TOP + 6, listW, 10, GRAY, "No subfolders");

        int y = h - FOOTER + 8;
        addLabel(M, y + 6, 62, 10, WHITE, "New folder");
        GuiTextFieldGeneric field = new GuiTextFieldGeneric(M + 66, y + 2, Math.min(150, listW - 66 - 64), 16, font);
        field.setValueWrapper(newName);
        field.setHint(Component.literal("name"));
        addTextField(field, f -> {
            newName = f.getValueWrapper();
            return false;
        }, TextFieldType.STRING);
        addButton(new ButtonGeneric(M + 66 + Math.min(150, listW - 66 - 64) + 4, y, 60, ROW, "Create"), (b, mouse) -> create());
        y += ROW + 6;
        addButton(new ButtonGeneric(M, y, 70, ROW, "Select", "Use " + ShapeSaver.display(current)), (b, mouse) -> {
            onSelect.accept(current);
            closeGui(true);
        });
        addButton(new ButtonGeneric(M + 74, y, 60, ROW, "Cancel"), (b, mouse) -> closeGui(true));
    }

    private void create() {
        String n = FileNameUtils.generateSimpleUnicodeSafeFileName(newName.trim());
        if (n.isBlank() || n.equals(".") || n.equals("..")) {
            error = "Type a folder name first";
        } else {
            try {
                Path dir = current.resolve(n).normalize();
                if (!dir.startsWith(root)) throw new IOException("outside the schematics folder");
                Files.createDirectories(dir);
                newName = "";
                enter(dir);
                return;
            } catch (IOException | RuntimeException e) {
                error = "Cannot create folder: " + e.getMessage();
            }
        }
        initGui();
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int before = scroll;
        scroll -= (int) Math.signum(vertical);
        initGui();   // clamps
        return scroll != before || super.onMouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    /** Closing in any way (Esc, Shift+Esc) shows the generator again. */
    @Override
    public void onClose() {
        closeGui(true);
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTick) {
        super.drawContents(ctx, mouseX, mouseY, partialTick);
        if (error != null) drawString(ctx, error, M, getScreenHeight() - 14, RED);
    }
}
