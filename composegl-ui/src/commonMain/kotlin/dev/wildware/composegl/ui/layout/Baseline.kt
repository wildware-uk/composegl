package dev.wildware.composegl.ui.layout

/**
 * Which line of a piece of text is meant, for the layouts that measure from a baseline.
 *
 * Two, because a paragraph has two edges that matter: the first line is what a title is spaced
 * down to from whatever is above it, and the last line is what the next thing is spaced down from.
 * For a single line of text they are the same line.
 *
 * See [dev.wildware.composegl.ui.modifier.paddingFrom] and [VerticalAlignment.Baseline].
 */
enum class Baseline {

    /** The line the first row of letters stands on. */
    First,

    /** The line the last row of letters stands on. */
    Last,
}
