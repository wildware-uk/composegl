package uk.wildware.composegl.lwjgl3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.wildware.composegl.ui.text.TextFieldValue
import uk.wildware.composegl.ui.text.TextRange
import uk.wildware.composegl.ui.text.apply

/**
 * Typing Japanese, as far as a machine with no input method installed can be made to type it.
 *
 * The native half of [GlfwTextInput] cannot be tested here — it needs a window, a display and an
 * input method — so these drive the half that decides what the edits are, and then apply them the
 * way the field would, which is the thing that actually has to come out right.
 */
class PreeditTest {

    private fun TextFieldValue.then(commands: List<uk.wildware.composegl.ui.text.EditCommand>) =
        commands.fold(this) { value, command -> value.apply(command) }

    @Test
    fun `a preedit shows as provisional text under the caret`() {
        val empty = TextFieldValue("", TextRange(0))

        val after = empty.then(Preedit.compose("ni", caretInCodePoints = 2, value = empty))

        assertEquals("ni", after.text)
        assertEquals(TextRange(0, 2), after.composition)
        assertEquals(TextRange(2), after.selection)
    }

    @Test
    fun `each report replaces the run rather than adding to it`() {
        val empty = TextFieldValue("", TextRange(0))
        val typed = empty.then(Preedit.compose("ni", 2, empty))

        // The input method has turned the romaji into kana. It sends the whole run again.
        val kana = typed.then(Preedit.compose("に", 1, typed))

        assertEquals("に", kana.text, "not 'niに'")
        assertEquals(TextRange(0, 1), kana.composition)
    }

    @Test
    fun `a preedit composes at the caret, not at the start of the text`() {
        val start = TextFieldValue("hello world", TextRange(6))

        val after = start.then(Preedit.compose("ni", 2, start))

        assertEquals("hello niworld", after.text)
        assertEquals(TextRange(6, 8), after.composition)
        assertEquals(TextRange(8), after.selection)
    }

    @Test
    fun `a preedit replaces the selection it was typed over`() {
        val selected = TextFieldValue("hello world", TextRange(0, 5))

        val after = selected.then(Preedit.compose("ni", 2, selected))

        assertEquals("ni world", after.text)
        assertEquals(TextRange(0, 2), after.composition)
    }

    @Test
    fun `the caret can sit inside the run`() {
        val empty = TextFieldValue("", TextRange(0))

        val after = empty.then(Preedit.compose("nihon", caretInCodePoints = 2, value = empty))

        assertEquals(TextRange(2), after.selection)
        assertEquals(TextRange(0, 5), after.composition, "all of it is still provisional")
    }

    @Test
    fun `the caret is counted in code points, not in chars`() {
        val empty = TextFieldValue("", TextRange(0))
        // Two code points, four Chars: each is a surrogate pair.
        val text = "𩸽𠮷"

        val after = empty.then(Preedit.compose(text, caretInCodePoints = 1, value = empty))

        assertEquals(TextRange(2), after.selection, "after the first character, not inside it")
        assertTrue(after.text.substring(0, 2) == "𩸽", "a whole character, not half of one")
    }

    @Test
    fun `a caret past the end of the run is pulled back to it`() {
        val empty = TextFieldValue("", TextRange(0))

        val after = empty.then(Preedit.compose("ni", caretInCodePoints = 9, value = empty))

        assertEquals(TextRange(2), after.selection)
    }

    @Test
    fun `an empty preedit takes the provisional text away`() {
        val empty = TextFieldValue("", TextRange(0))
        val typed = empty.then(Preedit.compose("ni", 2, empty))

        // What was chosen arrives separately, through the character callback, so this must not
        // try to keep the romaji — the field would show it twice.
        val gone = typed.then(Preedit.compose("", 0, typed))

        assertEquals("", gone.text)
        assertEquals(null, gone.composition)
        assertEquals(TextRange(0), gone.selection)
    }

    @Test
    fun `clearing leaves the text either side of the run alone`() {
        val start = TextFieldValue("hello world", TextRange(6))
        val typed = start.then(Preedit.compose("ni", 2, start))

        val gone = typed.then(Preedit.clear(typed))

        assertEquals("hello world", gone.text)
        assertEquals(TextRange(6), gone.selection, "back where the composing started")
    }

    @Test
    fun `clearing when nothing is provisional changes nothing`() {
        val plain = TextFieldValue("hello", TextRange(5))

        assertTrue(Preedit.clear(plain).isEmpty())
    }
}
