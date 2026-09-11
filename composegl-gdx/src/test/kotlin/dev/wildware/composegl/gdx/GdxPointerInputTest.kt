package dev.wildware.composegl.gdx

import com.badlogic.gdx.Input.Buttons
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

/**
 * The LibGDX pointer adapter, with no LibGDX running.
 *
 * Nothing here needs a window: the adapter's dependencies on the engine — the HDPI scale and the
 * clock — are handed in, so what is left is arithmetic and bookkeeping. That is the whole point of
 * the seam.
 */
class GdxPointerInputTest {

    /** 400 logical units of window showing an 800x600 interface, letterboxed. */
    private val letterboxed = Viewport(
        design = Size(800f, 600f),
        physical = Size(1000f, 600f),
        policy = ScalePolicy.Fit,
    )

    private val oneToOne = Viewport.oneToOne(Size(800f, 600f))

    private val recorder = Recorder()

    private fun input(
        viewport: Viewport = oneToOne,
        scale: Float = 1f,
        type: PointerType = PointerType.Mouse,
        sink: InputSink = recorder,
    ) = GdxPointerInput(sink, { viewport }, type, hdpiScale = { scale }, clock = { 7L })

    private inline fun <reified T : PointerEvent> only(): List<T> = recorder.events.filterIsInstance<T>()

    // --- coordinates ---

    @Test
    fun `window units become design units`() {
        input().touchDown(120, 90, 0, Buttons.LEFT)

        assertEquals(Offset(120f, 90f), only<PointerEvent.Press>().single().position)
    }

    @Test
    fun `a scaled display is undone before the viewport sees it`() {
        // A Retina window: 120 logical units is 240 framebuffer pixels.
        input(scale = 2f).touchDown(120, 90, 0, Buttons.LEFT)

        assertEquals(Offset(240f, 180f), only<PointerEvent.Press>().single().position)
    }

    @Test
    fun `the letterbox is undone too`() {
        // 1000x600 of window for an 800x600 interface: scale 1, and 100 units of bar each side.
        input(letterboxed).touchDown(150, 30, 0, Buttons.LEFT)

        assertEquals(Offset(50f, 30f), only<PointerEvent.Press>().single().position)
    }

    @Test
    fun `a press on the letterbox bar arrives with a negative coordinate, not swallowed`() {
        input(letterboxed).touchDown(20, 30, 0, Buttons.LEFT)

        assertEquals(-80f, only<PointerEvent.Press>().single().position.x)
    }

    // --- buttons ---

    @Test
    fun `the three buttons the toolkit has are named`() {
        val adapter = input()
        adapter.touchDown(0, 0, 0, Buttons.LEFT)
        adapter.touchDown(0, 0, 0, Buttons.RIGHT)
        adapter.touchDown(0, 0, 0, Buttons.MIDDLE)

        assertEquals(
            listOf(PointerButton.Primary, PointerButton.Secondary, PointerButton.Tertiary),
            only<PointerEvent.Press>().map { it.button },
        )
    }

    @Test
    fun `back and forward are declined rather than mapped onto something else`() {
        val adapter = input()

        assertEquals(false, adapter.touchDown(0, 0, 0, Buttons.BACK))
        assertEquals(false, adapter.touchUp(0, 0, 0, Buttons.FORWARD))
        assertTrue(recorder.events.isEmpty(), "the game keeps them")
    }

    @Test
    fun `whether the toolkit used the event is passed straight back to LibGDX`() {
        val ignored = Recorder(handled = false)

        assertEquals(false, input(sink = ignored).touchDown(0, 0, 0, Buttons.LEFT))
        assertEquals(true, input().touchDown(0, 0, 0, Buttons.LEFT))
    }

    // --- a drag is not a hover ---

    @Test
    fun `moving with nothing down is a move with nothing held`() {
        input().mouseMoved(10, 20)

        val move = only<PointerEvent.Move>().single()
        assertEquals(emptySet<PointerButton>(), move.pressed)
        assertEquals(PointerId.Mouse, move.pointerId)
    }

    @Test
    fun `dragging reports which buttons are still down`() {
        val adapter = input()
        adapter.touchDown(0, 0, 0, Buttons.LEFT)
        adapter.touchDown(0, 0, 0, Buttons.RIGHT)
        adapter.touchDragged(5, 5, 0)
        adapter.touchUp(5, 5, 0, Buttons.RIGHT)
        adapter.touchDragged(6, 6, 0)

        val moves = only<PointerEvent.Move>()
        assertEquals(setOf(PointerButton.Primary, PointerButton.Secondary), moves.first().pressed)
        assertEquals(setOf(PointerButton.Primary), moves.last().pressed)
    }

    // --- the sequence the whole thing exists for ---

    @Test
    fun `a press that ends outside the window still releases`() {
        val adapter = input()
        adapter.touchDown(100, 100, 0, Buttons.LEFT)
        adapter.touchDragged(400, 400, 0)
        // Off the left of the window and past the bottom: the platform still routes the release to
        // whoever got the press, and so must this.
        adapter.touchDragged(-60, 900, 0)
        adapter.touchUp(-60, 900, 0, Buttons.LEFT)

        assertEquals(
            listOf("Press", "Move", "Move", "Release"),
            recorder.events.map { it::class.simpleName },
        )
        assertEquals(Offset(-60f, 900f), only<PointerEvent.Release>().single().position)
    }

    @Test
    fun `two fingers keep their own presses, their own drags and their own buttons`() {
        val adapter = input(type = PointerType.Touch)
        adapter.touchDown(10, 10, 0, Buttons.LEFT)
        adapter.touchDown(200, 300, 1, Buttons.LEFT)
        adapter.touchDragged(20, 20, 0)
        adapter.touchUp(10, 10, 0, Buttons.LEFT)
        adapter.touchDragged(210, 310, 1)

        val ids = recorder.events.map { it.pointerId.value }
        assertEquals(listOf(0L, 1L, 0L, 0L, 1L), ids)
        assertTrue(recorder.events.all { it.type == PointerType.Touch })

        // The first finger let go; the second is still holding on.
        assertEquals(setOf(PointerButton.Primary), only<PointerEvent.Move>().last().pressed)
    }

    @Test
    fun `losing the window cancels every gesture in progress, without a click`() {
        val adapter = input()
        adapter.touchDown(30, 40, 0, Buttons.LEFT)
        adapter.touchDown(80, 90, 1, Buttons.LEFT)

        adapter.cancelAll()

        val cancels = only<PointerEvent.Cancel>()
        assertEquals(listOf(0L, 1L), cancels.map { it.pointerId.value }.sorted())
        assertEquals(Offset(30f, 40f), cancels.first { it.pointerId.value == 0L }.position)
        assertTrue(only<PointerEvent.Release>().isEmpty(), "a cancelled gesture is not a click")

        // And nothing is left holding a capture.
        adapter.cancelAll()
        assertEquals(2, only<PointerEvent.Cancel>().size)
    }

    @Test
    fun `leaving the window ends hover and leaves a drag alone`() {
        val adapter = input()
        adapter.touchDown(30, 40, 0, Buttons.LEFT)
        adapter.exited()
        adapter.touchDragged(35, 45, 0)

        assertEquals(1, only<PointerEvent.Exit>().size)
        assertEquals(
            setOf(PointerButton.Primary),
            only<PointerEvent.Move>().single().pressed,
            "the press survived the mouse leaving",
        )
    }

    @Test
    fun `a cancelled touch drops its buttons`() {
        val adapter = input()
        adapter.touchDown(30, 40, 0, Buttons.LEFT)
        adapter.touchCancelled(30, 40, 0, Buttons.LEFT)
        adapter.touchDragged(31, 41, 0)

        assertEquals(1, only<PointerEvent.Cancel>().size)
        assertEquals(emptySet<PointerButton>(), only<PointerEvent.Move>().single().pressed)
    }

    // --- the wheel ---

    @Test
    fun `the wheel scrolls where the mouse last was`() {
        val adapter = input()
        adapter.mouseMoved(70, 80)
        adapter.scrolled(0f, 1f)

        val scroll = only<PointerEvent.Scroll>().single()
        assertEquals(Offset(70f, 80f), scroll.position)
        assertEquals(Offset(0f, 1f), scroll.delta)
    }

    @Test
    fun `a wheel before the mouse has ever moved still reports something sensible`() {
        input().scrolled(0f, -1f)

        assertEquals(Offset.Zero, only<PointerEvent.Scroll>().single().position)
    }

    @Test
    fun `the safe area does not move the pointer`() {
        // Insets are for laying out, not for aiming. A notch does not change where a finger is.
        val inset = Viewport(
            design = Size(800f, 600f),
            physical = Size(800f, 600f),
            policy = ScalePolicy.Fit,
            safeArea = Padding.all(40f),
        )
        input(inset).touchDown(10, 10, 0, Buttons.LEFT)

        assertEquals(Offset(10f, 10f), only<PointerEvent.Press>().single().position)
    }

}
