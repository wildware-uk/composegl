package composegl.ui.layout

import composegl.ui.modifier.ResolvedModifier
import composegl.ui.node.UiNode

/**
 * One layout pass over a tree.
 *
 * Two walks, not one, and both of them fall out of the same recursion: a node measures its
 * children, chooses its own size, and then places them. Positions are stored relative to the
 * parent, so nothing has to be revisited when the parent itself moves.
 *
 * A pass is a throwaway object, and it is the only thing here that is. The small objects a walk
 * needs — the wrapper round each child, the list they go in, the placeable each node hands back —
 * live on the nodes and are used again next frame, because a game runs this every frame whether
 * anything changed or not: making them fresh each time is a few hundred pieces of rubbish a frame
 * for a screen that is standing still. Which pass is running is a reference, compared by identity,
 * so "measured exactly once" is still checked and still costs nothing.
 */
class MeasurePass {

    /** Measures and places [node] and everything under it. The root ends up at the origin. */
    fun run(node: UiNode, constraints: Constraints) {
        measure(node, constraints).placeAt(0f, 0f)
    }

    internal fun measure(node: UiNode, incoming: Constraints): Placeable {
        val resolved = node.resolved
        val outer = resolved.applyTo(incoming, node.outerConstraints)
        val padding = resolved.padding
        // Not loosened. A policy has to see the minimum it was given, or a row told to be 200
        // wide arranges its children inside the 40 they happen to add up to. Loosening for
        // children is each policy's own decision, and every one of them makes it.
        val content = outer.shrink(padding.horizontal, padding.vertical, node.contentConstraints)

        val measurables = measurables(node)
        val result = with(node.measurePolicy) { node.scope.measure(measurables, content) }

        // The two axes separately rather than through a Size: the object would be made and read
        // once each, per node, every frame.
        node.width = outer.constrainWidth(result.width + padding.horizontal)
        node.height = outer.constrainHeight(result.height + padding.vertical)

        // Children are placed now, in this node's coordinates. Where *this* node ends up is its
        // parent's business and does not change any of them.
        result.placeChildren(node.inset.at(padding.left, padding.top))

        return node.placeable.on(resolved)
    }

    /**
     * This node's children, each wrapped, in a list that belongs to the node.
     *
     * The list is refilled rather than rebuilt, and in the ordinary case — the same children as
     * last frame, in the same order — refilling it writes nothing at all.
     */
    private fun measurables(node: UiNode): List<Measurable> {
        val children = node.children
        val measurables = node.measurables
        if (measurables.size != children.size) {
            measurables.clear()
            for (index in children.indices) measurables.add(children[index].measurable.begin(this))
        } else {
            for (index in children.indices) {
                val wrapped = children[index].measurable.begin(this)
                if (measurables[index] !== wrapped) measurables[index] = wrapped
            }
        }
        return measurables
    }
}

/**
 * A child, wrapped so that measuring it twice is an error rather than a quiet cost.
 *
 * One per node, kept on the node. [begin] is what makes it safe to keep: it hands the wrapper to
 * whichever pass is running now, which is what the check below compares against.
 */
internal class OnceMeasurable(private val node: UiNode) : Measurable {

    private var pass: MeasurePass? = null
    private var measuredBy: MeasurePass? = null
    private var data = LayoutData.None

    override val layoutData: LayoutData get() = data

    fun begin(pass: MeasurePass): OnceMeasurable {
        this.pass = pass
        measuredBy = null

        // Rebuilt only when it actually differs, which for almost every node is never: a weight
        // and an alignment are written in a modifier chain and then stay there.
        val resolved = node.resolved
        if (data.weight != resolved.weight || data.alignment != resolved.alignment) {
            data = if (resolved.weight == null && resolved.alignment == null) LayoutData.None
            else LayoutData(resolved.weight, resolved.alignment)
        }
        return this
    }

    override fun measure(constraints: Constraints): Placeable {
        val pass = checkNotNull(pass) { "${node.name} was measured outside a pass" }
        check(measuredBy !== pass) {
            "${node.name} was measured twice in one pass. A layout that measures a child more " +
                "than once doubles the cost of every node beneath it, and nesting two of them " +
                "squares it. Measure once and use the Placeable you got back."
        }
        measuredBy = pass
        return pass.measure(node, constraints)
    }
}

/**
 * Placing a node writes its position, plus whatever its `offset` modifier asked for.
 *
 * One per node, kept on the node, and [on] points it at the resolution this pass read — which can
 * be a different object from last frame's even when it says the same thing.
 */
internal class NodePlaceable(private val node: UiNode) : Placeable() {

    private var resolved: ResolvedModifier = ResolvedModifier.None

    fun on(resolved: ResolvedModifier): NodePlaceable {
        this.resolved = resolved
        return this
    }

    override val width get() = node.width
    override val height get() = node.height

    override fun placeAt(x: Float, y: Float) {
        node.x = x + resolved.offset.x
        node.y = y + resolved.offset.y
    }
}

/**
 * Placement inside a padded node: the content box starts in from the edge.
 *
 * One per node, kept on the node. Nothing nests here — a node places its own children and then
 * hands back, so the two numbers are only ever read by the placement they were set for.
 */
internal class Inset : PlacementScope {

    private var dx = 0f
    private var dy = 0f

    fun at(dx: Float, dy: Float): Inset {
        this.dx = dx
        this.dy = dy
        return this
    }

    override fun Placeable.at(x: Float, y: Float) = placeAt(x + dx, y + dy)
}

/**
 * What `size`, `width`, `height` and the `fillMax*` family do to the room a node is offered.
 *
 * `size` asks for exactly that, clamped to what the parent allows — a child cannot escape its
 * parent by asking to be enormous. `fillMaxWidth` takes a share of what is on offer, and does
 * nothing at all when the offer is unbounded, because there is no share of infinity.
 *
 * When a chain says both, `fill` wins on the axis it names: `Modifier.size(50f).fillMaxWidth()`
 * is 50 tall and as wide as it can be.
 */
internal fun ResolvedModifier.applyTo(incoming: Constraints, cache: ConstraintsCache): Constraints {
    if (size == null && fill == null) return incoming

    // The four numbers first, one object at the end. Written out rather than with `let`, because
    // a lambda that assigns to a local puts that local in a heap box and makes a fresh lambda to
    // reach it — and built once rather than an axis at a time, because a node that says both
    // `width` and `height` would otherwise make an object to throw away on the way to the second.
    var minWidth = incoming.minWidth
    var maxWidth = incoming.maxWidth
    var minHeight = incoming.minHeight
    var maxHeight = incoming.maxHeight

    val width = size?.width
    if (width != null) {
        val fixed = incoming.constrainWidth(width)
        minWidth = fixed
        maxWidth = fixed
    }
    val height = size?.height
    if (height != null) {
        val fixed = incoming.constrainHeight(height)
        minHeight = fixed
        maxHeight = fixed
    }

    // `fill` wins on the axis it names: Modifier.size(50f).fillMaxWidth() is 50 tall and as wide
    // as it can be. There is no share of infinity, so an unbounded offer is left alone.
    val widthFraction = fill?.widthFraction
    if (widthFraction != null && incoming.hasBoundedWidth) {
        val fixed = incoming.constrainWidth(incoming.maxWidth * widthFraction)
        minWidth = fixed
        maxWidth = fixed
    }
    val heightFraction = fill?.heightFraction
    if (heightFraction != null && incoming.hasBoundedHeight) {
        val fixed = incoming.constrainHeight(incoming.maxHeight * heightFraction)
        minHeight = fixed
        maxHeight = fixed
    }

    if (minWidth == incoming.minWidth && maxWidth == incoming.maxWidth &&
        minHeight == incoming.minHeight && maxHeight == incoming.maxHeight
    ) {
        return incoming
    }
    return cache.of(minWidth, maxWidth, minHeight, maxHeight)
}

/**
 * The scope one node is measured in: its scratch space, and the result it hands back.
 *
 * One per node, kept on the node, which is what makes the room it lends free. Nothing here nests —
 * a node's policy runs to the end before the node's parent carries on, and a child measured in the
 * middle of it is using its own scope, not this one.
 */
internal class NodeMeasureScope : MeasureScope {

    private val result = ReusableResult()

    private var placeables = arrayOfNulls<Placeable>(0)
    private var sizes = FloatArray(0)
    private var positions = FloatArray(0)
    private var placements = FloatArray(0)
    private var offers = emptyArray<ConstraintsCache>()

    override fun layout(width: Float, height: Float, place: PlacementScope.() -> Unit): MeasureResult {
        result.width = width
        result.height = height
        result.place = place
        result.count = -1
        return result
    }

    override fun layout(width: Float, height: Float, count: Int): MeasureResult {
        result.width = width
        result.height = height
        result.place = null
        result.count = count
        result.placeables = placeables
        result.placements = placements
        return result
    }

    override fun placeables(count: Int): Array<Placeable?> {
        if (placeables.size < count) placeables = arrayOfNulls(count)
        return placeables
    }

    override fun sizes(count: Int): FloatArray {
        if (sizes.size < count) sizes = FloatArray(count)
        return sizes
    }

    override fun positions(count: Int): FloatArray {
        if (positions.size < count) positions = FloatArray(count)
        return positions
    }

    override fun placements(count: Int): FloatArray {
        if (placements.size < count * 2) placements = FloatArray(count * 2)
        return placements
    }

    override fun offers(count: Int): Array<ConstraintsCache> {
        if (offers.size < count) offers = Array(count) { ConstraintsCache() }
        return offers
    }

    /**
     * The answer, filled in again each time rather than made again.
     *
     * It holds whichever of the two ways of placing the layout chose: a block to run, or a count
     * of children and the corners they go at. [count] is -1 when there is a block.
     */
    private class ReusableResult : MeasureResult {
        override var width = 0f
        override var height = 0f
        var place: (PlacementScope.() -> Unit)? = null
        var count = -1
        var placeables: Array<Placeable?> = EMPTY_PLACEABLES
        var placements: FloatArray = EMPTY_FLOATS

        override fun placeChildren(scope: PlacementScope) {
            val place = place
            if (place != null) {
                scope.place()
                return
            }
            with(scope) {
                for (index in 0 until count) {
                    placeables[index]?.at(placements[index * 2], placements[index * 2 + 1])
                }
            }
        }

        private companion object {
            val EMPTY_PLACEABLES = arrayOfNulls<Placeable>(0)
            val EMPTY_FLOATS = FloatArray(0)
        }
    }
}
