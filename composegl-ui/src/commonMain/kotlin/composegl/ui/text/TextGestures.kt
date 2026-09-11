package composegl.ui.text

import composegl.ui.geometry.Offset
import composegl.ui.input.PointerButton
import composegl.ui.input.PointerEvent
import kotlin.math.abs

/**
 * Selecting text with a pointer: click to put the caret, drag to take a stretch, double-click a
 * word, triple-click a line.
 *
 * It knows nothing about fonts or nodes. The one thing it is told is [indexAt]: given a point, which
 * character is there. That is the widget's business, because only the widget knows where its text
 * was drawn and how far it has scrolled — and it is what makes this testable without a window.
 *
 * The part everybody gets wrong is dragging *out* of the field. The answer here is to ask [indexAt]
 * anyway and expect it to clamp: a pointer above the field is at the start and one below it is at
 * the end, so a drag that leaves keeps selecting instead of stopping dead at the edge. Which is also
 * why this handles [PointerEvent.Move] wherever it happens rather than only over the text.
 *
 * Word and line drags grow by whole words and whole lines, the way they do in every editor: once a
 * double click has taken `brave`, dragging right takes `world` entire rather than one letter at a
 * time.
 *
 * @param indexAt where a point is in the string, clamped to it.
 * @param multiClickMillis how close together two presses must be to count as a double click.
 * @param slop how far apart they may be and still count. A mouse moves a pixel or two under a
 *   finger; a touch screen moves more.
 */
class TextGestures(
    private val indexAt: (Offset) -> Int,
    private val multiClickMillis: Long = 400L,
    private val slop: Float = 6f,
) {

    /** What one press started: dragging by character, by word, or by line. */
    private enum class Granularity { Character, Word, Line }

    private var granularity = Granularity.Character

    /** Where the drag started from: a caret index, or the whole word or line it began in. */
    private var anchor = TextRange.Zero

    private var lastPressAt: Offset? = null
    private var lastPressTime = 0L
    private var clicks = 0

    /** True between a press and the release that ends it. */
    var isDragging: Boolean = false
        private set

    /**
     * @param shift whether the player is holding shift, which turns a click into "extend to here".
     * @return the value afterwards, or null if this event meant nothing to a text field.
     */
    fun onPointer(event: PointerEvent, value: TextFieldValue, shift: Boolean = false): TextFieldValue? =
        when (event) {
            is PointerEvent.Press -> if (event.button != PointerButton.Primary) null else press(event, value, shift)
            is PointerEvent.Move -> if (!isDragging) null else drag(event, value)
            is PointerEvent.Release -> {
                isDragging = false
                null
            }
            is PointerEvent.Cancel -> {
                isDragging = false
                null
            }
            is PointerEvent.Scroll, is PointerEvent.Exit -> null
        }

    private fun press(event: PointerEvent, value: TextFieldValue, shift: Boolean): TextFieldValue {
        val index = indexAt(event.position)
        isDragging = true

        // Shift and a click extends what is already there, from the end the player is not holding.
        if (shift) {
            granularity = Granularity.Character
            anchor = TextRange(value.selection.start)
            clicks = 0
            return value.copy(selection = TextRange(value.selection.start, index))
        }

        clicks = if (isRepeat(event)) clicks % 3 + 1 else 1
        lastPressAt = event.position
        lastPressTime = event.timeMillis

        granularity = when (clicks) {
            1 -> Granularity.Character
            2 -> Granularity.Word
            else -> Granularity.Line
        }
        anchor = rangeAround(value.text, index)
        return value.copy(selection = if (granularity == Granularity.Character) TextRange(index) else anchor)
    }

    private fun drag(event: PointerEvent, value: TextFieldValue): TextFieldValue {
        val index = indexAt(event.position)
        val reach = rangeAround(value.text, index)

        // The anchor end never moves, so a drag back past where it started reverses the selection
        // rather than collapsing it — selecting backwards works exactly as well as forwards.
        val selection = if (reach.max <= anchor.min && reach.min < anchor.min) {
            TextRange(anchor.max, reach.min)
        } else {
            TextRange(anchor.min, reach.max)
        }
        return value.copy(selection = selection)
    }

    /** What one press at [index] takes: a caret, the word it is in, or the line it is on. */
    private fun rangeAround(text: String, index: Int): TextRange = when (granularity) {
        Granularity.Character -> TextRange(index)
        Granularity.Word -> text.wordAt(index)
        Granularity.Line -> text.lineAt(index)
    }

    private fun isRepeat(event: PointerEvent): Boolean {
        val previous = lastPressAt ?: return false
        if (event.timeMillis - lastPressTime > multiClickMillis) return false
        return abs(event.position.x - previous.x) <= slop && abs(event.position.y - previous.y) <= slop
    }
}
