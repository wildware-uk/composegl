package dev.wildware.composegl.ui.text

import dev.wildware.composegl.ui.layout.LayoutDirection

/**
 * Where a key takes the caret.
 *
 * Left and right mean across the screen, the way the arrows point. In English that is the same as
 * along the string. In a Hebrew word it is the opposite, and in a line mixing the two it is neither:
 * the caret goes to the nearest place to its left or right that a caret can be drawn. Words follow
 * the paragraph instead — ctrl and left in a Hebrew line goes on to the next word, which is on the
 * left — and Home and End go to where the line starts and ends in the string.
 */
enum class Movement {
    /** One character — one *whole* character, emoji and all. See [graphemeBefore]. */
    Left,
    Right,

    /** To the edge of the word either side. What ctrl and an arrow do everywhere. */
    WordLeft,
    WordRight,

    /** Home and End, which stop at the line the caret is on rather than the whole field. */
    LineStart,
    LineEnd,

    /** Ctrl-Home and Ctrl-End: the ends of the lot. */
    TextStart,
    TextEnd,
}

/**
 * Moves the caret, or drags the far end of the selection if [extend] is true.
 *
 * The selection has an anchor — the end the player is *not* holding — and shift-arrow moves the
 * other one. That is why a selection made rightwards and then shortened with shift-left shrinks
 * instead of jumping: the anchor never moved.
 *
 * Without shift, a plain left or right on a selection collapses it to the near end rather than
 * moving a character, which is what every editor does and what a player who has just selected a
 * word and pressed left expects. The wider movements — a word, a line, the lot — move from the
 * caret, because there is nothing surprising about ctrl-left going to the start of the word.
 */
fun TextFieldValue.move(movement: Movement, extend: Boolean = false): TextFieldValue =
    move(movement, extend, LayoutDirection.Ltr)

/**
 * The same, in a screen that reads in [direction] — which decides the way a line with no letters in
 * it, a line of digits, reads. Every line with a letter in it reads the way that letter does.
 */
// An overload rather than a defaulted parameter, so a game compiled against the one above still links.
fun TextFieldValue.move(movement: Movement, extend: Boolean = false, direction: LayoutDirection): TextFieldValue {
    val sideways = movement == Movement.Left || movement == Movement.Right ||
        movement == Movement.WordLeft || movement == Movement.WordRight
    val bidi = if (sideways) BidiText(text, direction) else null

    if (!extend && !selection.collapsed && bidi != null) {
        // The end nearer the arrow, which in a right-to-left line is the later one for left.
        val backwards = bidi.isRightToLeftAt(selection.min) == (movement == Movement.Left)
        when (movement) {
            Movement.Left, Movement.Right ->
                return copy(selection = if (backwards) selection.collapseToEnd() else selection.collapseToStart())
            else -> Unit
        }
    }

    val from = selection.end
    val rightToLeft = bidi != null && bidi.isRightToLeftAt(from)
    val to = when (movement) {
        Movement.Left -> text.visualStep(from, rightwards = false, checkNotNull(bidi))
        Movement.Right -> text.visualStep(from, rightwards = true, checkNotNull(bidi))
        Movement.WordLeft -> if (rightToLeft) text.wordEndAfter(from) else text.wordStartBefore(from)
        Movement.WordRight -> if (rightToLeft) text.wordStartBefore(from) else text.wordEndAfter(from)
        Movement.LineStart -> text.lineStartAt(from)
        Movement.LineEnd -> text.lineEndAt(from)
        Movement.TextStart -> 0
        Movement.TextEnd -> text.length
    }

    return copy(selection = if (extend) TextRange(selection.start, to) else TextRange(to))
}

/**
 * Where one press of an arrow takes a caret at [from]: the nearest caret position on its line that
 * is drawn further that way.
 *
 * Every grapheme counts as one unit wide here. Real widths would put the positions in the same order
 * — each is a run's edge plus a sum of widths — so the answer needs no fonts, and a field and a
 * selectable label agree about it. At the edge of the line the caret carries on to the line after
 * or before, whichever lies that way for the paragraph's direction.
 */
internal fun String.visualStep(from: Int, rightwards: Boolean, bidi: BidiText): Int {
    if (bidi.allLeftToRight) return if (rightwards) graphemeAfter(from) else graphemeBefore(from)

    val lineStart = lineStartAt(from)
    val lineEnd = lineEndAt(from)
    val geometry = BidiLine(bidi.runs(lineStart, lineEnd), 0f) { a, b -> graphemesBetween(a, b).toFloat() }
    val here = geometry.x(from)
    val hereRun = geometry.runAt(from)

    var best = -1
    var bestX = 0f
    var at = lineStart
    while (true) {
        if (at != from) {
            val x = geometry.x(at)
            if (if (rightwards) x > here else x < here) {
                val closer = best < 0 || (if (rightwards) x < bestX else x > bestX)
                // Two positions drawn in one place, where two runs meet: stay in the run the caret is in.
                val staying = best >= 0 && x == bestX && geometry.runAt(at) == hereRun && geometry.runAt(best) != hereRun
                if (closer || staying) {
                    best = at
                    bestX = x
                }
            }
        }
        if (at >= lineEnd) break
        at = graphemeAfter(at).coerceAtMost(lineEnd)
    }
    if (best >= 0) return best

    val forwards = rightwards != bidi.isRightToLeftAt(lineStart)
    return when {
        forwards && lineEnd < length -> lineEnd + 1
        !forwards && lineStart > 0 -> lineStart - 1
        else -> from
    }
}

private fun String.graphemesBetween(from: Int, to: Int): Int {
    var count = 0
    var at = from
    while (at < to) {
        at = graphemeAfter(at)
        count++
    }
    return count
}

/** Selects everything. Ctrl-A, and the first half of "type over what is there". */
fun TextFieldValue.selectAll(): TextFieldValue = copy(selection = TextRange(0, text.length))

// --- words ---------------------------------------------------------------------------------------
//
// One rule, in three kinds. A run of letters and digits is a word, a run of spaces is a gap, and a
// run of anything else — punctuation, brackets, arrows — is its own thing. Moving stops where the
// kind changes, which is why ctrl-right through `foo.bar` stops at the dot rather than skipping to
// the end of the line, and why it crosses a run of twenty spaces in one press.

private enum class Kind { Word, Space, Other }

private fun kindOf(text: String, index: Int): Kind {
    val character = text[index]
    return when {
        character.isWhitespace() -> Kind.Space
        character.isLetterOrDigit() || character == '_' -> Kind.Word
        // The tail of a surrogate pair, and anything that hangs off a letter, belong to whatever
        // they are attached to rather than being punctuation in their own right.
        character.isLowSurrogate() || character.isHighSurrogate() -> Kind.Word
        else -> Kind.Other
    }
}

/**
 * The start of the word to the left, which is where ctrl-left goes.
 *
 * Spaces first: a caret sitting after `hello   ` goes to the start of `hello`, not to the start of
 * the spaces. From zero it stays at zero — a no-op, never an exception.
 */
internal fun String.wordStartBefore(index: Int): Int {
    var at = index.coerceIn(0, length)
    while (at > 0 && kindOf(this, at - 1) == Kind.Space) at--
    if (at == 0) return 0

    val kind = kindOf(this, at - 1)
    while (at > 0 && kindOf(this, at - 1) == kind) at--
    return at
}

/** The end of the word to the right, which is where ctrl-right goes. Past the end it stays there. */
internal fun String.wordEndAfter(index: Int): Int {
    var at = index.coerceIn(0, length)
    while (at < length && kindOf(this, at) == Kind.Space) at++
    if (at == length) return length

    val kind = kindOf(this, at)
    while (at < length && kindOf(this, at) == kind) at++
    return at
}

/**
 * The word [index] is in, for a double click.
 *
 * A click inside a word takes the word, and a click in a gap takes the gap, so a double click never
 * selects nothing. The boundaries are the same ones ctrl-left and ctrl-right stop at, which is the
 * point: two ways of asking for a word must not disagree.
 */
fun String.wordAt(index: Int): TextRange {
    if (isEmpty()) return TextRange.Zero
    val at = index.coerceIn(0, length)
    // A caret at the very end of a word belongs to that word, not to what follows it.
    val inside = if (at == length || (at > 0 && kindOf(this, at) == Kind.Space && kindOf(this, at - 1) != Kind.Space)) at - 1 else at

    val kind = kindOf(this, inside)
    var start = inside
    while (start > 0 && kindOf(this, start - 1) == kind) start--
    var end = inside
    while (end < length && kindOf(this, end) == kind) end++
    return TextRange(start, end)
}

// --- lines ---------------------------------------------------------------------------------------

/** The start of the line the caret is on. Home. */
internal fun String.lineStartAt(index: Int): Int {
    val at = index.coerceIn(0, length)
    val newline = lastIndexOf('\n', (at - 1).coerceAtLeast(0))
    return if (at == 0 || newline < 0) 0 else newline + 1
}

/** The end of the line the caret is on, before the line ending rather than after it. End. */
internal fun String.lineEndAt(index: Int): Int {
    val at = index.coerceIn(0, length)
    val newline = indexOf('\n', at)
    return if (newline < 0) length else newline
}

/** The whole line [index] is on, for a triple click. The line ending is not part of it. */
fun String.lineAt(index: Int): TextRange = TextRange(lineStartAt(index), lineEndAt(index))
