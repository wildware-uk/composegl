package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.modifier.ResolvedModifier
import dev.wildware.composegl.ui.node.UiNode

/**
 * Which of a child's two natural sizes a layout is sized to.
 *
 * `Max` is how big it would be if it had all the room it liked: a label on one line. `Min` is the
 * least it can be squeezed to without cutting anything: a label broken at every space, as wide as
 * its longest word.
 *
 * ```kotlin
 * Column(Modifier.width(IntrinsicSize.Max)) {       // as wide as the widest button…
 *     Button("PLAY", Modifier.fillMaxWidth())        // …and every button that wide
 *     Button("OPTIONS", Modifier.fillMaxWidth())
 * }
 * ```
 */
enum class IntrinsicSize { Min, Max }

/**
 * A child a parent may ask about before measuring it: how big would you like to be?
 *
 * The answer is a question and not a measurement. Asking changes nothing about the child, and does
 * not count as measuring it, so a layout may ask as often as it likes and still measure the child
 * once afterwards.
 *
 * Each answer depends on the other axis — a paragraph is taller the narrower it is — so each takes
 * the room on that axis, infinite for "as much as you like".
 */
interface IntrinsicMeasurable {

    /** What the child said about itself that its parent needs. See [Measurable.layoutData]. */
    val layoutData: LayoutData

    /** The narrowest this can be, [height] tall, without cutting anything off. */
    fun minIntrinsicWidth(height: Float): Float

    /** The width past which more room makes no difference, [height] tall. */
    fun maxIntrinsicWidth(height: Float): Float

    /** The shortest this can be, [width] wide, without cutting anything off. */
    fun minIntrinsicHeight(width: Float): Float

    /** The height past which more room makes no difference, [width] wide. */
    fun maxIntrinsicHeight(width: Float): Float
}

/** The four questions, as one value, so one function can ask any of them. */
internal enum class Intrinsic(val width: Boolean, val max: Boolean) {
    MinWidth(true, false),
    MaxWidth(true, true),
    MinHeight(false, false),
    MaxHeight(false, true);

    companion object {
        fun width(size: IntrinsicSize) = if (size == IntrinsicSize.Max) MaxWidth else MinWidth
        fun height(size: IntrinsicSize) = if (size == IntrinsicSize.Max) MaxHeight else MinHeight
    }
}

/** Asks [measurable] whichever question [kind] is. */
internal fun IntrinsicMeasurable.intrinsic(kind: Intrinsic, across: Float): Float = when (kind) {
    Intrinsic.MinWidth -> minIntrinsicWidth(across)
    Intrinsic.MaxWidth -> maxIntrinsicWidth(across)
    Intrinsic.MinHeight -> minIntrinsicHeight(across)
    Intrinsic.MaxHeight -> maxIntrinsicHeight(across)
}

/**
 * The answer a policy gives when it has not written one of its own: run the policy's own measure
 * over stand-ins for its children, each of which answers with that child's intrinsic size instead
 * of measuring it.
 *
 * So a custom layout gets intrinsics for nothing, and they agree with how it arranges — a ring of
 * icons reports the ring's size. The room offered is unbounded along the axis asked about and
 * [across] on the other, which is what "as big as you would like to be" means.
 */
internal fun MeasurePolicy.probe(
    scope: MeasureScope,
    measurables: List<IntrinsicMeasurable>,
    kind: Intrinsic,
    across: Float,
): Float {
    val room = across.coerceAtLeast(0f)
    val result = if (scope is NodeMeasureScope) {
        val constraints = scope.probeOffer(kind, room)
        with(this) { scope.measure(scope.probes(measurables, kind), constraints) }
    } else {
        val constraints = if (kind.width) Constraints(maxHeight = room) else Constraints(maxWidth = room)
        with(this) { scope.measure(measurables.map { IntrinsicProbe(it, kind) }, constraints) }
    }
    return if (kind.width) result.width else result.height
}

/**
 * A child standing in for itself while its parent works out an intrinsic size.
 *
 * Measuring it asks the child the question instead, and hands back a placeable that size. On the
 * other axis it takes the least it is allowed, so that a line of these does not use up room the
 * next one is owed. Placing it does nothing; nothing ever places it.
 */
internal class IntrinsicProbe(var target: IntrinsicMeasurable, var kind: Intrinsic) : Measurable {

    private val placeable = ProbePlaceable()

    override val layoutData: LayoutData get() = target.layoutData

    override fun measure(constraints: Constraints): Placeable {
        if (kind.width) {
            placeable.width = target.intrinsic(kind, constraints.maxHeight)
            placeable.height = constraints.minHeight
        } else {
            placeable.height = target.intrinsic(kind, constraints.maxWidth)
            placeable.width = constraints.minWidth
        }
        return placeable
    }

    override fun minIntrinsicWidth(height: Float) = target.minIntrinsicWidth(height)
    override fun maxIntrinsicWidth(height: Float) = target.maxIntrinsicWidth(height)
    override fun minIntrinsicHeight(width: Float) = target.minIntrinsicHeight(width)
    override fun maxIntrinsicHeight(width: Float) = target.maxIntrinsicHeight(width)

    private class ProbePlaceable : Placeable() {
        override var width = 0f
        override var height = 0f
        override fun placeAt(x: Float, y: Float) = Unit
    }
}

/**
 * What a node keeps for answering intrinsic questions, made the first time one is asked.
 *
 * Its own scope rather than the node's, because a question can arrive while the node's scope is
 * lent out — and a policy that ran its measure there would overwrite the scratch space its real
 * measure is about to read. Most nodes are never asked, and so never make one.
 */
internal class NodeIntrinsics {
    val scope = NodeMeasureScope()
    val children = ArrayList<IntrinsicMeasurable>()
    val constraints = ConstraintsCache()
}

private fun UiNode.intrinsics(): NodeIntrinsics = intrinsics ?: NodeIntrinsics().also { intrinsics = it }

/**
 * This node's intrinsic size, as its parent sees it: its modifiers included.
 *
 * A fixed `size` is the answer on its axis whatever is inside. Padding is added to what the
 * contents want and taken off the room they are asked about. An intrinsic modifier on the node
 * answers with the size it asked for, so a column sized to its narrowest child reports that width
 * to a parent that asked for its widest.
 *
 * The answer keeps the same rules measuring does: it stays inside a `widthIn` range, is lifted to a
 * `defaultMinSize` nothing else overrules, and a node keeping an `aspectRatio` answers with its
 * shape worked out from the other axis whenever that axis is known.
 */
internal fun UiNode.intrinsic(kind: Intrinsic, across: Float): Float {
    val resolved = resolved
    val padding = resolved.padding
    val size = resolved.size
    val range = resolved.sizeIn
    val asked = resolved.intrinsicSize
    val shape = resolved.aspectRatio
    val atLeast: Float
    val atMost: Float
    val answer: Float
    if (kind.width) {
        atLeast = range?.minWidth ?: 0f
        atMost = range?.maxWidth ?: Float.POSITIVE_INFINITY
        val height = (size?.height ?: across).coerceAtMost(range?.maxHeight ?: Float.POSITIVE_INFINITY)
        val fixed = size?.width
        answer = when {
            fixed != null -> fixed
            shape != null && height.isFinite() && height > 0f -> height * shape.ratio
            else -> {
                val chosen = asked?.width
                val actual = if (chosen != null) Intrinsic.width(chosen) else kind
                val wanted = contentIntrinsic(actual, inside(height, padding.vertical)) + padding.horizontal
                val default = resolved.defaultMinSize?.minWidth
                if (default != null && range?.minWidth == null && resolved.fill?.widthFraction == null) {
                    maxOf(wanted, default)
                } else {
                    wanted
                }
            }
        }
    } else {
        atLeast = range?.minHeight ?: 0f
        atMost = range?.maxHeight ?: Float.POSITIVE_INFINITY
        val width = (size?.width ?: across).coerceAtMost(range?.maxWidth ?: Float.POSITIVE_INFINITY)
        val fixed = size?.height
        answer = when {
            fixed != null -> fixed
            shape != null && width.isFinite() && width > 0f -> width / shape.ratio
            else -> {
                val chosen = asked?.height
                val actual = if (chosen != null) Intrinsic.height(chosen) else kind
                val wanted = contentIntrinsic(actual, inside(width, padding.horizontal)) + padding.vertical
                val default = resolved.defaultMinSize?.minHeight
                if (default != null && range?.minHeight == null && resolved.fill?.heightFraction == null) {
                    maxOf(wanted, default)
                } else {
                    wanted
                }
            }
        }
    }
    return answer.coerceIn(atLeast, atMost)
}

private fun inside(outer: Float, taken: Float) =
    if (outer.isFinite()) (outer - taken).coerceAtLeast(0f) else outer

private fun UiNode.contentIntrinsic(kind: Intrinsic, across: Float): Float {
    val held = intrinsics()
    val children = children
    val list = held.children
    while (list.size > children.size) list.removeAt(list.size - 1)
    for (index in children.indices) {
        val measurable = children[index].measurable.refresh()
        if (index < list.size) {
            if (list[index] !== measurable) list[index] = measurable
        } else {
            list.add(measurable)
        }
    }
    val scope = held.scope
    return with(measurePolicy) {
        when (kind) {
            Intrinsic.MinWidth -> scope.minIntrinsicWidth(list, across)
            Intrinsic.MaxWidth -> scope.maxIntrinsicWidth(list, across)
            Intrinsic.MinHeight -> scope.minIntrinsicHeight(list, across)
            Intrinsic.MaxHeight -> scope.maxIntrinsicHeight(list, across)
        }
    }
}

/**
 * What `width(IntrinsicSize.Max)` and friends do to the room a node is offered: fix that axis at
 * the node's own intrinsic size, clamped to what the parent allows.
 *
 * Width first, then height against the width just chosen — a paragraph sized both ways is as tall
 * as it is at the width it settled on. An axis a `size` or a `fill` already names is left to them.
 */
internal fun UiNode.applyIntrinsics(resolved: ResolvedModifier, outer: Constraints): Constraints {
    val asked = resolved.intrinsicSize ?: return outer
    var minWidth = outer.minWidth
    var maxWidth = outer.maxWidth
    var minHeight = outer.minHeight
    var maxHeight = outer.maxHeight

    val width = asked.width
    if (width != null && resolved.size?.width == null && resolved.fill?.widthFraction == null) {
        val chosen = outer.constrainWidth(intrinsic(Intrinsic.width(width), outer.maxHeight))
        minWidth = chosen
        maxWidth = chosen
    }
    val height = asked.height
    if (height != null && resolved.size?.height == null && resolved.fill?.heightFraction == null) {
        val chosen = outer.constrainHeight(intrinsic(Intrinsic.height(height), maxWidth))
        minHeight = chosen
        maxHeight = chosen
    }

    if (minWidth == outer.minWidth && maxWidth == outer.maxWidth &&
        minHeight == outer.minHeight && maxHeight == outer.maxHeight
    ) {
        return outer
    }
    return intrinsics().constraints.of(minWidth, maxWidth, minHeight, maxHeight)
}
