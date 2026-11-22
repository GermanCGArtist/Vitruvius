package dev.vitruvius.editor.view;

import dev.cg.uihost.Theme;
import dev.vitruvius.shape.Sketch;
import dev.vitruvius.shape.mc.BlockParsing;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiButtonFlags;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sketchpad of the Custom 2D shape: a pannable, zoomable pixel canvas. Every pixel is empty, "shape" (coloured by
 * the generator's Outline/Fill patterns) or pinned to a block. The drawing lives here as a sparse map and is handed to
 * the Shape generator as a {@link Sketch} ({@link #sketch}); that one only sees a change once a stroke or menu action
 * is finished, so a drag in the canvas is one regeneration and one undo step of the scene, not hundreds.
 *
 * <p>Tools: Brush, Eraser, Select (drag = pick the pixels under the pointer, Shift+drag = a box, Ctrl adds to the
 * selection), Fill and Pick. Middle drag pans, the wheel zooms, a right click opens the menu of the selection.
 */
public final class SketchPad {
    private SketchPad() {}

    private static final String[] TOOLS = {"Brush", "Eraser", "Select", "Box", "Circle", "Fill", "Pick"};
    private static final int BRUSH = 0, ERASE = 1, SELECT = 2, BOX = 3, CIRCLE = 4, FILL = 5, PICK = 6;
    private static final String SHAPE = "";
    private static final int MAX_UNDO = 60;

    private static final ImBoolean OPEN = new ImBoolean(true);
    private static boolean show;

    // the drawing
    private static Map<Long, String> px = new HashMap<>();
    private static final ArrayDeque<Map<Long, String>> UNDO = new ArrayDeque<>(), REDO = new ArrayDeque<>();
    private static int version;
    private static Sketch built = Sketch.EMPTY;
    private static int builtVersion = -1, builtDepth = -1;
    private static int[] bb = new int[4];                 // minCol, minRow, maxCol, maxRow of px at the start of a stroke

    // tools
    private static int tool = BRUSH, lastTool = BRUSH;
    private static int circleRadius = 3;
    private static boolean brushIsShape = true;
    private static final ImString brushBlock = new ImString("minecraft:stone", 256);
    private static final LinkedList<String> RECENT = new LinkedList<>();
    private static boolean mirrorH, mirrorV, mirrorOnCell;
    private static final Set<Long> sel = new HashSet<>();
    private static Map<Long, String> clipboard = new HashMap<>();

    // view
    private static float zoom = 1f, panX, panY;
    private static boolean viewInit;

    // gesture
    private static boolean stroking, changed, rectMode, subtract;
    private static Map<Long, String> before;
    private static long lastCell;
    private static boolean haveLast;
    private static int anchorC, anchorR;
    private static boolean pan;
    private static boolean hotForKeys;
    private static long ctxCell;
    private static final ImString ctxBlock = new ImString("minecraft:stone", 256);
    private static String message;

    // ------------------------------------------------------------------ public

    public static void open() {
        show = true;
    }

    public static boolean isOpen() {
        return show;
    }

    public static void close() {
        show = false;
        stroking = false;
    }

    /** Replaces the drawing (a shape was loaded); forgets the undo history. */
    public static void load(Sketch sk) {
        px = sk == null ? new HashMap<>() : sk.toMap();
        UNDO.clear();
        REDO.clear();
        sel.clear();
        version++;
        stroking = false;
        message = null;
        viewInit = false;
    }

    /** The drawing as the generator should see it: unchanged while a stroke is running. */
    public static Sketch sketch(int depth) {
        if (!stroking && (builtVersion != version || builtDepth != depth)) {
            built = Sketch.fromMap(px, depth);
            builtVersion = version;
            builtDepth = depth;
        }
        return built.depth() == depth ? built : built.withDepth(depth);
    }

    /**
     * A key while the pad is in use (hovered or focused): true when it was taken. Blender's selection keys: A select
     * all, Alt+A select none, Ctrl+I invert, B box select, C circle select, X / Delete delete the selection; Esc or
     * Enter leave circle select. Ctrl+Z/Y undo and redo, Ctrl+C/X/V the clipboard.
     */
    public static boolean onKey(int key, boolean ctrl, boolean shift, boolean alt) {
        if (!show || !hotForKeys) return false;
        if (ctrl) {
            switch (key) {
                case GLFW.GLFW_KEY_Z -> {
                    if (shift) redo();
                    else undo();
                }
                case GLFW.GLFW_KEY_Y -> redo();
                case GLFW.GLFW_KEY_C -> copy(false);
                case GLFW.GLFW_KEY_X -> copy(true);
                case GLFW.GLFW_KEY_V -> paste(ctxCell);
                case GLFW.GLFW_KEY_A -> selectAll();
                case GLFW.GLFW_KEY_I -> invertSelection();
                default -> {
                    return false;
                }
            }
            return true;
        }
        switch (key) {
            case GLFW.GLFW_KEY_A -> {
                if (alt) sel.clear();
                else selectAll();
            }
            case GLFW.GLFW_KEY_B -> setTool(BOX);
            case GLFW.GLFW_KEY_C -> setTool(CIRCLE);
            case GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_DELETE -> applyToSelection(null);
            case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER -> {
                if (tool == CIRCLE) tool = lastTool;
                else sel.clear();
            }
            case GLFW.GLFW_KEY_KP_ADD, GLFW.GLFW_KEY_EQUAL -> circleRadius = Math.min(64, circleRadius + 1);
            case GLFW.GLFW_KEY_KP_SUBTRACT, GLFW.GLFW_KEY_MINUS -> circleRadius = Math.max(1, circleRadius - 1);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void setTool(int t) {
        if (t != tool && tool != CIRCLE) lastTool = tool;
        tool = t;
    }

    private static void invertSelection() {
        if (px.isEmpty()) return;
        Set<Long> now = new HashSet<>();
        for (long k : px.keySet()) if (!sel.contains(k)) now.add(k);
        sel.clear();
        sel.addAll(now);
    }

    /** Selects (or deselects) every cell within the radius of the cell (c, r). */
    private static void circle(int c, int r, boolean add) {
        int rad = circleRadius;
        for (int dr = -rad; dr <= rad; dr++) {
            for (int dc = -rad; dc <= rad; dc++) {
                if (dc * dc + dr * dr > rad * rad + rad) continue;
                long k = Sketch.key(c + dc, r + dr);
                if (add) sel.add(k);
                else sel.remove(k);
            }
        }
    }

    private static boolean shiftDown() {
        return ImGui.getIO().getKeyShift() || ImGui.isKeyDown(ImGuiKey.LeftShift) || ImGui.isKeyDown(ImGuiKey.RightShift);
    }

    private static boolean ctrlDown() {
        return ImGui.getIO().getKeyCtrl() || ImGui.isKeyDown(ImGuiKey.LeftCtrl) || ImGui.isKeyDown(ImGuiKey.RightCtrl);
    }

    /** Removes every pixel as one undo step. */
    private static void reset() {
        if (px.isEmpty()) return;
        begin();
        px.clear();
        changed = true;
        sel.clear();
        commit();
        viewInit = false;
    }

    // ------------------------------------------------------------------ edits

    private static void begin() {
        if (before == null) before = new HashMap<>(px);
        changed = false;
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (long k : px.keySet()) {
            b[0] = Math.min(b[0], Sketch.col(k));
            b[1] = Math.min(b[1], Sketch.row(k));
            b[2] = Math.max(b[2], Sketch.col(k));
            b[3] = Math.max(b[3], Sketch.row(k));
        }
        bb = b;
    }

    private static void commit() {
        if (changed && before != null) {
            UNDO.push(before);
            while (UNDO.size() > MAX_UNDO) UNDO.removeLast();
            REDO.clear();
            version++;
        }
        before = null;
        changed = false;
    }

    private static void undo() {
        if (UNDO.isEmpty()) return;
        REDO.push(px);
        px = UNDO.pop();
        version++;
    }

    private static void redo() {
        if (REDO.isEmpty()) return;
        UNDO.push(px);
        px = REDO.pop();
        version++;
    }

    /** Whether (c, r) may hold a pixel: the drawing stays within the size limit. */
    private static boolean fits(int c, int r) {
        if (bb[0] == Integer.MAX_VALUE) return true;
        return Math.max(bb[2], c) - Math.min(bb[0], c) < Sketch.MAX && Math.max(bb[3], r) - Math.min(bb[1], r) < Sketch.MAX;
    }

    private static void set(int c, int r, String value) {
        long k = Sketch.key(c, r);
        if (value == null) {
            if (px.remove(k) != null) changed = true;
            return;
        }
        if (!fits(c, r)) {
            message = "The drawing is at the size limit (" + Sketch.MAX + " pixels)";
            return;
        }
        if (!value.equals(px.put(k, value))) changed = true;
        bb[0] = Math.min(bb[0] == Integer.MAX_VALUE ? c : bb[0], c);
        bb[1] = Math.min(bb[1] == Integer.MAX_VALUE ? r : bb[1], r);
        bb[2] = Math.max(bb[2] == Integer.MIN_VALUE ? c : bb[2], c);
        bb[3] = Math.max(bb[3] == Integer.MIN_VALUE ? r : bb[3], r);
    }

    /** Sets a pixel and its mirror images. */
    private static void setMirrored(int c, int r, String value) {
        int shift = mirrorOnCell ? 0 : -1;
        set(c, r, value);
        if (mirrorH) set(shift - c, r, value);
        if (mirrorV) set(c, shift - r, value);
        if (mirrorH && mirrorV) set(shift - c, shift - r, value);
    }

    private static String brushValue() {
        return brushIsShape ? SHAPE : brushBlock.get().trim();
    }

    private static String brushError() {
        return brushIsShape ? null : BlockParsing.error(brushBlock.get());
    }

    private static void remember(String block) {
        if (block == null || block.isEmpty()) return;
        RECENT.remove(block);
        RECENT.addFirst(block);
        while (RECENT.size() > 12) RECENT.removeLast();
    }

    /** Calls f for every cell on the line from (c0, r0) to (c1, r1). */
    private static void line(int c0, int r0, int c1, int r1, java.util.function.BiConsumer<Integer, Integer> f) {
        int dc = Math.abs(c1 - c0), dr = -Math.abs(r1 - r0), sc = c0 < c1 ? 1 : -1, sr = r0 < r1 ? 1 : -1, err = dc + dr;
        int c = c0, r = r0;
        for (int guard = 0; guard < 100000; guard++) {
            f.accept(c, r);
            if (c == c1 && r == r1) break;
            int e2 = 2 * err;
            if (e2 >= dr) {
                err += dr;
                c += sc;
            }
            if (e2 <= dc) {
                err += dc;
                r += sr;
            }
        }
    }

    private static void floodFill(int c, int r) {
        String start = px.get(Sketch.key(c, r));
        String value = brushValue();
        if (java.util.Objects.equals(start, value)) return;
        // an empty region needs walls: it is limited to the drawing's box plus one pixel, and when it leaks out nothing is filled
        int minC = bb[0] - 1, minR = bb[1] - 1, maxC = bb[2] + 1, maxR = bb[3] + 1;
        if (bb[0] == Integer.MAX_VALUE) {
            message = "Nothing is drawn yet, so there is nothing to fill";
            return;
        }
        boolean empty = start == null;
        if (empty && (c < minC || c > maxC || r < minR || r > maxR)) return;
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        List<Long> cells = new ArrayList<>();
        queue.add(new long[] {c, r});
        seen.add(Sketch.key(c, r));
        boolean leak = false;
        while (!queue.isEmpty() && cells.size() < 70000) {
            long[] p = queue.poll();
            int pc = (int) p[0], pr = (int) p[1];
            cells.add(Sketch.key(pc, pr));
            int[][] nb = {{pc + 1, pr}, {pc - 1, pr}, {pc, pr + 1}, {pc, pr - 1}};
            for (int[] n : nb) {
                if (empty && (n[0] < minC || n[0] > maxC || n[1] < minR || n[1] > maxR)) {
                    leak = true;
                    continue;
                }
                long nk = Sketch.key(n[0], n[1]);
                if (seen.contains(nk) || !java.util.Objects.equals(px.get(nk), start)) continue;
                if (!empty && !px.containsKey(nk)) continue;
                seen.add(nk);
                queue.add(new long[] {n[0], n[1]});
            }
        }
        if (leak) {
            message = "That area is not closed, so it was not filled";
            return;
        }
        for (long k : cells) set(Sketch.col(k), Sketch.row(k), value);
    }

    private static void selectAll() {
        sel.clear();
        sel.addAll(px.keySet());
    }

    private static void copy(boolean cut) {
        if (sel.isEmpty()) return;
        clipboard = new HashMap<>();
        for (long k : sel) {
            String v = px.get(k);
            if (v != null) clipboard.put(k, v);
        }
        if (cut) applyToSelection(null);
    }

    private static void paste(long at) {
        if (clipboard.isEmpty()) return;
        int minC = Integer.MAX_VALUE, minR = Integer.MAX_VALUE;
        for (long k : clipboard.keySet()) {
            minC = Math.min(minC, Sketch.col(k));
            minR = Math.min(minR, Sketch.row(k));
        }
        begin();
        sel.clear();
        for (var e : clipboard.entrySet()) {
            int c = Sketch.col(at) + Sketch.col(e.getKey()) - minC, r = Sketch.row(at) + Sketch.row(e.getKey()) - minR;
            set(c, r, e.getValue());
            sel.add(Sketch.key(c, r));
        }
        commit();
    }

    /** Sets (value) or clears (null) every selected pixel as one undo step. */
    private static void applyToSelection(String value) {
        if (sel.isEmpty()) return;
        begin();
        for (long k : sel) set(Sketch.col(k), Sketch.row(k), value);
        commit();
    }

    private static void flip(boolean horizontal) {
        if (sel.isEmpty()) return;
        int minC = Integer.MAX_VALUE, minR = Integer.MAX_VALUE, maxC = Integer.MIN_VALUE, maxR = Integer.MIN_VALUE;
        for (long k : sel) {
            minC = Math.min(minC, Sketch.col(k));
            maxC = Math.max(maxC, Sketch.col(k));
            minR = Math.min(minR, Sketch.row(k));
            maxR = Math.max(maxR, Sketch.row(k));
        }
        Map<Long, String> moved = new HashMap<>();
        for (long k : sel) {
            String v = px.get(k);
            if (v != null) moved.put(k, v);
        }
        begin();
        for (long k : sel) set(Sketch.col(k), Sketch.row(k), null);
        Set<Long> ns = new HashSet<>();
        for (long k : sel) {
            int c = Sketch.col(k), r = Sketch.row(k);
            int nc = horizontal ? minC + maxC - c : c, nr = horizontal ? r : minR + maxR - r;
            ns.add(Sketch.key(nc, nr));
        }
        for (var e : moved.entrySet()) {
            int c = Sketch.col(e.getKey()), r = Sketch.row(e.getKey());
            set(horizontal ? minC + maxC - c : c, horizontal ? r : minR + maxR - r, e.getValue());
        }
        sel.clear();
        sel.addAll(ns);
        commit();
    }

    private static void selectSame(long k) {
        String v = px.get(k);
        if (v == null) return;
        sel.clear();
        for (var e : px.entrySet()) if (e.getValue().equals(v)) sel.add(e.getKey());
    }

    // ------------------------------------------------------------------ window

    /**
     * Draws the sketchpad window when it is open. {@code depth} and {@code axisName} are shown in the status line only
     * ("size" is the extruded block size the generator reports).
     */
    public static void draw(String sizeText) {
        hotForKeys = false;
        if (!show) return;
        float sc = Ui.s();
        var vp = ImGui.getMainViewport();
        OPEN.set(true);
        ImGui.setNextWindowPos(vp.getPosX() + 360 * sc, vp.getPosY() + 80 * sc, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(Math.min(820 * sc, vp.getSizeX() * 0.6f), Math.min(640 * sc, vp.getSizeY() * 0.8f), ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSizeConstraints(420 * sc, 320 * sc, 4000, 4000);
        boolean visible = Ui.beginWindow("SKETCHPAD###Sketchpad", OPEN, 0, 0.97f);
        if (visible) {
            hotForKeys = ImGui.isWindowFocused(imgui.flag.ImGuiFocusedFlags.RootAndChildWindows) || ImGui.isWindowHovered(imgui.flag.ImGuiHoveredFlags.RootAndChildWindows);
            content(sc, sizeText);
        }
        Ui.endWindow();
        if (!OPEN.get()) close();
    }

    private static void content(float sc, String sizeText) {
        // tools
        int clicked = Ui.segmented("##sktool", TOOLS, tool, Math.min(440 * sc, ImGui.getContentRegionAvailX()), true);
        if (clicked >= 0) setTool(clicked);
        if (ImGui.button("Undo", 54 * sc, 0)) undo();
        ImGui.sameLine();
        if (ImGui.button("Redo", 54 * sc, 0)) redo();
        ImGui.sameLine();
        if (ImGui.button("Fit", 40 * sc, 0)) viewInit = false;
        Ui.tooltip("Centre the drawing in the view");
        ImGui.sameLine();
        if (ImGui.button("Reset", 60 * sc, 0)) reset();
        Ui.tooltip("Remove every pixel (Undo brings it back)");
        if (tool == CIRCLE) {
            ImGui.sameLine();
            ImGui.setNextItemWidth(90 * sc);
            int[] rad = {circleRadius};
            if (ImGui.sliderInt("Radius##skrad", rad, 1, 32)) circleRadius = rad[0];
        }

        // brush
        if (ImGui.radioButton("Shape##skb", brushIsShape)) brushIsShape = true;
        Ui.tooltip("Part of the shape: the Outline and Fill patterns of the generator colour it");
        ImGui.sameLine();
        if (ImGui.radioButton("Block##skb", !brushIsShape)) brushIsShape = false;
        Ui.tooltip("Pinned to one block, whatever the patterns say");
        ImGui.sameLine();
        if (brushIsShape) ImGui.beginDisabled();
        ImGui.setNextItemWidth(Math.max(120 * sc, ImGui.getContentRegionAvailX() - 70 * sc));
        ImGui.inputTextWithHint("##skblock", "minecraft:stone", brushBlock);
        ImGui.sameLine();
        if (ImGui.button("Hand##skhand")) {
            String held = BlockParsing.fromMainHand();
            if (held != null) brushBlock.set(held);
        }
        Ui.tooltip("Use the block in your main hand");
        if (brushIsShape) ImGui.endDisabled();
        drawRecentAndMirror(sc);

        String err = brushError();
        if (err != null) Ui.colored(Theme.ERR, err);
        else if (message != null) Ui.smallDim(message);
        else if (tool == CIRCLE) Ui.smallDim("Circle select: drag = select, Shift or middle drag = deselect, wheel = radius, Esc / right click = done");
        else Ui.smallDim(sizeText + "  ·  wheel zooms, middle drag pans, right click = menu  ·  A all, Alt+A none, Ctrl+I invert, B box, C circle, X delete");

        canvas(sc);
    }

    private static void drawRecentAndMirror(float sc) {
        // the recent blocks: one small button each
        if (!RECENT.isEmpty()) {
            Ui.smallDim("Recent");
            String pick = null;
            for (String r : RECENT) {
                ImGui.sameLine();
                if (ImGui.smallButton(shortName(r) + "##skr" + r)) pick = r;
                Ui.tooltip(r);
            }
            if (pick != null) {
                brushBlock.set(pick);
                brushIsShape = false;
            }
        }
        if (ImGui.checkbox("Mirror left/right", mirrorH)) mirrorH = !mirrorH;
        ImGui.sameLine();
        if (ImGui.checkbox("up/down", mirrorV)) mirrorV = !mirrorV;
        if (mirrorH || mirrorV) {
            ImGui.sameLine();
            if (ImGui.checkbox("axis through the cell", mirrorOnCell)) mirrorOnCell = !mirrorOnCell;
            Ui.tooltip("The mirror lines cross at the marked origin: between cells (even widths) or through the centre of cell 0,0 (odd widths)");
        }
    }

    private static String shortName(String b) {
        String s = b.startsWith("minecraft:") ? b.substring(10) : b;
        return s.length() > 14 ? s.substring(0, 13) + "…" : s;
    }

    private static final Map<String, Integer> COLORS = new HashMap<>();

    private static int blockColor(String block) {
        return COLORS.computeIfAbsent(block, b -> {
            int h = b.hashCode() * 0x9E3779B1;
            int r = 70 + ((h >>> 16) & 0x7f), g = 70 + ((h >>> 8) & 0x7f), bl = 70 + (h & 0x7f);
            return (r << 16) | (g << 8) | bl;
        });
    }

    private static void canvas(float sc) {
        float cw = Math.max(100, ImGui.getContentRegionAvailX()), ch = Math.max(120 * sc, ImGui.getContentRegionAvailY());
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY();
        ImGui.invisibleButton("##skcanvas", cw, ch, ImGuiButtonFlags.MouseButtonLeft | ImGuiButtonFlags.MouseButtonRight | ImGuiButtonFlags.MouseButtonMiddle);
        boolean hovered = ImGui.isItemHovered();
        var io = ImGui.getIO();

        if (!viewInit) fitView(cw, ch, sc);
        float cs = 22 * sc * zoom;
        float mx = ImGui.getMousePosX(), my = ImGui.getMousePosY();

        // zoom around the pointer, pan with the middle button
        if (hovered && io.getMouseWheel() != 0 && tool == CIRCLE && !ctrlDown()) {
            circleRadius = Math.max(1, Math.min(64, circleRadius + (io.getMouseWheel() > 0 ? 1 : -1)));      // Blender: wheel = radius
        } else if (hovered && io.getMouseWheel() != 0) {
            float nz = Math.max(0.15f, Math.min(6f, zoom * (float) Math.pow(1.15, io.getMouseWheel())));
            float wx = (mx - x0 - cw / 2 - panX) / cs, wy = (my - y0 - ch / 2 - panY) / cs;
            zoom = nz;
            cs = 22 * sc * zoom;
            panX = mx - x0 - cw / 2 - wx * cs;
            panY = my - y0 - ch / 2 - wy * cs;
        }
        if (hovered && ImGui.isMouseClicked(2) && tool != CIRCLE) pan = true;      // in circle select the middle button deselects
        if (pan) {
            if (ImGui.isMouseDown(2)) {
                panX += io.getMouseDeltaX();
                panY += io.getMouseDeltaY();
            } else {
                pan = false;
            }
        }

        int hc = (int) Math.floor((mx - x0 - cw / 2 - panX) / cs), hr = (int) Math.floor((my - y0 - ch / 2 - panY) / cs);
        boolean inside = hovered && !pan;
        handleMouse(inside, hc, hr, shiftDown(), ctrlDown());

        // right click: leaves circle select, otherwise the menu of the selection
        if (inside && ImGui.isMouseClicked(1) && tool == CIRCLE) {
            tool = lastTool;
        } else if (inside && ImGui.isMouseClicked(1)) {
            ctxCell = Sketch.key(hc, hr);
            if (!sel.contains(ctxCell)) {
                sel.clear();
                sel.add(ctxCell);
            }
            ctxBlock.set(brushBlock.get());
            ImGui.openPopup("##skctx");
        }

        draw(x0, y0, cw, ch, cs, hc, hr, inside, sc);
        contextMenu();
    }

    private static void fitView(float cw, float ch, float sc) {
        viewInit = true;
        if (px.isEmpty()) {
            zoom = 1f;
            panX = 0;
            panY = 0;
            return;
        }
        int minC = Integer.MAX_VALUE, minR = Integer.MAX_VALUE, maxC = Integer.MIN_VALUE, maxR = Integer.MIN_VALUE;
        for (long k : px.keySet()) {
            minC = Math.min(minC, Sketch.col(k));
            maxC = Math.max(maxC, Sketch.col(k));
            minR = Math.min(minR, Sketch.row(k));
            maxR = Math.max(maxR, Sketch.row(k));
        }
        float w = maxC - minC + 3, h = maxR - minR + 3;
        zoom = Math.max(0.15f, Math.min(4f, Math.min(cw / (w * 22 * sc), ch / (h * 22 * sc))));
        float cs = 22 * sc * zoom;
        panX = -((minC + maxC + 1) / 2f) * cs;
        panY = -((minR + maxR + 1) / 2f) * cs;
    }

    private static void handleMouse(boolean inside, int hc, int hr, boolean shift, boolean ctrl) {
        boolean down = ImGui.isMouseDown(0);
        if (tool == CIRCLE) {
            // Blender's circle select: left paints the selection, middle (or Shift + left) takes it away
            boolean add = down && !shift, remove = ImGui.isMouseDown(2) || (down && shift);
            if (inside && (add || remove)) {
                long cur = Sketch.key(hc, hr);
                int lc = haveLast ? Sketch.col(lastCell) : hc, lr = haveLast ? Sketch.row(lastCell) : hr;
                if (!haveLast || cur != lastCell) line(lc, lr, hc, hr, (c, r) -> circle(c, r, add));
                haveLast = true;
                lastCell = cur;
            } else {
                haveLast = false;
            }
            return;
        }
        if (!stroking) {
            if (!(inside && ImGui.isMouseClicked(0))) return;
            message = null;
            if ((tool == BRUSH || tool == FILL) && brushError() != null) return;
            haveLast = false;
            begin();
            anchorC = hc;
            anchorR = hr;
            rectMode = tool == BOX || (tool == SELECT && shift);
            subtract = ctrl && (tool == SELECT || tool == BOX);
            if (tool == FILL) {
                floodFill(hc, hr);
                remember(brushIsShape ? null : brushValue());
                commit();
                return;
            }
            if (tool == PICK) {
                String v = px.get(Sketch.key(hc, hr));
                if (v != null) {
                    brushIsShape = v.isEmpty();
                    if (!v.isEmpty()) brushBlock.set(v);
                    tool = BRUSH;
                }
                before = null;
                return;
            }
            stroking = true;
            if ((tool == SELECT || tool == BOX) && !ctrl && !shift) sel.clear();        // Shift extends, Ctrl subtracts, otherwise replace
            if (tool == BRUSH && !brushIsShape) remember(brushValue());
        }
        if (!down) {
            if (rectMode) {
                for (int r = Math.min(anchorR, hr); r <= Math.max(anchorR, hr); r++) {
                    for (int c = Math.min(anchorC, hc); c <= Math.max(anchorC, hc); c++) {
                        if (subtract) sel.remove(Sketch.key(c, r));
                        else sel.add(Sketch.key(c, r));
                    }
                }
            }
            commit();
            stroking = false;
            rectMode = false;
            return;
        }
        // button held: act on the cells between the last one and this one
        long cur = Sketch.key(hc, hr);
        if (haveLast && cur == lastCell) return;
        int lc = haveLast ? Sketch.col(lastCell) : hc, lr = haveLast ? Sketch.row(lastCell) : hr;
        haveLast = true;
        lastCell = cur;
        switch (tool) {
            case BRUSH -> {
                String v = brushValue();
                line(lc, lr, hc, hr, (c, r) -> setMirrored(c, r, v));
            }
            case ERASE -> line(lc, lr, hc, hr, (c, r) -> setMirrored(c, r, null));
            case SELECT, BOX -> {
                if (!rectMode) {
                    boolean rem = subtract;
                    line(lc, lr, hc, hr, (c, r) -> {
                        if (rem) sel.remove(Sketch.key(c, r));
                        else sel.add(Sketch.key(c, r));
                    });
                }
            }
            default -> {
            }
        }
    }

    private static void contextMenu() {
        if (!ImGui.beginPopup("##skctx")) return;
        int n = sel.size();
        Ui.smallDim(n + (n == 1 ? " pixel" : " pixels") + " selected");
        if (ImGui.selectable("Set to shape (patterns)")) applyToSelection(SHAPE);
        ImGui.setNextItemWidth(190 * Ui.s());
        ImGui.inputTextWithHint("##skctxblock", "block", ctxBlock);
        ImGui.sameLine();
        String err = BlockParsing.error(ctxBlock.get());
        if (err != null) ImGui.beginDisabled();
        if (ImGui.button("Set block##skctx")) {
            String b = ctxBlock.get().trim();
            applyToSelection(b);
            remember(b);
            ImGui.closeCurrentPopup();
        }
        if (err != null) ImGui.endDisabled();
        if (err != null) Ui.tooltip(err);
        for (String r : new ArrayList<>(RECENT)) {
            if (ImGui.selectable("  " + r + "##ctxr" + r)) {
                applyToSelection(r);
                ImGui.closeCurrentPopup();
            }
        }
        ImGui.separator();
        if (ImGui.selectable("Clear")) applyToSelection(null);
        if (ImGui.selectable("Select all drawn")) selectAll();
        if (ImGui.selectable("Select same block")) selectSame(ctxCell);
        ImGui.separator();
        if (ImGui.selectable("Copy")) copy(false);
        if (ImGui.selectable("Cut")) copy(true);
        if (clipboard.isEmpty()) ImGui.beginDisabled();
        if (ImGui.selectable("Paste here")) paste(ctxCell);
        if (clipboard.isEmpty()) ImGui.endDisabled();
        ImGui.separator();
        if (ImGui.selectable("Flip left/right")) flip(true);
        if (ImGui.selectable("Flip up/down")) flip(false);
        ImGui.endPopup();
    }

    private static void draw(float x0, float y0, float cw, float ch, float cs, int hc, int hr, boolean inside, float sc) {
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.pushClipRect(x0, y0, x0 + cw, y0 + ch, true);
        dl.addRectFilled(x0, y0, x0 + cw, y0 + ch, Ui.c(Theme.BG), 0f);
        float ox = x0 + cw / 2 + panX, oy = y0 + ch / 2 + panY;

        if (cs >= 7 * sc) {
            int c0 = (int) Math.floor((x0 - ox) / cs), c1 = (int) Math.ceil((x0 + cw - ox) / cs);
            int r0 = (int) Math.floor((y0 - oy) / cs), r1 = (int) Math.ceil((y0 + ch - oy) / cs);
            int grid = Ui.c(Theme.LINE2, 0.6f);
            for (int c = c0; c <= c1; c++) dl.addLine(ox + c * cs, y0, ox + c * cs, y0 + ch, grid, 1f);
            for (int r = r0; r <= r1; r++) dl.addLine(x0, oy + r * cs, x0 + cw, oy + r * cs, grid, 1f);
        }
        // origin and mirror lines
        int axisCol = Ui.c(Theme.ACCENT, mirrorH || mirrorV ? 0.9f : 0.35f);
        float shift = mirrorOnCell ? 0.5f : 0f;
        dl.addLine(ox + shift * cs, y0, ox + shift * cs, y0 + ch, axisCol, mirrorH ? 2f : 1f);
        dl.addLine(x0, oy + shift * cs, x0 + cw, oy + shift * cs, axisCol, mirrorV ? 2f : 1f);

        // pixels
        float inset = cs >= 6 * sc ? 1f : 0f;
        for (var e : px.entrySet()) {
            int c = Sketch.col(e.getKey()), r = Sketch.row(e.getKey());
            float px0 = ox + c * cs, py0 = oy + r * cs;
            if (px0 + cs < x0 || px0 > x0 + cw || py0 + cs < y0 || py0 > y0 + ch) continue;
            String v = e.getValue();
            int rgb = v.isEmpty() ? 0x7f9cbd : blockColor(v);
            if (!v.isEmpty() && BlockIcons.draw(dl, v, px0 + inset, py0 + inset, px0 + cs - inset, py0 + cs - inset)) continue;
            dl.addRectFilled(px0 + inset, py0 + inset, px0 + cs - inset, py0 + cs - inset, Ui.c(rgb));
            if (!v.isEmpty() && cs >= 18 * sc) {
                String t = shortName(v);
                if (ImGui.calcTextSizeX(t) < cs - 2) dl.addText(px0 + 2, py0 + (cs - ImGui.getFontSize()) * 0.5f, Ui.c(0x101216), t);
            }
        }
        // selection
        for (long k : sel) {
            float px0 = ox + Sketch.col(k) * cs, py0 = oy + Sketch.row(k) * cs;
            dl.addRectFilled(px0, py0, px0 + cs, py0 + cs, Ui.c(Theme.ACCENT, 0.28f));
            dl.addRect(px0, py0, px0 + cs, py0 + cs, Ui.c(Theme.ACCENT), 0f, 0, 1.5f);
        }
        // box being dragged
        if (stroking && rectMode) {
            float ax = ox + Math.min(anchorC, hc) * cs, ay = oy + Math.min(anchorR, hr) * cs;
            float bx = ox + (Math.max(anchorC, hc) + 1) * cs, by = oy + (Math.max(anchorR, hr) + 1) * cs;
            dl.addRectFilled(ax, ay, bx, by, Ui.c(Theme.ACCENT, 0.15f));
            dl.addRect(ax, ay, bx, by, Ui.c(Theme.ACCENT), 0f, 0, 2f);
        }
        // hover
        if (inside && tool == CIRCLE) {
            float rad = (float) Math.sqrt(circleRadius * circleRadius + circleRadius + 0.5f) * cs;
            dl.addCircle(ox + (hc + 0.5f) * cs, oy + (hr + 0.5f) * cs, rad, Ui.c(0xffffff, 0.85f), 48, 1.5f);
        } else if (inside) {
            float px0 = ox + hc * cs, py0 = oy + hr * cs;
            dl.addRect(px0, py0, px0 + cs, py0 + cs, Ui.c(0xffffff, 0.8f), 0f, 0, 1.5f);
        }
        dl.popClipRect();
        dl.addRect(x0, y0, x0 + cw, y0 + ch, Ui.c(Theme.BORDER_HI), 0f);

        if (inside && !stroking) {
            String v = px.get(Sketch.key(hc, hr));
            ImGui.setTooltip(hc + ", " + hr + (v == null ? "" : v.isEmpty() ? "  shape" : "  " + v));
        }
    }
}
