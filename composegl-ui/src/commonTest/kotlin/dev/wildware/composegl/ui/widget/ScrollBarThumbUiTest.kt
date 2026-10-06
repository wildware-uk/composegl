package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A scrollbar keeps its thumb's rectangle between frames, so a still bar draws without making one
 * (#258). These pin the other half of that: the moment the thumb moves, grows, shrinks or its bar is
 * laid out somewhere else, the thumb is drawn where it now is and not where it was kept.
 */
class ScrollBarThumbUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    /** The thumb as drawn: the last rectangle on the canvas, since the track goes down first. */
    private fun UiTest.thumb(): Rect {
        render()
        val drawn = (backend.canvas as RecordingCanvas).calls.filterIsInstance<DrawCall.Rectangle>()
        return drawn.last().rect
    }

    @Test
    fun `a vertical thumb follows a drag after standing still`() {
        val state = ScrollState()
        val ui = uiTest(Size(600f, 400f)) {
            ScrollArea(Modifier.size(200f), state) {
                Column(Modifier.fillMaxWidth()) { repeat(10) { Box(Modifier.fillMaxWidth().height(100f)) } }
            }
        }.also { opened += it }

        // Still for a few frames first, so the thumb drawn after the drag is one the bar had kept.
        val still = ui.thumb()
        assertEquals(still, ui.thumb(), "a still thumb is drawn where it was")
        assertEquals(0f, still.top)
        assertEquals(40f, still.height, "a fifth of the list is a fifth of the bar")

        ui.press(Offset(100f, 180f))
        ui.dragTo(Offset(100f, 120f))
        ui.release()

        assertEquals(60f, state.y, "the area scrolled with the finger")
        // A 40 thumb on a 200 bar has 160 to travel for 800 of scroll.
        val moved = ui.thumb()
        assertEquals(60f / 800f * 160f, moved.top, "the thumb followed the drag")
        assertEquals(40f, moved.height, "and is as long as it was")
    }

    @Test
    fun `a sideways thumb follows a drag after standing still`() {
        val state = ScrollState()
        val ui = uiTest(Size(600f, 400f)) {
            ScrollArea(Modifier.size(200f), state, horizontal = true, vertical = false) {
                Row { repeat(10) { Box(Modifier.size(100f, 50f)) } }
            }
        }.also { opened += it }

        val still = ui.thumb()
        assertEquals(still, ui.thumb(), "a still thumb is drawn where it was")
        assertEquals(0f, still.left)
        assertEquals(40f, still.width, "a fifth of the row is a fifth of the bar")

        ui.press(Offset(180f, 25f))
        ui.dragTo(Offset(100f, 25f))
        ui.release()

        assertEquals(80f, state.x, "the area scrolled with the finger")
        val moved = ui.thumb()
        assertEquals(80f / 800f * 160f, moved.left, "the thumb followed the drag")
        assertEquals(40f, moved.width, "and is as long as it was")
    }

    @Test
    fun `a thumb follows its bar when the area grows or moves`() {
        val state = ScrollState()
        var side by mutableFloatStateOf(200f)
        var inset by mutableFloatStateOf(0f)
        val ui = uiTest(Size(600f, 600f)) {
            Box(Modifier.padding(left = inset)) {
                ScrollArea(Modifier.size(side), state) {
                    Column(Modifier.width(side)) { repeat(10) { Box(Modifier.fillMaxWidth().height(100f)) } }
                }
            }
        }.also { opened += it }
        state.scrollTo(y = 120f)
        ui.settle()
        val before = ui.thumb()
        assertEquals(120f / 800f * 160f, before.top)

        side = 400f
        ui.settle()
        val grown = ui.thumb()
        // Four hundred of a thousand on a 400 bar is a 160 thumb with 240 to travel for 600 of scroll.
        assertEquals(160f, grown.height, "the thumb grew with the window")
        assertEquals(120f / 600f * 240f, grown.top, "and sits where the scroll now puts it")
        assertEquals(400f, grown.right, "on the bar at the edge of the bigger area")

        inset = 50f
        ui.settle()
        val moved = ui.thumb()
        assertEquals(grown.top, moved.top, "moving the area does not move the thumb along its bar")
        assertEquals(grown.height, moved.height)
        assertEquals(450f, moved.right, "the thumb went with its bar")
        assertEquals(grown.width, moved.width)
    }

    @Test
    fun `a vertical thumb at the top shrinks when the list gains rows`() {
        var rows by mutableIntStateOf(5)
        val ui = uiTest(Size(600f, 400f)) {
            ScrollArea(Modifier.size(200f)) {
                Column(Modifier.fillMaxWidth()) { repeat(rows) { Box(Modifier.fillMaxWidth().height(100f)) } }
            }
        }.also { opened += it }
        val before = ui.thumb()
        assertEquals(before, ui.thumb(), "a still thumb is drawn where it was")
        assertEquals(0f, before.top)
        assertEquals(80f, before.height, "two hundred of five hundred is two fifths of the bar")

        // Only the far end moves: the top stays at 0, which a thumb kept by its start alone would miss.
        rows = 10
        ui.settle()
        val after = ui.thumb()
        assertEquals(0f, after.top, "still at the top of the list")
        assertEquals(40f, after.height, "two hundred of a thousand is a fifth of the bar")
    }

    @Test
    fun `a sideways thumb at the start shrinks when the row gains items`() {
        var items by mutableIntStateOf(5)
        val ui = uiTest(Size(600f, 400f)) {
            ScrollArea(Modifier.size(200f), horizontal = true, vertical = false) {
                Row { repeat(items) { Box(Modifier.size(100f, 50f)) } }
            }
        }.also { opened += it }
        val before = ui.thumb()
        assertEquals(before, ui.thumb(), "a still thumb is drawn where it was")
        assertEquals(0f, before.left)
        assertEquals(80f, before.width, "two hundred of five hundred is two fifths of the bar")

        items = 10
        ui.settle()
        val after = ui.thumb()
        assertEquals(0f, after.left, "still at the start of the row")
        assertEquals(40f, after.width, "two hundred of a thousand is a fifth of the bar")
    }
}
