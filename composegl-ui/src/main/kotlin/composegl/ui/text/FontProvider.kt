package composegl.ui.text

/**
 * Measures text. The one thing layout cannot do without and cannot do itself.
 *
 * Supplied by a backend, because only a backend knows what a glyph looks like. Two rules the
 * toolkit relies on and cannot check for itself:
 *
 * - measuring the same text twice gives the same answer;
 * - drawing matches what was measured.
 *
 * Break either and layout is subtly wrong everywhere at once, in a way that looks like a layout
 * bug and is not.
 */
interface FontProvider {

    /**
     * @param maxWidth the width to wrap at. Infinite means do not wrap; explicit newlines still
     *   break lines.
     */
    fun measure(
        text: String,
        style: TextStyle = TextStyle.Default,
        maxWidth: Float = Float.POSITIVE_INFINITY,
    ): TextLayout
}
