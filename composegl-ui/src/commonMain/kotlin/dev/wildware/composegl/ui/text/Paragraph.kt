package dev.wildware.composegl.ui.text

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.absolute
import kotlin.math.abs

/**
 * One line of a [Paragraph]: which characters are on it, and where it sits.
 *
 * [range] never includes the newline or the spaces the line broke at, so the text on the line is
 * `text.substring(range.min, range.max)` and nothing has to be trimmed afterwards.
 *
 * @param left how far in the line starts, once [HorizontalAlignment] has been applied inside the
 *   block. Zero for every line of a `Start`-aligned paragraph.
 * @param top the top of this line's box, measured from the top of the paragraph.
 * @param baseline where the glyphs sit, measured from the top of the paragraph.
 * @param width how wide the line is, without the ellipsis if it has one.
 * @param ellipsised true when [Paragraph] cut the text off here and the line is drawn with the
 *   style's ellipsis after it.
 */
data class TextLine(
    val range: TextRange,
    val left: Float,
    val top: Float,
    val baseline: Float,
    val width: Float,
    val ellipsised: Boolean = false,
)

/**
 * A string broken into lines, with every position in it available to ask about.
 *
 * This is the toolkit doing its own line breaking, which [Text][dev.wildware.composegl.ui.widget.Text]
 * deliberately does not do: an ordinary label hands the whole string to the backend and never sees
 * the lines, because the backend is the only thing that knows what a glyph looks like. That stops
 * being enough the moment part of a paragraph has to look different from the rest — an underlined
 * term, a struck word, a value in another colour — because the widget then has to know where that
 * part *is*.
 *
 * So this is built on the one thing every backend already promises: measuring the same text twice
 * gives the same answer. Lines are found by measuring candidate lines, and positions inside a line
 * by measuring prefixes. Both are cached, so a paragraph that has not changed costs nothing to draw
 * again.
 *
 * The one thing it gives up is kerning across a break — where a backend draws `AV` closer together
 * than it measures `A` and `V` separately, a position between them is a fraction out. No backend in
 * this project does that, and the fix when one does is a wider [TextLayout], not a different
 * design. [FieldMetrics][dev.wildware.composegl.ui.widget] makes the same trade for the same reason.
 *
 * Everything here is measured from the paragraph's own top-left corner. Where the paragraph itself
 * is on the screen is the caller's business.
 *
 * Text that mixes directions — a Hebrew name in an English sentence, a number in Arabic — is laid
 * out in the order it is read: each line is [runsOf] one direction or the other, and every position
 * asked about here is where that character is *drawn*, not where it is stored. A right-to-left run's
 * width is measured the same way round as any other, so the no-kerning bargain above covers it too.
 */
class Paragraph internal constructor(
    val text: String,
    val style: TextStyle,
    /** The shape of the font this was laid out in. What a decoration is positioned off. */
    val metrics: FontMetrics,
    val lines: List<TextLine>,
    val size: Size,
    private val fonts: FontProvider,
    /**
     * The direction of the screen this was laid out for. It decides which side a `Start` line sits
     * on, and which way a paragraph with no letters in it — a line of digits — reads.
     */
    val direction: LayoutDirection,
) {

    /**
     * The style with its line limit taken off.
     *
     * Every measurement here is of a piece that is known to fit on one line already, and a backend
     * handed a style with `maxLines = 1` is entitled to cut it off and append an ellipsis. Measuring
     * against that would make a wrapped paragraph's own ellipsis appear inside it.
     */
    private val flat = style.copy(maxLines = 0)

    private val layouts = HashMap<Int, TextLayout>()
    private val widths = HashMap<Long, Float>()

    /** Which way each character reads, worked out the first time anybody asks. */
    private val bidi by lazy { BidiText(text, direction) }
    private val geometries = arrayOfNulls<BidiLine>(lines.size)
    private val spanWidths = HashMap<Long, Float>()
    private val ellipsisWidth by lazy { if (style.ellipsis.isEmpty()) 0f else fonts.measure(style.ellipsis, flat).size.width }

    /**
     * Line [line]'s stretches of one direction, in the order they are drawn from the left.
     *
     * One run for a line of English. Three for `Press שלום to start`, the middle one right to left.
     */
    fun runsOf(line: Int): List<BidiRun> =
        geometryOf(line)?.runs ?: listOf(BidiRun(lines[line].range, rightToLeft = false))

    /** Whether the paragraph line [line] belongs to reads from the right, so ends on the left. */
    fun isRightToLeft(line: Int): Boolean = bidi.isRightToLeftAt(lines[line].range.min)

    /** The characters of [run] in the order they are drawn: reversed, for one that reads from the right. */
    fun textOf(run: BidiRun): String = visualText(text, run.range.min, run.range.max, run.rightToLeft)

    /**
     * Where each run of line [line] is, or null for a line that is simply its characters in order —
     * which is every line of a paragraph with nothing right-to-left in it, and costs nothing to ask.
     */
    internal fun geometryOf(line: Int): BidiLine? {
        if (bidi.allLeftToRight) return null
        return geometries[line] ?: run {
            val on = lines[line]
            // A cut-off right-to-left line ends on the left, so its ellipsis is drawn there and the
            // words start after it.
            val lead = if (on.ellipsised && isRightToLeft(line)) ellipsisWidth else 0f
            BidiLine(bidi.runs(on.range.min, on.range.max), lead) { from, to -> spanWidth(from, to) }
                .also { geometries[line] = it }
        }
    }

    private fun spanWidth(from: Int, to: Int): Float = spanWidths.getOrPut(from.toLong() shl 32 or to.toLong()) {
        fonts.measure(text.substring(from, to), flat).size.width
    }

    /** Distance from the top down to the first line's baseline. */
    val firstBaseline: Float get() = lines.firstOrNull()?.baseline ?: metrics.ascent

    /** One baseline to the next. */
    val lineHeight: Float get() = style.lineHeight

    /**
     * The stretches the text may be broken between, in order.
     *
     * The answer to "where is each word's box", once each range is handed to [boxesOf]. Spaces are
     * not in any of them, and for text with no spaces in it — Chinese and Japanese — the ranges are
     * the places a line is allowed to break rather than anything a dictionary would call a word.
     */
    val words: List<TextRange> by lazy {
        val result = ArrayList<TextRange>()
        var start = 0
        for (opportunity in breakOpportunities(text, 0, text.length)) {
            if (opportunity.end > start) result += TextRange(start, opportunity.end)
            start = opportunity.next
        }
        result
    }

    /** Line [line], measured and ready to draw, ellipsis and all, in the order it is drawn. */
    fun layoutOf(line: Int): TextLayout = layouts.getOrPut(line) {
        val on = lines[line]
        val geometry = geometryOf(line)
        val body = geometry?.runs?.joinToString("") { textOf(it) } ?: text.substring(on.range.min, on.range.max)
        val shown = when {
            !on.ellipsised -> body
            geometry != null && isRightToLeft(line) -> style.ellipsis + body
            else -> body + style.ellipsis
        }
        fonts.measure(shown, flat)
    }

    /** Which line character [index] is on. A position at a break belongs to the line before it. */
    fun lineOf(index: Int): Int {
        val at = index.coerceIn(0, text.length)
        for (line in lines.indices) if (at <= lines[line].range.max) return line
        return lines.lastIndex.coerceAtLeast(0)
    }

    /** How far along its own line character [index] starts, before [TextLine.left]. */
    fun xOf(index: Int): Float = xIn(lineOf(index), index)

    /** Where character [index] starts, measured from the paragraph's top-left. */
    fun offsetOf(index: Int): Offset {
        val line = lines.getOrNull(lineOf(index)) ?: return Offset.Zero
        return Offset(line.left + xIn(lineOf(index), index), line.top)
    }

    /**
     * Which character is under a point in the paragraph's own coordinates.
     *
     * Nearest boundary rather than nearest character: a point in the left half of a letter gives
     * the position before it and the right half the position after it, which is what a caret wants
     * and what a run-aware hit test wants too.
     */
    fun indexAt(point: Offset): Int {
        if (lines.isEmpty()) return 0
        var line = 0
        for (index in lines.indices) if (point.y >= lines[index].top) line = index
        val on = lines[line]
        val x = point.x - on.left
        if (x <= 0f) return on.range.min

        var best = on.range.min
        var bestDistance = Float.MAX_VALUE
        var at = on.range.min
        while (true) {
            val distance = abs(xIn(line, at) - x)
            if (distance < bestDistance) {
                bestDistance = distance
                best = at
            }
            if (at >= on.range.max) break
            at = text.graphemeAfter(at).coerceAtMost(on.range.max)
        }
        return best
    }

    /**
     * The boxes [range] covers — one per line it touches, so a term that wrapped gets two.
     *
     * Each box is a line box: as tall as [lineHeight], not as tall as the glyphs. That is what a
     * highlight, a hover region and a hit test all want, and a decoration positions itself off
     * [TextLine.baseline] rather than off the box.
     */
    fun boxesOf(range: TextRange): List<Rect> {
        val from = range.min.coerceIn(0, text.length)
        val to = range.max.coerceIn(from, text.length)
        val result = ArrayList<Rect>()
        for (index in lines.indices) {
            val line = lines[index]
            if (line.range.max < from) continue
            if (line.range.min > to) break
            val start = maxOf(from, line.range.min)
            val end = minOf(to, line.range.max)
            if (end < start) continue
            // An empty slice of a line is nothing — but a caret is empty on purpose and is one box.
            if (end == start && from != to) continue
            val geometry = geometryOf(index)
            if (geometry == null || end == start) {
                result += Rect(
                    left = line.left + xIn(index, start),
                    top = line.top,
                    right = line.left + xIn(index, end),
                    bottom = line.top + lineHeight,
                )
            } else {
                // One box per run the range touches: across a Hebrew word and the English beside it,
                // the selected part of each is where that part is drawn.
                geometry.spans(start, end) { left, right ->
                    result += Rect(line.left + left, line.top, line.left + right, line.top + lineHeight)
                }
            }
            if (to <= line.range.max) break
        }
        return result
    }

    /** A prefix width, cached per line and position, because both are asked for every frame. */
    private fun xIn(line: Int, index: Int): Float {
        if (lines.isEmpty()) return 0f
        val on = lines[line]
        val at = index.coerceIn(on.range.min, on.range.max)
        geometryOf(line)?.let { return it.x(at) }
        if (at <= on.range.min) return 0f
        if (at >= on.range.max) return on.width
        return widths.getOrPut(line.toLong() shl 32 or at.toLong()) {
            fonts.measure(text.substring(on.range.min, at), flat).size.width
        }
    }
}

/**
 * Breaks [text] into lines at [maxWidth], the same way twice.
 *
 * The public answer to "given this string, this style and this width, where do the lines break".
 * A game laying out its own inline content — an icon in the middle of a sentence, a tooltip that
 * has to know which word the pointer is over — can ask this rather than approximating it, and what
 * it gets back agrees with what the toolkit's own styled text does, because it is the same code.
 *
 * Greedy, like every interface text layout: a line takes as many words as fit. A word longer than
 * the whole width is left to overflow rather than chopped, because a chopped word is harder to read
 * than one that pokes out, and the caller can see that it did from [TextLine.width].
 *
 * @param maxWidth what to wrap at. Infinite does not wrap; explicit newlines still break lines.
 * @param align where each line sits inside the block. The block itself is [Paragraph.size] wide —
 *   as wide as its widest line — so aligning it inside a wider node is still the caller's to do.
 */
fun FontProvider.paragraph(
    text: String,
    style: TextStyle = TextStyle.Default,
    maxWidth: Float = Float.POSITIVE_INFINITY,
    align: HorizontalAlignment = HorizontalAlignment.Start,
): Paragraph = paragraph(text, style, maxWidth, align, LayoutDirection.Ltr)

/**
 * The same, for a screen that reads in [direction].
 *
 * `Start` and `End` in [align] are the sides [direction] puts them on, so a `Start` paragraph in a
 * right-to-left screen has its lines against the right. Each paragraph still reads the way its own
 * first letter does — an English sentence in an Arabic screen reads left to right — and only one
 * with no letters at all, a line of digits, takes [direction] as its own.
 */
// An overload rather than a fifth defaulted parameter, for the reason the Text overloads record: a
// defaulted parameter added to a published function changes its signature.
fun FontProvider.paragraph(
    text: String,
    style: TextStyle = TextStyle.Default,
    maxWidth: Float = Float.POSITIVE_INFINITY,
    align: HorizontalAlignment = HorizontalAlignment.Start,
    direction: LayoutDirection,
): Paragraph {
    val flat = style.copy(maxLines = 0)
    val ranges = ArrayList<TextRange>()

    var paragraphStart = 0
    while (true) {
        val newline = text.indexOf('\n', paragraphStart)
        val paragraphEnd = if (newline < 0) text.length else newline
        breakInto(ranges, text, paragraphStart, paragraphEnd, maxWidth) {
            measure(it, flat).size.width
        }
        if (newline < 0) break
        paragraphStart = newline + 1
    }

    // The limit is applied after breaking rather than during it, because whether a paragraph needs
    // cutting at all is not known until the last line has been placed.
    var kept = ranges
    var ellipsisOn = -1
    val limit = style.maxLines
    if (limit > 0 && ranges.size > limit) {
        kept = ArrayList(ranges.subList(0, limit))
        if (style.ellipsis.isNotEmpty()) {
            ellipsisOn = limit - 1
            kept[ellipsisOn] = trimFor(text, kept[ellipsisOn], style.ellipsis, maxWidth) {
                measure(it, flat).size.width
            }
        }
    }

    val widths = FloatArray(kept.size) { measure(text.substring(kept[it].min, kept[it].max), flat).size.width }
    val blockWidth = widths.maxOrNull() ?: 0f
    val metrics = metrics(flat)
    val lineHeight = style.lineHeight
    // The first baseline sits an ascent down, and every line after it a line height further. The
    // block is as tall as its lines, not as tall as its glyphs, so two paragraphs in a column sit
    // the same distance apart whether or not either has a descender on its last line.
    val side = align.absolute(direction)
    val lines = kept.mapIndexed { index, range ->
        val width = widths[index]
        TextLine(
            range = range,
            left = when (side) {
                HorizontalAlignment.Start -> 0f
                HorizontalAlignment.Centre -> (blockWidth - width) / 2f
                HorizontalAlignment.End -> blockWidth - width
            }.coerceAtLeast(0f),
            top = index * lineHeight,
            baseline = index * lineHeight + metrics.ascent,
            width = width,
            ellipsised = index == ellipsisOn,
        )
    }
    return Paragraph(text, style, metrics, lines, Size(blockWidth, lines.size * lineHeight), this, direction)
}

/**
 * One paragraph — text with no newline in it — broken into lines and added to [into].
 *
 * Greedy with a remembered last-good break: the line grows one break opportunity at a time, and
 * when one does not fit the line is emitted at the previous one and the same opportunity is tried
 * again against the new, shorter line. Emitting always moves the start forward, so it terminates.
 */
private inline fun breakInto(
    into: MutableList<TextRange>,
    text: String,
    from: Int,
    to: Int,
    maxWidth: Float,
    widthOf: (String) -> Float,
) {
    if (from >= to) {
        into += TextRange(from, from)
        return
    }
    val opportunities = breakOpportunities(text, from, to)
    var start = from
    var lastFit = -1
    var lastFitNext = -1
    var index = 0
    while (index < opportunities.size) {
        val opportunity = opportunities[index]
        if (opportunity.end <= start) {
            index++
            continue
        }
        if (widthOf(text.substring(start, opportunity.end)) <= maxWidth) {
            lastFit = opportunity.end
            lastFitNext = opportunity.next
            index++
            continue
        }
        if (lastFit >= 0) {
            into += TextRange(start, lastFit)
            start = lastFitNext
            lastFit = -1
            lastFitNext = -1
            continue
        }
        // Nothing fits, not even to the first break. The word overflows rather than being chopped.
        into += TextRange(start, opportunity.end)
        start = opportunity.next
        index++
    }
    if (lastFit >= 0) into += TextRange(start, lastFit)
    else if (start <= to && into.lastOrNull()?.max != to) into += TextRange(start, to)
}

/** A line shortened until it and [ellipsis] fit, so a cut-off line ends in one rather than past it. */
private inline fun trimFor(
    text: String,
    range: TextRange,
    ellipsis: String,
    maxWidth: Float,
    widthOf: (String) -> Float,
): TextRange {
    if (maxWidth == Float.POSITIVE_INFINITY) return range
    var end = range.max
    while (end > range.min && widthOf(text.substring(range.min, end) + ellipsis) > maxWidth) {
        end = text.graphemeBefore(end).coerceAtLeast(range.min)
    }
    return TextRange(range.min, end)
}

/**
 * Where a line may be broken: [end] is the last character kept on the line, [next] the first
 * character of the line after it. They differ only where the break eats a space.
 */
internal class BreakOpportunity(val end: Int, val next: Int)

/**
 * Where [text] may be broken between [from] and [to].
 *
 * Three rules, which is what an interface needs and a long way short of the Unicode algorithm:
 * after a run of spaces, which the break eats; after a hyphen, which it keeps; and between two
 * ideographic characters, which is what makes Chinese and Japanese wrap at all — neither has spaces
 * between words, so a space-only breaker gives a whole sentence as one unbreakable line.
 *
 * The ideographic rule knows about the punctuation that may not start or end a line: a full stop or
 * a closing bracket is pulled onto the line before it rather than pushed onto the next.
 */
internal fun breakOpportunities(text: String, from: Int, to: Int): List<BreakOpportunity> {
    val result = ArrayList<BreakOpportunity>()
    var index = from
    while (index < to) {
        val character = text[index]
        if (character == ' ' || character == '\t') {
            var next = index
            while (next < to && (text[next] == ' ' || text[next] == '\t')) next++
            result += BreakOpportunity(index, next)
            index = next
            continue
        }
        if (character == '-' && index + 1 < to) {
            result += BreakOpportunity(index + 1, index + 1)
            index++
            continue
        }
        if (index + 1 < to && breaksAfter(character) && breaksBefore(text[index + 1])) {
            result += BreakOpportunity(index + 1, index + 1)
        }
        index++
    }
    result += BreakOpportunity(to, to)
    return result
}

/** Characters no line may end on, because they open something the next character belongs inside. */
private const val Openers = "([{（〔【《〈「『“‘"

/** Characters no line may start with, because they close or follow what came before them. */
private const val Closers = ")]}）〕】》〉」』”’、。，．！？：；ー〜々"

private fun breaksAfter(character: Char): Boolean = character.isIdeographic() && character !in Openers

private fun breaksBefore(character: Char): Boolean = character.isIdeographic() && character !in Closers

/**
 * Whether a character is one of the ones that wrap without spaces.
 *
 * Kana and the CJK ideographs, plus the punctuation that goes with them, which has to be in the set
 * or a sentence ending in `。` would have no break opportunity before its full stop.
 */
private fun Char.isIdeographic(): Boolean = when (code) {
    in 0x3000..0x303F -> true // CJK punctuation
    in 0x3040..0x30FF -> true // hiragana and katakana
    in 0x3400..0x4DBF -> true // ideographs, extension A
    in 0x4E00..0x9FFF -> true // ideographs
    in 0xF900..0xFAFF -> true // compatibility ideographs
    in 0xFF00..0xFF60 -> true // full-width forms
    else -> false
}
