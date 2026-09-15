package dev.wildware.composegl.korge

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
import korlibs.event.Key as KorgeKey
import korlibs.event.KeyEvent as KorgeKeyEvent

/**
 * KorGE's keyboard, translated into the toolkit's, with no KorGE game running.
 *
 * The translators take KorGE's own event objects, which are plain data, so the key table, the
 * modifier bookkeeping and the two rules that keep a text field working are testable on a plain JVM.
 */
class KorgeKeyboardInputTest {

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
    private val keyboard = KorgeKeyboardInput(recorder)
    private val typing = KorgeTextInput(recorder)

    private fun down(key: KorgeKey, shift: Boolean = false, ctrl: Boolean = false) =
        keyboard.onKey(KorgeKeyEvent(type = KorgeKeyEvent.Type.DOWN, key = key, shift = shift, ctrl = ctrl))

    private fun up(key: KorgeKey) = keyboard.onKey(KorgeKeyEvent(type = KorgeKeyEvent.Type.UP, key = key))

    private fun typed(character: Char) =
        typing.onKey(KorgeKeyEvent(type = KorgeKeyEvent.Type.TYPE, key = KorgeKey.UNKNOWN, character = character))

    @Test
    fun `a key is named by what it is, not by KorGE's name`() {
        down(KorgeKey.ESCAPE)
        up(KorgeKey.ESCAPE)
        down(KorgeKey.A)
        down(KorgeKey.KP_7)

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
    fun `every key the toolkit names comes from some KorGE key`() {
        val named = KorgeKey.entries.map { KorgeKeyboardInput.named(it) }.toSet()
        val toolkit = (0..130).map { Key(it) }.filter { it.name != "Unknown" }.toSet()

        assertEquals(emptySet<Key>(), toolkit - named, "toolkit keys no KorGE key turns into")
    }

    @Test
    fun `the keys an AWT window sends under their other names are still named`() {
        // AwtKeyMap sends QUOTE, BACKQUOTE, OPEN_BRACKET and CLOSE_BRACKET, not the names a reader
        // would guess. A table that only had APOSTROPHE would leave a British keyboard half dead.
        listOf(KorgeKey.QUOTE, KorgeKey.BACKQUOTE, KorgeKey.OPEN_BRACKET, KorgeKey.CLOSE_BRACKET, KorgeKey.SHIFT)
            .forEach { down(it) }

        assertEquals(
            listOf(Key.Apostrophe, Key.Grave, Key.LeftBracket, Key.RightBracket, Key.Shift),
            recorder.keys.map { it.key },
        )
    }

    @Test
    fun `a key nobody has a name for still arrives`() {
        down(KorgeKey.MEDIA_PLAY_PAUSE)

        assertEquals(listOf(Key.Unknown), recorder.keys.map { it.key })
    }

    @Test
    fun `modifiers are worked out from what is held`() {
        down(KorgeKey.CONTROL)
        down(KorgeKey.S)

        val save = recorder.keys.last()
        assertEquals(Key.S, save.key)
        assertTrue(save.modifiers.control, "Control-S is unreadable without this")

        up(KorgeKey.CONTROL)
        down(KorgeKey.S)
        assertFalse(recorder.keys.last().modifiers.control)
    }

    @Test
    fun `a modifier the window says is down counts even if its own key was never seen`() {
        // Shift pressed before the window had focus: AWT still stamps the next event with it.
        down(KorgeKey.A, shift = true)

        assertTrue(recorder.keys.single().modifiers.shift)
    }

    @Test
    fun `a held key is reported as a repeat`() {
        down(KorgeKey.ENTER)
        down(KorgeKey.ENTER)
        down(KorgeKey.ENTER)

        assertEquals(
            listOf(false, true, true),
            recorder.keys.map { it.repeat },
            "AWT repeats a held key as another press, and a button must not fire three times",
        )
    }

    @Test
    fun `releasing and pressing again is not a repeat`() {
        down(KorgeKey.ENTER)
        up(KorgeKey.ENTER)
        down(KorgeKey.ENTER)

        assertEquals(listOf(false, false, false), recorder.keys.map { it.repeat })
    }

    @Test
    fun `a typed event is text, not a key`() {
        assertFalse(keyboard.onKey(KorgeKeyEvent(type = KorgeKeyEvent.Type.TYPE, character = 'a')))
        assertTrue(recorder.keys.isEmpty(), "a character is not a key press")
    }

    @Test
    fun `text is text and control characters are not`() {
        typed('a')
        typed('\b')
        typed('\n')
        typed('\t')
        typed('')
        typed('£')

        assertEquals(
            listOf("a", "£"),
            recorder.text,
            "AWT sends Backspace as a typed character, and inserting it is a bug this project shipped once",
        )
    }

    @Test
    fun `a string typed at once arrives as one piece`() {
        // What a soft keyboard or an input method commits: one event, several characters.
        typing.onKey(KorgeKeyEvent(type = KorgeKeyEvent.Type.TYPE, str = "日本"))

        assertEquals(listOf("日本"), recorder.text)
    }

    @Test
    fun `key presses are not text`() {
        typing.onKey(KorgeKeyEvent(type = KorgeKeyEvent.Type.DOWN, key = KorgeKey.A, character = 'a'))

        assertTrue(recorder.text.isEmpty())
    }

    @Test
    fun `losing the window releases everything held`() {
        down(KorgeKey.SHIFT)
        down(KorgeKey.W)
        recorder.keys.clear()

        keyboard.releaseAll()

        assertEquals(setOf(Key.Shift, Key.W), recorder.keys.map { it.key }.toSet())
        assertTrue(recorder.keys.all { it.type == KeyEventType.Up })

        recorder.keys.clear()
        down(KorgeKey.W)
        assertFalse(recorder.keys.single().repeat, "and nothing is still thought to be down")
        assertFalse(recorder.keys.single().modifiers.shift, "and Shift is not still held")
    }
}
