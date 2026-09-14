package dev.wildware.composegl.ui.text

import dev.wildware.composegl.ui.graphics.Colour

/** A line drawn along a run of text. */
enum class TextDecoration {

    None,

    /** Under the words, where a link or a defined term is marked. */
    Underline,

    /** Through the words, where something has been replaced or no longer applies. */
    Strike;

    /**
     * Where the line sits, measured from the baseline, with down positive.
     *
     * Off the font's own shape rather than off a fixed number of pixels, so one decoration looks
     * the same at every text size in a skin. An underline clears the baseline by a tenth of the
     * size — close enough to belong to the words, far enough not to cut through a comma. A strike
     * sits halfway up a capital, which is where the eye expects it whether or not the run happens
     * to contain a tall letter.
     */
    fun offsetFrom(metrics: FontMetrics): Float = when (this) {
        None -> 0f
        Underline -> metrics.size * 0.1f
        Strike -> -metrics.capHeight * 0.5f - thicknessFor(metrics) / 2f
    }

    /** How thick the line is drawn. At least one pixel, so it never disappears at a small size. */
    fun thicknessFor(metrics: FontMetrics): Float = (metrics.size / 16f).coerceAtLeast(1f)
}

/**
 * Part of a string that looks different from the rest of it, or that the pointer can find.
 *
 * The whole of what inline styling is here: which characters, what colour, and whether a line is
 * drawn along them. Deliberately not a second [TextStyle] — a run cannot change the family or the
 * size, because a run that changes the size changes the line height, and a paragraph whose lines
 * are different heights is a different and much larger problem than an underlined term.
 *
 * [tag] is whatever the game wants back when the pointer is over the run: a term to look up, an
 * item id, an index into its own list. It is compared by equality like everything else in a data
 * class, so a run built fresh each recomposition still compares equal to last frame's.
 *
 * Runs are applied in order and may overlap; where two cover the same character the later one wins
 * for whichever of colour and decoration it names.
 *
 * @param range which characters, counted between them exactly as [TextRange] says.
 * @param colour the colour for these characters. Null keeps the label's own.
 */
data class TextRun(
    val range: TextRange,
    val colour: Colour? = null,
    val decoration: TextDecoration = TextDecoration.None,
    val tag: Any? = null,
)
