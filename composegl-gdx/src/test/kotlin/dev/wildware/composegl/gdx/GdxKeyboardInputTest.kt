package dev.wildware.composegl.gdx

import com.badlogic.gdx.Input
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.TextEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The translation from LibGDX's keyboard to the toolkit's, with no LibGDX application.
 *
 * Every method is a callback that can be called directly, so the key names, the modifier
 * bookkeeping and the two rules that keep a text field working are all testable on a plain JVM.
 */
class GdxKeyboardInputTest {

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
    private val input = GdxKeyboardInput(recorder)

    @Test
    fun `a key is named by what it is, not by LibGDX's number`() {
        input.keyDown(Input.Keys.ESCAPE)
        input.keyUp(Input.Keys.ESCAPE)
        input.keyDown(Input.Keys.A)
        input.keyDown(Input.Keys.NUMPAD_7)

        assertEquals(
            listOf(Key.Escape, Key.Escape, Key.A, Key.Digit7),
            recorder.keys.map { it.key },
            "the number pad types the same digits as the top row",
        )
        assertEquals(
            listOf(KeyEventType.Down, KeyEventType.Up, KeyEventType.Down, KeyEventType.Down),
            recorder.keys.map { it.type },
        )
    }

    @Test
    fun `a key nobody has a name for still arrives`() {
        input.keyDown(Input.Keys.MEDIA_PLAY_PAUSE)

        assertEquals(
            listOf(Key.Unknown),
            recorder.keys.map { it.key },
            "a strange keyboard should produce an event the game can look at, not silence",
        )
    }

    @Test
    fun `modifiers are worked out from what is held`() {
        input.keyDown(Input.Keys.CONTROL_LEFT)
        input.keyDown(Input.Keys.S)

        val save = recorder.keys.last()
        assertEquals(Key.S, save.key)
        assertTrue(save.modifiers.control, "Control-S is unreadable without this")

        input.keyUp(Input.Keys.CONTROL_LEFT)
        input.keyDown(Input.Keys.S)
        assertFalse(recorder.keys.last().modifiers.control)
    }

    @Test
    fun `a held key is reported as a repeat`() {
        input.keyDown(Input.Keys.ENTER)
        input.keyDown(Input.Keys.ENTER)
        input.keyDown(Input.Keys.ENTER)

        assertEquals(
            listOf(false, true, true),
            recorder.keys.map { it.repeat },
            "LibGDX repeats a held key as another keyDown, and a button must not fire three times",
        )
    }

    @Test
    fun `releasing and pressing again is not a repeat`() {
        input.keyDown(Input.Keys.ENTER)
        input.keyUp(Input.Keys.ENTER)
        input.keyDown(Input.Keys.ENTER)

        assertEquals(listOf(false, false, false), recorder.keys.map { it.repeat })
    }

    @Test
    fun `text is text and control characters are not`() {
        input.keyTyped('a')
        input.keyTyped('\b')
        input.keyTyped('\n')
        input.keyTyped('\t')
        input.keyTyped('£')

        assertEquals(
            listOf("a", "£"),
            recorder.text,
            "LibGDX sends Backspace through keyTyped, and inserting it is a bug this project shipped once",
        )
    }

    @Test
    fun `losing the window releases everything held`() {
        input.keyDown(Input.Keys.SHIFT_LEFT)
        input.keyDown(Input.Keys.W)
        recorder.keys.clear()

        input.releaseAll()

        assertEquals(
            setOf(Key.Shift, Key.W),
            recorder.keys.map { it.key }.toSet(),
            "a Shift still held after the window went away turns every click into a shift-click",
        )
        assertTrue(recorder.keys.all { it.type == KeyEventType.Up })

        recorder.keys.clear()
        input.keyDown(Input.Keys.W)
        assertFalse(recorder.keys.single().repeat, "and nothing is still thought to be down")
    }
}
