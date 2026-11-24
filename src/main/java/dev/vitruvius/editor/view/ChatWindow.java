package dev.vitruvius.editor.view;

import dev.vitruvius.editor.EditorSession;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft's chat as an editor window, so the vanilla chat screen (which stalls the client while typing with the
 * editor's UI running) is never opened in editor mode. The history is read from the game's own chat component, so
 * everything shows: server and player messages, and the messages other mods print to the player. Typing a line and
 * pressing Enter sends it (a leading slash sends a command); Up and Down walk the lines sent before. The chat key (T)
 * and the command key (/) open the window and put the cursor in the field.
 */
public final class ChatWindow {
    private ChatWindow() {}

    private static final int MAX_LINES = 300;
    private static final ImBoolean OPEN = new ImBoolean(true);
    private static final ImString INPUT = new ImString(512);

    private static Field allMessages;
    private static boolean reflectionFailed;
    private static boolean focusInput;
    private static String swallow;             // the character of the key that opened the window: it must not end up in the field
    private static int swallowFrames;
    private static int lastCount;
    private static int historyPos = -1;

    /** Opens the window with the cursor in the field; {@code command} starts the line with a slash. */
    public static void open(EditorSession s, boolean command) {
        s.showChat = true;
        s.windowsHidden = false;
        focusInput = true;
        if (command) INPUT.set("/");
        Minecraft mc = Minecraft.getInstance();
        var key = command ? mc.options.keyCommand : mc.options.keyChat;
        swallow = key.getTranslatedKeyMessage().getString().toLowerCase(java.util.Locale.ROOT);
        swallowFrames = 3;
        historyPos = -1;
    }

    /** The newest {@link #MAX_LINES} lines, oldest first. */
    @SuppressWarnings("unchecked")
    private static List<String> lines(Minecraft mc) {
        List<String> out = new ArrayList<>();
        if (reflectionFailed) return out;
        try {
            var chat = mc.gui.hud.getChat();
            if (allMessages == null) {
                allMessages = net.minecraft.client.gui.components.ChatComponent.class.getDeclaredField("allMessages");
                allMessages.setAccessible(true);
            }
            List<GuiMessage> all = (List<GuiMessage>) allMessages.get(chat);
            int n = Math.min(all.size(), MAX_LINES);
            for (int i = n - 1; i >= 0; i--) {          // the game keeps the newest first
                Component c = all.get(i).content();
                out.add(c == null ? "" : c.getString());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectionFailed = true;
        }
        return out;
    }

    public static void draw(EditorSession s) {
        float sc = Ui.s();
        var vp = ImGui.getMainViewport();
        OPEN.set(s.showChat);
        ImGui.setNextWindowPos(vp.getPosX() + 12 * sc, vp.getPosY() + vp.getSizeY() - 300 * sc, s.resetLayout ? ImGuiCond.Always : ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(460 * sc, 250 * sc, s.resetLayout ? ImGuiCond.Always : ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSizeConstraints(240 * sc, 120 * sc, 4000, 4000);
        boolean visible = Ui.beginWindow("04 CHAT###Chat", OPEN, 0, 0.94f);
        if (visible) drawContent(s, sc);
        Ui.endWindow();
        s.showChat = OPEN.get();
    }

    private static void drawContent(EditorSession s, float sc) {
        Minecraft mc = Minecraft.getInstance();
        List<String> lines = lines(mc);
        float footer = 34 * sc;
        ImGui.beginChild("##chatlog", 0, -footer, false, ImGuiWindowFlags.NoNavInputs);
        boolean atBottom = ImGui.getScrollY() >= ImGui.getScrollMaxY() - 4 * sc;
        if (reflectionFailed) Ui.dim("The chat history cannot be read in this version. You can still type below.");
        for (String line : lines) ImGui.textWrapped(line);
        if (lines.size() != lastCount && (atBottom || lastCount == 0)) ImGui.setScrollHereY(1f);
        lastCount = lines.size();
        ImGui.endChild();

        if (focusInput) {
            ImGui.setKeyboardFocusHere();
            focusInput = false;
        }
        float ix = ImGui.getCursorScreenPosX(), iy = ImGui.getCursorScreenPosY();
        ImGui.setNextItemWidth(-1);
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.FramePadding, 20 * sc, 4.5f * sc);
        boolean enter = ImGui.inputTextWithHint("##chatinput", "Type a message, or / for a command (Enter sends)", INPUT, ImGuiInputTextFlags.EnterReturnsTrue);
        ImGui.popStyleVar();
        float ih = ImGui.getItemRectSizeY();
        dev.liskov.ui.theme.MubTheme.push(dev.liskov.ui.theme.MubTheme.mono());
        ImGui.getWindowDrawList().addText(ix + 7 * sc, iy + (ih - ImGui.getFontSize()) * 0.5f, Ui.c(dev.liskov.ui.theme.MubTheme.ACCENT), ">");
        ImGui.popFont();
        boolean active = ImGui.isItemActive();
        if (swallowFrames > 0) {
            swallowFrames--;
            String t = INPUT.get().toLowerCase(java.util.Locale.ROOT);
            if (swallow != null && !swallow.isEmpty() && (t.equals(swallow) || t.equals("/" + swallow) && swallow.equals("/"))) {
                INPUT.set(swallow.equals("/") ? "/" : "");     // the key that opened the window typed itself once
            }
        }
        if (active) historyKeys(mc);
        if (enter) {
            send(mc, INPUT.get());
            INPUT.set("");
            historyPos = -1;
            focusInput = true;
        }
    }

    private static void historyKeys(Minecraft mc) {
        boolean up = ImGui.isKeyPressed(ImGuiKey.UpArrow), down = ImGui.isKeyPressed(ImGuiKey.DownArrow);
        if (!up && !down) return;
        var recent = mc.gui.hud.getChat().getRecentChat();
        if (recent.isEmpty()) return;
        historyPos = up ? Math.min(historyPos + 1, recent.size() - 1) : historyPos - 1;
        if (historyPos < 0) {
            historyPos = -1;
            INPUT.set("");
        } else {
            INPUT.set(recent.get(recent.size() - 1 - historyPos));
        }
    }

    private static void send(Minecraft mc, String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || mc.player == null) return;
        mc.gui.hud.getChat().addRecentChat(t);
        if (t.startsWith("/")) mc.player.connection.sendCommand(t.substring(1));
        else mc.player.connection.sendChat(t);
    }
}
