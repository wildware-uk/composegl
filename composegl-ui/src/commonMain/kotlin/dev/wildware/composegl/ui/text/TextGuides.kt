package dev.wildware.composegl.ui.text

import dev.wildware.composegl.ui.geometry.Rect

/**
 * One laid-out line of a text node, in the coordinates of the box that node was handed.
 *
 * @param left where the line's glyphs start.
 * @param right where they stop, ellipsis and all.
 * @param top the top of the line's box, as layout counted it.
 * @param bottom the bottom of that box: one line height further down.
 * @param baseline where the glyphs stand.
 */
internal data class GuideLine(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val baseline: Float,
)

/** Where a text node put its lines, and the face they were set in: what a text overlay draws. */
internal class TextGuides(val metrics: FontMetrics, val lines: List<GuideLine>)

/**
 * A text node's measure policy, able to say where the lines it measured went.
 *
 * On the policy rather than the node because the policy is what kept the measured text. Asked only
 * by a debug overlay, while it is on.
 */
internal interface TextGuideSource {

    /** The lines inside [box], the content box the node is drawn into, or null before anything measured. */
    fun textGuides(box: Rect): TextGuides?
}
