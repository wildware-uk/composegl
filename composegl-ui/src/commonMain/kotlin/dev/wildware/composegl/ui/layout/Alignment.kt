package dev.wildware.composegl.ui.layout

/** Where something sits across the width it was given. */
enum class HorizontalAlignment { Start, Centre, End }

/** Where something sits down the height it was given. */
enum class VerticalAlignment {
    Top,
    Centre,
    Bottom,

    /**
     * Stood on the same line as its neighbours' text: a big "120" beside a small "HP", with the
     * letters of both on one baseline rather than their boxes lined up at the top or the middle.
     *
     * Only a [Row] can do this, because only a row has neighbours side by side to line up with.
     * Each child's first baseline is lined up, the row is as tall as the tallest reach above that
     * line plus the deepest below it, and a child with no text in it at all goes at the top.
     *
     * Anywhere else — a [Box], or [Alignment.yIn] asked directly — there is nothing to line up
     * against, and it is the same as [Top].
     */
    Baseline,
}

/**
 * Where something sits inside a bigger box.
 *
 * Spelled the way the rest of this codebase is spelled. Nobody has ever misread `Centre`.
 */
data class Alignment(
    val horizontal: HorizontalAlignment = HorizontalAlignment.Start,
    val vertical: VerticalAlignment = VerticalAlignment.Top,
) {

    /** How far in from the left a child of [childWidth] goes, across a space [width] wide. */
    fun xIn(width: Float, childWidth: Float): Float = when (horizontal) {
        HorizontalAlignment.Start -> 0f
        HorizontalAlignment.Centre -> (width - childWidth) / 2f
        HorizontalAlignment.End -> width - childWidth
    }

    /**
     * The same, with `Start` and `End` on the sides [direction] puts them: in a right-to-left
     * screen a `Start` child goes against the right. What every layout here asks.
     */
    fun xIn(width: Float, childWidth: Float, direction: LayoutDirection): Float =
        when (horizontal.absolute(direction)) {
            HorizontalAlignment.Start -> 0f
            HorizontalAlignment.Centre -> (width - childWidth) / 2f
            HorizontalAlignment.End -> width - childWidth
        }

    /** How far down from the top a child of [childHeight] goes, down a space [height] tall. */
    fun yIn(height: Float, childHeight: Float): Float = when (vertical) {
        // A baseline is something only a row can line up, and it does so before asking this; see
        // VerticalAlignment.Baseline. Alone in a box it has nothing to line up with.
        VerticalAlignment.Top, VerticalAlignment.Baseline -> 0f
        VerticalAlignment.Centre -> (height - childHeight) / 2f
        VerticalAlignment.Bottom -> height - childHeight
    }

    /**
     * Both at once, for the callers that want a pair rather than two numbers.
     *
     * The two above are what the layouts themselves use: a pair of boxed floats per child per
     * frame is three objects that a game running at sixty frames a second does not need to make.
     */
    fun offsetIn(width: Float, height: Float, childWidth: Float, childHeight: Float): Pair<Float, Float> =
        xIn(width, childWidth) to yIn(height, childHeight)

    companion object {
        val TopStart = Alignment(HorizontalAlignment.Start, VerticalAlignment.Top)
        val TopCentre = Alignment(HorizontalAlignment.Centre, VerticalAlignment.Top)
        val TopEnd = Alignment(HorizontalAlignment.End, VerticalAlignment.Top)
        val CentreStart = Alignment(HorizontalAlignment.Start, VerticalAlignment.Centre)
        val Centre = Alignment(HorizontalAlignment.Centre, VerticalAlignment.Centre)
        val CentreEnd = Alignment(HorizontalAlignment.End, VerticalAlignment.Centre)
        val BottomStart = Alignment(HorizontalAlignment.Start, VerticalAlignment.Bottom)
        val BottomCentre = Alignment(HorizontalAlignment.Centre, VerticalAlignment.Bottom)
        val BottomEnd = Alignment(HorizontalAlignment.End, VerticalAlignment.Bottom)
    }
}

/** Space taken off each side of a box before its content is laid out. */
data class Padding(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {

    val horizontal: Float get() = left + right
    val vertical: Float get() = top + bottom

    operator fun plus(other: Padding) = Padding(
        left + other.left,
        top + other.top,
        right + other.right,
        bottom + other.bottom,
    )

    companion object {
        val None = Padding()

        fun all(amount: Float) = Padding(amount, amount, amount, amount)

        fun symmetric(horizontal: Float = 0f, vertical: Float = 0f) =
            Padding(horizontal, vertical, horizontal, vertical)
    }
}
