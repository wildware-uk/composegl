package uk.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import uk.wildware.composegl.ui.modifier.Modifier

/**
 * Children on top of each other, each placed where it asked to be.
 *
 * The layout a game reaches for most: a panel with a background, a health bar with a label over
 * it, a screen with a pause menu on top.
 */
internal data class BoxPolicy(val contentAlignment: Alignment) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val count = measurables.size
        // Lent by the node and used again next frame; see MeasureScope.
        val placeables = placeables(count)
        // Worked out once, and only when there is somebody to offer it to: every child of a box
        // gets the same room, and an empty box has no children to give any to.
        val offered = if (count == 0) constraints else constraints.loosen(offers(1)[0])

        var widest = 0f
        var tallest = 0f
        for (index in 0 until count) {
            val placeable = measurables[index].measure(offered)
            placeables[index] = placeable
            if (placeable.width > widest) widest = placeable.width
            if (placeable.height > tallest) tallest = placeable.height
        }

        val width = constraints.constrainWidth(widest)
        val height = constraints.constrainHeight(tallest)

        // Where each child goes is known now, so it is written down rather than closed over; see
        // MeasureScope.layout.
        val placements = placements(count)
        for (index in 0 until count) {
            val placeable = placeables[index] ?: continue
            val alignment = measurables[index].layoutData.alignment ?: contentAlignment
            placements[index * 2] = alignment.xIn(width, placeable.width)
            placements[index * 2 + 1] = alignment.yIn(height, placeable.height)
        }

        return layout(width, height, count)
    }
}

/**
 * Children on top of each other.
 *
 * @param contentAlignment where children go when they do not say for themselves. A child's own
 *   `Modifier.align(...)` wins over this.
 */
@Composable
fun Box(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit = {},
) {
    val policy = remember(contentAlignment) { BoxPolicy(contentAlignment) }
    Layout(modifier, name = "box", content = content, measurePolicy = policy)
}

/**
 * Empty room.
 *
 * `Spacer(Modifier.width(8f))` for a fixed gap, `Spacer(Modifier.weight(1f))` inside a row or
 * column to push everything after it to the far end.
 */
@Composable
fun Spacer(modifier: Modifier = Modifier) {
    LeafLayout(modifier, name = "spacer", measurePolicy = MeasurePolicy.Empty)
}
