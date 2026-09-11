package dev.wildware.composegl.ui.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeometryTest {

    @Test
    fun `a rectangle contains its top-left corner but not its bottom-right`() {
        val rect = Rect.of(10f, 10f, 100f, 50f)

        assertTrue(Offset(10f, 10f) in rect, "the top-left corner is inside")
        assertFalse(Offset(110f, 60f) in rect, "the bottom-right corner is not")

        // Half-open, so two rectangles that share an edge do not both own the pixels on it.
        assertTrue(Offset(109.9f, 59.9f) in rect)
        assertFalse(Offset(110f, 30f) in rect)
    }

    @Test
    fun `intersecting rectangles that do not overlap gives an empty one, not an error`() {
        val left = Rect.of(0f, 0f, 10f, 10f)
        val right = Rect.of(50f, 0f, 10f, 10f)

        val overlap = left.intersect(right)

        assertTrue(overlap.isEmpty, "nested clipping relies on this being an answer, not a throw")
        assertFalse(left.overlaps(right))
    }

    @Test
    fun `nested clips intersect down to the smallest`() {
        val outer = Rect.of(0f, 0f, 100f, 100f)
        val middle = Rect.of(20f, 20f, 100f, 100f)
        val inner = Rect.of(10f, 10f, 40f, 40f)

        val clip = outer.intersect(middle).intersect(inner)

        assertEquals(Rect(20f, 20f, 50f, 50f), clip)
    }

    @Test
    fun `insetting shrinks and a negative inset grows`() {
        val rect = Rect.of(0f, 0f, 100f, 100f)

        assertEquals(Rect(10f, 10f, 90f, 90f), rect.inset(10f))
        assertEquals(Rect(-5f, -5f, 105f, 105f), rect.inset(-5f))
    }

    @Test
    fun `insetting past the middle gives an empty rectangle rather than a negative one`() {
        val rect = Rect.of(0f, 0f, 10f, 10f)

        val gone = rect.inset(20f)

        assertTrue(gone.isEmpty)
        assertEquals(Size.Zero, gone.size, "a size is never negative, whatever the edges say")
    }

    @Test
    fun `a size cannot be negative`() {
        assertThrows(IllegalArgumentException::class.java) { Size(-1f, 10f) }
    }

    @Test
    fun `offsets add, subtract and scale`() {
        val a = Offset(3f, 4f)

        assertEquals(Offset(4f, 6f), a + Offset(1f, 2f))
        assertEquals(Offset(2f, 2f), a - Offset(1f, 2f))
        assertEquals(Offset(6f, 8f), a * 2f)
        assertEquals(5f, Offset.Zero.distanceTo(a), 0.0001f)
    }
}
