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
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sticky headers in real composed UI: scrolled by the wheel and a drag, clicked, walked by keys and
 * a pad.
 *
 * The screen is an inventory in sections: a 200 by 200 window, headers 20 tall and rows 40 tall.
 * Section s is a header then ten rows, so its header is item 11s and its rows follow it. Header one
 * starts 420 down the list and header two 840.
 */
class StickyHeaderTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun screen(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 400f)) { content() }.also { opened += it }

    private val names = listOf("WEAPONS", "ARMOUR", "POTIONS")

    /**
     * Three sections of ten, headers as painted boxes.
     *
     * The rows are painted boxes too unless [buttons] asks for buttons that record a click. Only
     * the tests about clicks and focus want those: a press on a button is the button's, so a drag
     * cannot start on one, and the mouse resting on one moves focus to it, which pulls the list
     * back to wherever that button is.
     */
    private fun inventory(
        state: LazyListState = LazyListState(),
        buttons: Boolean = false,
        clicked: MutableList<String> = mutableListOf(),
    ) = screen {
        LazyColumn(Modifier.size(200f).testTag("list"), state = state, bars = false) {
            names.forEachIndexed { section, name ->
                stickyHeader(key = name) {
                    Box(Modifier.fillMaxWidth().height(20f).testTag("h$section")) { Text(name) }
                }
                items(10, key = { "$name$it" }) { row ->
                    val tag = "s${section}r$row"
                    if (buttons) {
                        Button(
                            "$name $row",
                            onClick = { clicked += "$name $row" },
                            initialFocus = section == 0 && row == 0,
                            modifier = Modifier.fillMaxWidth().height(40f).testTag(tag),
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(40f).testTag(tag)) { Text("$name $row") }
                    }
                }
            }
        }
    }

    /** Which header is pinned, as a label, telling [composed] every time it is composed. */
    @Composable
    private fun SectionLabel(state: LazyListState, composed: () -> Unit) {
        composed()
        Text("${state.pinnedHeader}", Modifier.testTag("section"))
    }

    private fun UiTest.top(tag: String) = node(tag).boundsInRoot.top

    /** A drag on the list's contents from its middle, by [by] upwards, in steps as a hand makes. */
    private fun UiTest.dragUp(by: Float) {
        val from = node("list").boundsInRoot.centre
        press(from)
        val steps = 10
        for (step in 1..steps) dragTo(Offset(from.x, from.y - by * step / steps))
        release()
    }

    // --- where the header goes ---

    @Test
    fun `a header starts where it is in the list and its rows follow it`() {
        val state = LazyListState()
        val ui = inventory(state)

        assertEquals(0f, ui.top("h0"))
        assertEquals(20f, ui.top("s0r0"))
        assertEquals(60f, ui.top("s0r1"))
        assertEquals(0, state.pinnedHeader)
    }

    @Test
    fun `the wheel scrolls the rows under a header that stays at the top`() {
        val state = LazyListState()
        val ui = inventory(state)

        ui.scroll("list", Offset(0f, 1f))

        assertEquals(48f, state.position)
        assertEquals(0f, ui.top("h0"), "the header held at the top edge")
        assertEquals(20f - 48f, ui.top("s0r0"), "the first row has gone up under it")
    }

    @Test
    fun `a pinned header is drawn over the rows it covers`() {
        val ui = inventory()

        ui.scroll("list", Offset(0f, 1f))

        // The first row now starts above the window and runs under the header, so the header has
        // to be painted after it to be seen.
        val drawn = ui.texts("list")
        assertEquals("WEAPONS", drawn.last(), "drawn last, so on top: $drawn")
    }

    @Test
    fun `a header stays long after its own place has scrolled out of the window`() {
        val state = LazyListState()
        val ui = inventory(state)

        repeat(7) { ui.scroll("list", Offset(0f, 1f)) }

        assertEquals(336f, state.position)
        // Row seven is the first on screen, so the window reaches back to row five and no further.
        ui.assertDoesNotExist("s0r2")
        assertEquals(0f, ui.top("h0"), "still there, and still at the top")
        assertEquals(0, state.pinnedHeader)
    }

    @Test
    fun `a header keeps its state as it leaves the window and is pinned from outside it`() {
        var next = 0
        val born = mutableListOf<Int>()
        val state = LazyListState()
        screen {
            LazyColumn(Modifier.size(200f).testTag("list"), state = state, bars = false) {
                stickyHeader(key = "weapons") {
                    val mine = remember { next++ }
                    born += mine
                    Box(Modifier.fillMaxWidth().height(20f).testTag("header"))
                }
                items(50) { Box(Modifier.fillMaxWidth().height(40f)) }
            }
        }.let { ui ->
            // In the window, out of it and pulled in from above, then back in again.
            repeat(10) { ui.scroll("list", Offset(0f, 1f)) }
            repeat(10) { ui.scroll("list", Offset(0f, -1f)) }
            ui.assertExists("header")
        }

        assertEquals(setOf(0), born.toSet(), "one header the whole way, never built again: $born")
    }

    // --- the next header ---

    @Test
    fun `the next header pushes the pinned one off rather than sliding over it`() {
        val state = LazyListState()
        val ui = inventory(state)

        // Header one starts at 420; 410 down it is ten from the top, with header zero right above it.
        ui.dragUp(410f)

        assertEquals(410f, state.position, 0.01f)
        assertEquals(10f, ui.top("h1"), 0.01f)
        assertEquals(-10f, ui.top("h0"), 0.01f, "pushed up by header one, half of it gone")
        assertEquals(0, state.pinnedHeader)

        // Fifteen more and header one has reached the top and taken over.
        ui.dragUp(15f)

        assertEquals(11, state.pinnedHeader, "header one is item eleven")
        assertEquals(0f, ui.top("h1"), 0.01f)
        ui.assertDoesNotExist("h0")
    }

    @Test
    fun `spacing between rows stays between the two headers while one pushes the other`() {
        val state = LazyListState()
        val ui = screen {
            LazyColumn(Modifier.size(200f).testTag("list"), state = state, spacing = 10f, bars = false) {
                for (section in 0..1) {
                    stickyHeader { Box(Modifier.fillMaxWidth().height(20f).testTag("h$section")) }
                    items(5) { Box(Modifier.fillMaxWidth().height(40f)) }
                }
            }
        }

        // Header one: 20 + 10, then five rows of 40 and 10 each, is 280 down. At 270 it is ten from
        // the top, and header zero sits ten above that with the gap under it.
        ui.dragUp(270f)

        assertEquals(10f, ui.top("h1"), 0.01f)
        assertEquals(-20f, ui.top("h0"), 0.01f)
    }

    @Test
    fun `rows before the first header scroll away with no header pinned`() {
        val state = LazyListState()
        val ui = screen {
            LazyColumn(Modifier.size(200f).testTag("list"), state = state, bars = false) {
                item { Box(Modifier.fillMaxWidth().height(40f).testTag("banner")) }
                stickyHeader { Box(Modifier.fillMaxWidth().height(20f).testTag("header")) }
                items(20) { Box(Modifier.fillMaxWidth().height(40f)) }
            }
        }

        ui.dragUp(30f)
        assertNull(state.pinnedHeader, "the banner is still what is at the top")
        assertEquals(10f, ui.top("header"), 0.01f)

        ui.dragUp(30f)
        assertEquals(1, state.pinnedHeader)
        assertEquals(0f, ui.top("header"), 0.01f, "reached the top and stopped")
    }

    // --- the pointer ---

    @Test
    fun `a click on a pinned header does not reach the row hidden under it`() {
        val clicked = mutableListOf<String>()
        val ui = inventory(buttons = true, clicked = clicked)

        ui.scroll("list", Offset(0f, 1f))
        // The first row is 28 above the top and 12 below it, all of that under the header.
        assertTrue(ui.top("s0r0") < 0f)

        ui.click(Offset(100f, 6f))
        assertEquals(emptyList(), clicked, "the header was pressed, not the row under it")

        ui.click(Offset(100f, 30f))
        assertEquals(listOf("WEAPONS 1"), clicked, "and the row below the header still works")
    }

    @Test
    fun `a drag that starts on a pinned header scrolls the list`() {
        val state = LazyListState()
        val ui = inventory(state)
        ui.scroll("list", Offset(0f, 1f))

        ui.press(Offset(100f, 10f))
        ui.dragTo(Offset(100f, -30f))
        ui.release()

        assertEquals(88f, state.position, 0.01f)
        assertEquals(0f, ui.top("h0"))
    }

    // --- focus ---

    @Test
    fun `focus moving up brings a row out from under the header rather than behind it`() {
        val state = LazyListState()
        val ui = inventory(state, buttons = true)

        repeat(9) { ui.key(Key.Down) }
        ui.assertFocused("s0r9")
        assertTrue(state.position > 0f)

        // Up with the pad, one row at a time, back to the first. Every row focus lands on has to be
        // somewhere the player can see it: below the header, not behind it.
        for (row in 8 downTo 0) {
            ui.pad(GamepadButton.DpadUp)
            ui.assertFocused("s0r$row")
            val header = ui.node("h0").boundsInRoot
            assertTrue(
                ui.top("s0r$row") >= header.bottom - 0.5f,
                "row $row at ${ui.top("s0r$row")} is under the header ending at ${header.bottom}",
            )
        }
    }

    // --- building it ---

    @Test
    fun `scrolling a sectioned list does not recompose rows that have not changed`() {
        val built = HashMap<Int, Int>()
        val state = LazyListState()
        val ui = screen {
            LazyColumn(Modifier.size(200f).testTag("list"), state = state, bars = false) {
                stickyHeader { Box(Modifier.fillMaxWidth().height(20f)) }
                items(500, key = { it }) { index ->
                    built[index] = (built[index] ?: 0) + 1
                    Box(Modifier.fillMaxWidth().height(40f))
                }
            }
        }
        built.clear()

        ui.dragUp(40f)

        // The rows of the new window that were not built before, and nothing else.
        assertTrue(built.keys.isNotEmpty(), "a row arrived at the bottom")
        assertTrue(built.keys.all { it >= 5 }, "rows already on screen were left alone: ${built.keys}")
    }

    @Test
    fun `sections follow the state they are built from`() {
        var weapons by mutableStateOf(listOf("axe", "bow"))
        val ui = screen {
            LazyColumn(Modifier.size(200f).testTag("list"), bars = false) {
                stickyHeader { Box(Modifier.fillMaxWidth().height(20f).testTag("weapons")) }
                items(weapons, key = { it }) { name -> Box(Modifier.fillMaxWidth().height(40f).testTag(name)) }
                stickyHeader { Box(Modifier.fillMaxWidth().height(20f).testTag("armour")) }
                item { Box(Modifier.fillMaxWidth().height(40f).testTag("helm")) }
            }
        }
        assertEquals(100f, ui.top("armour"))

        weapons = weapons + "cleaver"
        ui.settle()

        assertEquals(100f, ui.top("cleaver"))
        assertEquals(140f, ui.top("armour"), "the next section moved down by a row")
        assertEquals(160f, ui.top("helm"))
    }

    @Test
    fun `a sectioned row pins its headers at the left edge`() {
        val state = LazyListState()
        val ui = screen {
            LazyRow(Modifier.size(200f).testTag("list"), state = state, bars = false) {
                for (section in 0..1) {
                    stickyHeader { Box(Modifier.width(20f).fillMaxHeight().testTag("h$section")) }
                    items(10) { index -> Box(Modifier.width(40f).fillMaxHeight().testTag("s${section}c$index")) }
                }
            }
        }

        ui.scroll("list", Offset(1f, 0f))

        assertEquals(48f, state.position)
        assertEquals(0f, ui.node("h0").boundsInRoot.left, "held at the left")
        assertEquals(0f, ui.node("h0").boundsInRoot.top, "and not moved down the screen")
        assertEquals(-28f, ui.node("s0c0").boundsInRoot.left)
    }

    // --- reading it, and edges ---

    @Test
    fun `a label reading the pinned header follows the list`() {
        val state = LazyListState()
        val ui = screen {
            Column {
                Text(state.pinnedHeader?.let { names[it / 11] } ?: "none", Modifier.testTag("section"))
                LazyColumn(Modifier.size(200f).testTag("list"), state = state, bars = false) {
                    names.forEachIndexed { section, name ->
                        stickyHeader(key = name) { Box(Modifier.fillMaxWidth().height(20f).testTag("h$section")) }
                        items(10) { Box(Modifier.fillMaxWidth().height(40f)) }
                    }
                }
            }
        }
        ui.assertText("section", "WEAPONS")

        // Header one starts 420 down; past it, and the label has moved on without being told.
        ui.dragUp(430f)

        assertEquals(11, state.pinnedHeader)
        ui.assertText("section", "ARMOUR")
    }

    @Test
    fun `a sectioned list standing still does not redraw or recompose what reads it`() {
        val state = LazyListState()
        var reads = 0
        val ui = screen {
            Column {
                SectionLabel(state) { reads++ }
                LazyColumn(Modifier.size(200f).testTag("list"), state = state, bars = false) {
                    names.forEach { name ->
                        stickyHeader(key = name) { Box(Modifier.fillMaxWidth().height(20f)) }
                        items(10) { Box(Modifier.fillMaxWidth().height(40f)) }
                    }
                }
            }
        }
        ui.dragUp(100f)
        ui.settle()
        val before = reads

        repeat(5) { assertFalse(ui.render(), "frame $it: nothing moved, so nothing is redrawn") }
        assertEquals(before, reads, "the pinned header did not change, so its reader was not composed again")
    }

    @Test
    fun `a button inside a pinned header takes its own click`() {
        var collapsed = 0
        val clicked = mutableListOf<Int>()
        val ui = screen {
            LazyColumn(Modifier.size(200f).testTag("list"), bars = false) {
                stickyHeader {
                    Button("FOLD", onClick = { collapsed++ }, modifier = Modifier.fillMaxWidth().height(30f).testTag("fold"))
                }
                items(20) { index ->
                    Button("$index", onClick = { clicked += index }, modifier = Modifier.fillMaxWidth().height(40f))
                }
            }
        }

        ui.scroll("list", Offset(0f, 1f))
        assertEquals(0f, ui.top("fold"), "pinned")

        ui.click(Offset(100f, 15f))

        assertEquals(1, collapsed, "the header's own button was pressed")
        assertEquals(emptyList(), clicked, "and not the row under it")
    }

    @Test
    fun `an empty sectioned list and one of only headers both scroll without falling over`() {
        val empty = LazyListState()
        val bare = LazyListState()
        val ui = screen {
            Column {
                LazyColumn(Modifier.size(100f).testTag("empty"), state = empty, bars = false) { }
                LazyColumn(Modifier.size(100f).testTag("bare"), state = bare, bars = false) {
                    // Zero tall and ordinary, one after the other.
                    repeat(10) { index ->
                        stickyHeader {
                            Box(Modifier.fillMaxWidth().height(if (index % 2 == 0) 0f else 30f).testTag("h$index"))
                        }
                    }
                }
            }
        }

        ui.scroll("empty", Offset(0f, 1f))
        ui.scroll("bare", Offset(0f, 1f))

        assertNull(empty.pinnedHeader)
        assertEquals(0f, empty.position)
        // Headers of nothing and of 30 in turn: 48 down is part way through header three, the second
        // of 30. Header four is nothing tall and 12 below the top, so it pushes three up to -18.
        assertEquals(48f, bare.position)
        assertEquals(3, bare.pinnedHeader)
        assertEquals(-18f, ui.top("h3") - ui.top("bare"), 0.01f)
    }
}
