package composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import composegl.ui.backend.MonospaceFontProvider
import composegl.ui.draw.DrawPass
import composegl.ui.focus.FocusManager
import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.graphics.Colour
import composegl.ui.graphics.DrawCall
import composegl.ui.graphics.RecordingCanvas
import composegl.ui.graphics.TextureHandle
import composegl.ui.graphics.UiCanvas
import composegl.ui.host.UiHost
import composegl.ui.input.Key
import composegl.ui.input.KeyEvent
import composegl.ui.input.KeyEventType
import composegl.ui.input.KeyNavigator
import composegl.ui.input.PointerButton
import composegl.ui.input.PointerEvent
import composegl.ui.input.PointerId
import composegl.ui.input.PointerRouter
import composegl.ui.layout.Column
import composegl.ui.layout.Constraints
import composegl.ui.layout.MeasurePass
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.fillMaxWidth
import composegl.ui.modifier.height
import composegl.ui.modifier.size
import composegl.ui.node.UiNode
import composegl.ui.text.TextLayout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A window onto something bigger than itself.
 *
 * The cases that matter are the ones a player notices: the wheel stops at the ends rather than
 * running on, a flick keeps going and slows down, focus moving to something off-screen brings it
 * into view, and the whole thing is clipped once rather than once per row — which is the difference
 * between a long list being cheap and being the reason a frame is late.
 */
class ScrollTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val clips = ClipCountingCanvas(canvas)
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val keys = KeyNavigator(focus)

    /** The area is 200×200 and its contents are 200×1000, so there are 800 units to scroll. */
    private val side = 200f
    private val tall = 1000f

    @AfterEach
    fun tearDown() = host.dispose()

    private var clock = 0L

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frame()
    }

    /** One turn of a game loop: recompose, lay out, settle focus, draw. */
    private fun frame() {
        canvas.clear()
        clips.clear()
        host.frame(clock)
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        focus.refresh()
        DrawPass(clips).draw(host.root)
        canvas.assertBalanced()
    }

    /** Several frames of the same loop, for anything that moves on its own. */
    private fun frames(count: Int) = repeat(count) { frame() }

    private fun wheel(x: Float, y: Float, dx: Float = 0f, dy: Float = 0f) =
        pointer.onPointer(PointerEvent.Scroll(PointerId.Mouse, Offset(x, y), Offset(dx, dy)))

    private fun press(x: Float, y: Float, at: Long = 0L) =
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y), timeMillis = at))

    private fun drag(x: Float, y: Float, at: Long = 0L) = pointer.onPointer(
        PointerEvent.Move(PointerId.Mouse, Offset(x, y), setOf(PointerButton.Primary), timeMillis = at),
    )

    private fun release(x: Float, y: Float, at: Long = 0L) =
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(x, y), timeMillis = at))

    private fun key(key: Key) {
        keys.onKey(KeyEvent(key, KeyEventType.Down))
        keys.onKey(KeyEvent(key, KeyEventType.Up))
        frame()
    }

    private fun node(name: String, from: UiNode = host.root): UiNode? =
        if (from.name == name) from else from.children.firstNotNullOfOrNull { node(name, it) }

    /** A tall column inside a small window: the shape every scrolling list has. */
    @Composable
    private fun TallList(state: ScrollState, bars: Boolean = true) {
        ScrollArea(Modifier.size(side), state = state, bars = bars) {
            Column(Modifier.fillMaxWidth()) {
                repeat(10) { Spacerish() }
            }
        }
    }

    /** One row, a tenth of the content's height. Nothing interactive, so a drag reaches the area. */
    @Composable
    private fun Spacerish() {
        composegl.ui.layout.Spacer(Modifier.fillMaxWidth().height(tall / 10f))
    }

    // --- what layout worked out ------------------------------------------------------------------

    @Test
    fun `the contents are measured with no ceiling on the axis that scrolls`() {
        val state = ScrollState()
        show { TallList(state) }

        assertEquals(side, state.viewport.height, "the window is the size it was given")
        assertEquals(tall, state.content.height, "and the contents are as tall as they asked to be")
        assertEquals(tall - side, state.maxY)
        assertTrue(state.canScrollY)
        assertFalse(state.canScrollX, "nothing is wider than the window, so sideways goes nowhere")
    }

    @Test
    fun `the area itself is the size it was given, whatever is inside it`() {
        val state = ScrollState()
        show { TallList(state) }

        val area = node("scroll")
        assertEquals(side, area?.height, "a window onto a tall thing is still a window")
    }

    // --- the wheel --------------------------------------------------------------------------------

    @Test
    fun `the wheel scrolls, and stops at both ends rather than running on`() {
        val state = ScrollState()
        show { TallList(state) }

        wheel(100f, 100f, dy = 1f)
        frame()
        assertEquals(48f, state.y, "one notch")

        repeat(100) { wheel(100f, 100f, dy = 1f) }
        frame()
        assertEquals(state.maxY, state.y, "a hundred more notches end at the bottom, not past it")

        repeat(100) { wheel(100f, 100f, dy = -1f) }
        frame()
        assertEquals(0f, state.y, "and back to the top rather than above it")
    }

    @Test
    fun `an area that does not scroll sideways ignores a sideways wheel`() {
        val state = ScrollState()
        show { TallList(state) }

        wheel(100f, 100f, dx = 1f)
        frame()

        assertEquals(0f, state.x)
    }

    // --- dragging ----------------------------------------------------------------------------------

    @Test
    fun `a drag moves the contents with the finger`() {
        val state = ScrollState()
        show { TallList(state) }

        press(100f, 150f, at = 0L)
        drag(100f, 100f, at = 16L)
        frame()

        assertEquals(50f, state.y, "dragged up by fifty, so fifty further down the list")
        release(100f, 100f, at = 16L)
    }

    @Test
    fun `a fling decelerates rather than stopping dead`() {
        val state = ScrollState()
        show { TallList(state) }

        // A quick flick upwards: 120 units in 60 milliseconds is 2000 a second.
        press(100f, 180f, at = 0L)
        drag(100f, 120f, at = 20L)
        drag(100f, 60f, at = 40L)
        release(100f, 60f, at = 40L)
        val atRelease = state.y

        frames(1)
        val first = state.y - atRelease
        assertTrue(first > 0f, "the list carried on after the finger left: moved $first")

        val before = state.y
        frames(1)
        val second = state.y - before
        assertTrue(second > 0f, "and was still moving a frame later")
        assertTrue(second < first, "but slower: $second was not less than $first")

        frames(120)
        assertFalse(state.isFlinging, "and two seconds later it has stopped")
    }

    @Test
    fun `touching a flinging list catches it`() {
        val state = ScrollState()
        show { TallList(state) }

        press(100f, 180f, at = 0L)
        drag(100f, 60f, at = 20L)
        release(100f, 60f, at = 20L)
        frames(1)
        assertTrue(state.isFlinging)

        press(100f, 100f, at = 100L)
        assertFalse(state.isFlinging, "a finger on a moving list stops it where it is")
        release(100f, 100f, at = 100L)
    }

    @Test
    fun `a drag with no timestamps scrolls but does not fling`() {
        val state = ScrollState()
        show { TallList(state) }

        press(100f, 180f)
        drag(100f, 60f)
        release(100f, 60f)

        assertEquals(120f, state.y, "the drag itself still works")
        assertFalse(state.isFlinging, "a backend that does not timestamp its events gets no fling")
    }

    // --- the scrollbar -------------------------------------------------------------------------------

    @Test
    fun `the thumb is as long a share of the bar as the window is of the contents`() {
        val state = ScrollState()
        show { TallList(state) }
        val bar = barLogicLength()

        assertEquals(side * side / tall, bar, 0.001f, "a fifth of the list is a fifth of the bar")
    }

    @Test
    fun `dragging the thumb scrolls the contents`() {
        val state = ScrollState()
        show { TallList(state) }

        // The bar is the right-hand edge of the area; the thumb starts at the top.
        press(side - 4f, 5f)
        drag(side - 4f, 85f)
        frame()

        // Eighty units down a 160-unit travel is half way, and half of 800 is 400.
        assertEquals(state.maxY / 2f, state.y, 1f)
        release(side - 4f, 85f)
    }

    @Test
    fun `pressing the empty part of the track takes the thumb there`() {
        val state = ScrollState()
        show { TallList(state) }

        press(side - 4f, side - 10f)
        frame()

        assertTrue(state.y > state.maxY * 0.8f, "pressed near the bottom, so near the bottom: ${state.y}")
        release(side - 4f, side - 10f)
    }

    @Test
    fun `a list that fits has no bar at all`() {
        val state = ScrollState()
        show {
            ScrollArea(Modifier.size(side), state = state) {
                composegl.ui.layout.Spacer(Modifier.size(50f))
            }
        }

        assertTrue(canvas.calls.isEmpty(), "nothing to scroll, so nothing drawn: ${canvas.calls}")
    }

    @Test
    fun `bars can be turned off without moving anything`() {
        val state = ScrollState()
        show { TallList(state, bars = false) }

        assertTrue(canvas.calls.isEmpty())
        assertEquals(tall - side, state.maxY, "and the contents are laid out exactly as before")
    }

    // --- clipping -------------------------------------------------------------------------------------

    @Test
    fun `the whole area is clipped once rather than once per child`() {
        val state = ScrollState()
        show {
            ScrollArea(Modifier.size(side), state = state) {
                Column(Modifier.fillMaxWidth()) {
                    repeat(10) { index -> Text("row $index") }
                }
            }
        }

        assertEquals(1, clips.pushes, "one scissor for the list, not one for every row")
        val area = Rect.of(0f, 0f, side, side)
        assertTrue(
            canvas.calls.all { it.clip == area },
            "everything drawn inside was clipped to the window: ${canvas.calls.map { it.clip }.toSet()}",
        )
    }

    @Test
    fun `a row scrolled out of the window is not drawn inside it`() {
        val state = ScrollState()
        show {
            ScrollArea(Modifier.size(side), state = state) {
                Column(Modifier.fillMaxWidth()) {
                    repeat(10) { index -> Text("row $index") }
                }
            }
        }
        val rows = canvas.calls.filterIsInstance<DrawCall.Text>().size

        // The rows are about twenty units tall, so ten of them do not all fit in two hundred.
        assertTrue(rows <= 10, "drew $rows runs of text")
    }

    // --- focus -----------------------------------------------------------------------------------------

    @Test
    fun `focus moving to a clipped child scrolls it into view`() {
        val state = ScrollState()
        show {
            ScrollArea(Modifier.size(side), state = state) {
                Column(Modifier.fillMaxWidth()) {
                    repeat(20) { index -> Button("ROW $index", onClick = {}, initialFocus = index == 0) }
                }
            }
        }
        frame()
        assertEquals(0f, state.y, "the first button is already in view")

        // Down the list, past the bottom of the window.
        repeat(12) { key(Key.Down) }

        assertTrue(state.y > 0f, "the list followed the focus: ${state.y}")
        val focused = focus.focused ?: error("nothing is focused")
        val window = node("scroll")?.boundsInRoot ?: error("no scroll area")
        val where = focused.boundsInRoot
        assertTrue(
            where.top >= window.top - 0.5f && where.bottom <= window.bottom + 0.5f,
            "the focused row is inside the window: $where against $window",
        )
    }

    @Test
    fun `focus coming back up scrolls back`() {
        val state = ScrollState()
        show {
            ScrollArea(Modifier.size(side), state = state) {
                Column(Modifier.fillMaxWidth()) {
                    repeat(20) { index -> Button("ROW $index", onClick = {}, initialFocus = index == 0) }
                }
            }
        }
        frame()
        repeat(12) { key(Key.Down) }
        assertTrue(state.y > 0f)

        repeat(12) { key(Key.Up) }

        assertEquals(0f, state.y, "back at the first row, so back at the top")
    }

    // --- correcting itself ---------------------------------------------------------------------------------

    @Test
    fun `contents that shrink pull the scroll back rather than leaving it looking at nothing`() {
        val state = ScrollState()
        var rows by mutableStateOf(10)
        show {
            ScrollArea(Modifier.size(side), state = state) {
                Column(Modifier.fillMaxWidth()) {
                    repeat(rows) { composegl.ui.layout.Spacer(Modifier.fillMaxWidth().height(100f)) }
                }
            }
        }
        state.scrollTo(y = state.maxY)
        frame()
        assertEquals(800f, state.y)

        rows = 3
        frame()

        assertEquals(100f, state.y, "three hundred units of list in a two-hundred window")
    }

    /** How long the bar's thumb is drawn, read back off the canvas. */
    private fun barLogicLength(): Float {
        val drawn = canvas.calls.filterIsInstance<DrawCall.Rectangle>()
        // The track first, then the thumb on top of it.
        return drawn.last().rect.height
    }
}

/**
 * A canvas that counts scissors.
 *
 * The one thing about a scrolling list that cannot be seen in what was drawn: whether it was
 * clipped once or a hundred times.
 */
private class ClipCountingCanvas(private val inner: UiCanvas) : UiCanvas by inner {

    var pushes = 0
        private set

    fun clear() {
        pushes = 0
    }

    override fun pushClip(rect: Rect) {
        pushes++
        inner.pushClip(rect)
    }

    override fun text(layout: TextLayout, at: Offset, colour: Colour) = inner.text(layout, at, colour)

    override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) =
        inner.image(texture, destination, tint, source)
}
