package composegl.ui.layout

import composegl.ui.geometry.Size

/**
 * How much room a child may take: a range on each axis.
 *
 * The heart of the layout model, and the reason it is worth copying Compose's rather than
 * inventing one. A parent says "you may be between 0 and 300 wide", the child answers with a size
 * inside that range, and the parent then decides where to put it. Two passes, no negotiation, no
 * cycles.
 *
 * An infinite maximum means "as much as you like" — what a scrolling column offers its children,
 * and what a row offers before it knows how to share out the space.
 */
data class Constraints(
    val minWidth: Float = 0f,
    val maxWidth: Float = Float.POSITIVE_INFINITY,
    val minHeight: Float = 0f,
    val maxHeight: Float = Float.POSITIVE_INFINITY,
) {

    init {
        require(minWidth >= 0f && minHeight >= 0f) { "constraints cannot be negative: $this" }
        require(minWidth <= maxWidth) { "minWidth $minWidth is more than maxWidth $maxWidth" }
        require(minHeight <= maxHeight) { "minHeight $minHeight is more than maxHeight $maxHeight" }
    }

    val hasBoundedWidth: Boolean get() = maxWidth.isFinite()
    val hasBoundedHeight: Boolean get() = maxHeight.isFinite()

    /** True when only one size is allowed, so measuring is a formality. */
    val isTight: Boolean get() = minWidth == maxWidth && minHeight == maxHeight

    fun constrainWidth(width: Float): Float = width.coerceIn(minWidth, maxWidth)

    fun constrainHeight(height: Float): Float = height.coerceIn(minHeight, maxHeight)

    /** The nearest size that is allowed. */
    fun constrain(size: Size): Size = Size(constrainWidth(size.width), constrainHeight(size.height))

    /**
     * The same constraints with room taken out for padding or a border.
     *
     * Both ends shrink, and neither goes below zero: a box smaller than its own padding has no
     * room for content rather than negative room for it.
     */
    fun shrink(horizontal: Float = 0f, vertical: Float = 0f): Constraints {
        // Taking nothing out changes nothing, and most nodes have no padding at all. Worth the
        // line: this is called once per node per frame, and the object it would have made is
        // thrown away a few microseconds later.
        if (horizontal == 0f && vertical == 0f) return this
        return Constraints(
            minWidth = (minWidth - horizontal).coerceAtLeast(0f),
            maxWidth = if (hasBoundedWidth) (maxWidth - horizontal).coerceAtLeast(0f) else maxWidth,
            minHeight = (minHeight - vertical).coerceAtLeast(0f),
            maxHeight = if (hasBoundedHeight) (maxHeight - vertical).coerceAtLeast(0f) else maxHeight,
        )
    }

    /** The same maxima with no minimum, which is what most parents actually want to offer. */
    fun loosen(): Constraints {
        if (minWidth == 0f && minHeight == 0f) return this
        return Constraints(0f, maxWidth, 0f, maxHeight)
    }

    /** Nothing but this size is allowed. */
    fun tighten(width: Float = minWidth, height: Float = minHeight) =
        Constraints(width, width, height, height)

    override fun toString(): String {
        fun axis(min: Float, max: Float) = if (min == max) "$min" else "$min..${if (max.isFinite()) "$max" else "∞"}"
        return "Constraints(w=${axis(minWidth, maxWidth)}, h=${axis(minHeight, maxHeight)})"
    }

    companion object {

        /** Any size at all. What a root with no window would offer, and what tests usually want. */
        val Unbounded = Constraints()

        /** Exactly this size and nothing else. */
        fun fixed(width: Float, height: Float) = Constraints(width, width, height, height)

        /** Up to this size, with no minimum. */
        fun atMost(width: Float = Float.POSITIVE_INFINITY, height: Float = Float.POSITIVE_INFINITY) =
            Constraints(maxWidth = width, maxHeight = height)
    }
}
