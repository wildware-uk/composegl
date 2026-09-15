package dev.wildware.composegl.ui.geometry

/**
 * A radius for each corner of a box, in virtual pixels.
 *
 * One number is right for nearly everything, and every call that draws a box still takes one. This
 * is for the shapes one number cannot make: a tab rounded only along its top, a panel docked to the
 * edge of the screen and square against it, a speech bubble with one sharp corner pointing at
 * whoever is talking, the middle button of a group with none rounded at all.
 *
 * Clockwise from the top-left, the order CSS uses, so a four-number skin entry reads the same way
 * a web developer already reads one. "Top" is the top on screen: y grows downwards here, and a
 * backend that counts the other way sorts that out once, inside itself.
 *
 * ```kotlin
 * Modifier.background(colour, Corners(topLeft = 8f, topRight = 8f))   // a tab
 * Modifier.background(colour, Corners.left(12f))                      // docked to the right edge
 * ```
 *
 * A radius bigger than half the box's shorter side is drawn as half of it, exactly as a single
 * corner already was. That is decided by whatever draws the box, at the size it is drawn, rather
 * than here, where the size is not known yet.
 */
data class Corners(
    val topLeft: Float = 0f,
    val topRight: Float = 0f,
    val bottomRight: Float = 0f,
    val bottomLeft: Float = 0f,
) {
    init {
        // Checked here, where the bad number is nearest whatever produced it, rather than turning
        // into a shader that quietly draws a box inside out.
        require(topLeft >= 0f && topRight >= 0f && bottomRight >= 0f && bottomLeft >= 0f) {
            "a corner radius cannot be negative or NaN, was $this"
        }
    }

    /** True when all four are the same, which is the case a single radius already covers. */
    val isUniform: Boolean
        get() = topLeft == topRight && topRight == bottomRight && bottomRight == bottomLeft

    /** The smallest of the four. What a canvas that cannot round corners separately draws instead. */
    val smallest: Float get() = minOf(topLeft, topRight, bottomRight, bottomLeft)

    /** The largest of the four. */
    val largest: Float get() = maxOf(topLeft, topRight, bottomRight, bottomLeft)

    /**
     * The smallest box these corners fit in without two curves running into each other.
     *
     * Across the top the two top radii sit side by side, and across the bottom the two bottom ones
     * do, so the width is whichever pair is wider — and the same down each side for the height.
     * One radius all round gives twice the radius each way, which is what it always was.
     */
    val minimumSize: Size
        get() = Size(
            maxOf(topLeft + topRight, bottomLeft + bottomRight),
            maxOf(topLeft + bottomLeft, topRight + bottomRight),
        )

    companion object {

        /** Four square corners. */
        val None = Corners()

        /** The same radius on every corner: exactly what a single `corner` means. */
        fun all(radius: Float) = Corners(radius, radius, radius, radius)

        /** The top two rounded and the bottom two square. A tab sitting on the panel it opens. */
        fun top(radius: Float) = Corners(topLeft = radius, topRight = radius)

        /** The bottom two rounded. A drop-down hanging from the thing that opened it. */
        fun bottom(radius: Float) = Corners(bottomRight = radius, bottomLeft = radius)

        /** The left two rounded. A panel docked against the right-hand edge of the screen. */
        fun left(radius: Float) = Corners(topLeft = radius, bottomLeft = radius)

        /** The right two rounded. A panel docked against the left-hand edge of the screen. */
        fun right(radius: Float) = Corners(topRight = radius, bottomRight = radius)

        /**
         * What an old single `corner` argument becomes: that radius all round, or square if it is not
         * above zero.
         *
         * Lenient where [Corners] itself is strict, because a single radius never was checked. A
         * negative one drew square corners, and a corner animated with a spring that overshoots zero
         * still has to draw rather than throw in the middle of a frame.
         */
        internal fun single(radius: Float) = if (radius > 0f) all(radius) else None
    }
}
