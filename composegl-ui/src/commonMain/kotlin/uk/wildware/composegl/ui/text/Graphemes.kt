package uk.wildware.composegl.ui.text

/**
 * Where one character ends and the next begins — the one a *person* would point at.
 *
 * A player who presses backspace after typing 👨‍👩‍👧 expects the family to go, not a leg of it. That
 * emoji is eight `Char`s, five code points and one character, and the difference between those three
 * numbers is the whole of this file.
 *
 * What is handled here is the part of Unicode's rules that games actually meet:
 *
 * - a surrogate pair is one code point, not two;
 * - combining marks stay with the letter in front of them, so `e` and an acute accent are one;
 * - a zero-width joiner glues what is either side of it, which is what makes a family one emoji;
 * - a variation selector and a skin-tone modifier belong to what they change;
 * - two regional indicators are one flag;
 * - a carriage return and a line feed are one line ending.
 *
 * What is not handled is the rest of UAX #29: Indic conjuncts, Hangul jamo composition, and the
 * prepend class. Those need the full property tables, and the full tables need the text shaper this
 * toolkit has not written yet. The rule of thumb this file keeps is that it is never *worse* than
 * splitting code points, and for the characters a player types into a name box it is right.
 */

private const val ZeroWidthJoiner = 0x200D
private const val RegionalIndicatorFirst = 0x1F1E6
private const val RegionalIndicatorLast = 0x1F1FF
private const val SkinToneFirst = 0x1F3FB
private const val SkinToneLast = 0x1F3FF
private const val VariationSelectorFirst = 0xFE00
private const val VariationSelectorLast = 0xFE0F

/** The code point at [index], which is one `Char` unless it is the start of a surrogate pair. */
internal fun String.codePointAt(index: Int): Int {
    val first = this[index]
    if (!first.isHighSurrogate() || index + 1 >= length || !this[index + 1].isLowSurrogate()) {
        return first.code
    }
    return 0x10000 + ((first.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00)
}

/** True for anything that belongs to the character in front of it rather than standing alone. */
private fun extendsWhatCameBefore(codePoint: Int): Boolean = when {
    codePoint in VariationSelectorFirst..VariationSelectorLast -> true
    codePoint in SkinToneFirst..SkinToneLast -> true
    else -> when (codePointCategory(codePoint)) {
        CharCategory.NON_SPACING_MARK,
        CharCategory.ENCLOSING_MARK,
        CharCategory.COMBINING_SPACING_MARK,
        -> true
        else -> false
    }
}

/**
 * The category of a code point.
 *
 * Only the basic plane is asked: [CharCategory] is a `Char` property, and everything above 0xFFFF
 * that this file cares about — skin tones, flags, the emoji themselves — is decided above by range.
 */
private fun codePointCategory(codePoint: Int): CharCategory? =
    if (codePoint > 0xFFFF) null else codePoint.toChar().category

private fun isRegionalIndicator(codePoint: Int) = codePoint in RegionalIndicatorFirst..RegionalIndicatorLast

/**
 * Where the character that ends at [index] begins.
 *
 * This is what backspace deletes back to. [index] is assumed to be on a boundary already; a
 * [TextFieldValue] cannot hold one that is not.
 */
internal fun String.graphemeBefore(index: Int): Int {
    if (index <= 0) return 0

    var at = startOfCharBefore(index)
    if (this[at] == '\n' && at > 0 && this[at - 1] == '\r') return at - 1

    // Flags are counted rather than looked at: an odd number of regional indicators behind this one
    // means it is the second half of a flag, and an even number means it is the first half of the
    // next one.
    if (isRegionalIndicator(codePointAt(at))) {
        var indicators = 0
        var walk = at
        while (walk > 0) {
            val previous = startOfCharBefore(walk)
            if (!isRegionalIndicator(codePointAt(previous))) break
            indicators++
            walk = previous
        }
        return if (indicators % 2 == 1) startOfCharBefore(at) else at
    }

    while (at > 0) {
        if (extendsWhatCameBefore(codePointAt(at))) {
            at = startOfCharBefore(at)
            continue
        }
        val previous = startOfCharBefore(at)
        if (codePointAt(previous) == ZeroWidthJoiner && previous > 0) {
            at = startOfCharBefore(previous)
            continue
        }
        break
    }
    return at
}

/** Where the character that starts at [index] ends. What delete takes. */
internal fun String.graphemeAfter(index: Int): Int {
    if (index >= length) return length
    if (this[index] == '\r' && index + 1 < length && this[index + 1] == '\n') return index + 2

    val first = codePointAt(index)
    var at = endOfCharAfter(index)

    if (isRegionalIndicator(first)) {
        if (at < length && isRegionalIndicator(codePointAt(at))) at = endOfCharAfter(at)
        return at
    }

    while (at < length) {
        val next = codePointAt(at)
        when {
            extendsWhatCameBefore(next) -> at = endOfCharAfter(at)
            next == ZeroWidthJoiner -> {
                val joined = endOfCharAfter(at)
                at = if (joined < length) endOfCharAfter(joined) else joined
            }
            else -> return at
        }
    }
    return at
}
