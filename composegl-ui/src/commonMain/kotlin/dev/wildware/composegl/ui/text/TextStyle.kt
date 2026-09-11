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

    companion object {

        /** What a widget uses when a game has not said otherwise. */
        val Default = TextStyle()
    }
}
