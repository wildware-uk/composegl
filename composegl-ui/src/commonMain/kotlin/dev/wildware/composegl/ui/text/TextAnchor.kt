package dev.wildware.composegl.ui.text

/**
 * Which part of a piece of text lands on the y it was placed at.
 *
 * A text node is positioned by its **line box**, whose top sits at the tallest glyph's ascent.
 * That is the right default — a box stopping at the cap height would clip the accent off a capital
 * — but a caller who already knows where the glyphs have to go has no way to say so, and has to
 * subtract the difference at every site instead.
 *
 * The cost of leaving that to the caller is not the arithmetic. It is that getting it wrong is
 * **silent**: nothing overflows and nothing clips, the text is simply low and looks deliberate. And
 * the error is proportional to the font size, so a screen with three text sizes is wrong by three
 * different amounts and reads as "the layout is a bit off" rather than as one systematic offset.
 *
 * It bites hardest on a port. An immediate-mode or batch renderer almost always anchors a string by
 * its cap or its baseline, so every coordinate carried across is a cap top.
 *
 * Nothing about the text changes: the node is the same size, wraps the same way and draws the same
 * glyphs. Only where it sits moves, the way [dev.wildware.composegl.ui.modifier.offset] moves a
 * node — so a background, a border and a click all move with it.
 */
enum class TextAnchor {

    /**
     * The top of the line box, which is what a text node has always been placed by.
     *
     * The default everywhere, and the right one for laying out with: a column of labels lines up by
     * its line boxes, whatever letters happen to be in each of them.
     */
    LineBox,

    /**
     * The top of a capital letter.
     *
     * What a ported coordinate almost always means. Also what the eye reads as the top of a word,
     * which is why a figure anchored this way sits where a designer expected rather than where the
     * font's tallest accent would have put it.
     */
    CapTop,

    /**
     * The line the letters stand on.
     *
     * The one with a second use: it is also what lines a label up with an icon, or two strings at
     * different sizes against each other.
     */
    Baseline,
    ;

    /**
     * How far **up** a node has to move for this anchor to land on the y it was given.
     *
     * Zero for [LineBox], which is where the node already is. Positive for the other two, because
     * both are below the top of the line box.
     *
     * Read off the font rather than off a measured string on purpose: it is a property of the face
     * at that size and not of the words, so it is the same answer for every label in a style and is
     * known before anything has been measured. The backends put a style's extra line spacing below
     * the baseline rather than above it, which is what makes the line box top and the ascent the
     * same edge here.
     */
    fun lift(metrics: FontMetrics): Float = when (this) {
        LineBox -> 0f
        CapTop -> metrics.capInset
        Baseline -> metrics.ascent
    }
}
