package composegl.ui.text

import kotlin.math.max
import kotlin.math.min

/**
 * A stretch of text, or a place in it.
 *
 * Two indices into a string, counted between the characters: 0 is before the first one and
 * `text.length` is after the last. A range where [start] and [end] are the same is a caret.
 *
 * The two ends are not sorted, and that is the point. [start] is where the selection was begun and
 * [end] is where the player has dragged it to, so a selection made right-to-left has [end] before
 * [start]. Holding shift and pressing left again has to shrink that selection rather than grow it,
 * and the only thing that knows which end to move is which end the player is holding. [min] and
 * [max] are there for everything that does not care.
 *
 * It is not an `IntRange` for the same reason: an `IntRange` has no empty-at-a-position — `3..2`
 * and `5..4` are both "empty" — and a text field is a caret far more often than it is a selection.
 */
data class TextRange(val start: Int, val end: Int) {

    /** A caret: a selection of nothing, at [index]. */
    constructor(index: Int) : this(index, index)

    /** The lower end, whichever way round it was made. */
    val min: Int get() = min(start, end)

    /** The upper end, whichever way round it was made. */
    val max: Int get() = max(start, end)

    /** How many characters are selected. Zero for a caret. */
    val length: Int get() = max - min

    /** True when this is a caret rather than a selection. */
    val collapsed: Boolean get() = start == end

    /** True when the player made it backwards, by dragging or shifting to the left. */
    val reversed: Boolean get() = end < start

    /** A caret at [min], where a left arrow puts one after a selection. */
    fun collapseToStart(): TextRange = TextRange(min)

    /** A caret at [max], where a right arrow puts one, and where typing over a selection lands. */
    fun collapseToEnd(): TextRange = TextRange(max)

    operator fun contains(index: Int): Boolean = index in min..max

    /** True when the two overlap in more than a shared edge. */
    fun intersects(other: TextRange): Boolean = min < other.max && other.min < max

    override fun toString(): String = if (collapsed) "TextRange($start)" else "TextRange($start, $end)"

    companion object {
        val Zero = TextRange(0)
    }
}
