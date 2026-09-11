package uk.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import uk.wildware.composegl.ui.backend.MonospaceFontProvider
import uk.wildware.composegl.ui.draw.DrawPass
import uk.wildware.composegl.ui.focus.FocusManager
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.graphics.DrawCall
import uk.wildware.composegl.ui.graphics.RecordingCanvas
import uk.wildware.composegl.ui.host.UiHost
import uk.wildware.composegl.ui.input.BackStack
import uk.wildware.composegl.ui.input.Key
import uk.wildware.composegl.ui.input.KeyEvent
import uk.wildware.composegl.ui.input.KeyEventType
import uk.wildware.composegl.ui.input.KeyNavigator
import uk.wildware.composegl.ui.input.KeyRouter
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.PointerId
import uk.wildware.composegl.ui.input.PointerRouter
import uk.wildware.composegl.ui.layout.Box
import uk.wildware.composegl.ui.layout.Column
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.MeasurePass
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.fillMaxSize
import uk.wildware.composegl.ui.node.UiNode
import uk.wildware.composegl.ui.skin.Skin
import uk.wildware.composegl.ui.skin.SkinDrawable
import uk.wildware.composegl.ui.skin.WidgetState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The containers: a surface, a question the player must answer, and a row of pages.
 *
 * What matters here is not how they look. It is that a pad cannot wander behind a dialogue, that
 * Escape closes the one on top rather than the one underneath, and that a tab a player switched
 * away from is the same tab when they come back — the three ways every hand-rolled version of these
 * goes wrong.
 */
class ContainerTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val router = KeyRouter(focus)
    private val keys = KeyNavigator(focus)
    private val backs = BackStack()

    @AfterEach
    fun tearDown() = host.dispose()

    private var clock = 0L

    private fun show(content: @Composable () -> Unit) {
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) { ProvideBackStack(backs) { content() } }
        }
        frames(2)
    }

    private fun frame() {
        canvas.clear()
        host.frame(clock)
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.fixed(400f, 400f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun frames(count: Int) = repeat(count) { frame() }

    /** A router first, then the navigator: the order a game wires them, and the order that matters. */
    private fun key(key: Key) {
        val down = KeyEvent(key, KeyEventType.Down)
        if (!router.onKey(down)) keys.onKey(down)
        val up = KeyEvent(key, KeyEventType.Up)
        if (!router.onKey(up)) keys.onKey(up)
        frame()
    }

    private fun press(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y)))

    private fun release(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(x, y)))

    private fun click(x: Float, y: Float) {
        press(x, y)
        release(x, y)
        frame()
    }

    private fun node(name: String, from: UiNode = host.root): UiNode? =
        if (from.name == name) from else from.children.firstNotNullOfOrNull { node(name, it) }

    /** Every control a pad could land on, in tree order. A button is a box, so it has no name. */
    private fun buttons(): List<UiNode> {
        val found = mutableListOf<UiNode>()
        host.root.forEach { node ->
            if (node.resolved.focusable?.enabled != true) return@forEach
            var walk: UiNode? = node
            while (walk != null && walk.resolved.alpha > 0f) walk = walk.parent
            if (walk == null) found += node
        }
        return found
    }

    private fun rectangles() = canvas.calls.filterIsInstance<DrawCall.Rectangle>()

    private fun fill(style: String, vararg states: WidgetState): Colour {
        val background = Skin.Default.resolve(style, states.toSet()).background
        return (background as SkinDrawable.Fill).colour
    }

    /** Which button has focus, by the label drawn inside it. */
    private fun focusedLabel(): String? {
        var node = focus.focused
        while (node != null) {
            val where = node.boundsInRoot
            // Last, not first: dialogues stack on top of each other and draw in that order, so
            // the label drawn last is the one the player is actually looking at.
            val text = canvas.calls.filterIsInstance<DrawCall.Text>().lastOrNull {
                it.at.x >= where.left - 0.5f && it.at.x <= where.right + 0.5f &&
                    it.at.y >= where.top - 0.5f && it.at.y <= where.bottom + 0.5f
            }
            if (text != null) return text.text
            node = node.parent
        }
        return null
    }

    // --- panel ------------------------------------------------------------------------------------

    @Test
    fun `a panel is whatever the skin says a panel is`() {
        show { Panel { Text("HELLO") } }

        assertEquals(fill("panel"), rectangles().first().colour)
    }

    @Test
    fun `a panel puts its contents inside the skin's padding`() {
        show { Panel { Text("HELLO") } }

        val padding = Skin.Default.resolve("panel").padding
        val text = canvas.calls.filterIsInstance<DrawCall.Text>().first()
        assertEquals(padding.left, text.at.x, 0.001f, "the art decides the gap, not the widget")
    }

    // --- a dialogue's promises -----------------------------------------------------------------------

    @Test
    fun `a dialogue takes focus, and gives it back when it closes`() {
        var open by mutableStateOf(false)
        show {
            Box(Modifier.fillMaxSize()) {
                Column {
                    Button("PLAY", onClick = {})
                    Button("QUIT", onClick = { open = true }, initialFocus = true)
                }
                if (open) {
                    Dialog(onDismiss = { open = false }) {
                        Button("STAY", onClick = { open = false }, initialFocus = true)
                    }
                }
            }
        }
        val quit = focus.focused
        assertEquals("QUIT", focusedLabel())

        open = true
        frames(2)
        assertEquals("STAY", focusedLabel(), "focus went into the dialogue")

        open = false
        frames(2)

        assertEquals(quit, focus.focused, "and came back to the control that opened it")
    }

    @Test
    fun `a pad cannot wander behind a dialogue`() {
        show {
            Box(Modifier.fillMaxSize()) {
                Column {
                    Button("PLAY", onClick = {})
                    Button("QUIT", onClick = {})
                }
                Dialog(onDismiss = {}) {
                    Column {
                        Button("YES", onClick = {}, initialFocus = true)
                        Button("NO", onClick = {})
                    }
                }
            }
        }

        repeat(6) { key(Key.Down) }
        assertTrue(focusedLabel() in setOf("YES", "NO"), "focus was on ${focusedLabel()}")

        repeat(6) { key(Key.Up) }
        assertTrue(focusedLabel() in setOf("YES", "NO"), "focus was on ${focusedLabel()}")

        repeat(8) { key(Key.Tab) }
        assertTrue(focusedLabel() in setOf("YES", "NO"), "tab wrapped inside it, not out of it")
    }

    @Test
    fun `nothing behind the scrim can be clicked`() {
        var played = false
        show {
            Box(Modifier.fillMaxSize()) {
                Button("PLAY", onClick = { played = true })
                Dialog(onDismiss = {}) { Button("YES", onClick = {}, initialFocus = true) }
            }
        }

        // Straight onto the button behind, which is at the top-left of the screen.
        click(10f, 10f)

        assertFalse(played, "the scrim took the press")
    }

    @Test
    fun `escape closes the innermost dialogue`() {
        var outer by mutableStateOf(true)
        var inner by mutableStateOf(true)
        show {
            Box(Modifier.fillMaxSize()) {
                Button("PLAY", onClick = {})
                if (outer) {
                    Dialog(onDismiss = { outer = false }) {
                        Button("OUTER", onClick = {}, initialFocus = true)
                    }
                }
                if (inner) {
                    Dialog(onDismiss = { inner = false }) {
                        Button("INNER", onClick = {}, initialFocus = true)
                    }
                }
            }
        }
        assertEquals("INNER", focusedLabel())

        key(Key.Escape)
        assertFalse(inner, "the one on top closed")
        assertTrue(outer, "and the one underneath did not")

        frames(2)
        key(Key.Escape)
        assertFalse(outer, "and then it did")
    }

    @Test
    fun `back closes the innermost dialogue too`() {
        var outer by mutableStateOf(true)
        var inner by mutableStateOf(true)
        show {
            Box(Modifier.fillMaxSize()) {
                if (outer) Dialog(onDismiss = { outer = false }) { Button("OUTER", onClick = {}) }
                if (inner) Dialog(onDismiss = { inner = false }) { Button("INNER", onClick = {}) }
            }
        }

        assertTrue(backs.back(), "somebody answered")
        frames(2)
        assertFalse(inner)
        assertTrue(outer)

        assertTrue(backs.back())
        frames(2)
        assertFalse(outer)

        assertFalse(backs.back(), "and now nobody is listening")
    }

    @Test
    fun `a dialogue with no way out eats escape rather than passing it on`() {
        var back = false
        val navigator = KeyNavigator(focus, onBack = { back = true })
        show {
            Box(Modifier.fillMaxSize()) {
                Dialog(onDismiss = null) { Button("AGREE", onClick = {}, initialFocus = true) }
            }
        }

        val event = KeyEvent(Key.Escape, KeyEventType.Down)
        val used = router.onKey(event)
        if (!used) navigator.onKey(event)

        assertTrue(used, "the dialogue consumed it")
        assertFalse(back, "so the screen behind never heard about it")
    }

    @Test
    fun `a press on the scrim dismisses only when it was asked to`() {
        var open by mutableStateOf(true)
        var dismissible by mutableStateOf(false)
        show {
            Box(Modifier.fillMaxSize()) {
                if (open) {
                    Dialog(
                        onDismiss = { open = false },
                        dismissOnScrim = dismissible,
                    ) { Button("YES", onClick = {}, initialFocus = true) }
                }
            }
        }

        click(5f, 5f)
        assertTrue(open, "a mis-aimed click is not an answer")

        dismissible = true
        frames(2)
        click(5f, 5f)

        assertFalse(open)
    }

    // --- tabs ------------------------------------------------------------------------------------------

    @Test
    fun `a tab keeps its state when you switch away and back`() {
        var chosen by mutableStateOf(0)
        show {
            Tabs(chosen, onSelect = { chosen = it }, titles = listOf("ONE", "TWO")) { page ->
                if (page == 0) {
                    var count by remember { mutableStateOf(0) }
                    Button("COUNT $count", onClick = { count++ })
                } else {
                    Text("NOTHING HERE")
                }
            }
        }
        // The two headings come first in the tree; the page's own button is the last of the three.
        val counter = buttons().last()
        val at = counter.boundsInRoot.centre
        click(at.x, at.y)
        click(at.x, at.y)
        assertTrue(canvas.calls.filterIsInstance<DrawCall.Text>().any { it.text == "COUNT 2" })

        chosen = 1
        frames(2)
        chosen = 0
        frames(2)

        assertTrue(
            canvas.calls.filterIsInstance<DrawCall.Text>().any { it.text == "COUNT 2" },
            "the page was hidden, not thrown away",
        )
    }

    @Test
    fun `the page that is not chosen is neither drawn nor reachable`() {
        show {
            Tabs(0, onSelect = {}, titles = listOf("ONE", "TWO")) { page ->
                Button("PAGE $page", onClick = {})
            }
        }

        val drawn = canvas.calls.filterIsInstance<DrawCall.Text>().map { it.text }
        assertTrue("PAGE 0" in drawn)
        assertFalse("PAGE 1" in drawn, "the hidden page was drawn: $drawn")

        // A hidden page is measured at no size at all, so a focused node with an empty rectangle
        // is a cursor that has walked off onto the page nobody can see.
        repeat(8) {
            key(Key.Tab)
            val where = focus.focused?.boundsInRoot
            assertTrue(
                where != null && !where.isEmpty,
                "focus landed on something with no size, which is a widget on the hidden page",
            )
        }
        assertFalse(focusedLabel() == "PAGE 1", "focus reached a page nobody can see")
    }

    @Test
    fun `the chosen tab wears the chosen style`() {
        show { Tabs(1, onSelect = {}, titles = listOf("ONE", "TWO")) { Text("page") } }

        val colours = rectangles().map { it.colour }
        assertTrue(fill("tab.selected") in colours, "the chosen tab is drawn differently: $colours")
    }

    @Test
    fun `pressing a tab chooses it`() {
        var chosen by mutableStateOf(0)
        show {
            Tabs(chosen, onSelect = { chosen = it }, titles = listOf("ONE", "TWO")) { Text("page $it") }
        }

        val at = buttons()[1].boundsInRoot.centre
        click(at.x, at.y)

        assertEquals(1, chosen)
    }

    @Test
    fun `a panel with nothing in it still exists`() {
        show { Panel {} }

        assertNotNull(node("box"), "an empty panel is still a surface")
    }
}
