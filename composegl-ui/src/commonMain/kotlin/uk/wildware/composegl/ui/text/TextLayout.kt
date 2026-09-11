package uk.wildware.composegl.ui.text

import uk.wildware.composegl.ui.geometry.Size

/**
 * A string that has been measured and is ready to draw.
 *
 * The toolkit never breaks lines or measures glyphs itself — a backend does that, because a
 * backend is the only thing that knows what a glyph looks like. What the toolkit needs back is a
 * size to lay out with and something opaque to hand to the canvas later.
 *
 * Measuring twice must give the same answer, and drawing must match what was measured. Everything
 * about layout depends on that and nothing else about text.
 */
interface TextLayout {

    /** What was measured. */
    val text: String

    /** How much room it needs. */
    val size: Size

    /** After wrapping. One for a short label, more when it wrapped or contained newlines. */
    val lineCount: Int

    /**
     * Distance from the top of [size] down to the first line's baseline.
     *
     * Needed to line up text with things that are not text — an icon beside a label, two labels at
     * different sizes in a row.
     */
    val firstBaseline: Float
}
