package dev.vitruvius.editor.view;

import imgui.ImDrawList;

/**
 * The editor's line icons (DESIGN 13): a 16-unit grid scaled to radius {@code r} around ({@code cx}, {@code cy}),
 * round joins, stroke {@code max(1.4, r / 6)}. Same signature idea as Manhattan's {@code Icons.draw}; unknown names
 * draw nothing. Colours are packed ImGui colours.
 */
public final class EditorIcons {
    private EditorIcons() {}

    /** Draws one icon. */
    public static void draw(ImDrawList dl, String name, float cx, float cy, float r, int col) {
        G g = new G(dl, cx, cy, r, col);
        switch (name) {
            case "cursor" -> { g.poly(true, 4, 2, 4, 13, 7.2f, 10, 10, 14.5f, 11.8f, 13.6f, 9, 9.2f, 13, 8.6f); }
            case "move" -> {
                g.line(8, 2, 8, 14); g.line(2, 8, 14, 8);
                g.poly(false, 6, 4, 8, 2, 10, 4); g.poly(false, 6, 12, 8, 14, 10, 12);
                g.poly(false, 4, 6, 2, 8, 4, 10); g.poly(false, 12, 6, 14, 8, 12, 10);
            }
            case "rotate" -> {
                g.arc(8, 8, 5.5f, -50, 250);
                g.poly(false, 10.8f, 1.6f, 13.6f, 3.4f, 10.6f, 5.2f);
            }
            case "mirror" -> {
                g.line(8, 2, 8, 14);
                g.poly(true, 6.5f, 4, 6.5f, 12, 2.5f, 12); g.poly(true, 9.5f, 4, 9.5f, 12, 13.5f, 12);
            }
            case "magnet" -> {
                g.arc(8, 7.5f, 4.5f, 180, 360);
                g.line(3.5f, 7.5f, 3.5f, 13); g.line(12.5f, 7.5f, 12.5f, 13);
                g.line(2, 11, 5, 11); g.line(11, 11, 14, 11);
            }
            case "undo" -> { g.arc(8.5f, 9, 4.5f, -90, 90); g.line(8.5f, 4.5f, 4, 4.5f); g.poly(false, 6, 2.5f, 3.6f, 4.5f, 6, 6.5f); }
            case "redo" -> { g.arc(7.5f, 9, 4.5f, 90, 270); g.line(7.5f, 4.5f, 12, 4.5f); g.poly(false, 10, 2.5f, 12.4f, 4.5f, 10, 6.5f); }
            case "eye" -> { g.poly(true, 1.5f, 8, 5, 4.5f, 11, 4.5f, 14.5f, 8, 11, 11.5f, 5, 11.5f); g.circle(8, 8, 2f); }
            case "eyeoff" -> {
                g.poly(true, 1.5f, 8, 5, 4.5f, 11, 4.5f, 14.5f, 8, 11, 11.5f, 5, 11.5f); g.line(3, 13.5f, 13, 2.5f);
            }
            case "lock" -> { g.rect(3.5f, 7, 12.5f, 14, 1.5f); g.arc(8, 7, 3, 180, 360); }
            case "folder" -> { g.poly(true, 2, 4, 6.5f, 4, 8, 5.8f, 14, 5.8f, 14, 12.5f, 2, 12.5f); }
            case "object" -> {
                g.poly(true, 8, 1.8f, 13.6f, 5, 13.6f, 11, 8, 14.2f, 2.4f, 11, 2.4f, 5);
                g.poly(false, 2.4f, 5, 8, 8.2f, 13.6f, 5); g.line(8, 8.2f, 8, 14.2f);
            }
            case "assembly" -> { cube(g, 5.2f, 4.2f, 3.6f); cube(g, 10.8f, 4.2f, 3.6f); cube(g, 8, 10.4f, 3.6f); }
            case "cutter", "boolean" -> { g.circle(6, 8, 4f); g.circle(10, 8, 4f); }
            case "array" -> { g.rect(2, 4, 5, 12, 0.5f); g.rect(6.5f, 4, 9.5f, 12, 0.5f); g.rect(11, 4, 14, 12, 0.5f); }
            case "link" -> { g.rect(1.8f, 6, 8, 10, 2f); g.rect(8, 6, 14.2f, 10, 2f); }
            case "project" -> { g.rect(2.5f, 3.5f, 13.5f, 13, 1.5f); g.line(2.5f, 6.5f, 13.5f, 6.5f); g.line(5.5f, 2, 5.5f, 5); g.line(10.5f, 2, 10.5f, 5); }
            case "loose" -> { g.circle(4.5f, 5, 1.6f); g.circle(11, 6, 1.6f); g.circle(7, 11.5f, 1.6f); }
            case "sync" -> { g.arc(8, 8, 5, -20, 150); g.arc(8, 8, 5, 160, 330); g.poly(false, 11.8f, 1.8f, 12.8f, 5.2f, 9.4f, 5.6f); g.poly(false, 4.2f, 14.2f, 3.2f, 10.8f, 6.6f, 10.4f); }
            case "chev" -> g.poly(false, 6, 3.5f, 10.5f, 8, 6, 12.5f);
            case "chevdown" -> g.poly(false, 3.5f, 6, 8, 10.5f, 12.5f, 6);
            case "plus" -> { g.line(8, 3, 8, 13); g.line(3, 8, 13, 8); }
            case "close" -> { g.line(3.5f, 3.5f, 12.5f, 12.5f); g.line(12.5f, 3.5f, 3.5f, 12.5f); }
            case "trash" -> { g.line(2.5f, 4.5f, 13.5f, 4.5f); g.line(6, 4.5f, 6, 2.8f); g.line(10, 4.5f, 10, 2.8f); g.line(6, 2.8f, 10, 2.8f); g.poly(false, 4, 4.5f, 4.8f, 13.5f, 11.2f, 13.5f, 12, 4.5f); }
            case "dup" -> { g.rect(2.5f, 5.5f, 10, 13.5f, 1f); g.poly(false, 5.5f, 5.5f, 5.5f, 2.5f, 13.5f, 2.5f, 13.5f, 10.5f, 10, 10.5f); }
            case "export" -> { g.line(8, 2, 8, 9.5f); g.poly(false, 5, 5, 8, 2, 11, 5); g.poly(false, 2.5f, 9, 2.5f, 13.5f, 13.5f, 13.5f, 13.5f, 9); }
            case "front" -> { g.rect(2.5f, 2.5f, 10, 10, 1f); g.rect(6, 6, 13.5f, 13.5f, 1f); }
            case "back" -> { g.rect(6, 6, 13.5f, 13.5f, 1f); g.rect(2.5f, 2.5f, 10, 10, 1f); }
            case "explode" -> {
                g.line(8, 2, 8, 5.5f); g.line(8, 10.5f, 8, 14); g.line(2, 8, 5.5f, 8); g.line(10.5f, 8, 14, 8);
                g.line(3.6f, 3.6f, 5.6f, 5.6f); g.line(12.4f, 3.6f, 10.4f, 5.6f); g.line(3.6f, 12.4f, 5.6f, 10.4f); g.line(12.4f, 12.4f, 10.4f, 10.4f);
            }
            case "realise" -> { g.rect(1.5f, 4.5f, 4, 11.5f, 0.5f); g.dashedRect(5.5f, 4.5f, 8, 11.5f); g.line(10, 8, 14.5f, 8); g.poly(false, 12.5f, 6, 14.5f, 8, 12.5f, 10); }
            case "group" -> { g.dashedRect(2, 2, 14, 14); g.rect(5.5f, 5.5f, 10.5f, 10.5f, 0.5f); }
            case "local" -> { g.circle(8, 8, 5.5f); g.fillCircle(8, 8, 1.8f); }
            case "frame" -> {
                g.poly(false, 2.5f, 6, 2.5f, 2.5f, 6, 2.5f); g.poly(false, 10, 2.5f, 13.5f, 2.5f, 13.5f, 6);
                g.poly(false, 2.5f, 10, 2.5f, 13.5f, 6, 13.5f); g.poly(false, 10, 13.5f, 13.5f, 13.5f, 13.5f, 10);
            }
            case "active" -> { g.circle(8, 8, 5.8f); g.poly(false, 5.2f, 8.2f, 7.2f, 10.2f, 10.8f, 6); }
            case "grip" -> { for (int i = 0; i < 3; i++) { g.fillCircle(6, 4 + i * 4, 0.9f); g.fillCircle(10, 4 + i * 4, 0.9f); } }
            case "kebab" -> { g.fillCircle(3.5f, 8, 1f); g.fillCircle(8, 8, 1f); g.fillCircle(12.5f, 8, 1f); }
            case "layout" -> { g.rect(2, 3, 14, 13, 1.5f); g.line(6.5f, 3, 6.5f, 13); g.line(6.5f, 8, 14, 8); }
            case "search" -> { g.circle(7, 7, 4.2f); g.line(10.2f, 10.2f, 13.8f, 13.8f); }
            case "box" -> { g.dashedRect(2, 3, 12, 11); g.poly(true, 9.5f, 9.5f, 9.5f, 14, 11.2f, 12.4f); }
            case "help" -> { g.circle(8, 8, 6f); g.arc(8, 6.3f, 1.9f, 180, 400); g.line(8, 8.2f, 8, 9.2f); g.fillCircle(8, 11.2f, 0.7f); }
            case "areas" -> g.dashedRect(2.5f, 2.5f, 13.5f, 13.5f);
            case "map" -> { g.rect(2.5f, 2.5f, 13.5f, 13.5f, 1f); g.line(8, 2.5f, 8, 13.5f); g.line(2.5f, 8, 13.5f, 8); g.fillCircle(11, 5, 1.2f); }
            case "capture" -> { g.circle(8, 8, 4f); g.line(8, 1.5f, 8, 4); g.line(8, 12, 8, 14.5f); g.line(1.5f, 8, 4, 8); g.line(12, 8, 14.5f, 8); }
            default -> { }
        }
    }

    private static void cube(G g, float cx, float cy, float r) {
        g.poly(true, cx, cy - r, cx + r * 0.95f, cy - r * 0.5f, cx + r * 0.95f, cy + r * 0.5f, cx, cy + r, cx - r * 0.95f, cy + r * 0.5f, cx - r * 0.95f, cy - r * 0.5f);
    }

    /** A spinner: a 270 degree arc turning 0.7 s per turn. */
    public static void spinner(ImDrawList dl, float cx, float cy, float r, int col, double timeSeconds) {
        float a0 = (float) ((timeSeconds / 0.7) % 1.0) * 360f;
        G g = new G(dl, cx, cy, r * 2f, col);
        g.thick = 2f;
        g.arcPx(0, 0, r, a0, a0 + 270);
    }

    /** The drawing helper: grid units to pixels. */
    private static final class G {
        final ImDrawList dl;
        final float cx, cy, k;
        final int col;
        float thick;

        G(ImDrawList dl, float cx, float cy, float r, int col) {
            this.dl = dl;
            this.cx = cx;
            this.cy = cy;
            this.k = r / 8f;
            this.col = col;
            this.thick = Math.max(1.4f, r / 6f);
        }

        float x(float u) { return cx + (u - 8) * k; }

        float y(float u) { return cy + (u - 8) * k; }

        void line(float x1, float y1, float x2, float y2) {
            dl.addLine(x(x1), y(y1), x(x2), y(y2), col, thick);
        }

        void poly(boolean closed, float... p) {
            int n = p.length / 2;
            for (int i = 0; i < n - 1; i++) line(p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3]);
            if (closed && n > 2) line(p[2 * n - 2], p[2 * n - 1], p[0], p[1]);
        }

        void rect(float x1, float y1, float x2, float y2, float round) {
            dl.addRect(x(x1), y(y1), x(x2), y(y2), col, round * k, 0, thick);
        }

        void dashedRect(float x1, float y1, float x2, float y2) {
            float d = 2f, gap = 1.6f;
            dashed(x1, y1, x2, y1, d, gap); dashed(x2, y1, x2, y2, d, gap);
            dashed(x2, y2, x1, y2, d, gap); dashed(x1, y2, x1, y1, d, gap);
        }

        private void dashed(float x1, float y1, float x2, float y2, float d, float gap) {
            float len = (float) Math.hypot(x2 - x1, y2 - y1), ux = (x2 - x1) / len, uy = (y2 - y1) / len;
            for (float t = 0; t < len; t += d + gap) {
                float e = Math.min(len, t + d);
                line(x1 + ux * t, y1 + uy * t, x1 + ux * e, y1 + uy * e);
            }
        }

        void circle(float ux, float uy, float ur) {
            dl.addCircle(x(ux), y(uy), ur * k, col, 20, thick);
        }

        void fillCircle(float ux, float uy, float ur) {
            dl.addCircleFilled(x(ux), y(uy), ur * k, col, 12);
        }

        /** An arc in grid units, degrees measured clockwise from 3 o'clock (screen space). */
        void arc(float ux, float uy, float ur, float fromDeg, float toDeg) {
            arcPx(x(ux) - cx, y(uy) - cy, ur * k, fromDeg, toDeg);
        }

        void arcPx(float ox, float oy, float rad, float fromDeg, float toDeg) {
            int seg = Math.max(6, (int) (Math.abs(toDeg - fromDeg) / 20f));
            float px = 0, py = 0;
            for (int i = 0; i <= seg; i++) {
                double a = Math.toRadians(fromDeg + (toDeg - fromDeg) * i / seg);
                float qx = cx + ox + (float) Math.cos(a) * rad, qy = cy + oy + (float) Math.sin(a) * rad;
                if (i > 0) dl.addLine(px, py, qx, qy, col, thick);
                px = qx;
                py = qy;
            }
        }
    }
}
