package composegl.android

import composegl.ui.text.EditCommand
import composegl.ui.text.TextFieldValue
import composegl.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The half of typing that a key event cannot say.
 *
 * These are the three things that did not work before this class existed, written as the keyboard
 * actually performs them: autocorrect replacing a word nobody typed, a Japanese keyboard composing
 * and then committing, and a swipe arriving as one whole word.
 *
 * The connection keeps its own copy of the text and answers the keyboard's questions from it, so
 * every test checks both halves: what the field was told, and what the keyboard would read back.
 * A drift between those two is exactly the bug this class is here to not have.
 */
class ComposeGlInputConnectionTest {

    private var field = TextFieldValue("")
    private val batches = mutableListOf<List<EditCommand>>()
    private var submitted = 0

    private fun connect(
        initial: TextFieldValue = TextFieldValue(""),
        multiline: Boolean = false,
    ): ComposeGlInputConnection {
        field = initial
        return ComposeGlInputConnection(
            initial = initial,
            multiline = multiline,
            apply = { commands, resulting ->
                batches += commands
                field = resulting
            },
            submit = { submitted++ },
        )
    }

    // --- typing a language you cannot type one key at a time ---------------------------------------

    @Test
    fun `a Japanese keyboard composes, and what is provisional is marked as provisional`() {
        val ime = connect()

        ime.setComposingText("に", 1)
        ime.setComposingText("にほ", 1)
        ime.setComposingText("にほん", 1)

        assertEquals("にほん", field.text)
        assertEquals(TextRange(0, 3), field.composition, "all of it is still provisional")
        assertEquals(TextRange(3), field.selection, "the caret is after what was typed")
    }

    @Test
    fun `choosing a candidate replaces the whole provisional run and ends the composition`() {
        val ime = connect()
        ime.setComposingText("にほん", 1)

        ime.commitText("日本", 1)

        assertEquals("日本", field.text, "not にほん日本")
        assertNull(field.composition, "committing is what ends a composition")
        assertEquals(TextRange(2), field.selection)
    }

    @Test
    fun `a batch reaches the field as one edit, not as its halves`() {
        val ime = connect()
        ime.setComposingText("にほん", 1)
        batches.clear()

        ime.beginBatchEdit()
        ime.commitText("日本", 1)
        ime.setComposingText("ご", 1)
        ime.endBatchEdit()

        assertEquals(1, batches.size, "one batch, because the keyboard meant it as one")
        assertEquals("日本ご", field.text)
        assertEquals(TextRange(2, 3), field.composition)
    }

    @Test
    fun `abandoning a composition leaves the text alone`() {
        val ime = connect()
        ime.setComposingText("にほん", 1)

        ime.finishComposingText()

        assertEquals("にほん", field.text, "what was on screen stays on screen")
        assertNull(field.composition)
    }

    // --- the phone things that never worked --------------------------------------------------------

    @Test
    fun `autocorrect replaces a word nobody typed`() {
        val ime = connect()
        // Gboard holds the word open as it is typed…
        ime.setComposingText("teh", 1)
        // …and then decides it was wrong. This is the call that never arrived before.
        ime.commitText("the", 1)
        ime.commitText(" ", 1)

        assertEquals("the ", field.text)
        assertNull(field.composition)
    }

    @Test
    fun `a swiped word arrives whole`() {
        val ime = connect(TextFieldValue("hello "))

        ime.commitText("world", 1)

        assertEquals("hello world", field.text)
    }

    @Test
    fun `the keyboard can delete what is around the cursor`() {
        val ime = connect(TextFieldValue("abcdef", TextRange(3)))

        ime.deleteSurroundingText(2, 1)

        assertEquals("aef", field.text, "bc before it and d after it")
    }

    @Test
    fun `deleting by code points does not cut an emoji in half`() {
        val ime = connect(TextFieldValue("hi🙂", TextRange(4)))

        ime.deleteSurroundingTextInCodePoints(1, 0)

        assertEquals("hi", field.text, "one code point, which is two Chars")
    }

    // --- what the keyboard reads back --------------------------------------------------------------

    @Test
    fun `the keyboard reads the text back out of the connection, and it agrees with the field`() {
        val ime = connect(TextFieldValue("hello world", TextRange(5)))

        assertEquals("hello", ime.getTextBeforeCursor(20, 0))
        assertEquals(" world", ime.getTextAfterCursor(20, 0))
        assertNull(ime.getSelectedText(0), "a caret is not a selection")

        ime.commitText("!", 1)

        assertEquals("hello!", ime.getTextBeforeCursor(20, 0), "and it keeps up with its own edits")
        assertEquals("hello! world", field.text)
    }

    @Test
    fun `a selection reads back as a selection`() {
        val ime = connect(TextFieldValue("hello world", TextRange(6, 11)))

        assertEquals("world", ime.getSelectedText(0))
    }

    @Test
    fun `asking for more text than there is gives what there is`() {
        val ime = connect(TextFieldValue("hi", TextRange(1)))

        assertEquals("h", ime.getTextBeforeCursor(100, 0))
        assertEquals("i", ime.getTextAfterCursor(100, 0))
    }

    @Test
    fun `committing over a selection replaces it`() {
        val ime = connect(TextFieldValue("hello world", TextRange(6, 11)))

        ime.commitText("there", 1)

        assertEquals("hello there", field.text)
    }

    // --- where the caret ends up -------------------------------------------------------------------

    @Test
    fun `a keyboard asking for the caret before what it inserted gets it there`() {
        val ime = connect(TextFieldValue("ab", TextRange(1)))

        // Zero means "leave the caret where the text began", which several keyboards ask for.
        ime.commitText("XY", 0)

        assertEquals("aXYb", field.text)
        assertEquals(TextRange(1), field.selection)
    }

    // --- the action button -------------------------------------------------------------------------

    @Test
    fun `the action button submits a single-line field`() {
        val ime = connect(TextFieldValue("sam"))

        assertTrue(ime.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE))

        assertEquals(1, submitted)
    }

    @Test
    fun `moving the caret is an edit like any other`() {
        val ime = connect(TextFieldValue("hello", TextRange(5)))

        ime.setSelection(1, 3)

        assertEquals(TextRange(1, 3), field.selection)
        assertEquals("el", ime.getSelectedText(0))
    }
}
