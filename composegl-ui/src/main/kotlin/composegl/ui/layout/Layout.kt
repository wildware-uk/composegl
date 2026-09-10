package composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import composegl.ui.modifier.Modifier
import composegl.ui.node.UiApplier
import composegl.ui.node.UiNode

/**
 * A layout of your own.
 *
 * This is the whole extension point, and it is the same one `Row`, `Column` and `Box` are built
 * on — there is no private door they go through. If a game needs children arranged around a
 * circle, in a hex grid, or along a path, it writes a `MeasurePolicy` and gets a first-class
 * layout, not a workaround.
 *
 * ```kotlin
 * Layout(content = { Icon(); Icon(); Icon() }) { children, constraints ->
 *     val placeables = children.map { it.measure(constraints) }
 *     layout(constraints.maxWidth, placeables.maxOf { it.height }) {
 *         var x = 0f
 *         placeables.forEach { it.at(x, 0f); x += it.width }
 *     }
 * }
 * ```
 *
 * @param name what this node is called in a debug dump and in test failures.
 */
@Composable
fun Layout(
    modifier: Modifier = Modifier,
    name: String = "layout",
    content: @Composable () -> Unit = {},
    measurePolicy: MeasurePolicy,
) {
    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode() },
        update = {
            set(name) { this.name = it }
            set(modifier) { this.modifier = it }
            set(measurePolicy) { this.measurePolicy = it }
        },
        content = content,
    )
}

/**
 * A node with no children of its own to arrange — an image, a run of text, a nine-patch.
 *
 * It still takes a modifier, so it can be sized, padded and painted like anything else; it simply
 * has nothing inside to place.
 */
@Composable
fun LeafLayout(
    modifier: Modifier = Modifier,
    name: String = "leaf",
    measurePolicy: MeasurePolicy = MeasurePolicy.Empty,
) {
    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode() },
        update = {
            set(name) { this.name = it }
            set(modifier) { this.modifier = it }
            set(measurePolicy) { this.measurePolicy = it }
        },
    )
}
