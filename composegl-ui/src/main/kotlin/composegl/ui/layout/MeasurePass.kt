package composegl.ui.layout

import composegl.ui.geometry.Size
import composegl.ui.modifier.ResolvedModifier
import composegl.ui.node.UiNode

/**
 * One layout pass over a tree.
 *
 * Two walks, not one, and both of them fall out of the same recursion: a node measures its
 * children, chooses its own size, and then places them. Positions are stored relative to the
 * parent, so nothing has to be revisited when the parent itself moves.
 *
 * A pass is a throwaway object rather than state on the nodes, which is what makes
 * "measured exactly once" cheap to check: the bookkeeping is born and dies with the pass.
 */
class MeasurePass {

    private val scope = object : MeasureScope {}

    /** Measures and places [node] and everything under it. The root ends up at the origin. */
    fun run(node: UiNode, constraints: Constraints) {
        measure(node, constraints).placeAt(0f, 0f)
    }

    internal fun measure(node: UiNode, incoming: Constraints): Placeable {
        val resolved = node.resolved
        val outer = resolved.applyTo(incoming)
        val padding = resolved.padding
        // Not loosened. A policy has to see the minimum it was given, or a row told to be 200
        // wide arranges its children inside the 40 they happen to add up to. Loosening for
        // children is each policy's own decision, and every one of them makes it.
        val content = outer.shrink(padding.horizontal, padding.vertical)

        val measurables = node.children.map { OnceMeasurable(it) }
        val result = with(node.measurePolicy) { scope.measure(measurables, content) }

        val size = outer.constrain(
            Size(result.width + padding.horizontal, result.height + padding.vertical),
        )
        node.width = size.width
        node.height = size.height

        // Children are placed now, in this node's coordinates. Where *this* node ends up is its
        // parent's business and does not change any of them.
        result.placeChildren(Inset(padding.left, padding.top))

        return NodePlaceable(node, resolved)
    }

    /** A child, wrapped so that measuring it twice is an error rather than a quiet cost. */
    private inner class OnceMeasurable(private val node: UiNode) : Measurable {

        private var measured = false

        override val layoutData = LayoutData(node.resolved.weight, node.resolved.alignment)

        override fun measure(constraints: Constraints): Placeable {
            check(!measured) {
                "${node.name} was measured twice in one pass. A layout that measures a child more " +
                    "than once doubles the cost of every node beneath it, and nesting two of them " +
                    "squares it. Measure once and use the Placeable you got back."
            }
            measured = true
            return this@MeasurePass.measure(node, constraints)
        }
    }
}

/** Placing a node writes its position, plus whatever its `offset` modifier asked for. */
private class NodePlaceable(
    private val node: UiNode,
    private val resolved: ResolvedModifier,
) : Placeable() {

    override val width get() = node.width
    override val height get() = node.height

    override fun placeAt(x: Float, y: Float) {
        node.x = x + resolved.offset.x
        node.y = y + resolved.offset.y
    }
}

/** Placement inside a padded node: the content box starts in from the edge. */
private class Inset(private val dx: Float, private val dy: Float) : PlacementScope {
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
internal fun ResolvedModifier.applyTo(incoming: Constraints): Constraints {
    var result = incoming

    size?.width?.let { result = result.tightenWidth(incoming.constrainWidth(it)) }
    size?.height?.let { result = result.tightenHeight(incoming.constrainHeight(it)) }

    fill?.widthFraction?.let { fraction ->
        if (incoming.hasBoundedWidth) result = result.tightenWidth(incoming.constrainWidth(incoming.maxWidth * fraction))
    }
    fill?.heightFraction?.let { fraction ->
        if (incoming.hasBoundedHeight) result = result.tightenHeight(incoming.constrainHeight(incoming.maxHeight * fraction))
    }

    return result
}

private fun Constraints.tightenWidth(width: Float) = copy(minWidth = width, maxWidth = width)

private fun Constraints.tightenHeight(height: Float) = copy(minHeight = height, maxHeight = height)
