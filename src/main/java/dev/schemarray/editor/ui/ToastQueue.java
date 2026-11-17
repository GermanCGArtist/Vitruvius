package dev.schemarray.editor.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * The toasts of DESIGN 12.6 as a model: levels with their lifetimes, at most three at once (the oldest goes),
 * a hovered toast pauses its timer, and a fade-out at the end. Times are milliseconds from any clock that only goes
 * forward; the window code passes ImGui's time.
 */
public final class ToastQueue {
    public enum Level {
        INFO(3600), WARN(5000), ERROR(8000);

        public final long lifeMillis;

        Level(long lifeMillis) {
            this.lifeMillis = lifeMillis;
        }
    }

    public static final int MAX = 3;
    public static final long FADE_MILLIS = 600;

    /** One toast. {@code action} (with {@code actionLabel}) is the single optional button. */
    public static final class Toast {
        public final Level level;
        public final String text;
        public final String actionLabel;
        public final Runnable action;
        private long remaining;
        private boolean clicked;

        private Toast(Level level, String text, String actionLabel, Runnable action) {
            this.level = level;
            this.text = text;
            this.actionLabel = actionLabel;
            this.action = action;
            this.remaining = level.lifeMillis + FADE_MILLIS;
        }

        /** 1 while shown fully, falling to 0 during the fade-out. */
        public float alpha() {
            return remaining >= FADE_MILLIS ? 1f : Math.max(0f, remaining / (float) FADE_MILLIS);
        }

        public long remainingMillis() {
            return remaining;
        }

        public boolean hasAction() {
            return action != null && actionLabel != null;
        }
    }

    private final List<Toast> toasts = new ArrayList<>();
    private long lastTick = -1;

    /** The toasts, oldest first. */
    public List<Toast> toasts() {
        return toasts;
    }

    public Toast add(Level level, String text) {
        return add(level, text, null, null);
    }

    public Toast add(Level level, String text, String actionLabel, Runnable action) {
        Toast t = new Toast(level, text, actionLabel, action);
        toasts.add(t);
        while (toasts.size() > MAX) toasts.remove(0);
        return t;
    }

    /** Runs the toast's action (the button) and dismisses it. */
    public void click(Toast t) {
        if (!toasts.contains(t)) return;
        t.clicked = true;
        t.remaining = 0;
        if (t.action != null) t.action.run();
    }

    /** Dismisses a toast (a click on an error toast without an action). */
    public void dismiss(Toast t) {
        t.remaining = 0;
    }

    /**
     * Advances the timers to {@code nowMillis}. {@code hovered} is the toast under the pointer (its timer stands
     * still), or null. Expired toasts are removed.
     */
    public void tick(long nowMillis, Toast hovered) {
        long dt = lastTick < 0 ? 0 : Math.max(0, nowMillis - lastTick);
        lastTick = nowMillis;
        for (Toast t : toasts) {
            if (t == hovered && t.remaining > FADE_MILLIS) continue;
            t.remaining -= dt;
        }
        toasts.removeIf(t -> t.remaining <= 0);
    }
}
