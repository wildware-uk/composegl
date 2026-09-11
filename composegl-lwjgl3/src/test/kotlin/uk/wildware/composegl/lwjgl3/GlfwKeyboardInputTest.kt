package uk.wildware.composegl.lwjgl3

import uk.wildware.composegl.ui.input.GamepadEvent
import uk.wildware.composegl.ui.input.InputSink
import uk.wildware.composegl.ui.input.Key
import uk.wildware.composegl.ui.input.KeyEvent
import uk.wildware.composegl.ui.input.KeyEventType
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.TextEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW

/**
 * The translation from GLFW's keyboard to the toolkit's, with no window.
 *
 * The second of two independent translations of the same contract. Where it agrees with the LibGDX
 * one the contract is probably right; where it would disagree, the contract is wrong.
 */
class GlfwKeyboardInputTest {

    /** Remembers everything, consumes nothing. */
    private class Recorder : InputSink {
        val keys = mutableListOf<KeyEvent>()
        val text = mutableListOf<String>()
        override fun onPointer(event: PointerEvent) = false
        override fun onGamepad(event: GamepadEvent) = false
        override fun onKey(event: KeyEvent): Boolean {
            keys += event
            return false
        }
        override fun onText(event: TextEvent): Boolean {
            text += event.text
            return false
        }
    }

    private val recorder = Recorder()
    private val input = GlfwKeyboardInput(recorder)

    private fun press(key: Int, mods: Int = 0) = input.key(key, GLFW.GLFW_PRESS, mods)
    private fun release(key: Int, mods: Int = 0) = input.key(key, GLFW.GLFW_RELEASE, mods)

    @Test
    fun `a key is named by what it is, not by GLFW's number`() {
        press(GLFW.GLFW_KEY_ESCAPE)
        release(GLFW.GLFW_KEY_ESCAPE)
        press(GLFW.GLFW_KEY_A)
        press(GLFW.GLFW_KEY_KP_7)

        assertEquals(
            listOf(Key.Escape, Key.Escape, Key.A, Key.Digit7),
            recorder.keys.map { it.key },
        )
        assertEquals(
            listOf(KeyEventType.Down, KeyEventType.Up, KeyEventType.Down, KeyEventType.Down),
            recorder.keys.map { it.type },
        )
    }

    @Test
    fun `both halves of a modifier have one name`() {
        press(GLFW.GLFW_KEY_LEFT_SHIFT)
        press(GLFW.GLFW_KEY_RIGHT_SHIFT)

        assertEquals(
            listOf(Key.Shift, Key.Shift),
            recorder.keys.map { it.key },
            "no interface has ever wanted to know which Shift it was",
        )
    }

    @Test
    fun `a key nobody has a name for still arrives`() {
        press(GLFW.GLFW_KEY_F25)

        assertEquals(listOf(Key.Unknown), recorder.keys.map { it.key })
    }

    @Test
    fun `modifiers come from GLFW rather than from bookkeeping`() {
        press(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT)

        val held = recorder.keys.single().modifiers
        assertTrue(held.control && held.shift)
        assertFalse(held.alt || held.meta)
    }

    @Test
    fun `GLFW says outright when it is repeating`() {
        press(GLFW.GLFW_KEY_ENTER)
        input.key(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_REPEAT, 0)
        release(GLFW.GLFW_KEY_ENTER)

        assertEquals(listOf(false, true, false), recorder.keys.map { it.repeat })
        assertEquals(
            listOf(KeyEventType.Down, KeyEventType.Down, KeyEventType.Up),
            recorder.keys.map { it.type },
            "a repeat is the key still being down, not a second press",
        )
    }

    @Test
    fun `text is text and control characters are not`() {
        input.typed('a'.code)
        input.typed(0x08)
        input.typed(0x7F)
        input.typed('£'.code)

        assertEquals(listOf("a", "£"), recorder.text)
    }

    @Test
    fun `a character outside the basic plane survives`() {
        // An emoji is one code point and two chars. A translator that assumed one char would send
        // half of it, which renders as a square.
        input.typed(0x1F600)

        assertEquals(listOf("😀"), recorder.text)
    }
}
