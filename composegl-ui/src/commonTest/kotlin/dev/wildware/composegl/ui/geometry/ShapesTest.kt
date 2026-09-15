package dev.wildware.composegl.ui.geometry

import dev.wildware.composegl.ui.modifier.ClipElement
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The outlines a clip and a hit test share, checked as geometry with nothing drawn. */
class ShapesTest {

    private val square = Size(100f, 100f)

    @Test
    fun `a circle contains its middle and not the corners of its box`() {
        assertTrue(Shapes.Circle.contains(Offset(50f, 50f), square))
        assertTrue(Shapes.Circle.contains(Offset(50f, 1f), square), "the top of the circle touches the box")
        assertFalse(Shapes.Circle.contains(Offset(5f, 5f), square))
        assertFalse(Shapes.Circle.contains(Offset(95f, 95f), square))
    }

    @Test
    fun `a circle stays round in a box wider than it is tall`() {
        val wide = Size(200f, 100f)
        assertTrue(Shapes.Circle.contains(Offset(100f, 50f), wide))
        assertFalse(Shapes.Circle.contains(Offset(10f, 50f), wide), "an ellipse would reach here and a circle does not")
        assertTrue(Shapes.Ellipse.contains(Offset(10f, 50f), wide), "which is what the ellipse is for")
    }

    @Test
    fun `every point of a circle's outline sits on its radius`() {
        val outline = Shapes.Circle.outline(80f, 60f)
        assertTrue(outline.size / 2 >= 16, "walked smoothly enough to read as round, got ${outline.size / 2}")
        for (at in outline.indices step 2) {
            val r = hypot(outline[at] - 40f, outline[at + 1] - 30f)
            assertTrue(abs(r - 30f) < 0.01f, "every point on the radius of the shorter side, got $r")
        }
    }

    @Test
    fun `a diamond gives up the corners of its box and keeps its middle`() {
        assertTrue(Shapes.Diamond.contains(Offset(50f, 50f), square))
        assertTrue(Shapes.Diamond.contains(Offset(50f, 2f), square))
        assertFalse(Shapes.Diamond.contains(Offset(10f, 10f), square))
        assertFalse(Shapes.Diamond.contains(Offset(90f, 90f), square))
    }

    @Test
    fun `a hexagon's outline fills its box from edge to edge`() {
        val outline = Shapes.Hexagon.outline(60f, 80f)
        val xs = outline.filterIndexed { at, _ -> at % 2 == 0 }
        val ys = outline.filterIndexed { at, _ -> at % 2 == 1 }
        assertEquals(0f, xs.min())
        assertEquals(60f, xs.max())
        assertEquals(0f, ys.min())
        assertEquals(80f, ys.max())
        assertFalse(Shapes.Hexagon.contains(Offset(2f, 2f), Size(60f, 80f)), "the top-left corner is cut")
    }

    @Test
    fun `a rounded rectangle is only cut at its corners`() {
        val shape = Shapes.roundedRect(20f)
        assertFalse(shape.contains(Offset(1f, 1f), square), "the very corner is outside the curve")
        assertTrue(shape.contains(Offset(50f, 1f), square), "the middle of an edge is not")
        assertTrue(shape.contains(Offset(1f, 50f), square))
        assertTrue(shape.contains(Offset(8f, 8f), square), "inside the curve of the corner")
    }

    @Test
    fun `a corner bigger than the box makes a pill`() {
        val pill = Shapes.roundedRect(500f)
        val size = Size(100f, 40f)
        assertTrue(pill.contains(Offset(50f, 20f), size))
        assertFalse(pill.contains(Offset(2f, 2f), size))
        val outline = pill.outline(100f, 40f)
        for (at in outline.indices step 2) {
            assertTrue(outline[at] in -0.01f..100.01f && outline[at + 1] in -0.01f..40.01f, "never outside the box")
        }
    }

    @Test
    fun `a polygon is written in fractions and fits any box`() {
        val triangle = Shapes.polygon(0.5f, 0f, 1f, 1f, 0f, 1f)
        assertEquals(listOf(100f, 0f, 200f, 50f, 0f, 50f), triangle.outline(200f, 50f).toList())
        assertTrue(triangle.contains(Offset(100f, 40f), Size(200f, 50f)))
        assertFalse(triangle.contains(Offset(10f, 5f), Size(200f, 50f)))
    }

    @Test
    fun `a polygon wound either way contains the same points`() {
        val clockwise = Shapes.polygon(0f, 0f, 1f, 0f, 1f, 1f)
        val anticlockwise = Shapes.polygon(0f, 0f, 1f, 1f, 1f, 0f)
        for (point in listOf(Offset(80f, 20f), Offset(20f, 80f), Offset(60f, 30f))) {
            assertEquals(clockwise.contains(point, square), anticlockwise.contains(point, square), "at $point")
        }
    }

    @Test
    fun `a concave polygon is refused by name`() {
        // An arrowhead: its notch turns back on itself.
        val failure = assertFailsWith<IllegalArgumentException> {
            Shapes.polygon(0f, 0f, 1f, 0.5f, 0f, 1f, 0.3f, 0.5f)
        }
        assertTrue("convex" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a star that turns the same way at every point is still refused`() {
        // A pentagram, drawn point to point: every corner turns right, but it goes round twice.
        val star = FloatArray(10)
        for (i in 0 until 5) {
            val angle = -PI / 2 + i * 4 * PI / 5
            star[i * 2] = (0.5 + 0.5 * cos(angle)).toFloat()
            star[i * 2 + 1] = (0.5 + 0.5 * sin(angle)).toFloat()
        }
        assertFailsWith<IllegalArgumentException> { Shapes.polygon(*star) }
    }

    @Test
    fun `a polygon that doubles back along its own edge is refused`() {
        assertFailsWith<IllegalArgumentException> { Shapes.polygon(0f, 0f, 1f, 0f, 0.5f, 0f, 0.5f, 1f) }
    }

    @Test
    fun `a polygon with a point in the middle of a straight edge is still convex`() {
        val square = Shapes.polygon(0f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertTrue(square.contains(Offset(50f, 50f), this.square))
    }

    @Test
    fun `fewer than three points is not a polygon`() {
        assertFailsWith<IllegalArgumentException> { Shapes.polygon(0f, 0f, 1f, 1f) }
    }

    @Test
    fun `two shapes made the same way are equal so a recomposition can skip`() {
        assertEquals(Shapes.polygon(0f, 0f, 1f, 0f, 0f, 1f), Shapes.polygon(0f, 0f, 1f, 0f, 0f, 1f))
        assertEquals(Shapes.roundedRect(6f), Shapes.roundedRect(6f))
        assertEquals(ClipElement(Shapes.Circle), ClipElement(Shapes.Circle))
    }

    @Test
    fun `a clip with a corner is a rounded rectangle and one without is the plain box`() {
        assertEquals(Shapes.roundedRect(6f), ClipElement(6f).shape)
        assertSame(Shapes.Rectangle, ClipElement(0f).shape)
        assertSame(Shapes.Rectangle, ClipElement().shape)
    }

    @Test
    fun `a rectangle rounded only along its top keeps its bottom corners square`() {
        val tab = Shapes.roundedRect(Corners.top(30f))
        assertFalse(tab.contains(Offset(2f, 2f), square), "the top-left is cut")
        assertFalse(tab.contains(Offset(98f, 2f), square), "and the top-right")
        assertTrue(tab.contains(Offset(1f, 99f), square), "the bottom-left is not")
        assertTrue(tab.contains(Offset(99f, 99f), square), "nor the bottom-right")
        val outline = tab.outline(100f, 100f)
        val corners = (outline.indices step 2).map { Offset(outline[it], outline[it + 1]) }
        assertTrue(Offset(0f, 100f) in corners && Offset(100f, 100f) in corners, "a square corner is its own point")
        assertFalse(Offset(0f, 0f) in corners, "a rounded one is not")
    }

    @Test
    fun `a clip written with corners is that rounded rectangle and says its corners back`() {
        assertEquals(Shapes.roundedRect(Corners.top(6f)), ClipElement(Corners.top(6f)).shape)
        assertEquals(Corners.top(6f), ClipElement(Corners.top(6f)).corners)
        assertEquals(Corners.all(6f), ClipElement(6f).corners)
        assertSame(Shapes.Rectangle, ClipElement(Corners.None).shape)
        assertEquals(Corners.None, ClipElement(Shapes.Circle).corners)
    }

    @Test
    fun `a negative corner is refused`() {
        assertFailsWith<IllegalArgumentException> { Shapes.roundedRect(-1f) }
    }
}
