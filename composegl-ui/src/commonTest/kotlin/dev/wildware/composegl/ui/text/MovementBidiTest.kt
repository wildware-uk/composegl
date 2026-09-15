package dev.wildware.composegl.ui.text

import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.layout.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The arrow keys in text that reads from the right.
 *
 * Every expectation is a list of where the caret is after each press, counted in the string. Drawn
 * with every character one unit wide, `abc אבג` has its caret positions at x = 0, 1, 2, 3, 7, 6, 5, 4
 * for indices 0 to 7 — the Hebrew is drawn reversed — so pressing right from the start should visit
 * x = 1 to 7 in turn, which is indices 1, 2, 3, 7, 6, 5, 4.
 */
class MovementBidiTest {

    private fun field(text: String, at: Int) = TextFieldValue(text, TextRange(at))

    private fun presses(
        text: String,
        from: Int,
        movement: Movement,
        times: Int,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ): List<Int> {
        var value = field(text, from)
        return (1..times).map {
            value = value.move(movement, direction = direction)
            value.selection.end
        }
    }

    @Test
    fun `left in a hebrew word moves on through the word`() {
        assertEquals(listOf(1, 2, 3, 4), presses("שלום", 0, Movement.Left, 4))
    }

    @Test
    fun `right in a hebrew word moves back to its first letter`() {
        assertEquals(listOf(3, 2, 1, 0), presses("שלום", 4, Movement.Right, 4))
    }

    @Test
    fun `at the edges of a hebrew line the arrows go nowhere`() {
        assertEquals(field("שלום", 4), field("שלום", 4).move(Movement.Left))
        assertEquals(field("שלום", 0), field("שלום", 0).move(Movement.Right))
    }

    @Test
    fun `right across a mixed line visits every drawn position in order`() {
        assertEquals(listOf(1, 2, 3, 7, 6, 5, 4, 4), presses("abc אבג", 0, Movement.Right, 8))
    }

    @Test
    fun `left across a mixed line is the same path backwards`() {
        assertEquals(listOf(5, 6, 7, 3, 2, 1, 0, 0), presses("abc אבג", 4, Movement.Left, 8))
    }

    @Test
    fun `digits inside hebrew are crossed the way they are drawn`() {
        // Drawn as "12 םולש": the digits on the left, in their own order.
        assertEquals(listOf(1, 2, 3, 4, 7, 6, 5, 5), presses("שלום 12", 0, Movement.Left, 8))
    }

    @Test
    fun `english moves exactly as it always has`() {
        assertEquals(listOf(1, 2, 3, 4, 5, 5), presses("hello", 0, Movement.Right, 6))
        assertEquals(listOf(1, 2, 3, 4, 5, 5), presses("hello", 0, Movement.Right, 6, LayoutDirection.Rtl))
    }

    @Test
    fun `left on a selection in hebrew collapses it to its left end`() {
        val selected = TextFieldValue("שלום", TextRange(1, 3))

        assertEquals(TextRange(3), selected.move(Movement.Left).selection, "the later end is on the left")
        assertEquals(TextRange(1), selected.move(Movement.Right).selection)
    }

    @Test
    fun `shift and left in hebrew selects the letter to the left`() {
        val after = field("שלום", 0).move(Movement.Left, extend = true)

        assertEquals(TextRange(0, 1), after.selection)
        assertEquals("ש", after.selected)
    }

    @Test
    fun `ctrl and left in a hebrew line goes on to the next word`() {
        assertEquals(4, field("שלום עולם", 0).move(Movement.WordLeft).selection.end)
        assertEquals(5, field("שלום עולם", 9).move(Movement.WordRight).selection.end)
    }

    @Test
    fun `right at the end of an english line carries on into a hebrew line below`() {
        val text = "abc\nשלום"

        assertEquals(4, field(text, 3).move(Movement.Right).selection.end, "to the start of the Hebrew, on its right")
        assertEquals(5, field(text, 4).move(Movement.Left).selection.end)
    }

    @Test
    fun `left at the left edge of a hebrew line carries on to the line after it`() {
        assertEquals(5, field("שלום\nabc", 4).move(Movement.Left).selection.end)
        assertEquals(4, field("שלום\nabc", 5).move(Movement.Left).selection.end, "and left at the start of english goes back up")
    }

    @Test
    fun `the keyboard sends the arrows across the screen`() {
        val editor = KeyboardEditor()
        val left = KeyEvent(Key.Left, KeyEventType.Down, Modifiers.None)
        val right = KeyEvent(Key.Right, KeyEventType.Down, Modifiers.None)

        assertEquals(TextRange(1), editor.onKey(left, field("שלום", 0))?.selection)
        assertEquals(TextRange(3), editor.onKey(right, field("שלום", 4))?.selection)
    }
}
