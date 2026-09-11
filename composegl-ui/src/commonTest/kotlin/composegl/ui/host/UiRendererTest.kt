package composegl.ui.host

import composegl.ui.geometry.Rect
import composegl.ui.geometry.Size
import composegl.ui.graphics.Colour
import composegl.ui.debug.FrameBudget
import composegl.ui.graphics.RecordingCanvas
import composegl.ui.graphics.UiCanvas
import composegl.ui.layout.LeafLayout
import composegl.ui.layout.Viewport
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.background
import composegl.ui.modifier.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The one-call frame: that it really is the five lines, in the right order.
 *
 * What can go wrong here is ordering, not arithmetic. Drawing before layout puts everything at
 * the origin; opening the canvas after drawing loses the frame; telling input that positions
 * exist before they do makes a click land on whatever was there last frame. None of those are
 * visible in a screenshot of a still screen, which is why they are asserted rather than looked at.
 */
class UiRendererTest {

    private val host = UiHost()
    private val canvas = CountingCanvas()
    private val viewport = Viewport.oneToOne(Size(200f, 100f))

    private fun content() = host.setContent {
        LeafLayout(Modifier.size(40f, 20f).background(Colour.White), name = "block")
    }

    @Test
    fun `one call lays out then draws inside the frame it opens`() {
        content()
        val renderer = UiRenderer(host, canvas)

        assertTrue(renderer.render(viewport, 0L), "the first frame is a change")

        assertEquals(listOf("begin", "rect", "end"), canvas.order)
        assertEquals(Rect.of(0f, 0f, 40f, 20f), canvas.painted, "laid out before it was drawn")
    }

    @Test
    fun `a still screen reports no change`() {
        content()
        val renderer = UiRenderer(host, canvas)
        renderer.render(viewport, 0L)
        canvas.order.clear()

        assertFalse(renderer.render(viewport, 16_000_000L), "nothing changed, so nothing changed")
        // Still drawn: a game that is not double-buffering its own world redraws anyway, and the
        // renderer is not the thing that gets to decide.
        assertEquals(listOf("begin", "rect", "end"), canvas.order)
    }

    @Test
    fun `input is told about a frame after layout and before drawing`() {
        content()
        val renderer = UiRenderer(host, canvas)
        val seen = mutableListOf<Long>()
        renderer.onLaidOut = { millis ->
            seen += millis
            canvas.order += "input"
        }

        renderer.render(viewport, 32_000_000L)

        assertEquals(listOf(32L), seen, "the frame's own time, in milliseconds")
        assertEquals(listOf("input", "begin", "rect", "end"), canvas.order)
    }

    @Test
    fun `a game's own world is drawn inside the frame and under the interface`() {
        content()
        val renderer = UiRenderer(host, canvas)
        var given: UiCanvas? = null
        renderer.drawBehind = { behind ->
            given = behind
            canvas.order += "board"
        }

        renderer.render(viewport, 0L)

        assertEquals(listOf("begin", "board", "rect", "end"), canvas.order)
        assertSame(canvas, given, "the same canvas the interface is drawn into, not another")
    }

    @Test
    fun `nothing behind is the default`() {
        content()

        UiRenderer(host, canvas).render(viewport, 0L)

        assertEquals(listOf("begin", "rect", "end"), canvas.order)
    }

    @Test
    fun `the budget it keeps is the one it was given`() {
        content()
        val budget = FrameBudget()
        budget.isOn = true
        val renderer = UiRenderer(host, canvas, budget)

        renderer.render(viewport, 0L)

        assertSame(budget, renderer.budget, "a game that wants the overlay reads this one")
    }

    /** A canvas that records the order it was called in, and the one rectangle it was asked for. */
    private class CountingCanvas(
        private val inner: RecordingCanvas = RecordingCanvas(),
    ) : UiCanvas by inner {

        val order = mutableListOf<String>()
        var painted: Rect? = null

        override fun begin(viewport: Viewport) {
            order += "begin"
        }

        override fun end() {
            order += "end"
        }

        override fun rect(rect: Rect, colour: Colour, corner: Float) {
            order += "rect"
            painted = rect
            inner.rect(rect, colour, corner)
        }
    }
}
