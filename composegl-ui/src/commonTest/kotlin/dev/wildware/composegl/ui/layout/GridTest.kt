package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.ScrollState
import dev.wildware.composegl.ui.widget.rememberScrollState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A grid in real composed UI: laid out by a host, walked by keys and a pad, clicked by a pointer.
 *
 * The screens are the ones the grid is for — a bag of slots, a wall of level tiles — made of real
 * buttons, so what moves focus here is what moves it in a game.
 */
class GridTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun screen(size: Size = Size(400f, 400f), content: @Composable () -> Unit): UiTest =
        uiTest(size) { content() }.also { opened += it }

    /** Nine level tiles, three across, the first one selected. */
    private fun levelSelect(count: Int = 9, clicked: MutableList<Int> = mutableListOf()) = screen {
        Grid(GridCells.Fixed(3), Modifier.width(240f), spacing = 8f) {
            repeat(count) { index ->
                Button(
                    "L$index",
                    onClick = { clicked += index },
                    initialFocus = index == 0,
                    modifier = Modifier.size(64f, 40f).testTag("slot$index"),
                )
            }
        }
    }

    // --- where the cells are ---

    @Test
    fun `an inventory of slots is laid out in rows of six`() {
        val ui = screen {
            Grid(GridCells.Fixed(6), Modifier.width(6 * 40f + 5 * 4f).testTag("bag"), spacing = 4f) {
                repeat(14) { index -> Button("$index", onClick = {}, modifier = Modifier.size(40f).testTag("slot$index")) }
            }
        }

        assertEquals(0f, ui.node("slot0").boundsInRoot.left)
        assertEquals(220f, ui.node("slot5").boundsInRoot.left, "the sixth slot is the last in the first row")
        assertEquals(0f, ui.node("slot6").boundsInRoot.left, "the seventh starts the second row")
        assertEquals(44f, ui.node("slot6").boundsInRoot.top)
        assertEquals(88f, ui.node("slot13").boundsInRoot.top, "fourteen slots is three rows")
        assertEquals(128f, ui.node("bag").height, "and the grid is three rows tall")
    }

    @Test
    fun `an adaptive grid reflows when the room it has changes`() {
        var width by mutableStateOf(300f)
        val ui = screen {
            Grid(GridCells.Adaptive(minSize = 64f), Modifier.width(width).testTag("grid"), spacing = 4f) {
                repeat(6) { index -> Button("$index", onClick = {}, modifier = Modifier.size(64f).testTag("slot$index")) }
            }
        }
        // (300 + 4) / 68 = 4 columns, so the fifth tile wraps.
        assertEquals(0f, ui.node("slot3").boundsInRoot.top, "four fit across 300")
        assertEquals(68f, ui.node("slot4").boundsInRoot.top)
        assertEquals(0f, ui.node("slot4").boundsInRoot.left)

        width = 140f
        ui.settle()

        // (140 + 4) / 68 = 2 columns.
        assertEquals(68f, ui.node("slot2").boundsInRoot.top, "only two fit across 140")
        assertEquals(0f, ui.node("slot2").boundsInRoot.left)
        assertEquals(136f, ui.node("slot5").boundsInRoot.top, "so six tiles are three rows")
        assertEquals(200f, ui.node("grid").height)
    }

    @Test
    fun `a small child sits where the grid or the child says in its cell`() {
        val ui = screen {
            Grid(GridCells.Fixed(2), Modifier.width(200f), contentAlignment = Alignment.Centre) {
                Button("a", onClick = {}, modifier = Modifier.size(40f, 20f).testTag("centred"))
                Button("b", onClick = {}, modifier = Modifier.size(40f, 60f).testTag("tall"))
                Button("c", onClick = {}, modifier = Modifier.size(40f, 20f).align(Alignment.BottomEnd).testTag("cornered"))
            }
        }

        val centred = ui.node("centred").boundsInRoot
        assertEquals(30f, centred.left, "the middle of a 100-wide cell")
        assertEquals(20f, centred.top, "the middle of a row the tall button made 60 tall")
        val cornered = ui.node("cornered").boundsInRoot
        assertEquals(60f, cornered.left, "its own alignment wins: the right of the first cell")
        assertEquals(60f, cornered.top, "and the top of the second row, which is only as tall as it")
    }

    // --- moving between cells ---

    @Test
    fun `arrow keys move focus to the neighbouring cell`() {
        val ui = levelSelect()
        ui.assertFocused("slot0")

        ui.key(Key.Right)
        ui.assertFocused("slot1")
        ui.key(Key.Down)
        ui.assertFocused("slot4") // straight below, not a diagonal
        ui.key(Key.Down)
        ui.assertFocused("slot7")
        ui.key(Key.Left)
        ui.assertFocused("slot6")
        ui.key(Key.Up)
        ui.assertFocused("slot3")
    }

    @Test
    fun `the edge of the grid stops focus rather than wrapping it`() {
        val ui = levelSelect()
        ui.key(Key.Right)
        ui.key(Key.Right)
        ui.assertFocused("slot2")

        ui.key(Key.Right)
        ui.assertFocused("slot2") // nothing to the right of the end of a row
        ui.key(Key.Up)
        ui.assertFocused("slot2") // nothing above the top row
    }

    @Test
    fun `tab walks the cells across and then down`() {
        val ui = levelSelect()
        ui.key(Key.Right)
        ui.key(Key.Right)
        ui.assertFocused("slot2")

        ui.key(Key.Tab)
        ui.assertFocused("slot3") // after the end of a row comes the start of the next
        ui.key(Key.Tab, Modifiers.Shift)
        ui.assertFocused("slot2") // and shift-tab goes back up to the end of the last one
    }

    @Test
    fun `down from past the end of a short last row lands on its last cell`() {
        val ui = levelSelect(count = 5)
        ui.key(Key.Right)
        ui.key(Key.Right)
        ui.assertFocused("slot2")

        ui.key(Key.Down)
        ui.assertFocused("slot4") // the nearest tile below, when none is straight below
    }

    @Test
    fun `the pad's d-pad moves between cells the same way`() {
        val ui = levelSelect()

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("slot3")
        ui.pad(GamepadButton.DpadRight)
        ui.assertFocused("slot4")
        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("slot7")
        ui.pad(GamepadButton.DpadUp)
        ui.assertFocused("slot4")
    }

    @Test
    fun `enter the pad and a pointer all press the cell they are on`() {
        val clicked = mutableListOf<Int>()
        val ui = levelSelect(clicked = clicked)

        ui.key(Key.Down)
        ui.key(Key.Right)
        ui.key(Key.Enter)
        assertEquals(listOf(4), clicked, "the tile focus walked to")

        ui.pad(GamepadButton.DpadRight)
        ui.pad(GamepadButton.South)
        assertEquals(listOf(4, 5), clicked, "the tile the pad walked to")

        ui.click("slot8")
        assertEquals(listOf(4, 5, 8), clicked, "the tile under the pointer")
    }

    @Test
    fun `a grid inside a grid is walked as one set of cells`() {
        val ui = screen {
            Grid(GridCells.Fixed(2), Modifier.width(300f), spacing = 20f) {
                Button("A", onClick = {}, initialFocus = true, modifier = Modifier.size(60f, 40f).testTag("outer"))
                Grid(GridCells.Fixed(2), spacing = 4f) {
                    repeat(4) { index ->
                        Button("$index", onClick = {}, modifier = Modifier.size(40f, 18f).testTag("inner$index"))
                    }
                }
            }
        }
        // The inner grid has a 140-wide cell to share: 68 a column.
        assertEquals(160f + 72f, ui.node("inner3").boundsInRoot.left)
        assertEquals(22f, ui.node("inner3").boundsInRoot.top)

        ui.key(Key.Right)
        ui.assertFocused("inner0")
        ui.key(Key.Down)
        ui.assertFocused("inner2")
        ui.key(Key.Right)
        ui.assertFocused("inner3")
    }

    @Test
    fun `moving down a grid taller than its scroll area brings the cell into view`() {
        val scroll = mutableListOf<ScrollState>()
        val ui = screen {
            val state = rememberScrollState().also { if (scroll.isEmpty()) scroll += it }
            ScrollArea(Modifier.size(200f, 100f), state, bars = false) {
                Grid(GridCells.Fixed(2), spacing = 10f) {
                    repeat(10) { index ->
                        Button("$index", onClick = {}, initialFocus = index == 0, modifier = Modifier.height(40f).testTag("slot$index"))
                    }
                }
            }
        }
        assertEquals(0f, scroll[0].y)
        assertEquals(40f * 5 + 10f * 4, scroll[0].content.height, "five rows of two")

        repeat(4) { ui.pad(GamepadButton.DpadDown) }
        ui.assertFocused("slot8")

        val slot = ui.node("slot8").boundsInRoot
        assertTrue(scroll[0].y > 0f, "the area scrolled")
        assertTrue(slot.top >= 0f && slot.bottom <= 100f, "and the last row is on screen: $slot")
    }

    @Test
    fun `a fixed grid in a sideways scroll area is as wide as its widest cell and scrolls to the far one`() {
        val scroll = mutableListOf<ScrollState>()
        val ui = screen {
            val state = rememberScrollState().also { if (scroll.isEmpty()) scroll += it }
            ScrollArea(Modifier.size(150f, 100f), state, horizontal = true, vertical = false, bars = false) {
                Grid(GridCells.Fixed(4), Modifier.testTag("grid"), spacing = 10f) {
                    repeat(8) { index ->
                        // One wide tile makes every column wide, since there is no width to share.
                        val width = if (index == 5) 90f else 40f
                        Button("$index", onClick = {}, initialFocus = index == 0, modifier = Modifier.size(width, 30f).testTag("slot$index"))
                    }
                }
            }
        }
        assertEquals(4 * 90f + 3 * 10f, ui.node("grid").width, "four columns of the widest tile")
        assertEquals(100f, ui.node("slot1").boundsInRoot.left, "the second column starts after one 90-wide column and a gap")
        assertEquals(0f, scroll[0].x)

        ui.pad(GamepadButton.DpadDown)
        repeat(3) { ui.pad(GamepadButton.DpadRight) }
        ui.assertFocused("slot7")

        val slot = ui.node("slot7").boundsInRoot
        assertTrue(scroll[0].x > 0f, "the area scrolled sideways")
        assertTrue(slot.left >= 0f && slot.right <= 150f, "and the far tile is on screen: $slot")
    }

    // --- items coming and going ---

    @Test
    fun `a keyed item keeps its focus when the items are reordered`() {
        var items by mutableStateOf(listOf("sword", "shield", "bow", "helm"))
        val ui = screen {
            Grid(GridCells.Fixed(2), Modifier.width(140f), spacing = 4f) {
                items.forEach { item ->
                    key(item) {
                        Button(item, onClick = {}, modifier = Modifier.size(64f, 32f).testTag(item))
                    }
                }
            }
        }
        ui.click("bow")
        val bow = ui.node("bow")
        assertSame(bow, ui.focus.focused)
        assertEquals(36f, bow.boundsInRoot.top, "the bow starts in the second row")

        items = items.reversed()
        ui.settle()

        assertSame(bow, ui.node("bow"), "the bow's node moved with it rather than being remade")
        ui.assertFocused("bow")
        assertEquals(0f, bow.boundsInRoot.top, "which is now in the first row")
        assertEquals(72f, bow.boundsInRoot.left, "second along: one 68-wide column and a gap")
        assertFalse(ui.node("sword").boundsInRoot.top == 0f, "and the sword went to the back")

        ui.key(Key.Left)
        ui.assertFocused("helm") // the keys follow where the cells are now, not where they were
    }

    @Test
    fun `taking items out closes the gap and shrinks the grid`() {
        var items by mutableStateOf((0 until 7).toList())
        val ui = screen {
            Grid(GridCells.Fixed(3), Modifier.width(120f).testTag("grid"), spacing = 0f) {
                items.forEach { item ->
                    key(item) { Button("$item", onClick = {}, modifier = Modifier.size(40f).testTag("slot$item")) }
                }
            }
        }
        assertEquals(120f, ui.node("grid").height, "seven is three rows")
        ui.click("slot4")

        items = items - listOf(0, 1)
        ui.settle()

        ui.assertDoesNotExist("slot0")
        assertEquals(80f, ui.node("slot4").boundsInRoot.left, "the slot moved up to third along, with its node")
        assertEquals(0f, ui.node("slot4").boundsInRoot.top)
        ui.assertFocused("slot4")
        assertEquals(80f, ui.node("grid").height, "five is two rows")

        items = emptyList()
        ui.settle()
        assertEquals(0f, ui.node("grid").height, "and an empty grid takes no room")
        assertEquals(null, ui.focus.focused, "with nothing left to hold focus")
    }

    @Test
    fun `taking the whole grid away lets go of its cells`() {
        var shown by mutableStateOf(true)
        val ui = screen {
            if (shown) {
                Grid(GridCells.Adaptive(50f), Modifier.width(200f).testTag("grid")) {
                    repeat(6) { index -> Button("$index", onClick = {}, initialFocus = index == 0, modifier = Modifier.testTag("slot$index")) }
                }
            }
        }
        ui.assertFocused("slot0")

        shown = false
        ui.settle()
        ui.assertDoesNotExist("grid")
        ui.assertDoesNotExist("slot0")
        assertEquals(null, ui.focus.focused)

        shown = true
        ui.settle()
        ui.assertFocused("slot0") // fresh cells, so the first one asks for focus again
        ui.key(Key.Right)
        ui.assertFocused("slot1")
    }

    @Test
    fun `a grid that is standing still asks for no frames`() {
        val ui = levelSelect()
        ui.key(Key.Right)

        repeat(30) { frame ->
            assertFalse(ui.host.frame(ui.nanos + (frame + 1) * 16_666_667L), "frame $frame redrew a still grid")
        }
    }
}
