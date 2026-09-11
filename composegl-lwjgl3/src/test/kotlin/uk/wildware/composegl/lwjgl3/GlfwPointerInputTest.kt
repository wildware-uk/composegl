package uk.wildware.composegl.lwjgl3

import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.input.GamepadEvent
import uk.wildware.composegl.ui.input.InputSink
import uk.wildware.composegl.ui.input.KeyEvent
import uk.wildware.composegl.ui.input.PointerButton
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.PointerId
import uk.wildware.composegl.ui.input.PointerType
import uk.wildware.composegl.ui.input.TextEvent
import uk.wildware.composegl.ui.layout.ScalePolicy
import uk.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW

/**
 * The translation from GLFW's mouse to the toolkit's pointer, with no window.
 *
 * Every method the translator exposes can be called directly, so the coordinate maths, the button
 * names and the held-button bookkeeping are all testable on a plain JVM. That is deliberate: a
 * test that needs a window to check arithmetic is a test that gets deleted the first time CI has
 * no display.
 */
class GlfwPointerInputTest {

    /** Remembers everything, consumes nothing. */
    private class Recorder : InputSink {
        val events = mutableListOf<PointerEvent>()
        override fun onPointer(event: PointerEvent): Boolean {
            events += event
            return false
        }
        override fun onKey(event: KeyEvent) = false
        override fun onText(event: TextEvent) = false
        override fun onGamepad(event: GamepadEvent) = false
    }

    private val recorder = Recorder()

    /** One virtual pixel per window unit, with no letterbox. */
    private val oneToOne = Viewport.oneToOne(Size(800f, 600f))

    private fun input(
        viewport: Viewport = oneToOne,
        scale: Float = 1f,
        type: PointerType = PointerType.Mouse,
    ) = GlfwPointerInput(
        sink = recorder,
        viewport = { viewport },
        type = type,
        pixelScale = { scale },
        clock = { 1_000L },
    )

    private inline fun <reified T : PointerEvent> event(at: Int): T =
        assertInstanceOf(T::class.java, recorder.events[at])

    @Test
    fun `a move becomes a move, with nothing held`() {
        input().moved(120.0, 64.0)

        val move = event<PointerEvent.Move>(0)
        assertEquals(Offset(120f, 64f), move.position)
        assertTrue(move.pressed.isEmpty(), "hover is a move with nothing down")
        assertEquals(PointerId.Mouse, move.pointerId)
        assertEquals(1_000L, move.timeMillis)
    }

    @Test
    fun `a press and a release name the button`() {
        val input = input()
        input.moved(10.0, 10.0)
        input.button(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS)
        input.button(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_RELEASE)

        assertEquals(PointerButton.Secondary, event<PointerEvent.Press>(1).button)
        assertEquals(PointerButton.Secondary, event<PointerEvent.Release>(2).button)
    }

    @Test
    fun `a press happens where the cursor was last seen`() {
        val input = input()
        input.moved(300.0, 200.0)
        input.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)

        // GLFW's button callback carries no position of its own, which is the whole reason the
        // translator remembers one.
        assertEquals(Offset(300f, 200f), event<PointerEvent.Press>(1).position)
    }

    @Test
    fun `a drag is a move that says what is still held`() {
        val input = input()
        input.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
        input.moved(40.0, 40.0)

        assertEquals(setOf(PointerButton.Primary), event<PointerEvent.Move>(1).pressed)
    }

    @Test
    fun `two buttons at once are both reported`() {
        val input = input()
        input.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
        input.button(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS)
        input.moved(40.0, 40.0)

        assertEquals(
            setOf(PointerButton.Primary, PointerButton.Secondary),
            event<PointerEvent.Move>(2).pressed,
        )
    }

    @Test
    fun `a button the toolkit has no name for is declined`() {
        // Back and forward mean nothing in an interface, and mapping them onto something that
        // looks similar would put them in front of a game that has its own use for them.
        assertFalse(input().button(GLFW.GLFW_MOUSE_BUTTON_4, GLFW.GLFW_PRESS))
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun `the wheel is turned into a scroll the toolkit's way up`() {
        input().scrolled(0.0, 1.0)

        // GLFW reports which way the wheel turned; the toolkit reports which way the content
        // should move, and they are opposites.
        assertEquals(Offset(0f, -1f), event<PointerEvent.Scroll>(0).delta)
    }

    @Test
    fun `a scaled display is undone before the viewport sees it`() {
        // The cursor is reported in logical window units and the viewport is measured in
        // framebuffer pixels, so on a 2x display the two have to be reconciled first.
        input(viewport = Viewport.oneToOne(Size(1600f, 1200f)), scale = 2f).moved(100.0, 50.0)

        assertEquals(Offset(200f, 100f), event<PointerEvent.Move>(0).position)
    }

    @Test
    fun `a letterbox is undone, and a point on the bar reads as outside`() {
        val letterboxed = Viewport(
            design = Size(800f, 600f),
            physical = Size(1600f, 600f),
            policy = ScalePolicy.Fit,
        )
        val input = input(viewport = letterboxed)
        input.moved(400.0, 0.0)
        input.moved(20.0, 20.0)

        assertEquals(Offset(0f, 0f), event<PointerEvent.Move>(0).position, "the left edge of the design")
        assertTrue(event<PointerEvent.Move>(1).position.x < 0f, "a point on the bar is outside it")
    }

    @Test
    fun `a release outside the window still arrives`() {
        val input = input()
        input.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
        input.moved(-40.0, 900.0)
        input.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)

        // A widget that captured the pointer has to hear about the release wherever it happens.
        // A toolkit that filters these is a toolkit whose buttons stick down.
        assertEquals(Offset(-40f, 900f), event<PointerEvent.Release>(2).position)
    }

    @Test
    fun `leaving the window ends hover without ending a drag`() {
        val input = input()
        input.moved(10.0, 10.0)
        input.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
        input.exited()
        input.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)

        event<PointerEvent.Exit>(2)
        // Still a release rather than nothing: the drag survived the cursor leaving.
        assertEquals(PointerButton.Primary, event<PointerEvent.Release>(3).button)
    }

    @Test
    fun `losing focus abandons the gesture without firing a click`() {
        val input = input()
        input.moved(10.0, 10.0)
        input.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
        input.cancelAll()

        event<PointerEvent.Cancel>(2)
        assertEquals(3, recorder.events.size, "a cancelled gesture must not also release")
    }

    @Test
    fun `cancelling when nothing is held does nothing at all`() {
        // Called on every focus change, so it has to be free when there is nothing to cancel.
        assertFalse(input().cancelAll())
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun `a cancelled button is no longer held`() {
        val input = input()
        input.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
        input.cancelAll()
        input.moved(10.0, 10.0)

        assertTrue(event<PointerEvent.Move>(2).pressed.isEmpty())
    }

    @Test
    fun `what is doing the pointing is passed through`() {
        input(type = PointerType.Ray).moved(1.0, 1.0)

        // An in-world panel hit by a raycast is not a special case; it is a pointer with a
        // different name on it.
        assertEquals(PointerType.Ray, event<PointerEvent.Move>(0).type)
    }
}
