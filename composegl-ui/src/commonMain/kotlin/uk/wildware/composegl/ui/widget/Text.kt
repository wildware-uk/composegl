package uk.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.graphics.UiCanvas
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.HorizontalAlignment
import uk.wildware.composegl.ui.layout.LeafLayout
import uk.wildware.composegl.ui.layout.Measurable
import uk.wildware.composegl.ui.layout.MeasurePolicy
import uk.wildware.composegl.ui.layout.MeasureResult
import uk.wildware.composegl.ui.layout.MeasureScope
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.skin.rememberStyle
import uk.wildware.composegl.ui.text.FontProvider
import uk.wildware.composegl.ui.text.TextLayout
import uk.wildware.composegl.ui.text.TextStyle

/**
 * A piece of text.
 *
 * The leaf every other widget ends up containing. It takes its font, size and colour from the
 * skin's style called [style], so a label written in a game says what it says rather than what
 * colour it is.
 *
 * Measuring and drawing use the same layout object, never two. That is the one rule text has to
 * keep: measure twice and a line can break in one of them and not the other, and the result is
 * text drawn a word away from the space that was reserved for it.
 *
 * @param style the skin style to take the font and colour from. Null takes them from whatever
 *   widget this is inside — the label in a button is the button's colour, in whichever state the
 *   button is in — and falls back to `"label"` when nothing is wrapping it.
 * @param textStyle overrides the style's font, for the rare place that needs one.
 * @param colour overrides the style's colour.
 * @param align where the text sits when it was given more room than it needs. This aligns the
 *   *block*: a centred paragraph is a centred block of ragged lines, because where a line breaks
 *   is the backend's business and the toolkit never sees the lines.
 * @param softWrap false to let the text run on past the width it was given rather than wrap.
 *   Explicit newlines still break lines.
 * @param maxLines cuts the text off after this many lines, ending with the ellipsis. Zero is no
 *   limit. A shortcut for `textStyle = someStyle.copy(maxLines = n)`.
 * @param ellipsis what a cut-off line ends with. Null keeps whatever the style says, which is "…";
 *   an empty string cuts the line off cleanly instead.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: String? = null,
    textStyle: TextStyle? = null,
    colour: Colour? = null,
    align: HorizontalAlignment = HorizontalAlignment.Start,
    softWrap: Boolean = true,
    maxLines: Int = 0,
    ellipsis: String? = null,
) {
    val named = rememberStyle(style ?: "label")
    val inherited = LocalContentStyle.current
    val resolved = if (style == null && inherited != null) inherited else named
    val fonts = rememberFonts()
    val ink = colour ?: resolved.textColour
    val face = remember(resolved.textStyle, textStyle, maxLines, ellipsis) {
        val base = textStyle ?: resolved.textStyle
        base.copy(
            maxLines = if (maxLines > 0) maxLines else base.maxLines,
            ellipsis = ellipsis ?: base.ellipsis,
        )
    }

    // One object measures and draws, and it is remembered on everything it was built from. Same
    // text under the same style: the same object, so the node sees nothing change and the frame is
    // not redrawn. Different text: a different object, so it is.
    val painter = remember(text, face, ink, align, softWrap, fonts) {
        TextPainter(text, face, ink, align, softWrap, fonts)
    }

    LeafLayout(modifier = modifier, name = "text", measurePolicy = painter, draw = painter.draw)
}

/**
 * The measuring and the drawing of one run of text, together, because they must agree.
 *
 * The layout the backend handed back at measure time is kept and drawn. Nothing re-measures at
 * draw time, and nothing can: that is what makes "it measures exactly as it draws" a property of
 * the design rather than a thing to be careful about.
 */
private class TextPainter(
    private val text: String,
    private val style: TextStyle,
    private val colour: Colour,
    private val align: HorizontalAlignment,
    private val softWrap: Boolean,
    private val fonts: FontProvider,
) : MeasurePolicy {

    private var measured: TextLayout? = null

    /**
     * The width the kept layout was measured against.
     *
     * This object already stands for one run of text in one style — it is remembered on both — so
     * the only thing that can change between one frame and the next is how much room it was
     * offered. When that is the same too, last frame's answer is still the answer, and measuring
     * again would lay the same words out into the same lines to arrive at the same numbers.
     */
    private var measuredFor = Float.NaN

    /** How far right the measured block sits inside the width layout settled on. */
    private var shift = 0f

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val room = if (softWrap) constraints.maxWidth else Float.POSITIVE_INFINITY
        val kept = measured
        val block = if (kept != null && room == measuredFor) kept else fonts.measure(text, style, room)
        measured = block
        measuredFor = room

        val width = constraints.constrainWidth(block.size.width)
        shift = when (align) {
            HorizontalAlignment.Start -> 0f
            HorizontalAlignment.Centre -> (width - block.size.width) / 2f
            HorizontalAlignment.End -> width - block.size.width
        }.coerceAtLeast(0f)

        return layout(width, constraints.constrainHeight(block.size.height)) {}
    }

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        // Null only if a frame is drawn before anything measured, which the passes do not do.
        // The two-float call, not the Offset one: this runs for every run of text every frame.
        measured?.let { text(it, bounds.left + shift, bounds.top, colour) }
    }
}
