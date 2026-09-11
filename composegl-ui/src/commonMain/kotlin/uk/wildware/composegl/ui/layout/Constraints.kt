package uk.wildware.composegl.ui.layout

import uk.wildware.composegl.ui.geometry.Size

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
    fun shrink(horizontal: Float = 0f, vertical: Float = 0f): Constraints =
        shrink(horizontal, vertical, null)

    /** The same, remembering the answer in [cache], so a screen standing still makes nothing. */
    fun shrink(horizontal: Float, vertical: Float, cache: ConstraintsCache): Constraints =
        shrink(horizontal, vertical, cache as ConstraintsCache?)

    internal fun shrink(horizontal: Float, vertical: Float, cache: ConstraintsCache?): Constraints {
        // Taking nothing out changes nothing, and most nodes have no padding at all. Worth the
        // line: this is called once per node per frame, and the object it would have made is
        // thrown away a few microseconds later.
        if (horizontal == 0f && vertical == 0f) return this
        val minWidth = (this.minWidth - horizontal).coerceAtLeast(0f)
        val maxWidth = if (hasBoundedWidth) (this.maxWidth - horizontal).coerceAtLeast(0f) else this.maxWidth
        val minHeight = (this.minHeight - vertical).coerceAtLeast(0f)
        val maxHeight = if (hasBoundedHeight) (this.maxHeight - vertical).coerceAtLeast(0f) else this.maxHeight
        return cache?.of(minWidth, maxWidth, minHeight, maxHeight)
            ?: Constraints(minWidth, maxWidth, minHeight, maxHeight)
    }

    /** The same maxima with no minimum, which is what most parents actually want to offer. */
    /** The same, remembering the answer in [cache], so a screen standing still makes nothing. */
    fun loosen(cache: ConstraintsCache): Constraints {
        if (minWidth == 0f && minHeight == 0f) return this
        return cache.of(0f, maxWidth, 0f, maxHeight)
    }

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

/**
 * The last [Constraints] worked out here, handed back when the numbers have not moved.
 *
 * A layout pass runs every frame in a game whether anything changed or not, and the numbers a node
 * or a child is offered are the same every time for a screen that is standing still. So the object
 * is kept and compared field by field rather than rebuilt — which costs four float comparisons and
 * saves an allocation on nearly every node, nearly every frame.
 *
 * A layout gets these lent to it by [MeasureScope.offers], one per child. Holding one privately
 * works just as well; what must not happen is two different sets of numbers sharing one, because
 * then neither is ever the one that was kept.
 *
 * [Constraints] is immutable, so handing the same one back twice is safe however far it travels.
 */
class ConstraintsCache {

    private var held: Constraints? = null

    fun of(minWidth: Float, maxWidth: Float, minHeight: Float, maxHeight: Float): Constraints {
        val held = held
        if (held != null &&
            held.minWidth == minWidth && held.maxWidth == maxWidth &&
            held.minHeight == minHeight && held.maxHeight == maxHeight
        ) {
            return held
        }
        return Constraints(minWidth, maxWidth, minHeight, maxHeight).also { this.held = it }
    }
}
