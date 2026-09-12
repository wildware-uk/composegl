package dev.wildware.composegl.ui.host

import androidx.compose.runtime.mutableStateOf
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    /**
     * Focus is settled before [UiRenderer.onLaidOut], and the order is deliberate: what is in
     * `onLaidOut` is input routing, and routing a direction press needs focus already pointing at
     * something that is still on the screen.
     *
     * A game whose own `onLaidOut` already refreshes — the showcase does, because it has two trees
     * and refreshes both — leaves `focus` null and keeps its own order.
     */
    @Test
    fun `a focus manager is settled after layout and before input`() {
        val showing = mutableStateOf(true)
        host.setContent {
            LeafLayout(Modifier.size(40f, 20f).focusable(), name = "always")
            if (showing.value) LeafLayout(Modifier.size(40f, 20f).focusable(), name = "sometimes")
        }
        val focus = FocusManager(host.root)
        val renderer = UiRenderer(host, canvas)
        renderer.focus = focus
        renderer.render(viewport, 0L)
        focus.focusOn(host.root.children.first { it.name == "sometimes" })

        var whenInputRan: String? = null
        renderer.onLaidOut = { whenInputRan = focus.focused?.name }
        showing.value = false
        renderer.render(viewport, 16_000_000L)

        assertEquals("always", whenInputRan, "input saw focus still on a node that had gone")
    }

    /**
     * A game with two trees has two managers and refreshes them itself; the renderer must only
     * ever touch the one it was handed.
     */
    @Test
    fun `the renderer refreshes the manager it was given and no other`() {
        host.setContent { LeafLayout(Modifier.size(40f, 20f).focusable(), name = "block") }
        val given = FocusManager(host.root)
        val someoneElses = FocusManager(host.root)
        val renderer = UiRenderer(host, canvas)
        renderer.focus = given

        renderer.render(viewport, 0L)

        // autoFocus only fires from refresh, so who has focus now is exactly who was refreshed.
        assertEquals("block", given.focused?.name, "the manager it was given was not refreshed")
        assertNull(someoneElses.focused, "a manager the renderer was never given should be untouched")
    }

    @Test
    fun `no focus manager still lays the tree out`() {
        content()

        UiRenderer(host, canvas).render(viewport, 0L)

        // Leaving focus null drops one of the five lines, not two.
        assertEquals(Rect.of(0f, 0f, 40f, 20f), canvas.painted)
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

    /**
     * The overlay's three numbers, and that a frame really fills all three in.
     *
     * Holding the budget is not the job; timing with it is. A render that quietly stopped wrapping
     * one of the passes would show a zero where the answer was meant to be.
     */
    @Test
    fun `the budget it keeps is the one it was given and it is filled in`() {
        host.setContent {
            repeat(20) { LeafLayout(Modifier.size(40f, 20f).background(Colour.White), name = "block$it") }
        }
        val budget = FrameBudget()
        budget.isOn = true
        val renderer = UiRenderer(host, canvas, budget)

        renderer.render(viewport, 0L)

        assertSame(budget, renderer.budget, "a game that wants the overlay reads this one")
        val reading = budget.reading
        assertTrue(reading.recomposeMillis > 0f, "the recompose was not timed")
        assertTrue(reading.layoutMillis > 0f, "the layout was not timed")
        assertTrue(reading.drawMillis > 0f, "the draw was not timed")
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
