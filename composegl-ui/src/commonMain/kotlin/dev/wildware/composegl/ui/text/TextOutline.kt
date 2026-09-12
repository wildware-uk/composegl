package dev.wildware.composegl.ui.text

import dev.wildware.composegl.ui.graphics.Colour

/**
 * A ring of [colour], [width] design units wide, drawn round the letters of a run of text.
 *
 * What makes a score, a nameplate or a damage number readable over a moving, colourful background.
 * Without it the only way to outline a string is to draw it again several times underneath itself,
 * which costs a node per copy, puts the paint outside the box that was measured, and leaves every
 * game to rediscover the offsets.
 *
 * ### It is stamped, not stroked
 *
 * Be clear about what comes out. The canvas draws the run nine times — eight offset copies in
 * [colour], then the real one on top — out of the same bitmap glyphs it was already drawing. That
 * is honest at the widths an interface uses, roughly **up to a sixth of the text size** (two units
 * on sixteen-unit text). Past that the ring shows its corners, a round letter goes lumpy and a thin
 * serif gets swallowed. It is not a stroke and it does not stay crisp as the viewport scales — a
 * stretched outline goes soft exactly as the stretched glyphs already do. A signed distance field
 * is the real answer to that, and it is a much bigger job: a different bake in both backends, a
 * registration API that says a family is a distance field, and a branch of its own in the batch
 * shader. When that lands, width and softness become shader parameters and a backend overrides
 * [dev.wildware.composegl.ui.graphics.UiCanvas.text] to do this properly, with no call site changing.
 *
 * ### Two things to know before you pick a colour
 *
 * The copies overlap, so a **see-through** outline colour reads darker where they stack — two or
 * three copies deep round the ring. Use an opaque colour.
 *
 * The ring carries the **face's** alpha rather than its own: a run drawn at a quarter alpha gets a
 * ring at a quarter of [colour]'s alpha, so a label that fades out fades whole instead of leaving a
 * solid silhouette round letters that have gone. What it still cannot do is fade *evenly* — those
 * stacked copies keep the ring reading stronger than the letters all the way down — and that is
 * most visible on damage numbers, the thing that fades most. When it is not good enough,
 * `Modifier.outline` in `composegl-effects` composites once instead of stacking and so is
 * alpha-correct; it costs an offscreen picture and a draw call per node, which is right for one
 * hero label and wrong for two hundred numbers.
 *
 * ### It does not change layout
 *
 * The ring is painted outside the text's box and the box does not grow for it, exactly as
 * `Modifier.outline`'s bleed already reaches past the node it wraps. So switching an outline on
 * moves nothing, rewraps nothing and cannot pull a label off the baseline its neighbours sit on.
 * The price is the same price that effect already pays: a tight clip round the text trims the ring,
 * and a background sized to the text does not cover it. Where that matters, `Modifier.padding` of
 * [width] on the node is the whole fix.
 */
data class TextOutline(val colour: Colour, val width: Float = 2f) {

    init {
        require(width >= 0f) { "an outline cannot be $width wide" }
    }

    /** False for a ring that would put no paint down, which is the cheap case worth skipping. */
    val isVisible: Boolean get() = width > 0f && !colour.isTransparent

    /**
     * How far out the four corner copies sit **on each axis**: cos 45 of [width], about seven
     * tenths of it.
     *
     * That puts a corner copy [width] away from the letter — the same distance as the four straight
     * ones, measured the way a ring is measured — which is the whole of why it is not [width] on
     * both axes. Offset by a full width the corners sit 1.41 widths out and the ring reads as a
     * square with the letter in the middle of it.
     *
     * Not rounded to whole design units, which an earlier version did on the grounds that a nearest
     * filter would collapse a fractional offset onto the straight copy beside it. The arithmetic
     * does not agree: at the default width the corner is (1.41, 1.41) and the nearest straight copy
     * is (2, 0), a unit and a half away, and design units are not texels in any case. What rounding
     * did do was pull the width-2 corners in to 1.41 from the letter while the straight copies
     * stayed at 2 — a ring 29% short at exactly the four places these copies exist to fill.
     */
    val diagonal: Float get() = width * Cosine45

    /**
     * Calls [stamp] once for each offset copy, and not at all when the ring would be invisible.
     *
     * Eight copies: four straight and four diagonal. Four alone leaves the corners bare and the
     * ring reads as a plus sign round each letter. The count is part of the recipe rather than
     * something a caller chooses, because it is the count that makes the ring look round.
     *
     * Inline, and the offsets are arithmetic rather than a list, so drawing an outlined run every
     * frame allocates nothing.
     */
    inline fun forEachStamp(stamp: (dx: Float, dy: Float) -> Unit) {
        if (!isVisible) return
        val straight = width
        val corner = diagonal
        stamp(-straight, 0f)
        stamp(straight, 0f)
        stamp(0f, -straight)
        stamp(0f, straight)
        stamp(-corner, -corner)
        stamp(corner, -corner)
        stamp(-corner, corner)
        stamp(corner, corner)
    }

    companion object {

        /** Private: [forEachStamp] is inline but reaches this through [diagonal], which is not. */
        private const val Cosine45 = 0.70710678f
    }
}
