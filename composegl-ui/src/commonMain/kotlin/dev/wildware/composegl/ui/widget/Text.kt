package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.graphics.textRun
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.text.TextOutline
import dev.wildware.composegl.ui.text.TextStyle

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
) = Text(text, modifier, style, textStyle, colour, align, softWrap, maxLines, ellipsis, LocalTextOutline.current)

/**
 * The same, with a ring of [outline] round the letters. Null is no ring, whatever the surroundings
 * say — the overload without this parameter is the one that takes [LocalTextOutline].
 *
 * The ring is painted outside the box and **the box does not grow for it**, exactly as
 * `Modifier.outline`'s bleed already reaches past the node it wraps. So an outlined label lays out
 * byte for byte like the same label unoutlined: turning the ring on cannot move its neighbours,
 * cannot rewrap a paragraph, and cannot push the words off the baseline a [PromptGlyph] beside them
 * is sitting on. Where the ring needs room of its own — a background that must cover it, an
 * ancestor clip that would trim it — `Modifier.padding` of the outline width says so out loud.
 *
 * @see dev.wildware.composegl.ui.text.TextOutline for what a stamped ring can and cannot do.
 */
// A separate function rather than a tenth parameter with a default on the one above, and it has to
// stay that way: adding a defaulted parameter to a published function changes its signature, so
// every game compiled against 0.1.0 would fail to link against the "tidier" version. `outline` has
// no default here for the same reason the two can coexist at all — give it one and `Text("hi")`
// matches both.
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
    outline: TextOutline?,
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
    val painter = remember(text, face, ink, align, softWrap, fonts, outline) {
        TextPainter(text, face, ink, align, softWrap, fonts, outline)
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
    private val outline: TextOutline?,
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
        // The outline goes to the canvas rather than being stacked here, so an outlined label is
        // still one node, and it is painted outside these bounds rather than inside a bigger box —
        // which is what lets measuring ignore it entirely.
        measured?.let { textRun(it, bounds.left + shift, bounds.top, colour, outline) }
    }
}
