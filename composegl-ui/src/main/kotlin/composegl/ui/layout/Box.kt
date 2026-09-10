package composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import composegl.ui.modifier.Modifier

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
        val placeables = measurables.map { it.measure(constraints.loosen()) }
        val width = constraints.constrainWidth(placeables.maxOfOrNull { it.width } ?: 0f)
        val height = constraints.constrainHeight(placeables.maxOfOrNull { it.height } ?: 0f)

        return layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val alignment = measurables[index].layoutData.alignment ?: contentAlignment
                val (x, y) = alignment.offsetIn(width, height, placeable.width, placeable.height)
                placeable.at(x, y)
            }
        }
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
