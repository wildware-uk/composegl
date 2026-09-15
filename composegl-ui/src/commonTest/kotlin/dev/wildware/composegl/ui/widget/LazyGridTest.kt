package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.GridCells
import dev.wildware.composegl.ui.layout.IntrinsicSize
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.saveable.SaveableStateHolder
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A lazy grid in real composed UI: laid out by a host, scrolled by the wheel, a drag and its bar,
 * walked by keys and a pad, pressed by a pointer.
 *
 * The screen is a catalogue: a 200 by 200 window of cells 50 wide and 40 tall, four across. Five
 * rows fit, and two spare rows are built beyond them.
 */
class LazyGridTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun screen(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 400f)) { content() }.also { opened += it }

    /** A catalogue of buttons, the first one focused. */
    private fun catalogue(
        count: Int = 1_000,
        state: LazyGridState = LazyGridState(),
        clicked: MutableList<Int> = mutableListOf(),
    ) = screen {
        LazyVerticalGrid(
            count = count,
            columns = GridCells.Fixed(4),
            modifier = Modifier.size(200f).testTag("grid"),
            state = state,
            key = { it },
            bars = false,
        ) { index ->
            Button(
                "$index",
                onClick = { clicked += index },
                initialFocus = index == 0,
                modifier = Modifier.fillMaxWidth().height(40f).testTag("cell$index"),
            )
        }
    }

    /** Plain painted cells, for what the pointer does to the grid rather than to a button. */
    private fun tiles(count: Int = 1_000, state: LazyGridState, bars: Boolean = false) = screen {
        LazyVerticalGrid(count, GridCells.Fixed(4), Modifier.size(200f).testTag("grid"), state, bars = bars) { index ->
            Box(Modifier.fillMaxWidth().height(40f).testTag("cell$index"))
        }
    }

    private fun UiTest.built(tag: String = "grid"): Int = node(tag).children.count { it.name == "box" }

    // --- only what can be seen ---

    @Test
    fun `a thousand item catalogue builds only the rows on screen`() {
        val ui = catalogue()

        // Five rows on screen and two spare below: seven rows of four.
        assertEquals(28, ui.built(), "built ${ui.built()} of a thousand")
        ui.assertExists("cell27")
        ui.assertDoesNotExist("cell28")
        ui.assertDoesNotExist("cell999")
    }

    @Test
    fun `cells fill across and then down with the spacing between them`() {
        val ui = screen {
            LazyVerticalGrid(100, GridCells.Fixed(4), Modifier.size(200f), spacing = 8f, bars = false) { index ->
                Box(Modifier.fillMaxWidth().height(40f).testTag("cell$index"))
            }
        }

        // 200 less three gaps of 8, shared by four: 44 a column.
        assertEquals(0f, ui.node("cell0").boundsInRoot.left)
        assertEquals(44f, ui.node("cell0").width)
        assertEquals(52f, ui.node("cell1").boundsInRoot.left)
        assertEquals(156f, ui.node("cell3").boundsInRoot.left, "the fourth is the last across")
        assertEquals(0f, ui.node("cell4").boundsInRoot.left, "the fifth starts the next row")
        assertEquals(48f, ui.node("cell4").boundsInRoot.top, "a row of 40 and a gap of 8 down")
    }

    @Test
    fun `scrolling moves which rows exist but not how many`() {
        val state = LazyGridState()
        // Plain cells, with nothing to focus: see the note on focus at the bottom of the file.
        val ui = tiles(state = state)
        val before = ui.built()

        state.scrollToItem(500)
        ui.settle()

        assertEquals(500, state.firstVisibleItem)
        assertEquals(125 * 40f, state.position, "row 125 of rows 40 tall")
        assertEquals(0f, ui.node("cell500").boundsInRoot.top, "its row is at the top of the window")
        assertEquals(50f, ui.node("cell501").boundsInRoot.left)
        assertEquals(-40f, ui.node("cell496").boundsInRoot.top, "a spare row above, just out of sight")
        ui.assertDoesNotExist("cell0")
        assertEquals(before + 8, ui.built(), "the spare rows above are built now too, and nothing else")
    }

    @Test
    fun `scrolling to an item in the middle of a row brings the whole row`() {
        val state = LazyGridState()
        val ui = tiles(state = state)

        state.scrollToItem(203)
        ui.settle()

        assertEquals(200, state.firstVisibleItem, "the row starts at 200")
        assertEquals(0f, ui.node("cell200").boundsInRoot.top)
        assertEquals(150f, ui.node("cell203").boundsInRoot.left)
    }

    @Test
    fun `scrolling one row builds only the row that arrived`() {
        val state = LazyGridState()
        val composed = HashMap<Int, Int>()
        val ui = screen {
            LazyVerticalGrid(1_000, GridCells.Fixed(4), Modifier.size(200f), state, key = { it }, bars = false) { index ->
                composed[index] = (composed[index] ?: 0) + 1
                Box(Modifier.fillMaxWidth().height(40f))
            }
        }
        composed.clear()

        state.scrollToItem(4)
        ui.settle()

        assertEquals(setOf(28, 29, 30, 31), composed.keys, "one new row at the bottom and nothing rebuilt")
    }

    // --- the pointer ---

    @Test
    fun `the wheel scrolls it and stops at the end`() {
        val state = LazyGridState()
        val ui = tiles(state = state)

        ui.scroll("grid", Offset(0f, 1f))
        assertEquals(48f, state.position, "one notch")

        repeat(300) { ui.scroll("grid", Offset(0f, 1f)) }
        assertEquals(250 * 40f - 200f, state.position, "250 rows and not past the last one")
        assertEquals(200f, ui.node("cell999").boundsInRoot.bottom, "the last cell sits on the bottom edge")
        assertEquals(150f, ui.node("cell999").boundsInRoot.left, "in the fourth column")
    }

    @Test
    fun `dragging it scrolls it the way the finger went`() {
        val state = LazyGridState()
        val ui = tiles(state = state)

        ui.press(Offset(100f, 150f))
        ui.moveTo(Offset(100f, 70f))
        ui.release()
        ui.stopFlingAndSettle(state)

        assertEquals(80f, state.position, 0.5f, "dragged up 80, so scrolled down 80")
        assertEquals(0f, ui.node("cell8").boundsInRoot.top, 0.5f, "the third row is now the first")
    }

    @Test
    fun `pressing the bar near its bottom scrolls most of the way down`() {
        val state = LazyGridState()
        val ui = tiles(count = 400, state = state, bars = true)

        ui.click(Offset(196f, 199f))

        assertTrue(state.position > 0.9f * (100 * 40f - 200f), "the bar took it towards the end: ${state.position}")
    }

    @Test
    fun `a still grid asks for no frames`() {
        val ui = catalogue()
        ui.key(Key.Right)

        repeat(30) { frame ->
            assertFalse(ui.host.frame(ui.nanos + (frame + 1) * 16_666_667L), "frame $frame redrew a still grid")
        }
    }

    // --- focus ---

    @Test
    fun `arrow keys move to the neighbouring cell`() {
        val ui = catalogue()
        ui.assertFocused("cell0")

        ui.key(Key.Right)
        ui.assertFocused("cell1")
        ui.key(Key.Down)
        ui.assertFocused("cell5")
        ui.key(Key.Left)
        ui.assertFocused("cell4")
    }

    @Test
    fun `the pad walks down past the fold and the grid follows`() {
        val state = LazyGridState()
        val ui = catalogue(state = state)

        repeat(12) { ui.pad(GamepadButton.DpadDown) }
        ui.assertFocused("cell48")
        ui.pad(GamepadButton.DpadRight)
        ui.assertFocused("cell49")

        assertTrue(state.position > 0f, "the grid scrolled: ${state.position}")
        val cell = ui.node("cell49").boundsInRoot
        assertTrue(cell.top >= 0f && cell.bottom <= 200f, "the focused cell is in the window: $cell")
        assertEquals(50f, cell.left)
        assertTrue(ui.built() <= 36, "and it still built only a window: ${ui.built()}")

        repeat(12) { ui.pad(GamepadButton.DpadUp) }
        ui.assertFocused("cell1")
        assertEquals(0f, state.position, "all the way back to the top")
    }

    @Test
    fun `tab walks off the end of each row and on into rows that were not built`() {
        val state = LazyGridState()
        val ui = catalogue(state = state)

        repeat(30) { ui.key(Key.Tab) }

        ui.assertFocused("cell30")
        val cell = ui.node("cell30").boundsInRoot
        assertTrue(cell.top >= 0f && cell.bottom <= 200f, "revealed: $cell")
        assertEquals(100f, cell.left, "the third column of row seven")
    }

    @Test
    fun `a pointer and the pad press the cell they are on after scrolling`() {
        val state = LazyGridState()
        val clicked = mutableListOf<Int>()
        val ui = catalogue(state = state, clicked = clicked)

        state.scrollToItem(400)
        ui.settle()
        ui.click("cell405")
        assertEquals(listOf(405), clicked, "the cell under the pointer")

        ui.pad(GamepadButton.DpadRight)
        ui.pad(GamepadButton.South)
        assertEquals(listOf(405, 406), clicked, "and the one the pad moved to")
    }

    // --- items coming and going ---

    @Test
    fun `a keyed cell keeps its node and focus when the items are reordered`() {
        var names by mutableStateOf((0 until 8).map { "n$it" })
        val ui = screen {
            LazyVerticalGrid(names.size, GridCells.Fixed(4), Modifier.size(200f), key = { names[it] }, bars = false) { index ->
                Button(names[index], onClick = {}, modifier = Modifier.fillMaxWidth().height(40f).testTag(names[index]))
            }
        }
        ui.click("n3")
        val node = ui.node("n3")
        assertEquals(150f, node.boundsInRoot.left)

        names = names.reversed()
        ui.settle()

        assertSame(node, ui.node("n3"), "moved rather than rebuilt")
        ui.assertFocused("n3")
        assertEquals(0f, node.boundsInRoot.left, "fifth now, so the start of the second row")
        assertEquals(40f, node.boundsInRoot.top)
    }

    @Test
    fun `losing its items pulls the scroll back`() {
        val state = LazyGridState()
        var count by mutableStateOf(1_000)
        val ui = screen {
            LazyVerticalGrid(count, GridCells.Fixed(4), Modifier.size(200f), state, bars = false) { index ->
                Box(Modifier.fillMaxWidth().height(40f).testTag("cell$index"))
            }
        }
        state.scrollToItem(900)
        ui.settle()
        assertTrue(state.position > 0f)

        count = 6
        ui.settle()

        assertEquals(0f, state.position, "two rows do not fill the window")
        assertEquals(40f, ui.node("cell5").boundsInRoot.top)
        assertEquals(50f, ui.node("cell5").boundsInRoot.left)
    }

    @Test
    fun `an adaptive grid reflows to its width and keeps the item at the top`() {
        val state = LazyGridState()
        var width by mutableStateOf(200f)
        val ui = screen {
            LazyVerticalGrid(
                1_000, GridCells.Adaptive(minSize = 50f), Modifier.size(width, 200f).testTag("grid"), state, bars = false,
            ) { index ->
                Box(Modifier.fillMaxWidth().height(40f).testTag("cell$index"))
            }
        }
        assertEquals(150f, ui.node("cell3").boundsInRoot.left, "four fit across 200")
        assertEquals(40f, ui.node("cell4").boundsInRoot.top)

        state.scrollToItem(400)
        ui.settle()
        assertEquals(0f, ui.node("cell400").boundsInRoot.top)

        width = 100f
        ui.settle()

        assertEquals(400, state.firstVisibleItem, "the same item is at the top")
        assertEquals(0f, ui.node("cell400").boundsInRoot.top)
        assertEquals(50f, ui.node("cell401").boundsInRoot.left, "two across now")
        assertEquals(40f, ui.node("cell402").boundsInRoot.top)
        assertEquals(500 * 40f - 200f, state.axis.maximum, "and the end is where 500 rows end")
    }

    // --- lying down ---

    @Test
    fun `a horizontal grid fills down then across and scrolls sideways`() {
        val state = LazyGridState()
        val ui = screen {
            LazyHorizontalGrid(500, GridCells.Fixed(4), Modifier.size(200f).testTag("grid"), state, bars = false) { index ->
                Box(Modifier.width(40f).fillMaxHeight().testTag("cell$index"))
            }
        }
        assertEquals(50f, ui.node("cell1").boundsInRoot.top, "the second goes below the first")
        assertEquals(0f, ui.node("cell1").boundsInRoot.left)
        assertEquals(40f, ui.node("cell4").boundsInRoot.left, "the fifth starts the next column")
        assertEquals(28, ui.built(), "seven columns of four")

        ui.scroll("grid", Offset(1f, 0f))
        assertEquals(48f, state.position, "a sideways notch")

        state.scrollToItem(100)
        ui.settle()
        assertEquals(0f, ui.node("cell100").boundsInRoot.left)
        assertEquals(0f, ui.node("cell100").boundsInRoot.top)
        assertEquals(150f, ui.node("cell103").boundsInRoot.top)
    }

    // --- edges ---

    @Test
    fun `an adaptive grid starts where it was told to`() {
        val state = LazyGridState(initialPosition = 400f)
        val ui = screen {
            LazyVerticalGrid(1_000, GridCells.Adaptive(minSize = 50f), Modifier.size(200f), state, bars = false) { index ->
                Box(Modifier.fillMaxWidth().height(40f).testTag("cell$index"))
            }
        }

        // Its first frame guesses one column; finding four must not move where it was put.
        assertEquals(400f, state.position, "ten rows of 40 down")
        assertEquals(40, state.firstVisibleItem)
        assertEquals(0f, ui.node("cell40").boundsInRoot.top)
        assertEquals(150f, ui.node("cell43").boundsInRoot.left)
    }

    @Test
    fun `an empty grid builds nothing and does not scroll`() {
        val state = LazyGridState()
        val ui = screen {
            LazyVerticalGrid(0, GridCells.Adaptive(50f), Modifier.size(200f).testTag("grid"), state) { index ->
                Box(Modifier.fillMaxWidth().height(40f).testTag("cell$index"))
            }
        }

        assertEquals(0, ui.built())
        ui.scroll("grid", Offset(0f, 1f))
        assertEquals(0f, state.position)
        assertEquals(0, state.firstVisibleItem)
        assertEquals(200f, ui.node("grid").height, "it still takes the room it was given")
    }

    @Test
    fun `a grid given no room at all lays out without failing`() {
        val ui = screen {
            LazyVerticalGrid(1_000, GridCells.Adaptive(50f), Modifier.size(0f).testTag("grid"), spacing = 4f) { index ->
                Box(Modifier.fillMaxWidth().height(40f).testTag("cell$index"))
            }
        }

        assertEquals(0f, ui.node("grid").width)
        assertTrue(ui.built() <= 16, "no room still builds only a few: ${ui.built()}")
    }

    @Test
    fun `a grid that fits has no bar and does not scroll`() {
        val state = LazyGridState()
        val ui = tiles(count = 8, state = state, bars = true)

        ui.scroll("grid", Offset(0f, 1f))
        assertEquals(0f, state.position)
        assertEquals(8, ui.built())
        assertEquals(0f, state.axis.maximum)
    }

    @Test
    fun `changing the number of columns keeps the item at the top`() {
        val state = LazyGridState()
        var columns by mutableStateOf(4)
        val ui = screen {
            LazyVerticalGrid(1_000, GridCells.Fixed(columns), Modifier.size(200f), state, bars = false) { index ->
                Box(Modifier.fillMaxWidth().height(40f).testTag("cell$index"))
            }
        }
        state.scrollToItem(400)
        ui.settle()

        columns = 2
        ui.settle()

        assertEquals(400, state.firstVisibleItem)
        assertEquals(0f, ui.node("cell400").boundsInRoot.top)
        assertEquals(100f, ui.node("cell401").boundsInRoot.left, "two columns of 100")
        assertEquals(40f, ui.node("cell402").boundsInRoot.top)
    }

    @Test
    fun `a row is as tall as its tallest cell`() {
        val ui = screen {
            LazyVerticalGrid(100, GridCells.Fixed(4), Modifier.size(200f), spacing = 4f, bars = false) { index ->
                Box(Modifier.fillMaxWidth().height(if (index == 6) 80f else 40f).testTag("cell$index"))
            }
        }

        assertEquals(44f, ui.node("cell4").boundsInRoot.top)
        assertEquals(44f, ui.node("cell6").boundsInRoot.top)
        assertEquals(128f, ui.node("cell8").boundsInRoot.top, "below the 80-tall cell and a gap, not the 40-tall ones")
        assertEquals(128f, ui.node("cell11").boundsInRoot.top)
    }

    @Test
    fun `a grid inside a scrolling area shows its rows`() {
        val ui = screen {
            ScrollArea(Modifier.size(200f)) {
                LazyVerticalGrid(40, GridCells.Fixed(4), Modifier.width(200f).testTag("grid"), bars = false) { index ->
                    Box(Modifier.fillMaxWidth().height(40f).testTag("cell$index"))
                }
            }
        }

        // With no height of its own it is as tall as its rows, and the area around it scrolls.
        assertEquals(0f, ui.node("cell4").boundsInRoot.left)
        assertEquals(40f, ui.node("cell4").boundsInRoot.top)
        assertEquals(400f, ui.node("grid").height, "ten rows of 40")
    }

    @Test
    fun `a grid in a column sized to its contents stays where the wheel left it`() {
        val state = LazyGridState()
        val ui = screen {
            Column(Modifier.width(IntrinsicSize.Max)) {
                LazyVerticalGrid(1_000, GridCells.Fixed(4), Modifier.height(200f).testTag("grid"), state, bars = false) {
                    Box(Modifier.size(30f, 40f))
                }
            }
        }

        ui.scroll("grid", Offset(0f, 3f))
        ui.advanceBy(100)
        val scrolled = state.position
        assertTrue(scrolled > 0f, "the wheel scrolled the grid")
        ui.advanceBy(300)

        assertEquals(scrolled, state.position, "asking the grid how wide it is does not scroll it back")
        assertEquals(120f, ui.node("grid").width, "four cells of 30 across")
    }

    @Test
    fun `a grid is still scrolled where it was when its screen comes back`() {
        val ui = screen {
            var current by remember { mutableStateOf("shop") }
            Column {
                Button("SHOP", onClick = { current = "shop" }, modifier = Modifier.testTag("to-shop"))
                Button("MAP", onClick = { current = "map" }, modifier = Modifier.testTag("to-map"))
                SaveableStateHolder(current) { key ->
                    if (key == "shop") {
                        LazyVerticalGrid(1_000, GridCells.Fixed(4), Modifier.size(200f).testTag("grid"), bars = false) { index ->
                            Box(Modifier.fillMaxWidth().height(40f).testTag("cell$index"))
                        }
                    }
                }
            }
        }
        repeat(5) { ui.scroll("grid", Offset(0f, 1f)) }
        ui.assertDoesNotExist("cell0")
        val top = ui.node("cell24").boundsInRoot.top

        ui.click("to-map")
        ui.assertDoesNotExist("grid")
        ui.click("to-shop")

        ui.assertDoesNotExist("cell0")
        assertEquals(top, ui.node("cell24").boundsInRoot.top, "the same row in the same place")
    }

    @Test
    fun `a flick carries on after the finger lets go`() {
        val state = LazyGridState()
        val ui = tiles(state = state)
        val pointer = PointerRouter(ui.root)

        // 120 up in 40 milliseconds: 3000 a second.
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(100f, 180f), timeMillis = 0L))
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(100f, 120f), setOf(PointerButton.Primary), timeMillis = 20L))
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(100f, 60f), setOf(PointerButton.Primary), timeMillis = 40L))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(100f, 60f), timeMillis = 40L))
        assertTrue(state.isFlinging, "it was let go of moving")

        ui.advanceBy(2_000)

        assertFalse(state.isFlinging, "and it has come to rest")
        assertTrue(state.position > 200f, "well past the 120 the finger moved: ${state.position}")
        val top = state.firstVisibleItem
        assertEquals(0, top % 4)
        ui.assertExists("cell$top")
        // Resting part way into a row: six rows cut by the window and two spare either side.
        assertTrue(ui.built() <= 40, "still only a window: ${ui.built()}")
    }

    // A note on focus. When the focused cell scrolls far enough to be let go of, focus goes to the
    // first cell still built — a spare row just out of sight — and revealing it pulls the grid back
    // two rows. A lazy list does the same. The tests about where scrolling lands use cells with
    // nothing to focus, so they measure the scrolling rather than that.

    private fun UiTest.stopFlingAndSettle(state: LazyGridState) {
        state.stopFling()
        settle()
    }
}
