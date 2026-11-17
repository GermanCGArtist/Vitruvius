package dev.vitruvius.editor.gizmo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.Vec3i;
import org.junit.jupiter.api.Test;

class TypedTransformTest {
    private static void type(TypedTransform t, String s) {
        for (char c : s.toCharArray()) {
            if (c == '-') t.negate();
            else if (c == ',') t.nextField();
            else t.digit(c - '0');
        }
    }

    private static void clear(TypedTransform t) {
        for (int i = 0; i < 10; i++) t.backspace();
    }

    @Test
    void typesAnAmountOnTheChosenAxis() {
        TypedTransform t = TypedTransform.move(TypedTransform.X);
        t.setAxis(TypedTransform.Y, false);
        type(t, "12");
        assertEquals(new Vec3i(0, 12, 0), t.delta());
        t.negate();
        assertEquals(new Vec3i(0, -12, 0), t.delta());
        assertEquals("-12", t.shown(0));
    }

    @Test
    void backspaceAndMinusEditTheAmount() {
        TypedTransform t = TypedTransform.move(TypedTransform.Z);
        type(t, "-45");
        assertEquals(-45, t.value(0));
        t.backspace();
        assertEquals(-4, t.value(0));
        t.backspace();
        t.backspace();
        assertEquals("", t.text(0));
        assertEquals(0, t.value(0));
        assertFalse(t.hasInput());
    }

    @Test
    void leadingZerosAreDropped() {
        TypedTransform t = TypedTransform.move(TypedTransform.X);
        type(t, "007");
        assertEquals("7", t.text(0));
    }

    @Test
    void anAmountHasALimit() {
        TypedTransform t = TypedTransform.move(TypedTransform.X);
        type(t, "1234567890");
        assertEquals(TypedTransform.MAX_CHARS, t.text(0).length());
    }

    @Test
    void planeTakesTwoAmounts() {
        TypedTransform t = TypedTransform.move(TypedTransform.X);
        t.setAxis(TypedTransform.Y, true);                  // Shift+Y: the XZ plane
        assertTrue(t.isPlane());
        type(t, "4,-2");
        assertEquals(new Vec3i(4, 0, -2), t.delta());
        t.backspace();
        t.backspace();
        t.backspace();                                       // an empty second field goes back to the first
        assertEquals(0, t.currentField());
    }

    @Test
    void anAxisChipClickReturnsToASingleAxis() {
        TypedTransform t = TypedTransform.move(TypedTransform.X | TypedTransform.Z);
        type(t, "3");
        t.clickAxis(TypedTransform.Z);
        assertFalse(t.isPlane());
        assertEquals(new Vec3i(0, 0, 3), t.delta());
    }

    @Test
    void rotateRoundsDegreesToQuarterTurns() {
        TypedTransform t = TypedTransform.rotate();
        type(t, "100");
        assertEquals(1, t.quarterTurns());
        clear(t);
        type(t, "180");
        assertEquals(2, t.quarterTurns());
        clear(t);
        type(t, "-90");
        assertEquals(-1, t.quarterTurns());
        clear(t);
        type(t, "-45");
        assertEquals(-1, t.quarterTurns());
        clear(t);
        type(t, "44");
        assertEquals(0, t.quarterTurns());
    }

    @Test
    void parseIsLenient() {
        assertEquals(0, TypedTransform.parse(""));
        assertEquals(0, TypedTransform.parse("-"));
        assertEquals(-7, TypedTransform.parse("-7"));
    }
}
