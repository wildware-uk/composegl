package dev.wildware.composegl.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusRequester
import dev.wildware.composegl.ui.modifier.focusTrap
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.focusableByPointer
import dev.wildware.composegl.ui.modifier.onFocusWithin
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.TestTree
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A frame where the tree did not change does not walk it for focus.
 *
 * What the focus refresh looks for — the focused node taken away, hidden, or left outside a trap
 * that just opened — can only happen on a frame where the tree changed. On a still screen the two
 * walks it makes over every node find exactly what they found the frame before, so they are not
 * made. The other half of every test is that focus still behaves on the frame something does
 * change, after a run of still frames has gone by.
 */
class StillFrameFocusTest {

    /** A column of plain focusable boxes, one per name, each its own node keyed by its name. */
    @Composable
    private fun Rows(names: List<String>, modifier: (String) -> Modifier = { Modifier }) {
        Column {
            names.forEach { name ->
                key(name) { Box(modifier(name).size(100f, 40f).focusable().testTag(name)) }
            }
        }
    }

    @Test
    fun `a still screen walks the tree for focus on its first frames and not after`() {
        uiTest(Size(400f, 300f)) {
            Column {
                Button("Play", onClick = {}, modifier = Modifier.testTag("play"))
                Button("Options", onClick = {}, modifier = Modifier.testTag("options"))
                Button("Quit", onClick = {}, modifier = Modifier.testTag("quit"))
            }
        }.use { ui ->
            ui.assertFocused("play")
            val settled = ui.focus.walks
            assertTrue(settled > 0, "the first frames have to look for something to focus")

            ui.advanceBy(500)

            assertEquals(settled, ui.focus.walks, "half a second of still frames walked the tree for focus")
            // And the screen is not asleep: the next key still moves focus.
            ui.key(Key.Tab)
            ui.assertFocused("options")
        }
    }

    @Test
    fun `a focused row taken away after still frames hands focus to the one that slid into its place`() {
        var rows by mutableStateOf(listOf("a", "b", "c"))
        uiTest(Size(400f, 300f)) { Rows(rows) }.use { ui ->
            ui.key(Key.Down)
            ui.assertFocused("b")
            ui.advanceBy(500)

            rows = listOf("a", "c")
            ui.settle()

            ui.assertFocused("c")
        }
    }

    @Test
    fun `a trap that opens and closes after still frames takes focus and gives it back`() {
        var open by mutableStateOf(false)
        uiTest(Size(400f, 300f)) {
            Column {
                Rows(listOf("a", "b"))
                if (open) Box(Modifier.focusTrap()) { Rows(listOf("inside")) }
            }
        }.use { ui ->
            ui.key(Key.Down)
            ui.assertFocused("b")
            ui.advanceBy(500)

            open = true
            ui.settle()
            ui.assertFocused("inside")
            ui.advanceBy(500)

            open = false
            ui.settle()
            ui.assertFocused("b")
        }
    }

    @Test
    fun `a focused node faded to nothing after still frames loses focus`() {
        var faded by mutableStateOf(false)
        uiTest(Size(400f, 300f)) {
            Rows(listOf("a", "b")) { if (it == "b" && faded) Modifier.alpha(0f) else Modifier }
        }.use { ui ->
            ui.key(Key.Down)
            ui.assertFocused("b")
            ui.advanceBy(500)

            faded = true
            ui.settle()

            ui.assertFocused("a")
        }
    }

    @Test
    fun `a focused node scaled to nothing after still frames loses focus`() {
        var shrunk by mutableStateOf(false)
        uiTest(Size(400f, 300f)) {
            Rows(listOf("a", "b")) { if (it == "b" && shrunk) Modifier.scale(0f) else Modifier }
        }.use { ui ->
            ui.key(Key.Down)
            ui.assertFocused("b")
            ui.advanceBy(500)

            shrunk = true
            ui.settle()

            ui.assertFocused("a")
        }
    }

    /**
     * A refresh that moves focus runs other code on the way — here a group that, the first time
     * focus arrives in it, sends focus on to a node nobody can see. The refresh that moved focus
     * never checked where that left it, so the next one has to, however still the screen is.
     * And a screen that keeps sending focus there is turned away every time, not only the first.
     */
    @Test
    fun `focus a handler moves during a refresh is checked by the next one`() {
        val hidden = FocusRequester()
        var showStart by mutableStateOf(true)
        var sendOn: (() -> Unit)? = null
        val group = FocusWithinHandler { focused ->
            if (focused) sendOn?.invoke()
            sendOn = null
        }
        uiTest(Size(400f, 300f)) {
            Column {
                Box(Modifier.onFocusWithin(group)) { Rows(listOf("a")) }
                if (showStart) Box(Modifier.size(100f, 40f).focusable(initial = true).testTag("start"))
                Box(Modifier.scale(0f)) {
                    Box(Modifier.size(100f, 40f).focusable().focusRequester(hidden).testTag("hidden"))
                }
            }
        }.use { ui ->
            ui.assertFocused("start")
            ui.advanceBy(500)
            sendOn = { ui.focus.focusOn(hidden) }

            // The refresh hands focus to the first focusable, "a", whose group sends it on.
            showStart = false
            ui.settle()

            ui.assertFocused("a")
            repeat(2) {
                ui.focus.focusOn(hidden)
                ui.settle()
                ui.assertFocused("a")
            }
        }
    }

    /**
     * Focus cleared on a menu that must always have something selected is put back on the next
     * frame. Clearing changes nothing in the tree, so only the move itself can tell the refresh.
     */
    @Test
    fun `focus cleared after still frames is put back by the next frame`() {
        uiTest(Size(400f, 300f)) { Rows(listOf("a", "b")) }.use { ui ->
            ui.key(Key.Down)
            ui.assertFocused("b")
            ui.advanceBy(500)

            ui.focus.clearFocus()
            ui.settle()

            ui.assertFocused("b")
        }
    }

    /**
     * Focus sent to a node nobody can see, on a screen where nothing had it, is taken off again by
     * the next frame. Nothing was let go of first, so only focus arriving can tell the refresh.
     */
    @Test
    fun `focus sent somewhere unseen from nowhere is taken back by the next frame`() {
        val hidden = FocusRequester()
        uiTest(Size(400f, 300f)) {
            Box(Modifier.scale(0f)) {
                Box(Modifier.size(100f, 40f).focusable().focusRequester(hidden).testTag("hidden"))
            }
        }.use { ui ->
            assertNull(ui.focus.focused, "nothing on this screen can be seen to take focus")
            ui.advanceBy(500)

            assertTrue(ui.focus.focusOn(hidden))
            ui.settle()

            assertNull(ui.focus.focused, "focus was left on a node nobody can see")
        }
    }

    /**
     * Enter held on a node only a click can focus is let go on the next frame, as it always was:
     * that node is not one the keys can reach, so the refresh cancels the press. Nothing about the
     * tree changes when the key goes down, so only the press itself can tell the refresh to look.
     */
    @Test
    fun `a press held on a node the keys cannot reach is let go by the next frame`() {
        var clicks = 0
        uiTest(Size(400f, 300f)) {
            Column {
                Rows(listOf("a"))
                Box(Modifier.size(100f, 40f).focusableByPointer().clickable { clicks++ }.testTag("label"))
            }
        }.use { ui ->
            ui.click("label")
            ui.assertFocused("label")
            assertEquals(1, clicks)
            ui.advanceBy(500)

            ui.keyDown(Key.Enter)
            ui.keyUp(Key.Enter)

            assertEquals(1, clicks, "the held press should have been let go before the key came up")
        }
    }

    private fun UiHost.settled(focus: FocusManager, width: Float, nanos: () -> Long) {
        val viewport = Viewport.oneToOne(Size(width, 300f))
        repeat(8) { if (!settle(viewport, focus, nanos())) return }
        throw AssertionError("settle still reported a change after 8 turns")
    }

    /**
     * A wider window moves every box with nothing recomposed, so only the layout pass knows. Where
     * focus was standing has to move with it, or the row that slides into the focused one's place
     * after the resize is looked for where the focused row used to be.
     */
    @Test
    fun `a resize with nothing recomposed moves where focus is remembered standing`() {
        val host = UiHost()
        val focus = FocusManager(host.root)
        var nanos = 0L
        val tick = { nanos += 16_666_667L; nanos }
        var rows by mutableStateOf(listOf("a", "b", "c"))
        try {
            // Pinned to the right edge, so a wider window moves the column right.
            host.setContent { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) { Rows(rows) } }
            host.settled(focus, 200f, tick)
            assertTrue(focus.moveFocus(FocusDirection.Down))
            assertSame(host.root.find("b"), focus.focused)
            host.settled(focus, 200f, tick)

            host.settled(focus, 400f, tick)
            rows = listOf("a", "c")
            host.settled(focus, 400f, tick)

            assertSame(host.root.find("c"), focus.focused, "focus was looked for where the row stood before the resize")
        } finally {
            host.dispose()
        }
    }

    /**
     * A canvas that can draw a scale neither through a transform nor through a picture draws the
     * node at its own size, and from then on it is clicked and focused there. No box moves, so no
     * layout says so; the draw pass marking the scale undrawn is the change.
     */
    @Test
    fun `a scale the canvas could not draw moves where focus is remembered standing`() {
        val host = UiHost()
        val focus = FocusManager(host.root)
        var nanos = 0L
        val tick = { nanos += 16_666_667L; nanos }
        var rows by mutableStateOf(listOf("a", "b", "c"))
        try {
            host.setContent { Box(Modifier.scale(2f, Alignment.TopStart).testTag("panel")) { Rows(rows) } }
            host.settled(focus, 400f, tick)
            assertTrue(focus.moveFocus(FocusDirection.Down))
            assertSame(host.root.find("b"), focus.focused)
            host.settled(focus, 400f, tick)

            // What the draw pass does on a canvas that refused both ways of drawing the scale.
            host.root.find("panel").scaleApplied = false
            host.settled(focus, 400f, tick)
            rows = listOf("a", "c")
            host.settled(focus, 400f, tick)

            assertSame(host.root.find("c"), focus.focused, "focus was looked for where the row was drawn at twice its size")
        } finally {
            host.dispose()
        }
    }

    /**
     * A list in a tree placed by hand, with focus on its middle row, moved down by hand and then
     * reported through [say]. The row is then taken away and the one under it slides into its place,
     * which is where focus has to land: where the row stood after the move, not before it.
     */
    private fun handMovedList(say: (TestTree, UiNode) -> Unit): String? {
        val screen = TestTree()
        val list = screen.box("list", 0f, 0f, width = 100f, height = 200f)
        screen.box("first", 0f, 0f, modifier = Modifier.focusable(), parent = list)
        val second = screen.box("second", 0f, 30f, modifier = Modifier.focusable(), parent = list)
        screen.box("third", 0f, 60f, modifier = Modifier.focusable(), parent = list)
        val focus = FocusManager(screen.root)
        focus.focusOn(second)
        focus.refresh()

        list.y = 100f
        say(screen, list)
        focus.refresh()
        screen.remove(second)
        screen["third"].y = 30f
        focus.refresh()
        return focus.focused?.name
    }

    /** The wiki's example: a tree placed by hand, moved by hand, says so with the node's `invalidate`. */
    @Test
    fun `a node moved by hand in a hand-built tree is looked at again once invalidated`() {
        assertEquals("third", handMovedList { _, moved -> moved.invalidate() }, "focus was looked for where the list stood before it moved")
    }

    /** The same said to the whole tree: how a game reports a change the tree cannot see for itself. */
    @Test
    fun `a node moved by hand is looked at again once the whole tree is invalidated`() {
        assertEquals("third", handMovedList { screen, _ -> screen.tree.invalidate() }, "focus was looked for where the list stood before it moved")
    }
}
