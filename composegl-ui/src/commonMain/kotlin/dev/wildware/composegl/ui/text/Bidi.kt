package dev.wildware.composegl.ui.text

import dev.wildware.composegl.ui.layout.LayoutDirection
import kotlin.math.max
import kotlin.math.min

/**
 * One stretch of a line that reads one way, in the order the line is drawn.
 *
 * A line of `Press שלום to start` is three of these: the English before, the Hebrew word, and the
 * English after, drawn left to right in that order, with the middle one's letters drawn from its
 * right edge leftwards. [range] is still counted in the string's own order, so a run and a
 * [TextRun] or a selection can be compared directly.
 *
 * @param rightToLeft whether the characters in [range] are drawn from the right.
 */
data class BidiRun(val range: TextRange, val rightToLeft: Boolean)

/**
 * Which way each character of [text] reads, worked out once.
 *
 * The parts of the Unicode Bidirectional Algorithm (UAX #9) that interface text needs: each
 * paragraph — a stretch between newlines — takes its direction from its first letter that has one,
 * or [fallback] when it has none (a line of digits, a lone "!"); numbers and punctuation take the
 * direction of what surrounds them; and each line is reordered into runs. Explicit embedding and
 * isolate controls are treated as invisible rather than obeyed.
 *
 * The common case costs one pass over the characters and nothing more: a string with nothing
 * right-to-left in it, in paragraphs that read left to right, is [allLeftToRight] and every line of
 * it is one run.
 */
internal class BidiText(val text: String, private val fallback: LayoutDirection) {

    /** Each character's class as it came, which the per-line rules need after the rest have run. */
    private val classes = ByteArray(text.length)

    /** Where each paragraph starts, and whether it reads from the right. */
    private val paragraphStarts = ArrayList<Int>()
    private val paragraphRtl = ArrayList<Boolean>()

    /** Every character's embedding level. Null when every one of them is zero. */
    private val levels: ByteArray?

    val allLeftToRight: Boolean get() = levels == null

    init {
        var rightToLeftInside = false
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val type = bidiClassOf(codePoint)
            val width = if (codePoint > 0xFFFF) 2 else 1
            for (unit in 0 until width) classes[index + unit] = type.ordinal.toByte()
            if (type == BidiClass.R || type == BidiClass.AL || type == BidiClass.AN) rightToLeftInside = true
            index += width
        }

        var start = 0
        var anyRtl = false
        while (true) {
            var end = start
            while (end < text.length && classes[end].toInt() != BidiClass.B.ordinal) end++
            val rtl = firstStrong(start, end) ?: (fallback == LayoutDirection.Rtl)
            paragraphStarts += start
            paragraphRtl += rtl
            if (rtl && end > start) anyRtl = true
            if (end >= text.length) break
            start = end + 1
        }

        levels = if (!rightToLeftInside && !anyRtl) null else ByteArray(text.length).also { resolved ->
            for (paragraph in paragraphStarts.indices) {
                val from = paragraphStarts[paragraph]
                val to = if (paragraph + 1 < paragraphStarts.size) paragraphStarts[paragraph + 1] else text.length
                resolve(from, to, paragraphRtl[paragraph], resolved)
            }
        }
    }

    /** Whether the paragraph holding character [index] reads from the right. */
    fun isRightToLeftAt(index: Int): Boolean {
        var found = paragraphRtl.firstOrNull() ?: (fallback == LayoutDirection.Rtl)
        for (paragraph in paragraphStarts.indices) {
            if (paragraphStarts[paragraph] <= index) found = paragraphRtl[paragraph] else break
        }
        return found
    }

    /**
     * The line [from] to [to] as runs, in the order they are drawn from the left.
     *
     * The line must not include the newline it ends at. An empty line is one empty run, in its
     * paragraph's direction, so a caret on it still has somewhere to be.
     */
    fun runs(from: Int, to: Int): List<BidiRun> {
        val paragraphRtl = isRightToLeftAt(from)
        val levels = levels
        if (levels == null || from >= to) return listOf(BidiRun(TextRange(from, to), levels != null && paragraphRtl && from >= to))

        val base = if (paragraphRtl) 1 else 0
        val line = IntArray(to - from) { levels[from + it].toInt() }
        // L1: separators, and the white space before them and at the end of the line, go back to
        // the paragraph's level — so a trailing space in a Hebrew line does not end up on the left.
        var trailing = true
        for (index in to - 1 downTo from) {
            val type = classes[index].toInt()
            when {
                type == BidiClass.S.ordinal || type == BidiClass.B.ordinal -> {
                    line[index - from] = base
                    trailing = true
                }
                trailing && (type == BidiClass.WS.ordinal || type == BidiClass.BN.ordinal) -> line[index - from] = base
                else -> trailing = false
            }
        }

        // Contiguous stretches of one level, in the string's order.
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val runLevels = ArrayList<Int>()
        var runStart = 0
        for (index in 1..line.size) {
            if (index == line.size || line[index] != line[runStart]) {
                starts += from + runStart
                ends += from + index
                runLevels += line[runStart]
                runStart = index
            }
        }

        // L2: from the highest level down to the lowest odd one, reverse every stretch of runs at
        // that level or above.
        val order = IntArray(starts.size) { it }
        val highest = runLevels.max()
        val lowestOdd = runLevels.filter { it % 2 == 1 }.minOrNull() ?: (highest + 1)
        for (level in highest downTo lowestOdd) {
            var index = 0
            while (index < order.size) {
                if (runLevels[order[index]] < level) {
                    index++
                    continue
                }
                var end = index
                while (end < order.size && runLevels[order[end]] >= level) end++
                order.reverse(index, end)
                index = end
            }
        }
        return order.map { BidiRun(TextRange(starts[it], ends[it]), runLevels[it] % 2 == 1) }
    }

    private fun firstStrong(from: Int, to: Int): Boolean? {
        for (index in from until to) {
            when (classes[index].toInt()) {
                BidiClass.L.ordinal -> return false
                BidiClass.R.ordinal, BidiClass.AL.ordinal -> return true
            }
        }
        return null
    }

    /** One paragraph's levels, from the weak rules through the implicit ones, into [into]. */
    private fun resolve(from: Int, to: Int, rtl: Boolean, into: ByteArray) {
        val count = to - from
        if (count == 0) return
        val types = Array(count) { BidiClass.entries[classes[from + it].toInt()] }
        val sos = if (rtl) BidiClass.R else BidiClass.L

        // W1: a mark takes the type of what it is on.
        var previous = sos
        for (index in 0 until count) {
            if (types[index] == BidiClass.NSM || types[index] == BidiClass.BN) types[index] = previous
            else previous = types[index]
        }
        // W2: a European digit after Arabic letters is an Arabic number. W3: Arabic letters are R.
        var strong = sos
        for (index in 0 until count) {
            when (types[index]) {
                BidiClass.L, BidiClass.R, BidiClass.AL -> strong = types[index]
                BidiClass.EN -> if (strong == BidiClass.AL) types[index] = BidiClass.AN
                else -> Unit
            }
        }
        for (index in 0 until count) if (types[index] == BidiClass.AL) types[index] = BidiClass.R
        // W4: one separator between two numbers of a kind belongs to them.
        for (index in 1 until count - 1) {
            val before = types[index - 1]
            val after = types[index + 1]
            when (types[index]) {
                BidiClass.ES -> if (before == BidiClass.EN && after == BidiClass.EN) types[index] = BidiClass.EN
                BidiClass.CS -> if (before == after && (before == BidiClass.EN || before == BidiClass.AN)) types[index] = before
                else -> Unit
            }
        }
        // W5: a currency sign or percent beside a European number is part of it.
        var index = 0
        while (index < count) {
            if (types[index] != BidiClass.ET) {
                index++
                continue
            }
            var end = index
            while (end < count && types[end] == BidiClass.ET) end++
            val touches = (index > 0 && types[index - 1] == BidiClass.EN) || (end < count && types[end] == BidiClass.EN)
            if (touches) for (at in index until end) types[at] = BidiClass.EN
            index = end
        }
        // W6: separators and terminators left over are just punctuation.
        for (at in 0 until count) {
            if (types[at] == BidiClass.ES || types[at] == BidiClass.ET || types[at] == BidiClass.CS) types[at] = BidiClass.ON
        }
        // W7: a European number in left-to-right text is left to right.
        strong = sos
        for (at in 0 until count) {
            when (types[at]) {
                BidiClass.L, BidiClass.R -> strong = types[at]
                BidiClass.EN -> if (strong == BidiClass.L) types[at] = BidiClass.L
                else -> Unit
            }
        }
        // N1 and N2: punctuation and spaces between two things of one direction take it; between
        // two different ones, they take the paragraph's.
        index = 0
        while (index < count) {
            if (!types[index].isNeutral()) {
                index++
                continue
            }
            var end = index
            while (end < count && types[end].isNeutral()) end++
            val before = if (index == 0) sos else types[index - 1].direction()
            val after = if (end == count) sos else types[end].direction()
            val resolved = if (before == after) before else sos
            for (at in index until end) types[at] = resolved
            index = end
        }
        // I1 and I2.
        val embedding = if (rtl) 1 else 0
        for (at in 0 until count) {
            val level = if (embedding == 0) {
                when (types[at]) {
                    BidiClass.R -> 1
                    BidiClass.AN, BidiClass.EN -> 2
                    else -> 0
                }
            } else {
                when (types[at]) {
                    BidiClass.L, BidiClass.EN, BidiClass.AN -> 2
                    else -> 1
                }
            }
            into[from + at] = level.toByte()
        }
    }
}

/**
 * How one line of a [BidiText] is laid out across the screen, given a way to measure a stretch of it.
 *
 * Positions inside a run come from measuring the run's logical prefixes, which only works because a
 * run's width is the same whichever way round its characters are — the no-kerning promise
 * [Paragraph] already depends on. In a right-to-left run, character *i* starts `prefix(i)` in from
 * the run's *right* edge.
 *
 * @param lead how far in from the left the first run starts, for an ellipsis drawn before it.
 */
internal class BidiLine(
    val runs: List<BidiRun>,
    lead: Float,
    private val widthOf: (from: Int, to: Int) -> Float,
) {
    val lefts = FloatArray(runs.size)
    val widths = FloatArray(runs.size)

    /** Where the last run ends: the lead and every run's width. */
    val right: Float

    init {
        var x = lead
        for (index in runs.indices) {
            val range = runs[index].range
            val width = if (range.collapsed) 0f else widthOf(range.min, range.max)
            lefts[index] = x
            widths[index] = width
            x += width
        }
        right = x
    }

    /**
     * The run a caret at [index] is drawn in: the one holding the character after it, or failing that
     * the one that ends there. That is what puts a caret at the end of a Hebrew line on its left.
     */
    fun runAt(index: Int): Int {
        for (run in runs.indices) {
            val range = runs[run].range
            if (index >= range.min && index < range.max) return run
        }
        for (run in runs.indices) if (runs[run].range.max == index) return run
        return 0
    }

    /** Where a caret at [index] is, inside run [run], from the left of the line. */
    fun xIn(run: Int, index: Int): Float {
        val on = runs[run]
        val at = index.coerceIn(on.range.min, on.range.max)
        val prefix = when (at) {
            on.range.min -> 0f
            on.range.max -> widths[run]
            else -> widthOf(on.range.min, at)
        }
        return if (on.rightToLeft) lefts[run] + widths[run] - prefix else lefts[run] + prefix
    }

    /** Where a caret at [index] is, from the left of the line. */
    fun x(index: Int): Float = xIn(runAt(index), index)

    /**
     * The stretches of the line [from] to [to] covers, as a left and a right each, one per run it
     * touches — a selection across a Hebrew word and the English beside it is two boxes that meet.
     */
    inline fun spans(from: Int, to: Int, each: (left: Float, right: Float) -> Unit) {
        for (run in runs.indices) {
            val range = runs[run].range
            val start = max(from, range.min)
            val end = min(to, range.max)
            if (end <= start) continue
            val a = xIn(run, start)
            val b = xIn(run, end)
            each(min(a, b), max(a, b))
        }
    }
}

/**
 * The characters [from] to [to] in the order they are drawn: as they are for a left-to-right run,
 * and for a right-to-left one reversed a whole character at a time — so an accent stays on its
 * letter and an emoji stays in one piece — with brackets turned to face the other way.
 */
internal fun visualText(text: String, from: Int, to: Int, rightToLeft: Boolean): String {
    if (!rightToLeft) return text.substring(from, to)
    val out = StringBuilder(to - from)
    var end = to
    while (end > from) {
        val start = text.graphemeBefore(end).coerceAtLeast(from)
        if (start + 1 == end) out.append(mirrored(text[start])) else out.append(text.substring(start, end))
        end = start
    }
    return out.toString()
}

/**
 * Whether [this] would be drawn exactly as stored in a [fallback] screen, so a label can keep handing
 * it to the backend whole. The same answer [BidiText.allLeftToRight] gives, without building one.
 */
internal fun String.readsLeftToRight(fallback: LayoutDirection): Boolean {
    var sawLeft = false
    var sawAnything = false
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        when (bidiClassOf(codePoint)) {
            BidiClass.R, BidiClass.AL, BidiClass.AN -> return false
            BidiClass.L -> sawLeft = true
            BidiClass.B -> {
                if (fallback == LayoutDirection.Rtl && sawAnything && !sawLeft) return false
                sawLeft = false
                sawAnything = false
                index++
                continue
            }
            else -> Unit
        }
        sawAnything = true
        index += if (codePoint > 0xFFFF) 2 else 1
    }
    return !(fallback == LayoutDirection.Rtl && sawAnything && !sawLeft)
}

/** The Unicode bidirectional classes this implementation tells apart. */
internal enum class BidiClass {
    /** A letter that reads left to right. */
    L,
    /** A letter that reads right to left: Hebrew. */
    R,
    /** An Arabic letter, which reads right to left and turns the digits after it into [AN]. */
    AL,
    /** A European digit. */
    EN,
    /** A plus or minus. */
    ES,
    /** A currency sign or a percent. */
    ET,
    /** An Arabic-Indic digit. */
    AN,
    /** A comma, full stop, colon or slash between digits. */
    CS,
    /** A mark that sits on the character before it. */
    NSM,
    /** Something that takes no part: a joiner, a control, a direction override. */
    BN,
    /** A paragraph break. */
    B,
    /** A tab. */
    S,
    /** A space. */
    WS,
    /** Every other kind of punctuation and symbol, emoji included. */
    ON;

    fun isNeutral() = this == B || this == S || this == WS || this == ON

    /** The strong direction this counts as for a neighbouring neutral, after the weak rules. */
    fun direction() = if (this == L) L else R
}

private fun mirrored(character: Char): Char {
    val at = MirrorPairs.indexOf(character)
    if (at < 0) return character
    return if (at % 2 == 0) MirrorPairs[at + 1] else MirrorPairs[at - 1]
}

/** Characters that face a direction, in pairs: each is drawn as its partner in right-to-left text. */
private const val MirrorPairs = "()<>[]{}«»‹›⁅⁆⁽⁾₍₎≤≥〈〉《》「」『』【】〔〕〖〗〘〙〚〛（）＜＞［］｛｝"

/**
 * The class of one character, from ranges rather than the full Unicode table.
 *
 * Every script that reads right to left is covered — Hebrew, Arabic and its supplements, Syriac,
 * Thaana, NKo and the presentation forms — along with the digits, separators and spaces the rules
 * turn on. Everything else falls back to its general category: a letter or a digit reads left to
 * right, a mark sits on what came before, and the rest is punctuation.
 */
internal fun bidiClassOf(codePoint: Int): BidiClass {
    val c = codePoint
    if (c < 0x80) return asciiClass(c)
    return when {
        c == 0x85 -> BidiClass.B
        c < 0xA0 -> BidiClass.BN
        c == 0xA0 -> BidiClass.CS
        c in 0xA2..0xA5 || c == 0xB0 || c == 0xB1 -> BidiClass.ET
        c == 0xB2 || c == 0xB3 || c == 0xB9 -> BidiClass.EN
        c == 0xAD -> BidiClass.BN
        c in 0x0590..0x05FF -> hebrewClass(c)
        c in 0x0600..0x07BF -> arabicClass(c)
        c in 0x07C0..0x085F -> if (c in 0x07EB..0x07F3 || c in 0x0816..0x082D || c in 0x0859..0x085B) BidiClass.NSM else BidiClass.R
        c == 0x08E2 -> BidiClass.AN
        c in 0x08D3..0x08FF -> BidiClass.NSM
        c in 0x0860..0x08FF -> BidiClass.AL
        c in 0x2000..0x200A -> BidiClass.WS
        c in 0x200B..0x200D -> BidiClass.BN
        c == 0x200E -> BidiClass.L
        c == 0x200F -> BidiClass.R
        c == 0x2028 -> BidiClass.WS
        c == 0x2029 -> BidiClass.B
        c in 0x202A..0x202E -> BidiClass.BN
        c == 0x205F -> BidiClass.WS
        c in 0x2060..0x206F -> BidiClass.BN
        c in 0x2030..0x2034 -> BidiClass.ET
        c == 0x2070 || c in 0x2074..0x2079 || c in 0x2080..0x2089 -> BidiClass.EN
        c == 0x207A || c == 0x207B || c == 0x208A || c == 0x208B || c == 0x2212 -> BidiClass.ES
        c in 0x20A0..0x20CF -> BidiClass.ET
        c == 0x3000 -> BidiClass.WS
        c == 0xFB1E -> BidiClass.NSM
        c == 0xFB29 -> BidiClass.ES
        c in 0xFB1D..0xFB4F -> BidiClass.R
        c == 0xFD3E || c == 0xFD3F -> BidiClass.ON
        c in 0xFB50..0xFDFF -> BidiClass.AL
        c in 0xFE00..0xFE0F -> BidiClass.NSM
        c == 0xFEFF -> BidiClass.BN
        c in 0xFE70..0xFEFE -> BidiClass.AL
        c in 0xFF10..0xFF19 -> BidiClass.EN
        c == 0xFF0B || c == 0xFF0D -> BidiClass.ES
        c in 0xFF03..0xFF05 || c == 0xFFE0 || c == 0xFFE1 || c == 0xFFE5 || c == 0xFFE6 -> BidiClass.ET
        c == 0xFF0C || c == 0xFF0E || c == 0xFF0F || c == 0xFF1A -> BidiClass.CS
        c in 0x10800..0x10FFF -> BidiClass.R
        c in 0x1EC70..0x1ECBF || c in 0x1ED00..0x1ED4F || c in 0x1EE00..0x1EEFF -> BidiClass.AL
        c in 0x1E800..0x1EFFF -> BidiClass.R
        c in 0xE0100..0xE01EF -> BidiClass.NSM
        // Outside the Basic Multilingual Plane there is no category to ask in common code. Emoji
        // and the symbols around them are punctuation; the historic scripts up there are letters.
        c > 0xFFFF -> if (c in 0x1F000..0x1FAFF) BidiClass.ON else BidiClass.L
        else -> when (c.toChar().category) {
            CharCategory.NON_SPACING_MARK, CharCategory.ENCLOSING_MARK -> BidiClass.NSM
            CharCategory.SPACE_SEPARATOR, CharCategory.LINE_SEPARATOR -> BidiClass.WS
            CharCategory.PARAGRAPH_SEPARATOR -> BidiClass.B
            CharCategory.FORMAT, CharCategory.CONTROL -> BidiClass.BN
            CharCategory.CURRENCY_SYMBOL -> BidiClass.ET
            CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER,
            CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER, CharCategory.COMBINING_SPACING_MARK,
            CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.PRIVATE_USE,
            CharCategory.UNASSIGNED,
            -> BidiClass.L
            else -> BidiClass.ON
        }
    }
}

private fun asciiClass(c: Int): BidiClass = when (c) {
    0x09, 0x0B, 0x1F -> BidiClass.S
    0x0A, 0x0D, 0x1C, 0x1D, 0x1E -> BidiClass.B
    0x0C, 0x20 -> BidiClass.WS
    in 0x00..0x1F, 0x7F -> BidiClass.BN
    in '0'.code..'9'.code -> BidiClass.EN
    '+'.code, '-'.code -> BidiClass.ES
    '#'.code, '$'.code, '%'.code -> BidiClass.ET
    ','.code, '.'.code, '/'.code, ':'.code -> BidiClass.CS
    in 'A'.code..'Z'.code, in 'a'.code..'z'.code -> BidiClass.L
    else -> BidiClass.ON
}

private fun hebrewClass(c: Int): BidiClass = when {
    c in 0x0591..0x05BD || c == 0x05BF || c in 0x05C1..0x05C2 || c in 0x05C4..0x05C5 || c == 0x05C7 -> BidiClass.NSM
    else -> BidiClass.R
}

private fun arabicClass(c: Int): BidiClass = when {
    c in 0x0600..0x0605 -> BidiClass.AN
    c in 0x0606..0x0607 -> BidiClass.ON
    c in 0x0609..0x060A -> BidiClass.ET
    c == 0x060C -> BidiClass.CS
    c in 0x060E..0x060F -> BidiClass.ON
    c in 0x0610..0x061A -> BidiClass.NSM
    c in 0x064B..0x065F -> BidiClass.NSM
    c in 0x0660..0x0669 -> BidiClass.AN
    c == 0x066A -> BidiClass.ET
    c in 0x066B..0x066C -> BidiClass.AN
    c == 0x0670 -> BidiClass.NSM
    c in 0x06D6..0x06DC -> BidiClass.NSM
    c == 0x06DD -> BidiClass.AN
    c == 0x06DE || c == 0x06E9 -> BidiClass.ON
    c in 0x06DF..0x06E4 || c in 0x06E7..0x06E8 || c in 0x06EA..0x06ED -> BidiClass.NSM
    c in 0x06F0..0x06F9 -> BidiClass.EN
    c == 0x0711 || c in 0x0730..0x074A -> BidiClass.NSM
    c in 0x07A6..0x07B0 -> BidiClass.NSM
    else -> BidiClass.AL
}
