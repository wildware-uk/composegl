package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.GridCells
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The widgets that scroll or slide sideways, in a right-to-left screen, driven with a real mouse,
 * wheel and pad: each starts on the right and moves the way the screen reads.
 *
 * Every widget under test is the screen's first child, so it sits at the top-left and its numbers
 * are measured from zero.
 */
class MirroredScrollingUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(direction: LayoutDirection = LayoutDirection.Rtl, content: @Composable () -> Unit): UiTest =
        uiTest(Size(600f, 400f)) { ProvideLayoutDirection(direction, content) }.also { opened += it }

    private fun UiTest.left(tag: String) = node(tag).boundsInRoot.left

    /** Where the up-and-down bar of the scrolling widget tagged [tag] was put on the screen. */
    private fun UiTest.verticalBar(tag: String) =
        checkNotNull(named(node(tag), "scroll.bar.y")) { "nothing under $tag draws a vertical bar:\n" + dump() }
            .boundsInRoot

    /** The nearest node called [name], so that an outer scroll area finds its own bar and not an inner one. */
    private fun named(from: UiNode, name: String): UiNode? {
        var level = listOf(from)
        while (level.isNotEmpty()) {
            level.firstOrNull { it.name == name }?.let { return it }
            level = level.flatMap { it.children }
        }
        return null
    }

    @Composable
    private fun Square(tag: String) = Box(Modifier.size(50f).testTag(tag))

    /** A line of a page: narrower than the window, so where it sits can be seen. */
    @Composable
    private fun Line(tag: String) = Box(Modifier.size(200f, 50f).testTag(tag))

    @Test
    fun `a lazy row starts on the right and a drag to the right scrolls it on`() {
        val state = LazyListState()
        val ui = open {
            LazyRow(20, Modifier.width(300f).height(50f).testTag("row"), state, bars = false) { Square("item$it") }
        }

        assertEquals(250f, ui.left("item0"), "the first item is against the right")
        assertEquals(200f, ui.left("item1"))

        ui.press(Offset(100f, 25f))
        ui.dragTo(Offset(200f, 25f))
        ui.release()
        ui.settle()

        assertEquals(100f, state.position, 0.5f, "dragged right is scrolled on, towards the items on the left")
        assertEquals(250f, ui.left("item2"), 0.5f, "and the third item is now where the first was")
    }

    @Test
    fun `the same lazy row left to right drags the other way`() {
        val state = LazyListState()
        val ui = open(LayoutDirection.Ltr) {
            LazyRow(20, Modifier.width(300f).height(50f), state, bars = false) { Square("item$it") }
        }

        assertEquals(0f, ui.left("item0"))
        ui.press(Offset(200f, 25f))
        ui.dragTo(Offset(100f, 25f))
        ui.release()
        ui.settle()

        assertEquals(100f, state.position, 0.5f)
        assertEquals(0f, ui.left("item2"), 0.5f)
    }

    @Test
    fun `the wheel moves a mirrored lazy row the way its contents are drawn`() {
        val state = LazyListState()
        val ui = open {
            LazyRow(20, Modifier.width(300f).height(50f).testTag("row"), state, bars = false) { Square("item$it") }
        }
        state.scrollToItem(4)
        ui.settle()

        // Positive is "contents left", and in a mirrored row the contents moving left is going back.
        ui.scroll("row", Offset(1f, 0f))
        ui.settle()

        assertEquals(200f - 48f, state.position, 0.5f)
    }

    @Test
    fun `the pad walks a mirrored lazy row leftwards and keeps what it lands on in view`() {
        val state = LazyListState()
        val ui = open {
            LazyRow(20, Modifier.width(300f).height(50f), state, bars = false) { index ->
                Button("$index", {}, Modifier.size(50f).testTag("item$index"), initialFocus = index == 0)
            }
        }
        ui.assertFocused("item0")

        repeat(8) { ui.pad(GamepadButton.DpadLeft) }

        ui.assertFocused("item8")
        val bounds = ui.node("item8").boundsInRoot
        assertTrue(bounds.left >= 0f && bounds.right <= 300f, "the eighth item was scrolled into view: $bounds")
        assertEquals(0f, bounds.left, 0.5f, "against the far edge, the smallest move that shows it")
    }

    @Test
    fun `a sideways scroll area shows its right end first and a drag right scrolls on`() {
        val state = ScrollState()
        val ui = open {
            ScrollArea(Modifier.size(300f, 60f), state, horizontal = true, vertical = false, bars = false) {
                Row { repeat(18) { Square("cell$it") } }
            }
        }

        assertEquals(250f, ui.left("cell0"), "the row's first cell is at the right of the window")
        assertEquals(600f, state.maxX)

        ui.press(Offset(100f, 25f))
        ui.dragTo(Offset(220f, 25f))
        ui.release()
        ui.settle()

        assertEquals(120f, state.x, 0.5f)
        assertEquals(370f, ui.left("cell0"), 0.5f, "the contents followed the mouse to the right")
    }

    @Test
    fun `a mirrored sideways scrollbar starts on the right and a press at its left end scrolls to the end`() {
        val state = ScrollState()
        val ui = open {
            ScrollArea(Modifier.size(300f, 60f), state, horizontal = true, vertical = false) {
                Row { repeat(18) { Square("cell$it") } }
            }
        }

        // The bar runs along the bottom, 8 thick. Its thumb is a third of it, 200 to 300, leaving 200
        // of travel for 600 of contents.
        ui.press(Offset(280f, 56f))
        assertEquals(0f, state.x, 0.5f, "a press on the thumb where it already is does not move it")
        ui.dragTo(Offset(180f, 56f))
        ui.release()
        ui.settle()
        assertEquals(300f, state.x, 0.5f, "the thumb dragged halfway left is the contents scrolled halfway on")

        ui.press(Offset(2f, 56f))
        ui.release()
        ui.settle()
        assertEquals(state.maxX, state.x, 0.5f, "the left end of a mirrored bar is the far end of the contents")
    }

    @Test
    fun `a vertical lazy grid puts its first column on the right`() {
        val ui = open {
            LazyVerticalGrid(12, GridCells.Fixed(3), Modifier.size(300f, 200f), bars = false) { Square("cell$it") }
        }

        assertEquals(listOf(250f, 150f, 50f), listOf(ui.left("cell0"), ui.left("cell1"), ui.left("cell2")))
        assertEquals(250f, ui.left("cell3"), "and the next row starts on the right again")
    }

    @Test
    fun `a mirrored slider has its minimum on the right for the mouse and the pad`() {
        var level by mutableStateOf(0f)
        val ui = open {
            Column {
                Slider(level, { level = it }, Modifier.testTag("level").width(216f), range = 0f..10f, step = 1f, initialFocus = true)
            }
        }
        val track = ui.node("level").boundsInRoot
        // 216 wide with a 16 knob leaves 200 of travel, so a notch is 20 pixels, counted from the right.
        fun at(value: Float) = Offset(track.right - 8f - value * 20f, track.centre.y)

        ui.click(at(3f))
        assertEquals(3f, level, "three notches in from the right is three")

        ui.pad(GamepadButton.DpadLeft)
        assertEquals(4f, level, "left points at the maximum, so it goes up")
        ui.pad(GamepadButton.DpadRight)
        ui.pad(GamepadButton.DpadRight)
        assertEquals(2f, level)

        ui.press(at(2f))
        ui.dragTo(at(9f))
        ui.release()
        assertEquals(9f, level, "a drag to the left raises it")
    }

    @Test
    fun `turning a screen right to left mirrors a lazy row that is already showing`() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        val ui = uiTest(Size(600f, 400f)) {
            ProvideLayoutDirection(direction) {
                LazyRow(20, Modifier.width(300f).height(50f), bars = false) { Square("item$it") }
            }
        }.also { opened += it }
        assertEquals(0f, ui.left("item0"))

        direction = LayoutDirection.Rtl
        ui.settle()

        assertEquals(250f, ui.left("item0"))
    }

    // --- which edge the up-and-down bar sits on --------------------------------------------------

    @Test
    fun `a mirrored scroll area hangs its vertical bar on the left where the lines end`() {
        val state = ScrollState()
        val ui = open {
            ScrollArea(Modifier.size(300f, 200f).testTag("page"), state) {
                Column { repeat(20) { Line("line$it") } }
            }
        }

        val bar = ui.verticalBar("page")
        assertEquals(0f, bar.left, "the bar is against the left edge")
        assertEquals(8f, bar.right)

        val first = ui.node("line0").boundsInRoot
        assertEquals(300f, first.right, "a line begins against the right edge")
        assertTrue(first.left >= bar.right, "and none of it is hidden under the bar: $first against $bar")
    }

    @Test
    fun `the same scroll area left to right keeps its vertical bar on the right`() {
        val ui = open(LayoutDirection.Ltr) {
            ScrollArea(Modifier.size(300f, 200f).testTag("page")) {
                Column { repeat(20) { Line("line$it") } }
            }
        }

        val bar = ui.verticalBar("page")
        assertEquals(292f, bar.left)
        assertEquals(300f, bar.right, "unchanged for a screen that reads the usual way")
        assertEquals(0f, ui.left("line0"))
    }

    @Test
    fun `the thumb of a mirrored vertical bar is grabbed and dragged down the left edge`() {
        val state = ScrollState()
        val ui = open {
            ScrollArea(Modifier.size(300f, 200f).testTag("page"), state) {
                Column { repeat(20) { Line("line$it") } }
            }
        }

        // A 200-tall window onto 1000 of lines: the thumb is a fifth of the bar, 40 long, with 160
        // of travel for 800 of contents.
        ui.press(Offset(4f, 5f))
        assertEquals(0f, state.y, 0.5f, "a press on the thumb where it already is does not move it")
        ui.dragTo(Offset(4f, 85f))
        ui.release()
        ui.settle()

        assertEquals(400f, state.y, 1f, "half way down the travel is half way down the list")
    }

    @Test
    fun `a press on the right edge of a mirrored area misses the bar and reaches the contents`() {
        val state = ScrollState()
        val ui = open {
            ScrollArea(Modifier.size(300f, 200f).testTag("page"), state) {
                Column { repeat(20) { Line("line$it") } }
            }
        }

        ui.press(Offset(296f, 180f))
        ui.release()
        ui.settle()

        assertEquals(0f, state.y, "the old edge is ordinary content now and a press on it scrolls nothing")
    }

    @Test
    fun `a mirrored lazy column hangs its bar on the left and starts its rows on the right`() {
        val ui = open {
            LazyColumn(20, Modifier.size(300f, 200f).testTag("list")) { Line("row$it") }
        }

        val bar = ui.verticalBar("list")
        assertEquals(0f, bar.left)
        assertEquals(8f, bar.right)

        val first = ui.node("row0").boundsInRoot
        assertEquals(300f, first.right, "the row begins against the right edge")
        assertTrue(first.left >= bar.right, "clear of the bar: $first against $bar")
    }

    @Test
    fun `a mirrored vertical grid hangs its bar on the left`() {
        val ui = open {
            LazyVerticalGrid(30, GridCells.Fixed(3), Modifier.size(300f, 200f).testTag("grid")) { Square("cell$it") }
        }

        val bar = ui.verticalBar("grid")
        assertEquals(0f, bar.left)
        assertEquals(8f, bar.right)
        assertEquals(250f, ui.left("cell0"), "the first cell is still against the right")
    }

    @Test
    fun `a scroll area inside a mirrored one puts its bar on its own left edge`() {
        val ui = open {
            ScrollArea(Modifier.size(300f, 200f).testTag("outer")) {
                Column {
                    repeat(4) { Line("outerLine$it") }
                    ScrollArea(Modifier.size(200f, 100f).testTag("inner")) {
                        Column { repeat(20) { Box(Modifier.size(100f, 50f).testTag("innerLine$it")) } }
                    }
                }
            }
        }

        val outer = ui.verticalBar("outer")
        val inner = ui.verticalBar("inner")
        assertEquals(0f, outer.left, "the outer bar is on the screen's left")
        assertEquals(100f, inner.left, "and the inner one on the left of its own box")
        assertEquals(108f, inner.right)
    }

    @Test
    fun `a mirrored table keeps the room for its bar on the left so the columns line up`() {
        val ui = open {
            Table(List(30) { it }, Modifier.size(300f, 200f).testTag("table")) {
                column("One", width = 100f) { Box(Modifier.size(80f, 20f).testTag("one$it")) }
                column("Two", width = 100f) { Box(Modifier.size(80f, 20f).testTag("two$it")) }
            }
        }

        val bar = ui.verticalBar("table")
        val one = ui.node("one0").boundsInRoot
        val two = ui.node("two0").boundsInRoot
        assertTrue(one.left > two.left, "the first column is the right-hand one: $one against $two")
        assertTrue(two.left >= bar.right, "and the last column clears the bar on the left: $two against $bar")
    }

    @Test
    fun `a still mirrored screen of scrolling widgets draws nothing new`() {
        val ui = open {
            Column {
                LazyRow(20, Modifier.width(300f).height(50f)) { Square("item$it") }
                ScrollArea(Modifier.size(300f, 60f), horizontal = true, vertical = false) {
                    Row { repeat(18) { Square("cell$it") } }
                }
                LazyVerticalGrid(12, GridCells.Fixed(3), Modifier.size(300f, 100f)) { Square("grid$it") }
                var level by remember { mutableStateOf(3f) }
                Slider(level, { level = it }, range = 0f..10f)
            }
        }

        ui.render()

        assertFalse(ui.render(), "nothing moved, so nothing is drawn again")
    }
}
