package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a gradient paints where, worked out without a GPU.
 *
 * The shaders on both backends do exactly this sum per pixel, and their pixel tests compare against
 * [Brush.colourAt] rather than against numbers of their own. So this file is where "where does the
 * colour change" is decided, and the GPU tests only have to prove the GLSL agrees.
 */
class BrushTest {

    private val box = Rect.of(100f, 50f, 200f, 100f)
    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    private fun assertNear(expected: Colour, actual: Colour, because: String) {
        val close = abs(expected.alpha - actual.alpha) <= 1 &&
            abs(expected.red - actual.red) <= 1 &&
            abs(expected.green - actual.green) <= 1 &&
            abs(expected.blue - actual.blue) <= 1
        assertTrue(close, "$because: expected $expected, got $actual")
    }

    @Test
    fun `a vertical gradient is its first colour along the top and its last along the bottom`() {
        val brush = Brush.vertical(red, blue)

        assertEquals(red, brush.colourAt(150f, box.top, box))
        assertEquals(blue, brush.colourAt(150f, box.bottom, box))
        assertEquals(0.5f, brush.fractionAt(290f, box.centre.y, box), 0.0001f, "halfway down is halfway along")
        assertEquals(
            brush.fractionAt(box.left, 75f, box),
            brush.fractionAt(box.right, 75f, box),
            0.0001f,
            "it does not change across",
        )
    }

    @Test
    fun `a horizontal gradient runs left to right`() {
        val brush = Brush.horizontal(red, blue)

        assertEquals(0f, brush.fractionAt(box.left, 60f, box))
        assertEquals(1f, brush.fractionAt(box.right, 60f, box))
        assertEquals(0.25f, brush.fractionAt(150f, 140f, box), 0.0001f)
    }

    @Test
    fun `an angle turns clockwise on screen and reaches the corners`() {
        val brush = Brush.linear(red, blue, degrees = 45f)

        assertEquals(0f, brush.fractionAt(box.left, box.top, box), 0.0001f, "top-left is the start")
        assertEquals(1f, brush.fractionAt(box.right, box.bottom, box), 0.0001f, "bottom-right is the end")
        val topRight = brush.fractionAt(box.right, box.top, box)
        assertTrue(topRight > 0f && topRight < 1f, "the other corners are part of the way along: $topRight")

        // On a square the two other corners lie on the line straight across the middle.
        val square = Rect.of(0f, 0f, 80f, 80f)
        assertEquals(0.5f, brush.fractionAt(square.right, square.top, square), 0.0001f)
        assertEquals(0.5f, brush.fractionAt(square.left, square.bottom, square), 0.0001f)
    }

    @Test
    fun `a gradient pointing up starts at the bottom`() {
        val brush = Brush.linear(red, blue, degrees = -90f)

        assertEquals(0f, brush.fractionAt(150f, box.bottom, box), 0.0001f)
        assertEquals(1f, brush.fractionAt(150f, box.top, box), 0.0001f)
    }

    @Test
    fun `outside the box a straight gradient holds its end colours`() {
        val brush = Brush.vertical(red, blue)

        assertEquals(red, brush.colourAt(150f, box.top - 40f, box))
        assertEquals(blue, brush.colourAt(150f, box.bottom + 40f, box))
    }

    @Test
    fun `a radial gradient is its centre colour in the middle and its edge colour at each side`() {
        val brush = Brush.radial(red, blue)

        assertEquals(red, brush.colourAt(box.centre.x, box.centre.y, box))
        assertEquals(1f, brush.fractionAt(box.left, box.centre.y, box), 0.0001f, "left side")
        assertEquals(1f, brush.fractionAt(box.centre.x, box.top, box), 0.0001f, "top side of a wide box")
        assertEquals(0.5f, brush.fractionAt(box.centre.x + 50f, box.centre.y, box), 0.0001f)
        assertEquals(blue, brush.colourAt(box.left, box.top, box), "a corner is past the rim and stays there")
    }

    @Test
    fun `fading to transparent keeps the colour rather than going dark`() {
        val halfway = Brush.between(red, Colour.Transparent, 0.5f)

        assertNear(Colour(128, 255, 0, 0), halfway, "still red, at half strength")
        // What mixing the channels one at a time would have said, and why a gradient cannot.
        assertEquals(127, red.lerp(Colour.Transparent, 0.5f).red, "the plain lerp darkens")
    }

    @Test
    fun `two opaque colours mix the ordinary way`() {
        assertNear(Colour(255, 128, 0, 128), Brush.between(red, blue, 0.5f), "purple")
        assertEquals(red, Brush.between(red, blue, -1f), "clamped below")
        assertEquals(blue, Brush.between(red, blue, 2f), "clamped above")
    }

    @Test
    fun `a box with no size is all first colour and never divides by zero`() {
        val flat = Rect.of(10f, 10f, 0f, 0f)

        assertEquals(red, Brush.vertical(red, blue).colourAt(10f, 10f, flat))
        assertEquals(red, Brush.radial(red, blue).colourAt(10f, 10f, flat))
        assertEquals(Offset(0f, 0f), Brush.Linear(red, blue).axis(0f, 0f))
    }

    @Test
    fun `the axis dotted with an offset from the middle is the fraction less a half`() {
        val brush = Brush.Linear(red, blue, degrees = 30f)
        val axis = brush.axis(box.width, box.height)
        val x = 140f
        val y = 130f

        val viaAxis = (x - box.centre.x) * axis.x + (y - box.centre.y) * axis.y + 0.5f

        assertEquals(brush.fractionAt(x, y, box), viaAxis, 0.0001f)
    }

    @Test
    fun `fading and tinting reach both ends`() {
        val faded = Brush.vertical(red, blue).scaleAlpha(0.5f)
        assertEquals(red.scaleAlpha(0.5f), faded.first)
        assertEquals(blue.scaleAlpha(0.5f), faded.last)

        val tint = Colour.rgb(0x808080)
        val tinted = Brush.radial(red, blue).modulate(tint)
        assertEquals(red.modulate(tint), tinted.first)
        assertEquals(blue.modulate(tint), tinted.last)
    }

    @Test
    fun `an angle that is not a number is refused where it was written`() {
        assertFailsWith<IllegalArgumentException> { Brush.linear(red, blue, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Brush.linear(red, blue, Float.POSITIVE_INFINITY) }
    }

    @Test
    fun `the same brush written twice is equal so a still screen stays free`() {
        assertEquals(Brush.vertical(red, blue), Brush.vertical(red, blue))
        assertEquals(Brush.radial(red, blue), Brush.radial(red, blue))
    }
}
