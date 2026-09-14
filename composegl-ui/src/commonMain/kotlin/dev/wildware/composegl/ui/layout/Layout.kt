package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode

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
 * @param draw what this node paints inside itself, under its children. The rectangle handed over
 *   is the content box: the node's bounds with its padding taken off.
 */
@Composable
fun Layout(
    modifier: Modifier = Modifier,
    name: String = "layout",
    draw: (UiCanvas.(Rect) -> Unit)? = null,
    content: @Composable () -> Unit = {},
    measurePolicy: MeasurePolicy,
) {
    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode() },
        update = {
            set(name) { this.name = it }
            set(modifier) { this.modifier = it }
            set(measurePolicy) { this.measurePolicy = it }
            set(draw) { this.content = it }
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
    draw: (UiCanvas.(Rect) -> Unit)? = null,
) = LeafLayout(modifier, name, measurePolicy, draw, ink = null)

/**
 * The same, for a leaf whose ink does not fill the box it was given.
 *
 * [ink] is handed the content box and answers with the part of it this leaf really paints, or null
 * for "nothing this frame". Text is the case it exists for: a text node's box is a line box and the
 * glyphs sit in a smaller rectangle inside it, and only the widget knows by how much. Everything
 * else can leave it alone — a leaf with no [ink] is taken to have filled its box, which is true of
 * a picture, a nine-patch and a bar.
 *
 * It changes nothing about drawing. It is what [dev.wildware.composegl.ui.node.UiNode.paintedInRoot]
 * reads, so that a caller asking what a subtree painted gets the glyphs rather than the line boxes.
 *
 * @see dev.wildware.composegl.ui.node.UiNode.ink
 */
// A second function rather than a fifth defaulted parameter on the one above, for the reason the
// Text overloads record: a defaulted parameter added to a published function changes its signature,
// so a game compiled against the version before it would fail to link. `ink` has no default here,
// which is what keeps the two apart.
@Composable
fun LeafLayout(
    modifier: Modifier = Modifier,
    name: String = "leaf",
    measurePolicy: MeasurePolicy = MeasurePolicy.Empty,
    draw: (UiCanvas.(Rect) -> Unit)? = null,
    ink: ((Rect) -> Rect?)?,
) {
    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode() },
        update = {
            set(name) { this.name = it }
            set(modifier) { this.modifier = it }
            set(measurePolicy) { this.measurePolicy = it }
            set(draw) { this.content = it }
            set(ink) { this.ink = it }
        },
    )
}
