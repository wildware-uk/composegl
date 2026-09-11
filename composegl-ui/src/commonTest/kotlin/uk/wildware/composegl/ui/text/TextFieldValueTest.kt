package uk.wildware.composegl.ui.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editing model, tested to death.
 *
 * It is pure, so there is no excuse for a gap: every command is tried against the empty string,
 * against a caret at either end, against a caret in the middle, against a selection and against a
 * full selection. The last test throws ten thousand random commands at it and checks that the value
 * that comes out is still a legal one, because "some sequence nobody thought of" is exactly what a
 * player types.
 */
class TextFieldValueTest {

    /** One emoji, which is two `Char`s. Half of it is not a character, and must never be selected. */
    private val smile = "🙂"

    private fun value(text: String, at: Int) = TextFieldValue(text, TextRange(at))

    private fun value(text: String, from: Int, to: Int) = TextFieldValue(text, TextRange(from, to))

    // --- inserting ---------------------------------------------------------------------------------

    @Test
    fun `typing into an empty field`() {
        val after = TextFieldValue().apply(EditCommand.Insert("a"))

        assertEquals("a", after.text)
        assertEquals(TextRange(1), after.selection)
    }

    @Test
    fun `typing at the start and the middle and the end`() {
        assertEquals("Xabc", value("abc", 0).apply(EditCommand.Insert("X")).text)
        assertEquals("aXbc", value("abc", 1).apply(EditCommand.Insert("X")).text)
        assertEquals("abcX", value("abc", 3).apply(EditCommand.Insert("X")).text)
    }

    @Test
    fun `the caret ends up after what was typed however long it was`() {
        val after = value("ac", 1).apply(EditCommand.Insert("bbbb"))

        assertEquals("abbbbc", after.text)
        assertEquals(TextRange(5), after.selection)
    }

    @Test
    fun `typing replaces a selection`() {
        val after = value("hello world", 6, 11).apply(EditCommand.Insert("there"))

        assertEquals("hello there", after.text)
        assertEquals(TextRange(11), after.selection)
    }

    @Test
    fun `typing over everything replaces everything`() {
        val after = value("hello", 0, 5).apply(EditCommand.Insert("x"))

        assertEquals("x", after.text)
        assertEquals(TextRange(1), after.selection)
    }

    @Test
    fun `typing over a backwards selection is the same as over a forwards one`() {
        val forwards = value("hello", 1, 4).apply(EditCommand.Insert("X"))
        val backwards = value("hello", 4, 1).apply(EditCommand.Insert("X"))

        assertEquals(forwards, backwards)
        assertEquals("hXo", forwards.text)
    }

    @Test
    fun `typing nothing over a selection deletes it`() {
        val after = value("hello", 1, 4).apply(EditCommand.Insert(""))

        assertEquals("ho", after.text, "an empty insert over a selection is a delete, as everywhere else")
        assertEquals(TextRange(1), after.selection)
    }

    // --- replacing ---------------------------------------------------------------------------------

    @Test
    fun `a replacement can be anywhere rather than only where the player is`() {
        val after = value("hello world", 11).apply(EditCommand.Replace(TextRange(0, 5), "goodbye"))

        assertEquals("goodbye world", after.text)
        assertEquals(TextRange(7), after.selection, "the caret follows the edit")
    }

    @Test
    fun `a range that runs off the end is pulled back onto the text`() {
        val after = TextFieldValue("abc").apply(EditCommand.Replace(TextRange(2, 99), "Z"))

        assertEquals("abZ", after.text)
    }

    @Test
    fun `a range with a negative end is pulled back too`() {
        val after = TextFieldValue("abc").apply(EditCommand.Replace(TextRange(-4, 1), "Z"))

        assertEquals("Zbc", after.text)
    }

    // --- backspace ----------------------------------------------------------------------------------

    @Test
    fun `backspace at the very start does nothing at all`() {
        val before = value("abc", 0)

        assertEquals(before, before.apply(EditCommand.DeleteBackward))
    }

    @Test
    fun `backspace in an empty field does nothing at all`() {
        assertEquals(TextFieldValue.Empty, TextFieldValue.Empty.apply(EditCommand.DeleteBackward))
    }

    @Test
    fun `backspace takes the character before the caret`() {
        val after = value("abc", 2).apply(EditCommand.DeleteBackward)

        assertEquals("ac", after.text)
        assertEquals(TextRange(1), after.selection)
    }

    @Test
    fun `backspace at the end takes the last character`() {
        assertEquals("ab", value("abc", 3).apply(EditCommand.DeleteBackward).text)
    }

    @Test
    fun `backspace takes the selection instead when there is one`() {
        val after = value("hello world", 5, 11).apply(EditCommand.DeleteBackward)

        assertEquals("hello", after.text)
        assertEquals(TextRange(5), after.selection)
    }

    @Test
    fun `backspace over everything empties the field`() {
        val after = value("hello", 0, 5).apply(EditCommand.DeleteBackward)

        assertEquals("", after.text)
        assertEquals(TextRange(0), after.selection)
    }

    @Test
    fun `backspace takes a whole emoji rather than half of one`() {
        val after = value("hi $smile", 5).apply(EditCommand.DeleteBackward)

        assertEquals("hi ", after.text, "half a surrogate pair is not a character")
    }

    @Test
    fun `a held backspace deletes one character per repeat`() {
        val held = List(4) { EditCommand.DeleteBackward }
        val after = value("abcdef", 6).apply(held)

        assertEquals("ab", after.text)
        assertEquals(TextRange(2), after.selection)
    }

    // --- delete -------------------------------------------------------------------------------------

    @Test
    fun `delete at the very end does nothing at all`() {
        val before = value("abc", 3)

        assertEquals(before, before.apply(EditCommand.DeleteForward))
    }

    @Test
    fun `delete in an empty field does nothing at all`() {
        assertEquals(TextFieldValue.Empty, TextFieldValue.Empty.apply(EditCommand.DeleteForward))
    }

    @Test
    fun `delete takes the character after the caret and leaves it where it is`() {
        val after = value("abc", 1).apply(EditCommand.DeleteForward)

        assertEquals("ac", after.text)
        assertEquals(TextRange(1), after.selection)
    }

    @Test
    fun `delete takes the selection instead when there is one`() {
        val after = value("hello world", 0, 6).apply(EditCommand.DeleteForward)

        assertEquals("world", after.text)
        assertEquals(TextRange(0), after.selection)
    }

    @Test
    fun `delete takes a whole emoji rather than half of one`() {
        val after = value("$smile hi", 0).apply(EditCommand.DeleteForward)

        assertEquals(" hi", after.text)
    }

    // --- moving -------------------------------------------------------------------------------------

    @Test
    fun `a selection past the end is pulled back to the end`() {
        val after = TextFieldValue("abc").apply(EditCommand.SetSelection(TextRange(1, 99)))

        assertEquals(TextRange(1, 3), after.selection)
    }

    @Test
    fun `a selection before the start is pulled forward to it`() {
        val after = TextFieldValue("abc").apply(EditCommand.SetSelection(TextRange(-5, -2)))

        assertEquals(TextRange(0, 0), after.selection)
    }

    @Test
    fun `a backwards selection stays backwards`() {
        val after = TextFieldValue("hello").apply(EditCommand.SetSelection(TextRange(4, 1)))

        assertEquals(TextRange(4, 1), after.selection, "which end the player is holding is the point")
        assertTrue(after.selection.reversed)
        assertEquals(1, after.selection.min)
        assertEquals(4, after.selection.max)
    }

    @Test
    fun `a caret cannot land inside an emoji`() {
        val after = TextFieldValue("a$smile b").apply(EditCommand.SetSelection(TextRange(2)))

        assertEquals(TextRange(1), after.selection, "moved off the seam, to before the emoji")
    }

    @Test
    fun `a selection cannot end inside an emoji either`() {
        val after = TextFieldValue("a$smile b").apply(EditCommand.SetSelection(TextRange(0, 2)))

        assertEquals(TextRange(0, 1), after.selection)
    }

    @Test
    fun `what is selected is what the player would see highlighted`() {
        assertEquals("ell", value("hello", 1, 4).selected)
        assertEquals("ell", value("hello", 4, 1).selected, "backwards or forwards, the same words")
        assertEquals("", value("hello", 2).selected)
    }

    // --- composing ----------------------------------------------------------------------------------

    @Test
    fun `a composition can be set and taken away again`() {
        val composing = TextFieldValue("konnichiwa")
            .apply(EditCommand.SetComposition(TextRange(0, 10)))
        assertEquals(TextRange(0, 10), composing.composition)

        assertNull(composing.apply(EditCommand.SetComposition(null)).composition)
    }

    @Test
    fun `committing text ends the composition`() {
        val composing = TextFieldValue("nihon", TextRange(5), TextRange(0, 5))

        val after = composing.apply(EditCommand.Insert("go"))

        assertEquals("nihongo", after.text)
        assertNull(after.composition, "what the IME was deciding no longer describes anything")
    }

    @Test
    fun `deleting ends the composition too`() {
        val composing = TextFieldValue("nihon", TextRange(5), TextRange(0, 5))

        assertNull(composing.apply(EditCommand.DeleteBackward).composition)
    }

    @Test
    fun `a composition past the end is pulled back like a selection`() {
        val after = TextFieldValue("abc").apply(EditCommand.SetComposition(TextRange(1, 99)))

        assertEquals(TextRange(1, 3), after.composition)
    }

    // --- whatever the player does ---------------------------------------------------------------------

    @Test
    fun `typing a word and backspacing it leaves nothing behind`() {
        val typed = TextFieldValue().apply("hello".map { EditCommand.Insert(it.toString()) })
        assertEquals("hello", typed.text)

        val cleared = typed.apply(List(5) { EditCommand.DeleteBackward })

        assertEquals(TextFieldValue.Empty, cleared)
    }

    @Test
    fun `no sequence of commands can produce an illegal value`() {
        val random = Random(20260911)
        val words = listOf("", "a", "hi", smile, "$smile$smile", "hello world", "\n")
        var value = TextFieldValue("hello $smile world")

        repeat(10_000) { step ->
            val command = when (random.nextInt(6)) {
                0 -> EditCommand.Insert(words.random(random))
                1 -> EditCommand.Replace(randomRange(random), words.random(random))
                2 -> EditCommand.DeleteBackward
                3 -> EditCommand.DeleteForward
                4 -> EditCommand.SetSelection(randomRange(random))
                else -> EditCommand.SetComposition(if (random.nextBoolean()) randomRange(random) else null)
            }
            value = value.apply(command)

            assertLegal(value, "after $step commands, the last being $command")
        }
    }

    /** Deliberately wild: well off either end, and pointing both ways. */
    private fun randomRange(random: Random) = TextRange(random.nextInt(-8, 24), random.nextInt(-8, 24))

    private fun assertLegal(value: TextFieldValue, what: String) {
        assertInside(value.selection, value.text, "selection $what")
        value.composition?.let { assertInside(it, value.text, "composition $what") }
    }

    private fun assertInside(range: TextRange, text: String, what: String) {
        assertTrue(range.min >= 0 && range.max <= text.length, "$what: $range is outside '$text'")
        assertTrue(!text.splitsACharacterAt(range.start), "$what: $range starts inside a character")
        assertTrue(!text.splitsACharacterAt(range.end), "$what: $range ends inside a character")
    }

    private fun String.splitsACharacterAt(index: Int): Boolean =
        index in 1..<length && this[index - 1].isHighSurrogate() && this[index].isLowSurrogate()
}
