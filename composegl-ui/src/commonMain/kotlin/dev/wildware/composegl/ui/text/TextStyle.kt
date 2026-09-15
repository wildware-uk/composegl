package dev.wildware.composegl.ui.text

/** The family name a game gets if it registers only one font. */
const val DEFAULT_FAMILY = "default"

/**
 * How a piece of text should look and how much room it may take.
 *
 * A family name rather than a font object: the toolkit knows the name a game registered and lets
 * the backend decide what that means. A game that wants two weights registers two names.
 */
data class TextStyle(
    val family: String = DEFAULT_FAMILY,
    val size: Float = 16f,
    /** Space between baselines, as a multiple of [size]. */
    val lineHeightRatio: Float = 1.25f,
    /** Wrapped text is cut off after this many lines. Zero or fewer means no limit. */
    val maxLines: Int = 0,
    /** What to append when [maxLines] cut something off. Empty means cut it cleanly. */
    val ellipsis: String = "…",
) {

    init {
        require(size > 0f) { "text size must be positive, was $size" }
    }

    val lineHeight: Float get() = size * lineHeightRatio

    /**
     * This style with its size multiplied by [scale], rounded to a whole size.
     *
     * Rounded because fonts are baked one whole size at a time. Text at 16 scaled by 1.1 asks for
     * 18 rather than 17.6, so it is drawn from glyphs baked at 18 — one texel to one pixel — rather
     * than from 18's glyphs squeezed into a box measured for 17.6, and what was measured is still
     * what is drawn. Everything else — the family, the line spacing as a ratio, the line limit —
     * is left alone, so the line height grows with the size.
     *
     * A scale of one is this same style, not a copy: nothing that remembers on it sees a change. So
     * is a scale within float error of one, which is what two nested scales that cancel out give.
     *
     * @see dev.wildware.composegl.ui.widget.ProvideTextScale
     */
    fun scaled(scale: Float): TextStyle {
        require(scale > 0f && scale.isFinite()) { "a text scale must be positive, was $scale" }
        if (isUnscaled(scale)) return this
        return copy(size = scaledSize(size, scale))
    }

    companion object {

        /** What a widget uses when a game has not said otherwise. */
        val Default = TextStyle()
    }
}
