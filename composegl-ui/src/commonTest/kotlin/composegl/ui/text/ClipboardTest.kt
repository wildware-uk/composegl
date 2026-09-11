package composegl.ui.text

import composegl.ui.input.Key
import composegl.ui.input.KeyEvent
import composegl.ui.input.KeyEventType
import composegl.ui.backend.InMemoryClipboard
import composegl.ui.input.Modifiers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cut, copy and paste.
 *
 * The interesting half is paste, because a clipboard holds whatever the platform put on it: a
 * paragraph from a web page, a row from a spreadsheet, a line ending from another operating system.
 * A name box has nowhere to put any of that, so it is made safe on the way in.
 */
class ClipboardTest {

    private val clipboard = InMemoryClipboard()
    private val editor = KeyboardEditor(clipboard = clipboard)

    private var value = TextFieldValue("hello world", TextRange(0, 5))

    @AfterTest
    fun tearDown() {
        Modifiers.isMac = false
    }

    private fun key(key: Key, modifiers: Modifiers = Modifiers.Control, editor: KeyboardEditor = this.editor): Boolean {
        val after = editor.onKey(KeyEvent(key, KeyEventType.Down, modifiers), value) ?: return false
        value = after
        return true
    }

    // --- copy and cut ---------------------------------------------------------------------------------

    @Test
    fun `copy puts the selection on the clipboard and leaves the field alone`() {
        assertTrue(key(Key.C))

        assertEquals("hello", clipboard.read())
        assertEquals("hello world", value.text)
        assertEquals(TextRange(0, 5), value.selection, "and leaves it selected")
    }

    @Test
    fun `cut takes it away`() {
        assertTrue(key(Key.X))

        assertEquals("hello", clipboard.read())
        assertEquals(" world", value.text)
        assertEquals(TextRange(0), value.selection)
    }

    @Test
    fun `copying nothing is not a copy`() {
        value = TextFieldValue("hello", TextRange(2))
        clipboard.write("kept")

        assertTrue(!key(Key.C), "a caret has nothing to copy, so the key is left for the game")
        assertTrue(!key(Key.X))

        assertEquals("kept", clipboard.read(), "and what was on the clipboard is still there")
    }

    // --- paste ----------------------------------------------------------------------------------------

    @Test
    fun `paste puts the clipboard in place of the selection`() {
        clipboard.write("goodbye")

        assertTrue(key(Key.V))

        assertEquals("goodbye world", value.text)
        assertEquals(TextRange(7), value.selection)
    }

    @Test
    fun `paste at a caret inserts`() {
        value = TextFieldValue("ac", TextRange(1))
        clipboard.write("b")

        key(Key.V)

        assertEquals("abc", value.text)
    }

    @Test
    fun `pasting many lines into a single-line field puts them on one line`() {
        value = TextFieldValue("", TextRange(0))
        clipboard.write("Northgate\nSector Four")

        key(Key.V)

        assertEquals("Northgate Sector Four", value.text, "not run together into one word")
    }

    @Test
    fun `windows line endings do not leave a stray character behind`() {
        value = TextFieldValue("", TextRange(0))
        clipboard.write("one\r\ntwo\rthree")

        key(Key.V)

        assertEquals("one two three", value.text)
    }

    @Test
    fun `a multi-line field keeps the lines`() {
        val notes = KeyboardEditor(multiline = true, clipboard = clipboard)
        value = TextFieldValue("", TextRange(0))
        clipboard.write("one\r\ntwo")

        key(Key.V, editor = notes)

        assertEquals("one\ntwo", value.text, "as one newline, however the platform wrote it")
    }

    @Test
    fun `tabs and other control characters become spaces`() {
        value = TextFieldValue("", TextRange(0))
        clipboard.write("name\tvalue")

        key(Key.V)

        assertEquals("name value", value.text)
    }

    @Test
    fun `pasting an empty clipboard is not a paste`() {
        value = TextFieldValue("hello", TextRange(0, 5))

        assertTrue(!key(Key.V), "nothing to paste, so the selection is still there")
        assertEquals("hello", value.text)
    }

    @Test
    fun `a field with no clipboard behind it does not break on the keys`() {
        val alone = KeyboardEditor()
        value = TextFieldValue("hello", TextRange(0, 5))

        assertTrue(!key(Key.V, editor = alone))
        assertTrue(key(Key.C, editor = alone), "copy still counts as used, it just goes nowhere")
        assertEquals("hello", value.text)
    }

    // --- both keyboards ---------------------------------------------------------------------------------

    @Test
    fun `command works on a mac and control does not`() {
        Modifiers.isMac = true

        assertTrue(key(Key.C, Modifiers.Meta))
        assertEquals("hello", clipboard.read())

        clipboard.write("untouched")
        assertTrue(!key(Key.C, Modifiers.Control), "control-C is not a copy on a mac")
        assertEquals("untouched", clipboard.read())
    }

    @Test
    fun `control works everywhere else and command does not`() {
        assertTrue(key(Key.C, Modifiers.Control))
        assertEquals("hello", clipboard.read())

        clipboard.write("untouched")
        assertTrue(!key(Key.C, Modifiers.Meta))
        assertEquals("untouched", clipboard.read())
    }

    @Test
    fun `select all and then copy takes the lot`() {
        value = TextFieldValue("hello world", TextRange(3))

        key(Key.A)
        key(Key.C)

        assertEquals("hello world", clipboard.read())
    }

    @Test
    fun `a plain letter is not a clipboard key`() {
        assertTrue(!key(Key.C, Modifiers.None))
        assertNull(clipboard.read())
    }
}
