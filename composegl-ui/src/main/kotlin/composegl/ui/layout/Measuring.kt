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

/** Where measuring happens. Exists so that [layout] can only be called at the right moment. */
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
            val placeables = measurables.map { it.measure(constraints.loosen()) }
            layout(
                constraints.constrainWidth(placeables.maxOfOrNull { it.width } ?: 0f),
                constraints.constrainHeight(placeables.maxOfOrNull { it.height } ?: 0f),
            ) {
                placeables.forEach { it.at(0f, 0f) }
            }
        }

        /** Nothing inside: the node takes the smallest size it is allowed. */
        val Empty = MeasurePolicy { _, constraints ->
            layout(constraints.minWidth, constraints.minHeight) {}
        }
    }
}

