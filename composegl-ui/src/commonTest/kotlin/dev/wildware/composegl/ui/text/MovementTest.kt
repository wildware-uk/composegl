package dev.wildware.composegl.ui.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Moving about in a field, and what counts as one character while doing it.
 *
 * Two things are being checked. That word movement agrees with what a person expects — across
 * punctuation, across a run of spaces, and at the ends, where every naive version throws. And that
 * one press of a key moves past one *character*: an emoji with a skin tone on it is one, a family
 * is one, a flag is one, and a letter with an accent on it is one.
 */
class MovementTest {

    private val thumb = "👍"
    private val tanned = "👍🏽"
    private val family = "👨‍👩‍👧"
    private val flag = "🇬🇧"
    private val accented = "é"

    private fun field(text: String, at: Int) = TextFieldValue(text, TextRange(at))

    private fun caret(value: TextFieldValue) = value.selection.start

    // --- one character at a time ------------------------------------------------------------------

    @Test
    fun `left from the very start is a no-op rather than an exception`() {
        val start = field("hello", 0)

        assertEquals(start, start.move(Movement.Left))
        assertEquals(TextFieldValue.Empty, TextFieldValue.Empty.move(Movement.Left))
    }

    @Test
    fun `right from the very end is a no-op too`() {
        val end = field("hello", 5)

        assertEquals(end, end.move(Movement.Right))
        assertEquals(TextFieldValue.Empty, TextFieldValue.Empty.move(Movement.Right))
    }

    @Test
    fun `an emoji is one press of an arrow key`() {
        assertEquals(thumb.length, caret(field(thumb, 0).move(Movement.Right)))
        assertEquals(0, caret(field(thumb, thumb.length).move(Movement.Left)))
    }

    @Test
    fun `an emoji with a skin tone on it is one press`() {
        assertTrue(tanned.length > thumb.length, "it really is more than one code point")

        assertEquals(tanned.length, caret(field(tanned, 0).move(Movement.Right)))
        assertEquals(0, caret(field(tanned, tanned.length).move(Movement.Left)))
    }

    @Test
    fun `a family joined by zero-width joiners is one press`() {
        assertEquals(family.length, caret(field(family, 0).move(Movement.Right)))
        assertEquals(0, caret(field(family, family.length).move(Movement.Left)))
    }

    @Test
    fun `a flag is one press and two flags are two`() {
        val two = "$flag$flag"

        val once = field(two, 0).move(Movement.Right)
        assertEquals(flag.length, caret(once))
        assertEquals(two.length, caret(once.move(Movement.Right)))
        assertEquals(flag.length, caret(field(two, two.length).move(Movement.Left)))
    }

    @Test
    fun `a letter with an accent on it is one press`() {
        assertEquals(accented.length, caret(field(accented, 0).move(Movement.Right)))
        assertEquals(0, caret(field(accented, accented.length).move(Movement.Left)))
    }

    @Test
    fun `backspace takes a whole family`() {
        val after = field("hi $family", 3 + family.length).apply(EditCommand.DeleteBackward)

        assertEquals("hi ", after.text, "not a leg of it")
    }

    @Test
    fun `delete takes a whole skin tone`() {
        val after = field("$tanned hi", 0).apply(EditCommand.DeleteForward)

        assertEquals(" hi", after.text)
    }

    @Test
    fun `a line ending is one character even though it is two`() {
        val after = field("a\r\nb", 3).apply(EditCommand.DeleteBackward)

        assertEquals("ab", after.text)
    }

    // --- words ----------------------------------------------------------------------------------

    @Test
    fun `a word at a time goes to the far edge of the word`() {
        val text = "hello brave world"

        assertEquals(5, caret(field(text, 0).move(Movement.WordRight)))
        assertEquals(11, caret(field(text, 5).move(Movement.WordRight)))
        assertEquals(6, caret(field(text, 11).move(Movement.WordLeft)))
        assertEquals(0, caret(field(text, 5).move(Movement.WordLeft)))
    }

    @Test
    fun `a run of spaces is crossed in one press`() {
        val text = "hello        world"

        assertEquals(text.length, caret(field(text, 5).move(Movement.WordRight)))
        assertEquals(0, caret(field(text, 13).move(Movement.WordLeft)))
    }

    @Test
    fun `punctuation is its own word`() {
        val text = "foo.bar"

        assertEquals(3, caret(field(text, 0).move(Movement.WordRight)), "stops at the dot")
        assertEquals(4, caret(field(text, 3).move(Movement.WordRight)), "then crosses it")
        assertEquals(7, caret(field(text, 4).move(Movement.WordRight)))
        assertEquals(4, caret(field(text, 7).move(Movement.WordLeft)))
        assertEquals(3, caret(field(text, 4).move(Movement.WordLeft)))
    }

    @Test
    fun `a run of punctuation is one word`() {
        val text = "wait...  what"

        assertEquals(4, caret(field(text, 0).move(Movement.WordRight)))
        assertEquals(7, caret(field(text, 4).move(Movement.WordRight)), "the three dots together")
    }

    @Test
    fun `a word press at either end stops there`() {
        val text = "hello"

        assertEquals(0, caret(field(text, 0).move(Movement.WordLeft)))
        assertEquals(5, caret(field(text, 5).move(Movement.WordRight)))
    }

    @Test
    fun `trailing spaces do not strand the caret`() {
        val text = "hello   "

        assertEquals(text.length, caret(field(text, 5).move(Movement.WordRight)))
        assertEquals(0, caret(field(text, text.length).move(Movement.WordLeft)))
    }

    @Test
    fun `an emoji counts as part of a word rather than as punctuation`() {
        val text = "hi$thumb there"

        assertEquals(2 + thumb.length, caret(field(text, 0).move(Movement.WordRight)))
    }

    // --- lines ----------------------------------------------------------------------------------

    @Test
    fun `home and end stop at the line the caret is on`() {
        val text = "first\nsecond\nthird"
        val middle = 8

        assertEquals(6, caret(field(text, middle).move(Movement.LineStart)))
        assertEquals(12, caret(field(text, middle).move(Movement.LineEnd)))
    }

    @Test
    fun `home on the first line and end on the last are the ends of the text`() {
        val text = "first\nsecond"

        assertEquals(0, caret(field(text, 3).move(Movement.LineStart)))
        assertEquals(text.length, caret(field(text, 8).move(Movement.LineEnd)))
    }

    @Test
    fun `a caret just after a line ending is at the start of the new line`() {
        val text = "first\nsecond"

        assertEquals(6, caret(field(text, 6).move(Movement.LineStart)))
        assertEquals(text.length, caret(field(text, 6).move(Movement.LineEnd)))
    }

    @Test
    fun `the ends of the lot are one press away`() {
        val text = "first\nsecond\nthird"

        assertEquals(0, caret(field(text, 9).move(Movement.TextStart)))
        assertEquals(text.length, caret(field(text, 9).move(Movement.TextEnd)))
    }

    // --- with shift held ---------------------------------------------------------------------------

    @Test
    fun `shift and an arrow drag one end and leave the other`() {
        val after = field("hello", 2).move(Movement.Right, extend = true)

        assertEquals(TextRange(2, 3), after.selection)
        assertEquals("l", after.selected)
    }

    @Test
    fun `shifting back the way shrinks the selection rather than growing it`() {
        var value = field("hello world", 0)
        repeat(5) { value = value.move(Movement.Right, extend = true) }
        assertEquals(TextRange(0, 5), value.selection)

        value = value.move(Movement.Left, extend = true)

        assertEquals(TextRange(0, 4), value.selection, "the anchor never moved")
    }

    @Test
    fun `a selection can be made backwards and then grown`() {
        var value = field("hello world", 5)
        repeat(5) { value = value.move(Movement.Left, extend = true) }

        assertEquals(TextRange(5, 0), value.selection)
        assertTrue(value.selection.reversed)
        assertEquals("hello", value.selected)
    }

    @Test
    fun `shift and a word takes the word`() {
        val after = field("hello world", 0).move(Movement.WordRight, extend = true)

        assertEquals("hello", after.selected)
    }

    @Test
    fun `shift and end takes the rest of the line`() {
        val after = field("first\nsecond", 0).move(Movement.LineEnd, extend = true)

        assertEquals("first", after.selected)
    }

    @Test
    fun `select all takes everything`() {
        val after = field("hello", 2).selectAll()

        assertEquals(TextRange(0, 5), after.selection)
        assertEquals("hello", after.selected)
    }

    // --- a plain arrow on a selection ------------------------------------------------------------------

    @Test
    fun `left on a selection puts the caret at its start without moving a character`() {
        val after = field("hello world", 0).copy(selection = TextRange(2, 7)).move(Movement.Left)

        assertEquals(TextRange(2), after.selection)
    }

    @Test
    fun `right on a selection puts the caret at its end`() {
        val after = field("hello world", 0).copy(selection = TextRange(2, 7)).move(Movement.Right)

        assertEquals(TextRange(7), after.selection)
    }

    @Test
    fun `left on a backwards selection still goes to the near end`() {
        val after = field("hello world", 0).copy(selection = TextRange(7, 2)).move(Movement.Left)

        assertEquals(TextRange(2), after.selection)
    }

    // --- what a double or triple click will ask for ------------------------------------------------------

    @Test
    fun `the word at a position is the same one the arrows stop either side of`() {
        val text = "hello brave world"

        assertEquals(TextRange(6, 11), text.wordAt(8))
        assertEquals(TextRange(6, 11), text.wordAt(6), "the near edge counts as inside")
        assertEquals(TextRange(6, 11), text.wordAt(11), "and so does the far one")
    }

    @Test
    fun `the word at a gap is the gap so a double click never selects nothing`() {
        val text = "hello   world"

        assertEquals(TextRange(5, 8), text.wordAt(7))
    }

    @Test
    fun `the word at the end of the text is the last word`() {
        assertEquals(TextRange(6, 11), "hello world".wordAt(11))
        assertEquals(TextRange.Zero, "".wordAt(0), "and an empty field has nothing to give")
    }

    @Test
    fun `the line at a position leaves the line ending out of it`() {
        val text = "first\nsecond\nthird"

        assertEquals(TextRange(6, 12), text.lineAt(8))
        assertEquals(TextRange(0, 5), text.lineAt(0))
        assertEquals(TextRange(13, 18), text.lineAt(18))
    }
}
