package dev.wildware.composegl.ui.text

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Selecting with a pointer.
 *
 * The text is pretended to be laid out at ten units a character on one line, so a click at x=35 is
 * between the third and fourth character — and, crucially, a click at x=-40 or x=9999 is at one end
 * or the other rather than an exception. That clamping is what makes a drag that leaves the field
 * keep selecting, which is the thing every hand-rolled version gets wrong.
 */
class TextGesturesTest {

    private val text = "hello brave world"
    private val width = 10f

    private var value = TextFieldValue(text, TextRange(0))

    /** Where a point is in the string: rounded to the nearest gap between characters, and clamped. */
    private val gestures = TextGestures(indexAt = { point ->
        ((point.x + width / 2f) / width).toInt().coerceIn(0, text.length)
    })

    private var clock = 0L

    private fun at(index: Int) = Offset(index * width, 8f)

    private fun press(index: Int, shift: Boolean = false, after: Long = 500L) {
        clock += after
        send(PointerEvent.Press(PointerId.Mouse, at(index), PointerButton.Primary, timeMillis = clock), shift)
    }

    /** A second press in the same place, soon enough to count as a double click. */
    private fun pressAgain(index: Int) = press(index, after = 80L)

    private fun drag(index: Int) {
        clock += 16
        send(PointerEvent.Move(PointerId.Mouse, at(index), setOf(PointerButton.Primary), timeMillis = clock))
    }

    private fun dragTo(point: Offset) {
        clock += 16
        send(PointerEvent.Move(PointerId.Mouse, point, setOf(PointerButton.Primary), timeMillis = clock))
    }

    private fun release(index: Int) {
        clock += 16
        send(PointerEvent.Release(PointerId.Mouse, at(index), PointerButton.Primary, timeMillis = clock))
    }

    private fun send(event: PointerEvent, shift: Boolean = false) {
        gestures.onPointer(event, value, shift)?.let { value = it }
    }

    private val selected: String get() = value.selected

    // --- a click ------------------------------------------------------------------------------------

    @Test
    fun `a click puts the caret where it was clicked`() {
        press(3)

        assertEquals(TextRange(3), value.selection)
    }

    @Test
    fun `a click past the end puts the caret at the end`() {
        dragOrPressAt(Offset(9999f, 8f))

        assertEquals(TextRange(text.length), value.selection)
    }

    @Test
    fun `a click before the start puts the caret at the start`() {
        dragOrPressAt(Offset(-400f, 8f))

        assertEquals(TextRange(0), value.selection)
    }

    private fun dragOrPressAt(point: Offset) {
        clock += 500
        send(PointerEvent.Press(PointerId.Mouse, point, PointerButton.Primary, timeMillis = clock))
    }

    @Test
    fun `a right-hand click is not a text selection`() {
        value = value.copy(selection = TextRange(2, 5))
        clock += 500

        val answer = gestures.onPointer(
            PointerEvent.Press(PointerId.Mouse, at(0), PointerButton.Secondary, timeMillis = clock),
            value,
        )

        assertNull(answer, "the selection is left alone for whatever the menu does with it")
    }

    // --- a drag -------------------------------------------------------------------------------------

    @Test
    fun `a drag takes what it crosses`() {
        press(0)
        drag(5)

        assertEquals("hello", selected)
        assertTrue(gestures.isDragging)
    }

    @Test
    fun `a drag backwards works as well as one forwards`() {
        press(11)
        drag(6)

        assertEquals("brave", selected)
        assertEquals(TextRange(11, 6), value.selection, "the anchor is the end the player is not holding")
        assertTrue(value.selection.reversed)
    }

    @Test
    fun `a drag that runs back past where it started reverses rather than collapsing`() {
        press(6)
        drag(11)
        assertEquals("brave", selected)

        drag(0)

        assertEquals("hello ", selected)
        assertTrue(value.selection.reversed)
    }

    @Test
    fun `a drag that leaves the field keeps selecting`() {
        press(6)

        dragTo(Offset(9999f, 400f))

        assertEquals("brave world", selected, "below and to the right of the field is its end")

        dragTo(Offset(-9999f, -400f))

        assertEquals("hello ", selected, "and above and to the left is its start")
    }

    @Test
    fun `releasing keeps the selection and ends the drag`() {
        press(0)
        drag(5)
        release(5)

        assertEquals("hello", selected)
        assertTrue(!gestures.isDragging)
    }

    @Test
    fun `a move with nothing held changes nothing`() {
        press(0)
        drag(5)
        release(5)

        clock += 16
        send(PointerEvent.Move(PointerId.Mouse, at(12), timeMillis = clock))

        assertEquals("hello", selected, "hovering over a field does not select")
    }

    @Test
    fun `a cancelled drag stops rather than following the pointer`() {
        press(0)
        drag(5)

        clock += 16
        send(PointerEvent.Cancel(PointerId.Mouse, at(12), timeMillis = clock))
        clock += 16
        send(PointerEvent.Move(PointerId.Mouse, at(17), setOf(PointerButton.Primary), timeMillis = clock))

        assertEquals("hello", selected)
    }

    // --- two clicks ---------------------------------------------------------------------------------

    @Test
    fun `a double click takes the word under it`() {
        press(8)
        pressAgain(8)

        assertEquals("brave", selected)
        assertEquals(TextRange(6, 11), value.selection)
    }

    @Test
    fun `the word a double click takes is the one the arrow keys stop either side of`() {
        press(8)
        pressAgain(8)

        val keyboard = TextFieldValue(text, TextRange(8))
            .move(Movement.WordLeft)
            .move(Movement.WordRight, extend = true)

        assertEquals(keyboard.selection, value.selection, "two ways of asking for a word must agree")
    }

    @Test
    fun `two clicks far apart are two separate clicks`() {
        press(8)
        press(2, after = 80L)

        assertEquals(TextRange(2), value.selection, "a caret, not a word")
    }

    @Test
    fun `two clicks long apart are two separate clicks`() {
        press(8)
        press(8, after = 900L)

        assertEquals(TextRange(8), value.selection)
    }

    @Test
    fun `dragging after a double click grows by whole words`() {
        press(8)
        pressAgain(8)

        drag(13)

        assertEquals("brave world", selected, "not 'brave wor'")
    }

    @Test
    fun `dragging backwards after a double click grows by whole words too`() {
        press(8)
        pressAgain(8)

        drag(2)

        assertEquals("hello brave", selected)
        assertTrue(value.selection.reversed)
    }

    // --- three clicks -------------------------------------------------------------------------------

    @Test
    fun `a triple click takes the line`() {
        val lines = "first line\nsecond line"
        value = TextFieldValue(lines, TextRange(0))
        val gestures = TextGestures(indexAt = { point ->
            ((point.x + width / 2f) / width).toInt().coerceIn(0, lines.length)
        })

        var time = 0L
        repeat(3) {
            time += 80
            gestures.onPointer(
                PointerEvent.Press(PointerId.Mouse, Offset(15 * width, 8f), timeMillis = time),
                value,
            )?.let { value = it }
        }

        assertEquals("second line", value.selected)
    }

    @Test
    fun `a fourth click starts again with a caret`() {
        press(8)
        pressAgain(8)
        pressAgain(8)
        assertEquals(text, selected, "three clicks took the whole line")

        pressAgain(8)

        assertEquals(TextRange(8), value.selection)
    }

    // --- shift and a click ----------------------------------------------------------------------------

    @Test
    fun `shift and a click extends from where the caret was`() {
        press(6)

        press(11, shift = true)

        assertEquals("brave", selected)
    }

    @Test
    fun `shift and a click before the caret selects backwards`() {
        press(11)

        press(6, shift = true)

        assertEquals("brave", selected)
        assertTrue(value.selection.reversed)
    }

    @Test
    fun `shift and a click extends the selection that is already there`() {
        press(0)
        drag(5)
        release(5)

        press(11, shift = true)

        assertEquals("hello brave", selected, "from the anchor, not from the end it grew to")
    }

    @Test
    fun `dragging after a shift click carries on extending`() {
        press(6)
        press(11, shift = true)

        drag(17)

        assertEquals("brave world", selected)
    }
}
