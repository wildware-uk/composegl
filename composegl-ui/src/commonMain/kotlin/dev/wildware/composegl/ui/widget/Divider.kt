package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.rememberStyle

/**
 * A thin line between two sections.
 *
 * ```kotlin
 * Column {
 *     Text("AUDIO")
 *     Divider()
 *     Text("VIDEO")
 * }
 * Row(Modifier.height(24f)) { Text("HP 80"); Divider(vertical = true); Text("MP 12") }
 * ```
 *
 * It is as long as the room it is given and [thickness] across. A horizontal one spans all the
 * width its column may have, and a vertical one all the height its row may have — so a row nobody
 * sized grows to the height of whatever holds it, the way a column with a divider in it grows to
 * its full width. Size the row to keep it short: `Row(Modifier.height(24f))`, or
 * `Row(Modifier.height(IntrinsicSize.Min))` for the height of its tallest cell. Where there is no
 * limit at all, as inside a [ScrollArea], there is no length to take and the line is zero long
 * until it is given one. `Modifier.width(120f)` on a horizontal one is a short rule rather than a
 * full one.
 *
 * The colour is the skin's: the style's background is drawn across the whole line, so a skin can
 * make it a flat colour, a strip of art, or nothing. Its padding is not used — how far a divider
 * sits from its neighbours is the arrangement's business, or `Modifier.padding`'s.
 *
 * It takes no focus and no pointer: a line is scenery, and the pad steps straight over it.
 *
 * @param vertical a line running top to bottom, for a row, instead of left to right.
 * @param thickness how thick the line is, across its length.
 * @param style the skin name. `"divider.strong"` and the like fall back to `"divider"`.
 */
@Composable
fun Divider(
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    thickness: Float = 1f,
    style: String = "divider",
) {
    require(thickness >= 0f) { "a divider cannot be $thickness thick" }
    val resolved = rememberStyle(style)
    val policy = remember(vertical, thickness) { DividerPolicy(vertical, thickness) }
    val draw = remember(resolved) { dividerDraw(resolved) }
    LeafLayout(modifier = modifier, name = "divider", measurePolicy = policy, draw = draw)
}

/**
 * All of the length it is offered, and its thickness across.
 *
 * A data class, so recomposing with the same arguments is an equal policy and the node reports no
 * change.
 */
private data class DividerPolicy(val vertical: Boolean, val thickness: Float) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult = if (vertical) {
        layout(constraints.constrainWidth(thickness), constraints.length(bounded = false)) {}
    } else {
        layout(constraints.length(bounded = true), constraints.constrainHeight(thickness)) {}
    }

    /** The largest length on offer when there is a largest, and the smallest when there is not. */
    private fun Constraints.length(bounded: Boolean): Float = if (bounded) {
        if (hasBoundedWidth) maxWidth else minWidth
    } else {
        if (hasBoundedHeight) maxHeight else minHeight
    }
}

private fun dividerDraw(style: ResolvedStyle): UiCanvas.(Rect) -> Unit = { bounds ->
    if (!bounds.isEmpty) style.background.drawInto(this, bounds, style.tint)
}
