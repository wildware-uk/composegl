package dev.wildware.composegl.ui.text

/**
 * The shape of a font at one size, in the toolkit's own units.
 *
 * What widgets need in order to line text up with things that are not text: an icon beside a
 * label, a caret in a text field, two labels at different sizes sharing a baseline. All of it
 * comes from the backend, because only the backend knows what a glyph looks like.
 *
 * Everything is measured from the baseline and everything is positive, including [descent]. A
 * signed descent is the single most common source of upside-down text.
 */
data class FontMetrics(
    /** The text size these were measured at. */
    val size: Float,
    /** Baseline to the top of the tallest glyph. */
    val ascent: Float,
    /** Baseline to the bottom of the lowest glyph. */
    val descent: Float,
    /** Baseline to the top of a capital letter. */
    val capHeight: Float,
    /** One baseline to the next, at this font's natural spacing. */
    val lineHeight: Float,
    /** How far a space moves the pen. */
    val spaceAdvance: Float,
) {

    /** Top of the tallest glyph to the bottom of the lowest. */
    val height: Float get() = ascent + descent

    /**
     * Top of the line box down to the top of a capital.
     *
     * The gap a caller who thinks in cap tops keeps rediscovering, given a name so that it is one
     * library concept rather than a subtraction written out at every site. It is the room the
     * tallest accent needs, which is why a line box does not stop at the capitals and why text
     * placed by a cap-top coordinate draws exactly this much low.
     *
     * Proportional to [size], so a screen with three text sizes on it is wrong by three different
     * amounts — which is what makes the mistake read as a layout that needs tuning rather than as
     * one offset that needs subtracting. [TextAnchor] is the way to stop subtracting it by hand.
     */
    val capInset: Float get() = ascent - capHeight
}
