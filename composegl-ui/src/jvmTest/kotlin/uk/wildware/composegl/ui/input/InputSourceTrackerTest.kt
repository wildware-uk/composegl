package uk.wildware.composegl.ui.input

import uk.wildware.composegl.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Last one wins, and the two questions an interface actually asks about it. */
class InputSourceTrackerTest {

    private val tracker = InputSourceTracker()

    private fun mouse(x: Float = 1f) =
        PointerEvent.Move(PointerId.Mouse, Offset(x, 0f), type = PointerType.Mouse)

    @Test
    fun `picking up a pad switches everything, and touching the mouse switches it back`() {
        assertEquals(InputSource.Mouse, tracker.current)

        tracker.saw(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))
        assertEquals(InputSource.Gamepad, tracker.current)
        assertTrue(tracker.showsFocusRing)
        assertFalse(tracker.isPointing)

        tracker.saw(mouse())
        assertEquals(InputSource.Mouse, tracker.current)
        assertFalse(tracker.showsFocusRing, "a ring is a cursor for people with no cursor")
        assertTrue(tracker.isPointing)
    }

    @Test
    fun `a keyboard is its own source, and typing counts as one`() {
        tracker.saw(KeyEvent(Key.A, KeyEventType.Down))
        assertEquals(InputSource.Keyboard, tracker.current)
        assertTrue(tracker.showsFocusRing)

        tracker.saw(mouse())
        tracker.saw(TextEvent("a"))
        assertEquals(InputSource.Keyboard, tracker.current)
    }

    @Test
    fun `a finger is not a mouse`() {
        tracker.saw(PointerEvent.Press(PointerId(1), Offset.Zero, type = PointerType.Touch))
        assertEquals(InputSource.Touch, tracker.current)
        assertTrue(tracker.isPointing, "there is still something to point with")
        assertFalse(tracker.showsFocusRing)
    }

    @Test
    fun `the mouse leaving the window says nothing about what the player picked up`() {
        tracker.saw(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))
        tracker.saw(PointerEvent.Exit(PointerId.Mouse, Offset(-1f, -1f)))

        assertEquals(InputSource.Gamepad, tracker.current, "a cursor drifting off screen is not a choice")
    }

    @Test
    fun `a console can start on a pad`() {
        val console = InputSourceTracker(InputSource.Gamepad)
        assertTrue(console.showsFocusRing, "the menu has a cursor before anybody touches anything")
    }

    @Test
    fun `wrapping a sink tracks the source and changes nothing else`() {
        val seen = mutableListOf<Any>()
        val sink = object : InputSink {
            override fun onPointer(event: PointerEvent) = true.also { seen += event }
            override fun onKey(event: KeyEvent) = false.also { seen += event }
            override fun onText(event: TextEvent) = false.also { seen += event }
            override fun onGamepad(event: GamepadEvent) = true.also { seen += event }
        }
        val wrapped = SourceAware(tracker, sink)

        assertTrue(wrapped.onPointer(mouse()))
        assertEquals(InputSource.Mouse, tracker.current)

        assertFalse(wrapped.onKey(KeyEvent(Key.Tab, KeyEventType.Down)))
        assertEquals(InputSource.Keyboard, tracker.current)

        assertEquals(2, seen.size, "the answers and the events are the sink's, untouched")
    }
}
