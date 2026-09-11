package uk.wildware.composegl.ui.input

import uk.wildware.composegl.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The input model, exercised with no engine anywhere.
 *
 * That is the point of the whole file: these are the events a keyboard, a mouse and a pad produce,
 * written by hand, with LibGDX absent. If this ever needs an engine to compile, the toolkit has
 * stopped being portable.
 */
class InputModelTest {

    @Test
    fun `modifiers combine and read back`() {
        val both = Modifiers.Shift + Modifiers.Control

        assertTrue(both.shift)
        assertTrue(both.control)
        assertFalse(both.alt)
        assertFalse(both.none)
        assertTrue(Modifiers.None.none)
    }

    @Test
    fun `the shortcut key follows the platform`() {
        val original = Modifiers.isMac
        try {
            Modifiers.isMac = true
            assertTrue(Modifiers.Meta.isPrimary, "Command on a Mac")
            assertFalse(Modifiers.Control.isPrimary)

            Modifiers.isMac = false
            assertTrue(Modifiers.Control.isPrimary, "Control everywhere else")
            assertFalse(Modifiers.Meta.isPrimary)
        } finally {
            Modifiers.isMac = original
        }
    }

    @Test
    fun `a key prints its name, and an unknown one says so instead of crashing`() {
        assertEquals("Key.Backspace", Key.Backspace.toString())
        assertEquals("Key.Digit7", Key.Digit7.toString())
        assertEquals("Key.F11", Key.F11.toString())
        assertEquals("Key.Z", Key.Z.toString())
        assertEquals("Key(unknown 9999)", Key(9999).toString())
    }

    @Test
    fun `no two named keys share a code`() {
        // A duplicate here would silently make two different keys the same key, and the symptom
        // would be a keyboard that mostly works.
        // A value class compiles its getters down to the underlying Int, so plain Java reflection
        // finds every named key without needing kotlin-reflect on the test classpath.
        val named = Key.Companion.javaClass.declaredMethods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 }
            .filter { it.returnType == Int::class.javaPrimitiveType }
            .associate { it.name.removePrefix("get") to it.invoke(Key.Companion) as Int }

        assertTrue(named.size > 80, "expected the whole keyboard, found ${named.size}")

        val duplicates = named.entries.groupBy { it.value }.filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "these keys share a code: $duplicates")
    }

    @Test
    fun `a hover is a move with nothing pressed, and a drag is a move with a button held`() {
        val hover = PointerEvent.Move(PointerId.Mouse, Offset(10f, 10f))
        val drag = PointerEvent.Move(PointerId.Mouse, Offset(10f, 10f), pressed = setOf(PointerButton.Primary))

        assertTrue(hover.pressed.isEmpty())
        assertEquals(setOf(PointerButton.Primary), drag.pressed)
    }

    @Test
    fun `fingers are told apart by id`() {
        val first = PointerEvent.Press(PointerId(1), Offset(0f, 0f), type = PointerType.Touch)
        val second = PointerEvent.Press(PointerId(2), Offset(50f, 0f), type = PointerType.Touch)

        assertFalse(first.pointerId == second.pointerId)
    }

    @Test
    fun `an in-world panel produces the same events a mouse does`() {
        val ray = PointerEvent.Press(PointerId.Mouse, Offset(20f, 30f), type = PointerType.Ray)

        // Nothing about the event says "special case" except the type, which exists only so the
        // interface can choose to look different, never so the toolkit can behave differently.
        assertEquals(Offset(20f, 30f), ray.position)
        assertEquals(PointerButton.Primary, ray.button)
    }

    @Test
    fun `a repeat is a key event that says so`() {
        val first = KeyEvent(Key.Backspace, KeyEventType.Down)
        val held = KeyEvent(Key.Backspace, KeyEventType.Down, repeat = true)

        assertFalse(first.repeat)
        assertTrue(held.repeat)

        // The bug this models: LibGDX reports a held key only as repeated characters, never as a
        // second key-down. A backend has to synthesise this event, and the toolkit has to have
        // somewhere to put it.
    }

    @Test
    fun `text and keys are different things`() {
        val shiftThenA = listOf(
            KeyEvent(Key.Shift, KeyEventType.Down),
            KeyEvent(Key.A, KeyEventType.Down, Modifiers.Shift),
        )
        val whatGotTyped = TextEvent("A")

        assertEquals(2, shiftThenA.size)
        assertEquals("A", whatGotTyped.text, "two key events, one character")
    }

    @Test
    fun `a sink can be driven by hand, with no engine present`() {
        val seen = mutableListOf<String>()
        val sink = object : InputSink {
            override fun onPointer(event: PointerEvent) = true.also { seen += "pointer" }
            override fun onKey(event: KeyEvent) = true.also { seen += "key" }
            override fun onText(event: TextEvent) = true.also { seen += "text" }
            override fun onGamepad(event: GamepadEvent) = true.also { seen += "gamepad" }
        }

        sink.onPointer(PointerEvent.Press(PointerId.Mouse, Offset.Zero))
        sink.onKey(KeyEvent(Key.A, KeyEventType.Down))
        sink.onText(TextEvent("a"))
        sink.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))

        assertEquals(listOf("pointer", "key", "text", "gamepad"), seen)
    }
}
