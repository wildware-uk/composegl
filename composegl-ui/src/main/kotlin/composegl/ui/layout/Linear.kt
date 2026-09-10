package composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import composegl.ui.modifier.Modifier

/**
 * A row or a column: children in a line, with weights sharing out whatever is left over.
 *
 * One policy serves both, with the axes swapped, because the two differ in nothing else. Written
 * twice they would drift, and the second copy is always the one with the bug.
 *
 * It is a data class so that recomposing with the same arrangement produces an equal policy and
 * the node reports no change.
 */
internal data class LinearPolicy(
    val horizontal: Boolean,
    val arrangement: Arrangement,
    val crossAlignment: Alignment,
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val mainMax = if (horizontal) constraints.maxWidth else constraints.maxHeight
        val crossMax = if (horizontal) constraints.maxHeight else constraints.maxWidth
        val reserved = arrangement.spacing * (measurables.size - 1).coerceAtLeast(0)

        val placeables = arrayOfNulls<Placeable>(measurables.size)
        var used = reserved
        var totalWeight = 0f
        var lastWeighted = -1

        // Children with a fixed size first: until they are measured, there is no "left over" for
        // the weighted ones to share.
        measurables.forEachIndexed { index, measurable ->
            val weight = measurable.layoutData.weight
            if (weight != null) {
                totalWeight += weight
                lastWeighted = index
                return@forEachIndexed
            }
            val room = if (mainMax.isFinite()) (mainMax - used).coerceAtLeast(0f) else Float.POSITIVE_INFINITY
            val placeable = measurable.measure(childConstraints(main = room, crossMax = crossMax))
            placeables[index] = placeable
            used += placeable.main
        }

        if (totalWeight > 0f) {
            val spare = if (mainMax.isFinite()) (mainMax - used).coerceAtLeast(0f) else 0f
            var handedOut = 0f
            measurables.forEachIndexed { index, measurable ->
                val weight = measurable.layoutData.weight ?: return@forEachIndexed
                // The last weighted child takes exactly what is left rather than its own share.
                // Shares are floats, and a row of three thirds that adds up to a sliver under the
                // full width leaves a seam down the screen that nobody can find.
                val share =
                    if (index == lastWeighted) spare - handedOut else spare * (weight / totalWeight)
                handedOut += share
                val placeable = measurable.measure(
                    childConstraints(main = share, crossMax = crossMax, tight = true),
                )
                placeables[index] = placeable
                used += placeable.main
            }
        }

        val measured = placeables.requireNoNulls().toList()
        val main = if (horizontal) constraints.constrainWidth(used) else constraints.constrainHeight(used)
        val cross = measured.maxOfOrNull { it.cross } ?: 0f
        val crossSize =
            if (horizontal) constraints.constrainHeight(cross) else constraints.constrainWidth(cross)

        val width = if (horizontal) main else crossSize
        val height = if (horizontal) crossSize else main

        return layout(width, height) {
            val positions = arrangement.arrange(main, measured.map { it.main })
            measured.forEachIndexed { index, placeable ->
                val alignment = measurables[index].layoutData.alignment ?: crossAlignment
                val (x, y) = alignment.offsetIn(width, height, placeable.width, placeable.height)
                if (horizontal) {
                    placeable.at(positions[index], y)
                } else {
                    placeable.at(x, positions[index])
                }
            }
        }
    }

    /**
     * What a child is offered: as much of the main axis as is left, and the whole cross axis to
     * shrink inside. A weighted child gets a main axis it cannot argue with — that is what asking
     * for a share of the row means.
     */
    private fun childConstraints(main: Float, crossMax: Float, tight: Boolean = false) =
        if (horizontal) {
            Constraints(if (tight) main else 0f, main, 0f, crossMax)
        } else {
            Constraints(0f, crossMax, if (tight) main else 0f, main)
        }

    private val Placeable.main get() = if (horizontal) width else height
    private val Placeable.cross get() = if (horizontal) height else width
}

/**
 * Children in a line, left to right.
 *
 * ```kotlin
 * Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
 *     Label("Health")
 *     Bar(Modifier.weight(1f))
 * }
 * ```
 */
@Composable
fun Row(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement = Arrangement.Start,
    verticalAlignment: VerticalAlignment = VerticalAlignment.Top,
    content: @Composable () -> Unit,
) {
    val policy = remember(horizontalArrangement, verticalAlignment) {
        LinearPolicy(true, horizontalArrangement, Alignment(vertical = verticalAlignment))
    }
    Layout(modifier, name = "row", content = content, measurePolicy = policy)
}

/** Children in a line, top to bottom. */
@Composable
fun Column(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement = Arrangement.Top,
    horizontalAlignment: HorizontalAlignment = HorizontalAlignment.Start,
    content: @Composable () -> Unit,
) {
    val policy = remember(verticalArrangement, horizontalAlignment) {
        LinearPolicy(false, verticalArrangement, Alignment(horizontal = horizontalAlignment))
    }
    Layout(modifier, name = "column", content = content, measurePolicy = policy)
}
