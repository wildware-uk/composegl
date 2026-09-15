package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerType
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import korlibs.event.MouseButton
import korlibs.event.MouseEvent
import korlibs.event.Touch
import korlibs.event.TouchEvent
import korlibs.math.geom.Point
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * KorGE's mouse and touches, translated into the toolkit's, with no KorGE game running.
 *
 * KorGE's events are plain data, and the translator's only dependencies on the engine — the viewport,
 * the clock and the stage's scale — are handed in, so what is left is arithmetic and bookkeeping.
 */
class KorgePointerInputTest {

    /** An input sink that writes down what it was given. */
    private class Recorder(private val handled: Boolean = true) : InputSink {
        val events = mutableListOf<PointerEvent>()
        override fun onPointer(event: PointerEvent): Boolean {
            events += event
            return handled
        }
        override fun onKey(event: KeyEvent) = false
        override fun onText(event: TextEvent) = false
        override fun onGamepad(event: GamepadEvent) = false
    }

    /** A 1000x600 framebuffer showing an 800x600 interface, letterboxed. */
    private val letterboxed = Viewport(design = Size(800f, 600f), physical = Size(1000f, 600f), policy = ScalePolicy.Fit)

    private val oneToOne = Viewport.oneToOne(Size(800f, 600f))

    private val recorder = Recorder()

    private fun input(
        viewport: Viewport = oneToOne,
        sink: InputSink = recorder,
        stageToWindow: (Point) -> Point = { it },
    ) = KorgePointerInput(sink, { viewport }, clock = { 7L }, stageToWindow = stageToWindow)

    private inline fun <reified T : PointerEvent> only(): List<T> = recorder.events.filterIsInstance<T>()

    private fun mouse(type: MouseEvent.Type, x: Int, y: Int, button: MouseButton = MouseButton.NONE) =
        MouseEvent(type = type, x = x, y = y, button = button)

    /** One touch frame, as KorGE's stage hands it over: every finger in it, each with its status. */
    private fun touches(type: TouchEvent.Type, vararg fingers: Triple<Int, Point, Touch.Status>) =
        TouchEvent(type = type).apply {
            startFrame(type)
            fingers.forEach { (id, at, status) -> touch(id, at, status) }
            endFrame()
        }

    // --- coordinates ---

    @Test
    fun `window pixels become design units`() {
        input().onMouse(mouse(MouseEvent.Type.DOWN, 120, 90, MouseButton.LEFT))

        assertEquals(Offset(120f, 90f), only<PointerEvent.Press>().single().position)
    }

    @Test
    fun `a KorGE mouse event already counts framebuffer pixels, so nothing is scaled twice`() {
        // KorGE's AWT window multiplies the mouse by the display's scale before it dispatches
        // (BaseAwtGameWindow.handleMouseEvent), which is the opposite of LibGDX. A 1600x1200
        // framebuffer for an 800x600 design is scale 2, and the framebuffer's middle is the design's.
        val retina = Viewport(design = Size(800f, 600f), physical = Size(1600f, 1200f), policy = ScalePolicy.Fit)

        input(retina).onMouse(mouse(MouseEvent.Type.DOWN, 800, 600, MouseButton.LEFT))

        assertEquals(Offset(400f, 300f), only<PointerEvent.Press>().single().position)
    }

    @Test
    fun `the letterbox is undone too`() {
        input(letterboxed).onMouse(mouse(MouseEvent.Type.DOWN, 150, 30, MouseButton.LEFT))

        assertEquals(Offset(50f, 30f), only<PointerEvent.Press>().single().position)
    }

    @Test
    fun `a press on the letterbox bar arrives with a negative coordinate, not swallowed`() {
        input(letterboxed).onMouse(mouse(MouseEvent.Type.DOWN, 20, 30, MouseButton.LEFT))

        assertEquals(-80f, only<PointerEvent.Press>().single().position.x)
    }

    @Test
    fun `the safe area does not move the pointer`() {
        val inset = Viewport(Size(800f, 600f), Size(800f, 600f), ScalePolicy.Fit, safeArea = Padding.all(40f))
        input(inset).onMouse(mouse(MouseEvent.Type.DOWN, 10, 10, MouseButton.LEFT))

        assertEquals(Offset(10f, 10f), only<PointerEvent.Press>().single().position)
    }

    // --- buttons ---

    @Test
    fun `the three buttons the toolkit has are named`() {
        val adapter = input()
        adapter.onMouse(mouse(MouseEvent.Type.DOWN, 0, 0, MouseButton.LEFT))
        adapter.onMouse(mouse(MouseEvent.Type.DOWN, 0, 0, MouseButton.RIGHT))
        adapter.onMouse(mouse(MouseEvent.Type.DOWN, 0, 0, MouseButton.MIDDLE))

        assertEquals(
            listOf(PointerButton.Primary, PointerButton.Secondary, PointerButton.Tertiary),
            only<PointerEvent.Press>().map { it.button },
        )
    }

    @Test
    fun `back and forward are declined rather than mapped onto something else`() {
        val adapter = input()

        assertFalse(adapter.onMouse(mouse(MouseEvent.Type.DOWN, 0, 0, MouseButton.BUTTON4)))
        assertFalse(adapter.onMouse(mouse(MouseEvent.Type.UP, 0, 0, MouseButton.BUTTON5)))
        assertTrue(recorder.events.isEmpty(), "the game keeps them")
    }

    @Test
    fun `whether the toolkit used the event is passed straight back`() {
        assertFalse(input(sink = Recorder(handled = false)).onMouse(mouse(MouseEvent.Type.DOWN, 0, 0, MouseButton.LEFT)))
        assertTrue(input().onMouse(mouse(MouseEvent.Type.DOWN, 0, 0, MouseButton.LEFT)))
    }

    @Test
    fun `KorGE's own click and enter are not events the toolkit wants`() {
        val adapter = input()
        assertFalse(adapter.onMouse(mouse(MouseEvent.Type.CLICK, 5, 5, MouseButton.LEFT)))
        assertFalse(adapter.onMouse(mouse(MouseEvent.Type.ENTER, 5, 5)))
        assertTrue(recorder.events.isEmpty(), "the router decides what a click is")
    }

    // --- a drag is not a hover ---

    @Test
    fun `moving with nothing down is a move with nothing held`() {
        input().onMouse(mouse(MouseEvent.Type.MOVE, 10, 20))

        val move = only<PointerEvent.Move>().single()
        assertEquals(emptySet<PointerButton>(), move.pressed)
        assertEquals(PointerId.Mouse, move.pointerId)
        assertEquals(PointerType.Mouse, move.type)
    }

    @Test
    fun `dragging reports which buttons are still down`() {
        val adapter = input()
        adapter.onMouse(mouse(MouseEvent.Type.DOWN, 0, 0, MouseButton.LEFT))
        adapter.onMouse(mouse(MouseEvent.Type.DOWN, 0, 0, MouseButton.RIGHT))
        adapter.onMouse(mouse(MouseEvent.Type.DRAG, 5, 5))
        adapter.onMouse(mouse(MouseEvent.Type.UP, 5, 5, MouseButton.RIGHT))
        adapter.onMouse(mouse(MouseEvent.Type.DRAG, 6, 6))

        val moves = only<PointerEvent.Move>()
        assertEquals(setOf(PointerButton.Primary, PointerButton.Secondary), moves.first().pressed)
        assertEquals(setOf(PointerButton.Primary), moves.last().pressed)
    }

    @Test
    fun `a press that ends outside the window still releases`() {
        val adapter = input()
        adapter.onMouse(mouse(MouseEvent.Type.DOWN, 100, 100, MouseButton.LEFT))
        adapter.onMouse(mouse(MouseEvent.Type.DRAG, -60, 900))
        adapter.onMouse(mouse(MouseEvent.Type.UP, -60, 900, MouseButton.LEFT))

        assertEquals(listOf("Press", "Move", "Release"), recorder.events.map { it::class.simpleName })
        assertEquals(Offset(-60f, 900f), only<PointerEvent.Release>().single().position)
    }

    // --- the copies KorGE makes ---

    @Test
    fun `the mouse KorGE makes out of a touch is ignored, so a tap is not two taps`() {
        val adapter = input()
        assertFalse(adapter.onMouse(MouseEvent(type = MouseEvent.Type.DOWN, button = MouseButton.LEFT, emulated = true)))
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun `the touch KorGE makes out of the mouse is ignored, so a click is not two clicks`() {
        val adapter = input()
        val copy = touches(TouchEvent.Type.START, Triple(0, Point(5, 5), Touch.Status.ADD)).apply { emulated = true }

        assertFalse(adapter.onTouch(copy))
        assertTrue(recorder.events.isEmpty())
    }

    // --- touch ---

    @Test
    fun `a finger is a touch pointer of its own, and its position is brought back to the window`() {
        // The stage hands touches over in its own units. Half-size units, as a stage scaled to a
        // window twice its virtual size would.
        input(stageToWindow = { Point(it.x * 2, it.y * 2) })
            .onTouch(touches(TouchEvent.Type.START, Triple(3, Point(60, 45), Touch.Status.ADD)))

        val press = only<PointerEvent.Press>().single()
        assertEquals(Offset(120f, 90f), press.position)
        assertEquals(PointerType.Touch, press.type)
        assertTrue(press.pointerId != PointerId.Mouse, "a finger must not share the mouse's hover and capture")
    }

    @Test
    fun `two fingers keep their own presses, their own drags and their own releases`() {
        val adapter = input()
        adapter.onTouch(touches(TouchEvent.Type.START, Triple(0, Point(10, 10), Touch.Status.ADD)))
        adapter.onTouch(
            touches(TouchEvent.Type.START, Triple(0, Point(10, 10), Touch.Status.KEEP), Triple(1, Point(200, 300), Touch.Status.ADD)),
        )
        adapter.onTouch(
            touches(TouchEvent.Type.MOVE, Triple(0, Point(20, 20), Touch.Status.KEEP), Triple(1, Point(210, 310), Touch.Status.KEEP)),
        )
        adapter.onTouch(
            touches(TouchEvent.Type.END, Triple(0, Point(20, 20), Touch.Status.REMOVE), Triple(1, Point(210, 310), Touch.Status.KEEP)),
        )

        assertEquals(2, only<PointerEvent.Press>().size, "a finger already down is not pressed again")
        assertEquals(2, only<PointerEvent.Move>().size)
        assertEquals(1, only<PointerEvent.Release>().size, "the second finger is still down")
        assertTrue(only<PointerEvent.Move>().all { it.pressed == setOf(PointerButton.Primary) })
        val first = only<PointerEvent.Press>()[0].pointerId
        val second = only<PointerEvent.Press>()[1].pointerId
        assertTrue(first != second)
        assertEquals(first, only<PointerEvent.Release>().single().pointerId)
    }

    // --- cancelling ---

    @Test
    fun `losing the window cancels every gesture in progress, without a click`() {
        val adapter = input()
        adapter.onMouse(mouse(MouseEvent.Type.DOWN, 30, 40, MouseButton.LEFT))
        adapter.onTouch(touches(TouchEvent.Type.START, Triple(0, Point(80, 90), Touch.Status.ADD)))

        adapter.cancelAll()

        val cancels = only<PointerEvent.Cancel>()
        assertEquals(2, cancels.size)
        assertEquals(Offset(30f, 40f), cancels.first { it.pointerId == PointerId.Mouse }.position)
        assertTrue(only<PointerEvent.Release>().isEmpty(), "a cancelled gesture is not a click")

        adapter.cancelAll()
        assertEquals(2, only<PointerEvent.Cancel>().size, "and nothing is left holding a capture")

        adapter.onMouse(mouse(MouseEvent.Type.DRAG, 31, 41))
        assertEquals(emptySet<PointerButton>(), only<PointerEvent.Move>().single().pressed, "the button is not still down")
    }

    @Test
    fun `leaving the window ends hover and leaves a drag alone`() {
        val adapter = input()
        adapter.onMouse(mouse(MouseEvent.Type.DOWN, 30, 40, MouseButton.LEFT))
        adapter.onMouse(mouse(MouseEvent.Type.EXIT, 30, 40))
        adapter.onMouse(mouse(MouseEvent.Type.DRAG, 35, 45))

        assertEquals(1, only<PointerEvent.Exit>().size)
        assertEquals(setOf(PointerButton.Primary), only<PointerEvent.Move>().single().pressed)
    }

    // --- the wheel ---

    @Test
    fun `the wheel scrolls where the mouse is, by the notches KorGE reports`() {
        val adapter = input()
        adapter.onMouse(
            MouseEvent(type = MouseEvent.Type.SCROLL, x = 70, y = 80).apply {
                setScrollDelta(MouseEvent.ScrollDeltaMode.PIXEL, 0f, 1f, 0f)
            },
        )

        val scroll = only<PointerEvent.Scroll>().single()
        assertEquals(Offset(70f, 80f), scroll.position)
        assertEquals(Offset(0f, 1f), scroll.delta)
    }
}
