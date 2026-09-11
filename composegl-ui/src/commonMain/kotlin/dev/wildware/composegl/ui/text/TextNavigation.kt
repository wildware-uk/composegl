package dev.wildware.composegl.ui.text

/**
 * Where a key takes the caret.
 *
 * Left and right mean along the string, not across the screen: this toolkit has no bidirectional
 * text yet, so in Arabic or Hebrew these would be the wrong way round. That is a real limit, and it
 * is here rather than hidden in a widget so it can be fixed in one place when the shaper arrives.
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
fun TextFieldValue.move(movement: Movement, extend: Boolean = false): TextFieldValue {
    if (!extend && !selection.collapsed) {
        when (movement) {
            Movement.Left -> return copy(selection = selection.collapseToStart())
            Movement.Right -> return copy(selection = selection.collapseToEnd())
            else -> Unit
        }
    }

    val from = selection.end
    val to = when (movement) {
        Movement.Left -> text.graphemeBefore(from)
        Movement.Right -> text.graphemeAfter(from)
        Movement.WordLeft -> text.wordStartBefore(from)
        Movement.WordRight -> text.wordEndAfter(from)
        Movement.LineStart -> text.lineStartAt(from)
        Movement.LineEnd -> text.lineEndAt(from)
        Movement.TextStart -> 0
        Movement.TextEnd -> text.length
    }

    return copy(selection = if (extend) TextRange(selection.start, to) else TextRange(to))
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
