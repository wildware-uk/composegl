package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.RecomposeCounter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A scroll step moves the contents by laying the scroll area out again, and composes nothing.
 *
 * The offset used to be read while composing, only so the area's layout would run again; the price
 * was the area and its two bars recomposed on every frame of a drag or a fling (#250). These pin
 * that the contents and the thumb still follow every step, and that the recomposer is not woken
 * for one.
 */
class ScrollStepUiTest {

    private val opened = mutableListOf<UiTest>()
    private val counters = mutableListOf<RecomposeCounter>()

    @AfterTest
    fun tearDown() {
        counters.forEach { it.stop() }
        opened.forEach { it.close() }
    }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(600f, 400f)) { content() }.also { opened += it }

    /** Counts every recompose scope [this] screen runs from now on, whether or not it changes a node. */
    private fun UiTest.countRecompositions(): RecomposeCounter = host.countRecompositions().also { counters += it }

    /** Ten rows of 100: a thousand tall in a 200 window, so 800 to scroll. */
    @Composable
    private fun Tall(tag: String = "content") = Column(Modifier.fillMaxWidth().testTag(tag)) {
        repeat(10) { Box(Modifier.fillMaxWidth().height(100f)) }
    }

    private fun UiTest.contentTop(tag: String = "content") = node(tag).boundsInRoot.top

    /** Where the vertical bar's thumb starts, read back off the canvas: the last rectangle drawn. */
    private fun UiTest.thumbTop(): Float {
        render()
        val drawn = (backend.canvas as RecordingCanvas).calls.filterIsInstance<DrawCall.Rectangle>()
        return drawn.last().rect.top
    }

    @Test
    fun `a drag moves the contents and the thumb without composing anything`() {
        val state = ScrollState()
        val ui = open { ScrollArea(Modifier.size(200f).testTag("area"), state) { Tall() } }
        val composed = ui.countRecompositions()
        val changed = ui.host.changedFrames

        ui.press(Offset(100f, 180f))
        var scrolled = 0f
        for (y in listOf(150f, 120f, 90f, 60f)) {
            ui.dragTo(Offset(100f, y))
            scrolled += 30f
            assertEquals(scrolled, state.y, "the area scrolled with the finger")
            assertEquals(-scrolled, ui.contentTop(), "the contents followed the finger:\n" + ui.dump())
            // A 40 thumb on a 200 bar has 160 to travel for 800 of scroll.
            assertEquals(scrolled / 800f * 160f, ui.thumbTop(), "the thumb followed the scroll")
        }
        ui.release()

        assertEquals(0, composed.scopes, "a drag recomposed something")
        assertTrue(ui.host.changedFrames >= changed + 4, "every step was a changed frame, so a game draws it")
    }

    @Test
    fun `a fling plays out without composing anything`() {
        val state = ScrollState()
        val ui = open { ScrollArea(Modifier.size(200f).testTag("area"), state) { Tall() } }
        val composed = ui.countRecompositions()

        ui.flick(Offset(100f, 180f), Offset(100f, 60f))

        assertTrue(state.y > 120f, "the flicked area carried on past the finger: ${state.y}")
        assertEquals(-state.y, ui.contentTop(), "the contents are where the fling left the area")
        assertEquals(0, composed.scopes, "a fling recomposed something")
    }

    @Test
    fun `the wheel scrolls without composing anything`() {
        val state = ScrollState()
        val ui = open { ScrollArea(Modifier.size(200f).testTag("area"), state) { Tall() } }
        val composed = ui.countRecompositions()

        ui.scroll("area", Offset(0f, 2f))

        assertEquals(96f, state.y, "two notches of 48")
        assertEquals(-96f, ui.contentTop())
        assertEquals(0, composed.scopes, "the wheel recomposed something")
    }

    @Test
    fun `scrolling from outside moves the contents on the next frame without composing anything`() {
        val state = ScrollState()
        val ui = open { ScrollArea(Modifier.size(200f).testTag("area"), state) { Tall() } }
        val composed = ui.countRecompositions()

        state.scrollTo(y = 300f)
        ui.settle()
        assertEquals(-300f, ui.contentTop(), "a jump from game code is laid out")

        state.scrollBy(dy = -100f)
        ui.settle()
        assertEquals(-200f, ui.contentTop(), "and so is a step")
        assertEquals(0, composed.scopes, "scrolling from outside recomposed something")
    }

    @Test
    fun `a state is let go of by the area that leaves and taken by the one handed it`() {
        val first = ScrollState()
        val second = ScrollState()
        var current by mutableStateOf(first)
        var shown by mutableStateOf(true)
        val ui = open {
            if (shown) ScrollArea(Modifier.size(200f).testTag("area"), current) { Tall() }
        }
        val area = ui.node("area")
        assertEquals(listOf(area), first.nodes, "the area's own node is what a step marks")

        current = second
        ui.settle()
        assertEquals(emptyList(), first.nodes, "the state the area was given before is let go of")
        assertEquals(listOf(area), second.nodes, "the new one is taken")

        second.scrollTo(y = 250f)
        ui.settle()
        assertEquals(-250f, ui.contentTop(), "and steps on the new state move the contents")

        shown = false
        ui.settle()
        assertEquals(emptyList(), second.nodes, "an area that leaves keeps nothing of the screen alive through its state")
    }

    @Test
    fun `an area still follows its state after another area showing the same state leaves`() {
        val state = ScrollState()
        var second by mutableStateOf(true)
        val ui = open {
            Row {
                ScrollArea(Modifier.size(200f).testTag("first"), state, bars = false) { Tall("first.content") }
                if (second) ScrollArea(Modifier.size(200f).testTag("second"), state, bars = false) { Tall("second.content") }
            }
        }
        ui.scroll("first", Offset(0f, 1f))
        assertEquals(-48f, ui.contentTop("first.content"))
        assertEquals(-48f, ui.contentTop("second.content"), "both areas showing the state follow it")

        second = false
        ui.settle()
        ui.scroll("first", Offset(0f, 2f))
        assertEquals(144f, state.y)
        assertEquals(-144f, ui.contentTop("first.content"), "the area left behind still follows the wheel")

        ui.press(Offset(100f, 180f))
        ui.dragTo(Offset(100f, 150f))
        ui.release()
        assertEquals(174f, state.y)
        assertEquals(-174f, ui.contentTop("first.content"), "and a drag")
    }

    @Test
    fun `one state shown on two screens moves both`() {
        val state = ScrollState()
        val one = open { ScrollArea(Modifier.size(200f).testTag("area"), state) { Tall() } }
        val two = open { ScrollArea(Modifier.size(200f).testTag("area"), state) { Tall() } }

        one.scroll("area", Offset(0f, 2f))
        two.settle()
        assertEquals(-96f, one.contentTop(), "the screen scrolled follows")
        assertEquals(-96f, two.contentTop(), "and so does the other screen showing the same state")

        two.scroll("area", Offset(0f, -1f))
        one.settle()
        assertEquals(-48f, two.contentTop())
        assertEquals(-48f, one.contentTop(), "whichever screen is scrolled")
    }

    @Test
    fun `the counter sees a recomposition that changes no node`() {
        var tick by mutableStateOf(0)
        val ui = open {
            ScrollArea(Modifier.size(200f).testTag("area"), rememberScrollState()) { Tall() }
            // Reads the number while composing and emits the same nothing whatever it is.
            Box { if (tick < 0) Box {} }
        }
        val composed = ui.countRecompositions()

        tick++
        ui.settle()

        assertTrue(composed.scopes > 0, "a recomposition that changed no node went uncounted")
    }
}
