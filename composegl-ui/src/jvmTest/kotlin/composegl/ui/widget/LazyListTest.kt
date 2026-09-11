package composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import composegl.ui.backend.MonospaceFontProvider
import composegl.ui.draw.DrawPass
import composegl.ui.focus.FocusManager
import composegl.ui.geometry.Offset
import composegl.ui.graphics.RecordingCanvas
import composegl.ui.host.UiHost
import composegl.ui.input.Key
import composegl.ui.input.KeyEvent
import composegl.ui.input.KeyEventType
import composegl.ui.input.KeyNavigator
import composegl.ui.input.PointerEvent
import composegl.ui.input.PointerId
import composegl.ui.input.PointerRouter
import composegl.ui.layout.Constraints
import composegl.ui.layout.MeasurePass
import composegl.ui.layout.Spacer
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.fillMaxWidth
import composegl.ui.modifier.height
import composegl.ui.modifier.size
import composegl.ui.node.UiNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A list that builds only what can be seen.
 *
 * The three things worth proving are the three that make it worth having: ten thousand rows cost a
 * screenful rather than ten thousand, scrolling past a row that has not changed does not build it
 * again, and a key keeps an item's state with the item rather than with the slot it was in.
 */
class LazyListTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val keys = KeyNavigator(focus)

    /** A 200-unit window onto rows 40 units tall: five fit, and two spare are built either side. */
    private val side = 200f
    private val row = 40f

    @AfterEach
    fun tearDown() = host.dispose()

    private var clock = 0L

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        // Two frames: the first composes a guess, the second composes what layout learned.
        frames(3)
    }

    private fun frame() {
        canvas.clear()
        host.frame(clock)
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun frames(count: Int) = repeat(count) { frame() }

    private fun wheel(notches: Int) = repeat(notches) {
        pointer.onPointer(PointerEvent.Scroll(PointerId.Mouse, Offset(100f, 100f), Offset(0f, 1f)))
    }

    private fun key(key: Key) {
        keys.onKey(KeyEvent(key, KeyEventType.Down))
        keys.onKey(KeyEvent(key, KeyEventType.Up))
        frame()
    }

    private fun node(name: String, from: UiNode = host.root): UiNode? =
        if (from.name == name) from else from.children.firstNotNullOfOrNull { node(name, it) }

    /** How many item nodes exist. The bar is a child too, so it is not counted. */
    private fun built(): Int = (node("lazyColumn") ?: node("lazyRow"))!!.children.count { it.name == "box" }

    // --- only what fits ---------------------------------------------------------------------------

    @Test
    fun `ten thousand rows cost a screenful`() {
        val state = LazyListState()
        show {
            LazyColumn(count = 10_000, modifier = Modifier.size(side), state = state) {
                Spacer(Modifier.fillMaxWidth().height(row))
            }
        }

        // Five rows fit, and two spare either side: nowhere near ten thousand.
        assertTrue(built() <= 10, "built ${built()} of ten thousand rows")
        assertTrue(built() >= 5, "but enough to fill the window: ${built()}")
    }

    @Test
    fun `it knows how long the whole list is once its rows are the same height`() {
        val state = LazyListState()
        show {
            LazyColumn(count = 100, modifier = Modifier.size(side), state = state) {
                Spacer(Modifier.fillMaxWidth().height(row))
            }
        }

        state.scrollToItem(99)
        frames(2)

        assertEquals(100 * row - side, state.position, 0.5f, "the last row's bottom is the end")
    }

    @Test
    fun `scrolling moves which rows exist, not how many`() {
        val state = LazyListState()
        show {
            LazyColumn(count = 1_000, modifier = Modifier.size(side), state = state) {
                Spacer(Modifier.fillMaxWidth().height(row))
            }
        }
        assertEquals(0, state.firstVisibleItem)
        state.scrollToItem(300)
        frames(2)
        val before = built()

        state.scrollToItem(500)
        frames(2)

        assertEquals(500, state.firstVisibleItem)
        assertEquals(before, built(), "a window is a window wherever it is")
    }

    @Test
    fun `the wheel scrolls it and stops at the end`() {
        val state = LazyListState()
        show {
            LazyColumn(count = 20, modifier = Modifier.size(side), state = state) {
                Spacer(Modifier.fillMaxWidth().height(row))
            }
        }

        wheel(1)
        frames(2)
        assertEquals(48f, state.position, 0.001f)

        wheel(100)
        frames(2)
        assertEquals(20 * row - side, state.position, 0.5f, "the bottom, not past it")
    }

    // --- not building things twice -------------------------------------------------------------------

    @Test
    fun `scrolling does not recompose rows that have not changed`() {
        val state = LazyListState()
        val built = HashMap<Int, Int>()
        show {
            LazyColumn(count = 1_000, modifier = Modifier.size(side), state = state, key = { it }) { index ->
                built[index] = (built[index] ?: 0) + 1
                Spacer(Modifier.fillMaxWidth().height(row))
            }
        }
        // Everything up to here is the list settling on its first window. What matters is what
        // happens afterwards.
        built.clear()

        // One row further down: one row arrives at the bottom and nothing else should be touched.
        state.scrollToItem(1)
        frames(2)

        assertEquals(setOf(7), built.keys, "only the row that arrived was built")
    }

    // --- keys ------------------------------------------------------------------------------------------

    @Test
    fun `a key keeps an item's state with the item when the list reorders`() {
        var names by mutableStateOf(listOf("axe", "bow", "cup", "dagger", "elixir"))
        val seen = HashMap<String, Int>()
        var next = 0
        show {
            LazyColumn(count = names.size, modifier = Modifier.size(side), key = { names[it] }) { index ->
                val name = names[index]
                // Born once per item, so the number says which composition this row's state is from.
                val born = remember { next++ }
                seen[name] = born
                Spacer(Modifier.fillMaxWidth().height(row))
            }
        }
        val before = seen.toMap()

        names = names.reversed()
        frames(2)

        assertEquals(before, seen, "reordering moved the rows, it did not rebuild them")
    }

    @Test
    fun `without a key, state belongs to the position`() {
        var names by mutableStateOf(listOf("axe", "bow", "cup"))
        val seen = HashMap<String, Int>()
        var next = 0
        show {
            LazyColumn(count = names.size, modifier = Modifier.size(side)) { index ->
                val name = names[index]
                val born = remember { next++ }
                seen[name] = born
                Spacer(Modifier.fillMaxWidth().height(row))
            }
        }

        names = names.reversed()
        frames(2)

        assertEquals(seen["axe"], 2, "the third slot's state, because the third slot is where it is now")
    }

    // --- focus -----------------------------------------------------------------------------------------

    @Test
    fun `focus walks down a list that does not all exist`() {
        val state = LazyListState()
        show {
            LazyColumn(count = 500, modifier = Modifier.size(side), state = state, key = { it }) { index ->
                Button("ROW $index", onClick = {}, initialFocus = index == 0)
            }
        }
        frames(2)
        assertEquals(0f, state.position)

        repeat(12) { key(Key.Down) }

        assertTrue(state.position > 0f, "the list followed the focus: ${state.position}")
        val focused = focus.focused ?: error("nothing is focused")
        val window = node("lazyColumn")?.boundsInRoot ?: error("no list")
        val where = focused.boundsInRoot
        assertTrue(
            where.top >= window.top - 0.5f && where.bottom <= window.bottom + 0.5f,
            "the focused row is inside the window: $where against $window",
        )
    }

    // --- the shape of it ------------------------------------------------------------------------------------

    @Test
    fun `a list that fits has no bar and does not scroll`() {
        val state = LazyListState()
        show {
            LazyColumn(count = 2, modifier = Modifier.size(side), state = state) {
                Spacer(Modifier.fillMaxWidth().height(row))
            }
        }

        assertFalse(state.axis.canScroll)
        assertTrue(canvas.calls.isEmpty(), "nothing to scroll, so no bar: ${canvas.calls}")
    }

    @Test
    fun `a row scrolls sideways instead`() {
        val state = LazyListState()
        show {
            LazyRow(count = 500, modifier = Modifier.size(side), state = state) {
                Spacer(Modifier.size(row, side))
            }
        }

        state.scrollToItem(100)
        frames(2)

        assertEquals(100, state.firstVisibleItem)
        val first = node("box") ?: error("no items")
        assertEquals(0f, first.y, "a sideways list does not move down the screen")
    }

    @Test
    fun `a list that loses its items pulls the scroll back`() {
        val state = LazyListState()
        var count by mutableStateOf(100)
        show {
            LazyColumn(count = count, modifier = Modifier.size(side), state = state) {
                Spacer(Modifier.fillMaxWidth().height(row))
            }
        }
        state.scrollToItem(90)
        frames(2)
        assertTrue(state.position > 0f)

        count = 3
        frames(3)

        assertEquals(0f, state.position, "three rows do not fill the window, so there is nowhere to be")
    }

    @Test
    fun `spacing between rows counts towards where they are`() {
        val state = LazyListState()
        show {
            LazyColumn(count = 100, modifier = Modifier.size(side), state = state, spacing = 10f) {
                Spacer(Modifier.fillMaxWidth().height(row))
            }
        }

        state.scrollToItem(10)
        frames(2)

        assertEquals(10 * (row + 10f), state.position, 0.5f)
    }
}
