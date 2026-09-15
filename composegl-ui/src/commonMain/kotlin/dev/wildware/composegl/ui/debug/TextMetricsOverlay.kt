package dev.wildware.composegl.ui.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.text.FontMetrics
import dev.wildware.composegl.ui.text.GuideLine
import dev.wildware.composegl.ui.text.TextGuideSource

/** Which lines a [TextMetricsOverlay] draws through every piece of text. Any mix; [All] is every one. */
enum class TextGuide {
    /** Each line's box, as layout counted it: what `TextAnchor.LineBox` places by. Cyan edge. */
    LineBox,

    /** The top of the tallest glyph: baseline less the ascent. Red. */
    Ascent,

    /** The top of a capital: what `TextAnchor.CapTop` places by. Orange. */
    CapHeight,

    /** The line the letters stand on: what `TextAnchor.Baseline` places by. Green. */
    Baseline,

    /** The bottom of the lowest glyph: baseline plus the descent. Blue. */
    Descent;

    companion object {
        val All: Set<TextGuide> = entries.toSet()
    }
}

/**
 * The invisible lines of every piece of text on the screen, drawn through it.
 *
 * `TextAnchor` places text by its line box, its capitals or its baseline, and none of those can be
 * seen. So lining a label up with an icon, or two fonts side by side, is trial and error: nudge,
 * run, squint. This draws them, for every line of every `Text`, text field, typewriter, tooltip and
 * prompt glyph, in five colours:
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     Game()
 *     TextMetricsOverlay(enabled = debug)
 * }
 * ```
 *
 * Each line's box is outlined, and the ascent, cap height, baseline and descent are one-unit lines
 * across the width of that line's glyphs. The rows are read off the text as it was measured, and
 * off its face at its size, so a style with extra leading, a wrapped paragraph and a centred label
 * each show the lines they really have. A scaled label shows them where it is drawn.
 *
 * It is the same kind of node as [LayoutOverlay]: no size, no input, on top, reading the tree while
 * it is drawn. It moves nothing, a click goes through it, and a still screen with it on stays still.
 * Both can be on at once and neither marks the other. Faded-out text is left out.
 *
 * Deliberately unskinnable: its own colours, the same over any game. Take it off before shipping.
 *
 * @param enabled whether it is there at all. Off composes nothing.
 * @param show which lines to draw.
 */
@Composable
fun TextMetricsOverlay(
    enabled: Boolean,
    show: Set<TextGuide> = TextGuide.All,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    // By value, as LayoutOverlay does: an equal set is the same painter, and the tree hears nothing.
    val painter = remember(show) { TextMetricsPainter(show) }
    OverlayNode(TextOverlayName, modifier, painter)
}

/** What the overlay node is called in a dump. */
internal const val TextOverlayName = "text metrics overlay"

/**
 * The overlay's colours. Red, orange, green and blue top to bottom, so the order on screen is the
 * order of the rainbow, and none the same as a [LayoutOverlay] colour.
 */
internal object TextMetricsColours {
    val LineBox = Colour.rgb(0x00FFFF)
    val Ascent = Colour.rgb(0xFF0000)
    val CapHeight = Colour.rgb(0xFF9900)
    val Baseline = Colour.rgb(0x00FF00)
    val Descent = Colour.rgb(0x3355FF)
}

/**
 * The drawing itself. Every line box first, then every glyph line, so an edge never covers a line
 * a caller is trying to read — the ascent of the default face sits exactly on its line box's top.
 */
internal class TextMetricsPainter(private val show: Set<TextGuide>) : OverlayPainter() {

    override fun paint(canvas: UiCanvas, root: UiNode, self: UiNode, dx: Float, dy: Float) {
        if (TextGuide.LineBox in show) {
            walk(root, self) { node ->
                forEachLine(node) { place, line, _ ->
                    val box = place.rect(line.left, line.top, line.right, line.bottom)
                    edge(canvas, box, TextMetricsColours.LineBox, dx, dy)
                }
            }
        }
        val glyphs = show - TextGuide.LineBox
        if (glyphs.isEmpty()) return
        walk(root, self) { node ->
            forEachLine(node) { place, line, metrics ->
                for (guide in glyphs) {
                    val (y, colour) = when (guide) {
                        TextGuide.Ascent -> line.baseline - metrics.ascent to TextMetricsColours.Ascent
                        TextGuide.CapHeight -> line.baseline - metrics.capHeight to TextMetricsColours.CapHeight
                        TextGuide.Baseline -> line.baseline to TextMetricsColours.Baseline
                        TextGuide.Descent -> line.baseline + metrics.descent to TextMetricsColours.Descent
                        TextGuide.LineBox -> continue
                    }
                    val at = place.rect(line.left, y, line.right, y)
                    // At least a unit long, so a line of nothing but spaces is still marked.
                    val right = maxOf(at.right, at.left + 1f)
                    canvas.rect(Rect(at.left + dx, at.top + dy, right + dx, at.top + 1f + dy), colour)
                }
            }
        }
    }

    /**
     * Every line of [node], if it is text, with where it was laid out mapped to where it is drawn.
     *
     * The lines come back in the node's laid-out content box. A scale draws the node somewhere else,
     * and [Placement] carries each laid-out point across to the drawn rectangle.
     */
    private inline fun forEachLine(
        node: UiNode,
        visit: (Placement, GuideLine, FontMetrics) -> Unit,
    ) {
        val source = node.measurePolicy as? TextGuideSource ?: return
        val laid = node.layoutBoundsInRoot
        val padding = node.resolved.padding
        val content = Rect(
            laid.left + padding.left,
            laid.top + padding.top + node.baselineTop,
            laid.right - padding.right,
            laid.bottom - padding.bottom - node.baselineBottom,
        )
        val guides = source.textGuides(content) ?: return
        val place = Placement(laid, node.boundsInRoot)
        for (line in guides.lines) visit(place, line, guides.metrics)
    }

    /** A one-unit edge, or a filled line for a box too thin to have an inside. */
    private fun edge(canvas: UiCanvas, rect: Rect, colour: Colour, dx: Float, dy: Float) {
        if (rect.width < 1f || rect.height < 1f) {
            canvas.rect(
                Rect(rect.left + dx, rect.top + dy, rect.left + maxOf(rect.width, 1f) + dx, rect.top + maxOf(rect.height, 1f) + dy),
                colour,
            )
        } else {
            canvas.border(Rect(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy), colour, 1f)
        }
    }

    /** From a node's laid-out rectangle to its drawn one: the identity unless a scale moved it. */
    private class Placement(private val laid: Rect, private val drawn: Rect) {
        private val sx = if (laid.width > 0f) drawn.width / laid.width else 1f
        private val sy = if (laid.height > 0f) drawn.height / laid.height else 1f

        fun rect(left: Float, top: Float, right: Float, bottom: Float) = Rect(
            drawn.left + (left - laid.left) * sx,
            drawn.top + (top - laid.top) * sy,
            drawn.left + (right - laid.left) * sx,
            drawn.top + (bottom - laid.top) * sy,
        )
    }
}
