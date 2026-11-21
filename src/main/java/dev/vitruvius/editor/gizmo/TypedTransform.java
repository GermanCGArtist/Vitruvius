package dev.vitruvius.editor.gizmo;

import dev.vitruvius.editor.core.Vec3i;

/**
 * The typed-transform modal (G / R, DESIGN 6.4 and 12.5) without UI: the axis, the typed amount and what it means.
 * A move has an axis mask (one axis, or two for a plane with {@link #nextField()} separating the two amounts); a
 * rotate turns about Y and rounds the typed degrees to quarter turns.
 */
public final class TypedTransform {
    public enum Kind { MOVE, ROTATE }

    public static final int X = 1, Y = 2, Z = 4;
    /** Most characters of one amount, sign included. */
    public static final int MAX_CHARS = 6;

    private final Kind kind;
    private int mask;
    private final StringBuilder[] fields = {new StringBuilder(), new StringBuilder()};
    private int cur;

    private TypedTransform(Kind kind, int mask) {
        this.kind = kind;
        this.mask = mask;
    }

    public static TypedTransform move(int mask) {
        return new TypedTransform(Kind.MOVE, mask == 0 ? X : mask);
    }

    public static TypedTransform rotate() {
        return new TypedTransform(Kind.ROTATE, Y);
    }

    public Kind kind() {
        return kind;
    }

    public int mask() {
        return mask;
    }

    public boolean isPlane() {
        return Integer.bitCount(mask) > 1;
    }

    /** Chooses the axis of a move (X/Y/Z), or with {@code plane} the plane that leaves it out (Shift+Y = XZ). */
    public void setAxis(int bit, boolean plane) {
        if (kind != Kind.MOVE) return;
        mask = plane ? (X | Y | Z) & ~bit : bit;
        if (!isPlane()) cur = 0;
    }

    /** Selects a single axis (clicking a chip returns a plane to that single axis). */
    public void clickAxis(int bit) {
        if (kind != Kind.MOVE) return;
        setAxis(bit, false);
    }

    private StringBuilder field() {
        return fields[cur];
    }

    /** Appends a digit 0-9 to the amount; false when it did not fit. */
    public boolean digit(int d) {
        if (d < 0 || d > 9) return false;
        StringBuilder f = field();
        if (f.length() >= MAX_CHARS) return false;
        if (f.toString().equals("0") || f.toString().equals("-0")) f.setLength(f.length() - 1);   // no leading zeros
        f.append((char) ('0' + d));
        return true;
    }

    /** The minus key: flips the sign of the amount being typed. */
    public void negate() {
        StringBuilder f = field();
        if (f.length() > 0 && f.charAt(0) == '-') f.deleteCharAt(0);
        else f.insert(0, '-');
    }

    /** Backspace removes the last character; on an empty second amount it goes back to the first. */
    public void backspace() {
        StringBuilder f = field();
        if (f.length() > 0) f.setLength(f.length() - 1);
        else if (cur == 1) cur = 0;
    }

    /** Moves to the second amount of a plane. */
    public void nextField() {
        if (isPlane()) cur = 1;
    }

    public boolean hasInput() {
        return fields[0].length() > 0 || fields[1].length() > 0;
    }

    /** The parsed amount of a field: empty, a lone minus or garbage mean 0. */
    public static int parse(CharSequence text) {
        String s = text.toString();
        if (s.isEmpty() || s.equals("-")) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public int value(int field) {
        return parse(fields[field]);
    }

    /** The raw text of an amount as typed ("" when nothing). */
    public String text(int field) {
        return fields[field].toString();
    }

    /** Index of the amount that digits go to now. */
    public int currentField() {
        return cur;
    }

    /** The delta in blocks of a move. */
    public Vec3i delta() {
        int[] v = new int[3];
        int k = 0;
        for (int a = 0; a < 3; a++) {
            if ((mask & (1 << a)) != 0) v[a] = value(Math.min(k++, 1));
        }
        return new Vec3i(v[0], v[1], v[2]);
    }

    /** Typed degrees of a rotate. */
    public int degrees() {
        return value(0);
    }

    /** Quarter turns a rotate means: the typed degrees rounded to 90 (45 rounds away from zero). */
    public int quarterTurns() {
        return TurnMath.quarterTurns(Math.toRadians(degrees()));
    }

    /** "+12", "-3", or "+0" for nothing typed (what the HUD shows for one amount). */
    public String shown(int field) {
        String t = text(field);
        if (t.isEmpty()) return "+0";
        if (t.equals("-")) return "-";
        return t.startsWith("-") ? t : "+" + t;
    }
}
