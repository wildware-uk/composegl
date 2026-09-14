package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.text.TextLayout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shapes every backend gets for nothing, checked against the one primitive they are built on.
 *
 * None of these is asked of the backend: they are default bodies that walk geometry onto [
 * UiCanvas.fan], so a canvas written before they existed draws them correctly without being
 * touched. That is the property worth pinning - if any of them stopped being a default body, the
 * stand-in below would stop compiling.
 */
class UiCanvasShapesTest {

    @Test
    fun `a circle is a closed ring of points all one radius from the centre`() {
        val canvas = Recorder()

        canvas.circle(Offset(10f, 20f), radius = 5f, colour = Colour.White)

        val fan = canvas.fans.single()
        assertEquals(Offset(10f, 20f), fan.hub, "the hub is the centre, which is what a fan wants")
        assertTrue(fan.rim.size >= 8, "a circle is walked at a sensible smoothness, got ${fan.rim.size}")
        fan.rim.forEach {
            val r = hypot(it.x - 10f, it.y - 20f)
            assertTrue(abs(r - 5f) < 0.01f, "every rim point sits on the radius, got $r")
        }
    }

    @Test
    fun `a wedge spans exactly the angle it was asked for and no more`() {
        val canvas = Recorder()

        // A quarter turn clockwise from three o'clock. y grows downwards, so that ends at 6 o'clock.
        canvas.arc(Offset(0f, 0f), radius = 10f, startDegrees = 0f, sweepDegrees = 90f, colour = Colour.White)

        val fan = canvas.fans.single()
        assertEquals(Offset(0f, 0f), fan.hub, "the point of the wedge is the hub")
        val first = fan.rim.first()
        val last = fan.rim.last()
        assertTrue(abs(first.x - 10f) < 0.01f && abs(first.y) < 0.01f, "starts at three o'clock, got $first")
        assertTrue(abs(last.x) < 0.01f && abs(last.y - 10f) < 0.01f, "ends a quarter turn on, got $last")
    }

    @Test
    fun `a full turn either way is a whole circle rather than nothing`() {
        val clockwise = Recorder().also {
            it.circle(Offset.Zero, radius = 4f, colour = Colour.White, segments = 12)
        }
        val widdershins = Recorder().also {
            it.arc(Offset.Zero, 4f, startDegrees = 0f, sweepDegrees = -360f, colour = Colour.White, segments = 12)
        }

        assertEquals(12 + 1, clockwise.fans.single().rim.size, "twelve segments means thirteen rim points")
        assertEquals(12 + 1, widdershins.fans.single().rim.size, "and the same the other way round")
    }

    @Test
    fun `a line is a quad of the width asked for - square to its own direction`() {
        val canvas = Recorder()

        canvas.line(Offset(0f, 0f), Offset(10f, 0f), width = 4f, colour = Colour.White)

        val points = canvas.fans.single().all
        assertEquals(4, points.size, "a line is four corners")
        // Horizontal line, so the two edges are at y = -2 and y = +2.
        assertEquals(setOf(-2f, 2f), points.map { it.y }.toSet(), "half a width each side of the centreline")
        assertEquals(setOf(0f, 10f), points.map { it.x }.toSet(), "and it runs end to end")
    }

    @Test
    fun `nothing is drawn for a shape that cannot be seen`() {
        val canvas = Recorder()

        canvas.circle(Offset.Zero, radius = 0f, colour = Colour.White)
        canvas.arc(Offset.Zero, 10f, startDegrees = 0f, sweepDegrees = 0f, colour = Colour.White)
        canvas.line(Offset.Zero, Offset(10f, 0f), width = 0f, colour = Colour.White)
        canvas.line(Offset.Zero, Offset.Zero, width = 4f, colour = Colour.White)

        assertEquals(0, canvas.fans.size, "a radius, a sweep, a width and a length of nothing all draw nothing")
    }

    private class Fan(val all: List<Offset>) {
        val hub get() = all.first()
        val rim get() = all.drop(1)
    }

    /** Only what [UiCanvas] demanded before any of these shapes existed. */
    private class Recorder : UiCanvas {

        val fans = mutableListOf<Fan>()

        override fun fan(points: FloatArray, colour: Colour) {
            if (points.size < 6) return
            fans += Fan((points.indices step 2).map { Offset(points[it], points[it + 1]) })
        }

        override fun rect(rect: Rect, colour: Colour, corner: Float) = Unit
        override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) = Unit
        override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) = Unit
        override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) = Unit
        override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) = Unit
        override fun pushClip(rect: Rect) = Unit
        override fun popClip() = Unit
        override fun pushAlpha(alpha: Float) = Unit
        override fun popAlpha() = Unit
        override fun raw(block: (Any) -> Unit) = Unit
    }
}
