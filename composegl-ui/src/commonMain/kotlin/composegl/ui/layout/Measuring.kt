package composegl.ui.layout

/**
 * Something that has been measured and is waiting to be told where it goes.
 *
 * The split between measuring and placing is what keeps layout to two passes. A parent measures
 * every child to learn how big they are, works out the arrangement knowing all of them, and only
 * then places each one. Without the split, a row could not centre its children without measuring
 * them twice.
 */
abstract class Placeable {

    abstract val width: Float
    abstract val height: Float

    /** Called by the parent, from inside its placement block. Coordinates are relative to it. */
    abstract fun placeAt(x: Float, y: Float)
}

/**
 * A child a parent may measure.
 *
 * A parent gets these, not nodes: it can ask how big a child would like to be, and nothing else.
 * That is what stops layouts reaching into each other.
 */
interface Measurable {

    fun measure(constraints: Constraints): Placeable

    /**
     * What the child said about itself that its parent needs — a weight in a row, an alignment in
     * a box. Null when the child said nothing.
     */
    val layoutData: LayoutData
}

/** The parts of a child's modifier that only its parent can act on. */
data class LayoutData(
    val weight: Float? = null,
    val alignment: Alignment? = null,
) {
    companion object {
        val None = LayoutData()
    }
}

/** The size a layout chose, and how to place its children once it has been given that size. */
interface MeasureResult {
    val width: Float
    val height: Float
    fun placeChildren(scope: PlacementScope)
}

/** Where placing happens. Exists so that `placeAt` can only be called at the right moment. */
interface PlacementScope {
    fun Placeable.at(x: Float, y: Float) = placeAt(x, y)
}

/**
 * Where measuring happens. Exists so that [layout] can only be called at the right moment.
 *
 * It also lends a layout the scratch space it needs while it works. A layout has to hold on to its
 * children between measuring them and placing them, and the obvious way — a list built on the spot
 * — is a list per node per frame, for a tree that is measured every frame whether it changed or
 * not. So the room is lent instead: it belongs to the node, it is used again next frame, and it is
 * good until this measure returns. Keeping it past that is keeping somebody else's paper.
 *
 * A layout is free to ignore all three and build its own lists. Nothing checks.
 */
interface MeasureScope {

    /**
     * The size this layout has chosen, and what to do once it is settled.
     *
     * @param place runs after every layout in the tree has a size, which is why a parent can
     *   centre a child it measured earlier without measuring it again.
     */
    fun layout(width: Float, height: Float, place: PlacementScope.() -> Unit): MeasureResult =
        object : MeasureResult {
            override val width = width
            override val height = height
            override fun placeChildren(scope: PlacementScope) = scope.place()
        }

    /** Room for [count] children, from index zero. May be longer than asked for. */
    fun placeables(count: Int): Array<Placeable?> = arrayOfNulls(count)

    /** Room for [count] numbers: how big each child turned out. */
    fun sizes(count: Int): FloatArray = FloatArray(count)

    /** Room for [count] numbers: where each child starts. A different array from [sizes]. */
    fun positions(count: Int): FloatArray = FloatArray(count)
}

/**
 * How one kind of layout arranges its children. A row, a column, a box, or anything a game writes.
 *
 * There is no private hook here that only the built-in layouts can reach. If `Row` needs something
 * this interface cannot express, the model is wrong and the fix is to change the model.
 */
fun interface MeasurePolicy {

    fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult

    companion object {

        /**
         * Children laid on top of each other at the origin; the node is as big as the largest of
         * them. This is also what a node gets when nobody gave it a policy, so a tree of bare
         * `Layout` calls behaves sensibly instead of collapsing to nothing.
         */
        val Stack = MeasurePolicy { measurables, constraints ->
            val count = measurables.size
            val placeables = placeables(count)
            val offered = if (count == 0) constraints else constraints.loosen()

            var widest = 0f
            var tallest = 0f
            for (index in 0 until count) {
                val placeable = measurables[index].measure(offered)
                placeables[index] = placeable
                if (placeable.width > widest) widest = placeable.width
                if (placeable.height > tallest) tallest = placeable.height
            }

            layout(constraints.constrainWidth(widest), constraints.constrainHeight(tallest)) {
                for (index in 0 until count) placeables[index]?.at(0f, 0f)
            }
        }

        /** Nothing inside: the node takes the smallest size it is allowed. */
        val Empty = MeasurePolicy { _, constraints ->
            layout(constraints.minWidth, constraints.minHeight) {}
        }
    }
}

