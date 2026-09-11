package uk.wildware.composegl.ui.text

import uk.wildware.composegl.ui.input.Key
import uk.wildware.composegl.ui.input.KeyEvent
import uk.wildware.composegl.ui.input.KeyEventType
import uk.wildware.composegl.ui.input.Modifiers
import uk.wildware.composegl.ui.input.TextEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A keyboard, turned into edits.
 *
 * The first tests are the two bugs that reached a player in the previous version of this project,
 * written down so they cannot come back:
 *
 * - **a control character must never be inserted.** Backspace arrived as a character and a square
 *   appeared in the field.
 * - **a held key must keep deleting.** The repeat arrived with nothing to say it was a fresh press,
 *   the field ignored it, and holding backspace did nothing at all.
 *
 * Everything else is the ordinary contract: which keys a field takes, which it leaves for the
 * screen around it, and what shift and the shortcut key do to each.
 */
class KeyboardEditorTest {

    private val editor = KeyboardEditor()

    private var value = TextFieldValue("hello world", TextRange(11))

    private fun key(
        key: Key,
        modifiers: Modifiers = Modifiers.None,
        repeat: Boolean = false,
        type: KeyEventType = KeyEventType.Down,
    ): Boolean {
        val after = editor.onKey(KeyEvent(key, type, modifiers, repeat), value) ?: return false
        value = after
        return true
    }

    private fun type(text: String): Boolean {
        val after = editor.onText(TextEvent(text), value) ?: return false
        value = after
        return true
    }

    // --- the two bugs -------------------------------------------------------------------------------

    @Test
    fun `a control character is never inserted`() {
        val backspaceAsACharacter = "\b"

        val handled = type(backspaceAsACharacter)

        assertEquals("hello world", value.text, "a square in a name box is how this looked to a player")
        assertTrue(!handled, "and nothing pretended to deal with it")
    }

    @Test
    fun `every control character is refused and ordinary text is not`() {
        listOf("\u0000", "\b", "\u001B", "\u007F", "\t", "\r").forEach { control ->
            assertNull(editor.onText(TextEvent(control), value), "${control.first().code} was let through")
        }

        assertEquals("hello worldé", editor.onText(TextEvent("é"), value)?.text)
    }

    @Test
    fun `a held backspace keeps deleting`() {
        key(Key.Backspace)
        repeat(4) { key(Key.Backspace, repeat = true) }

        assertEquals("hello ", value.text, "a repeat is a keystroke, not a duplicate to be ignored")
    }

    @Test
    fun `a held arrow keeps moving`() {
        key(Key.Left)
        repeat(4) { key(Key.Left, repeat = true) }

        assertEquals(TextRange(6), value.selection)
    }

    // --- what a field takes -------------------------------------------------------------------------

    @Test
    fun `typing inserts`() {
        value = TextFieldValue("", TextRange(0))

        assertTrue(type("h"))
        assertTrue(type("i"))

        assertEquals("hi", value.text)
        assertEquals(TextRange(2), value.selection)
    }

    @Test
    fun `backspace and delete`() {
        value = TextFieldValue("abc", TextRange(2))

        key(Key.Backspace)
        assertEquals("ac", value.text)

        key(Key.Delete)
        assertEquals("a", value.text)
    }

    @Test
    fun `the arrows move and shift extends`() {
        value = TextFieldValue("hello", TextRange(0))

        key(Key.Right)
        assertEquals(TextRange(1), value.selection)

        key(Key.Right, Modifiers.Shift)
        assertEquals(TextRange(1, 2), value.selection)

        key(Key.Left, Modifiers.Shift)
        assertEquals(TextRange(1), value.selection)
    }

    @Test
    fun `the shortcut key and an arrow moves a word`() {
        value = TextFieldValue("hello brave world", TextRange(0))

        key(Key.Right, Modifiers.Control)

        assertEquals(TextRange(5), value.selection)
    }

    @Test
    fun `home and end go to the line's ends and the shortcut key goes to the text's`() {
        value = TextFieldValue("first\nsecond", TextRange(8))

        key(Key.Home)
        assertEquals(TextRange(6), value.selection)

        key(Key.End)
        assertEquals(TextRange(12), value.selection)

        key(Key.Home, Modifiers.Control)
        assertEquals(TextRange(0), value.selection)

        key(Key.End, Modifiers.Control)
        assertEquals(TextRange(12), value.selection)
    }

    @Test
    fun `the shortcut key and A selects everything`() {
        key(Key.A, Modifiers.Control)

        assertEquals(TextRange(0, 11), value.selection)
    }

    @Test
    fun `on a mac the shortcut key is command rather than control`() {
        Modifiers.isMac = true
        try {
            assertTrue(key(Key.A, Modifiers.Meta))
            assertEquals(TextRange(0, 11), value.selection)
        } finally {
            Modifiers.isMac = false
        }
    }

    @Test
    fun `typing over a selection replaces it`() {
        value = TextFieldValue("hello world", TextRange(0, 5))

        type("bye")

        assertEquals("bye world", value.text)
    }

    // --- what a field leaves alone --------------------------------------------------------------------

    @Test
    fun `tab and escape are not the field's`() {
        assertTrue(!key(Key.Tab), "tab moves focus, even from inside a field")
        assertTrue(!key(Key.Escape), "escape closes what the field is in")
    }

    @Test
    fun `enter is not a single-line field's`() {
        assertTrue(!key(Key.Enter))
        assertEquals("hello world", value.text)
    }

    @Test
    fun `enter is a multi-line field's and makes a line`() {
        val multiline = KeyboardEditor(multiline = true)

        val after = multiline.onKey(KeyEvent(Key.Enter, KeyEventType.Down), TextFieldValue("ab", TextRange(1)))

        assertEquals("a\nb", after?.text)
    }

    @Test
    fun `a shortcut nobody here handles is left for the game`() {
        assertTrue(!key(Key.S, Modifiers.Control), "ctrl-S is a game's to save with")
        assertTrue(!key(Key.F1))
    }

    @Test
    fun `a modifier on its own is not an edit`() {
        assertTrue(!key(Key.Shift))
        assertTrue(!key(Key.Control))
    }

    @Test
    fun `a key coming up is not an edit`() {
        assertTrue(!key(Key.Backspace, type = KeyEventType.Up))

        assertEquals("hello world", value.text)
    }

    @Test
    fun `space is text rather than a key press`() {
        value = TextFieldValue("hi", TextRange(2))

        assertTrue(!key(Key.Space), "the platform decides what a space key types and sends it as text")
        assertTrue(type(" "))

        assertEquals("hi ", value.text)
    }
}
