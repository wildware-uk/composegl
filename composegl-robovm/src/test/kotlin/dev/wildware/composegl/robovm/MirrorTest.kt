package dev.wildware.composegl.robovm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.wildware.composegl.ui.text.EditCommand
import dev.wildware.composegl.ui.text.TextFieldValue
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.apply

/**
 * Following UIKit's text field, as far as a machine with no UIKit on it can follow anything.
 *
 * The other half of [UiKitTextInput] is UIKit calls and cannot run here. This half decides what
 * the edits are, and these apply them the way the field would — which is the thing that actually
 * has to come out right.
 */
class MirrorTest {

    private fun TextFieldValue.then(commands: List<EditCommand>) =
        commands.fold(this) { value, command -> value.apply(command) }

    private fun sync(value: TextFieldValue, text: String, caret: Int, marked: TextRange? = null) =
        value.then(Mirror.commands(value, text, TextRange(caret), marked))

    @Test
    fun `a typed character arrives`() {
        val before = TextFieldValue("hell", TextRange(4))

        val after = sync(before, "hello", 5)

        assertEquals("hello", after.text)
        assertEquals(TextRange(5), after.selection)
    }

    @Test
    fun `only what changed is replaced`() {
        val before = TextFieldValue("hello world", TextRange(11))

        val commands = Mirror.commands(before, "hello brave world", TextRange(17))
        val replace = commands.filterIsInstance<EditCommand.Replace>().single()

        assertEquals(TextRange(6, 6), replace.range, "inserted at the seam, nothing else touched")
        assertEquals("brave ", replace.text)
    }

    @Test
    fun `an autocorrection replaces the word it corrected`() {
        val before = TextFieldValue("teh ", TextRange(4))

        // UIKit swaps the whole word when the space is typed.
        val after = sync(before, "the ", 4)

        assertEquals("the ", after.text)
        assertEquals(TextRange(4), after.selection)
    }

    @Test
    fun `a swiped word arrives whole`() {
        val before = TextFieldValue("", TextRange(0))

        val after = sync(before, "keyboard", 8)

        assertEquals("keyboard", after.text)
    }

    @Test
    fun `a backspace takes one character off`() {
        val before = TextFieldValue("hello", TextRange(5))

        val after = sync(before, "hell", 4)

        assertEquals("hell", after.text)
        assertEquals(TextRange(4), after.selection)
    }

    @Test
    fun `deleting from the middle leaves both ends alone`() {
        val before = TextFieldValue("hello world", TextRange(6))

        val after = sync(before, "helloworld", 5)

        assertEquals("helloworld", after.text)
    }

    @Test
    fun `a marked range comes through as a composition`() {
        val before = TextFieldValue("", TextRange(0))

        // Halfway to a kanji: UIKit is holding `ni` as provisional.
        val after = sync(before, "ni", caret = 2, marked = TextRange(0, 2))

        assertEquals("ni", after.text)
        assertEquals(TextRange(0, 2), after.composition, "drawn underlined")
    }

    @Test
    fun `choosing a candidate replaces the provisional run and stops marking`() {
        val composing = TextFieldValue("", TextRange(0))
            .then(Mirror.commands(TextFieldValue("", TextRange(0)), "ni", TextRange(2), TextRange(0, 2)))

        val chosen = sync(composing, "に", caret = 1, marked = null)

        assertEquals("に", chosen.text, "not 'niに'")
        assertEquals(null, chosen.composition)
    }

    @Test
    fun `moving the caret alone is not a text edit`() {
        val before = TextFieldValue("hello", TextRange(5))

        val commands = Mirror.commands(before, "hello", TextRange(2))

        assertTrue(commands.none { it is EditCommand.Replace }, "nothing was typed")
        assertEquals(TextRange(2), before.then(commands).selection)
    }

    @Test
    fun `saying the same thing twice changes nothing`() {
        val settled = TextFieldValue("hello", TextRange(5))

        assertTrue(Mirror.commands(settled, "hello", TextRange(5)).isEmpty())
    }

    @Test
    fun `clearing the field empties it`() {
        val before = TextFieldValue("hello", TextRange(5))

        val after = sync(before, "", 0)

        assertEquals("", after.text)
        assertEquals(TextRange(0), after.selection)
    }

    @Test
    fun `a repeated character is not mistaken for no change`() {
        val before = TextFieldValue("aa", TextRange(2))

        val after = sync(before, "aaa", 3)

        assertEquals("aaa", after.text, "three, not two")
    }
}
