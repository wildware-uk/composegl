package dev.wildware.composegl.ui.text

import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The toolkit breaking its own lines.
 *
 * Every other piece of text here hands the whole string to the backend and never sees where it
 * wrapped. That cannot work for a paragraph with an underlined term in it — a widget cannot
 * underline the fifth word without knowing where the fifth word is — so [Paragraph] does the
 * breaking itself, out of nothing but the promise every backend already keeps: measuring the same
 * text twice gives the same answer.
 *
 * The monospace provider makes the numbers checkable on paper. At size 10 a character is six
 * pixels wide and a line is 12.5 tall, so a 60-pixel width is exactly ten characters.
 */
class ParagraphTest {

    private val fonts = MonospaceFontProvider()
    private val style = TextStyle(size = 10f)

    private fun Paragraph.textOf(line: Int) =
        text.substring(lines[line].range.min, lines[line].range.max)

    @Test
    fun `a line takes as many words as fit and no more`() {
        val block = fonts.paragraph("hello there world", style, maxWidth = 60f)

        assertEquals(3, block.lines.size)
        assertEquals("hello", block.textOf(0))
        assertEquals("there", block.textOf(1))
        assertEquals("world", block.textOf(2))
    }

    @Test
    fun `the space a line broke at is on neither line`() {
        val block = fonts.paragraph("hello there world", style, maxWidth = 60f)

        // Not "hello " and not " there": a trailing space would make a centred line look off-centre
        // and would put a hit region where there is nothing to hit.
        assertEquals(30f, block.lines[0].width, 0.001f, "five characters, not six")
        assertEquals(30f, block.size.width, 0.001f, "the block is as wide as its widest line")
    }

    @Test
    fun `a wide enough paragraph is one line`() {
        val block = fonts.paragraph("hello there world", style, maxWidth = 300f)

        assertEquals(1, block.lines.size)
        assertEquals(102f, block.size.width, 0.001f, "seventeen characters")
    }

    @Test
    fun `a newline breaks a line however much room there is`() {
        val block = fonts.paragraph("one\ntwo\nthree", style)

        assertEquals(3, block.lines.size)
        assertEquals("one", block.textOf(0))
        assertEquals("three", block.textOf(2))
    }

    @Test
    fun `an empty line between two paragraphs is kept`() {
        val block = fonts.paragraph("one\n\ntwo", style)

        assertEquals(3, block.lines.size)
        assertEquals("", block.textOf(1))
    }

    @Test
    fun `a word too long for the width overflows rather than being chopped`() {
        val block = fonts.paragraph("supercalifragilistic", style, maxWidth = 30f)

        // The backend's own wrapping chops it. This does not, on purpose: a chopped word is harder
        // to read than one that pokes out, and the caller can see that it did from the line width.
        assertEquals(1, block.lines.size)
        assertEquals(120f, block.lines[0].width, 0.001f)
    }

    @Test
    fun `a hyphen is a place a line may break`() {
        val block = fonts.paragraph("well-behaved words", style, maxWidth = 60f)

        assertEquals("well-", block.textOf(0))
        assertEquals("behaved", block.textOf(1))
    }

    @Test
    fun `text with no spaces in it still wraps`() {
        // Japanese does not put spaces between words. A space-only breaker gives a whole sentence
        // as one unbreakable line, which is the bug this rule exists for.
        val block = fonts.paragraph("日本語のテキスト", style, maxWidth = 30f)

        assertEquals(2, block.lines.size)
        assertEquals("日本語のテ", block.textOf(0))
        assertEquals("キスト", block.textOf(1))
    }

    @Test
    fun `no line begins with a full stop`() {
        val block = fonts.paragraph("あい。うえ", style, maxWidth = 12f)

        assertTrue(
            block.lines.none { block.text.substring(it.range.min, it.range.max).startsWith("。") },
            "a closing mark was pushed onto a line of its own: ${block.lines}",
        )
        assertEquals("い。", block.textOf(1))
    }

    @Test
    fun `a line limit cuts the paragraph off and marks where`() {
        val block = fonts.paragraph(
            "one two three four five six",
            style.copy(maxLines = 2),
            maxWidth = 60f,
        )

        assertEquals(2, block.lines.size)
        assertTrue(block.lines[1].ellipsised, "the last line says it was cut")
        // Room was made for the ellipsis rather than it being drawn past the width.
        assertEquals("three fou", block.textOf(1))
    }

    @Test
    fun `an unlimited paragraph marks nothing as cut`() {
        val block = fonts.paragraph("one two three four five six", style, maxWidth = 60f)

        assertEquals(3, block.lines.size)
        assertTrue(block.lines.none { it.ellipsised })
    }

    @Test
    fun `centring puts every line in the middle of the block`() {
        val block = fonts.paragraph("one\nthree", style, align = HorizontalAlignment.Centre)

        assertEquals(30f, block.size.width, 0.001f)
        assertEquals(6f, block.lines[0].left, 0.001f, "the short line is inset by half the difference")
        assertEquals(0f, block.lines[1].left, 0.001f, "the widest line fills the block")
    }

    @Test
    fun `ending puts every line against the right of the block`() {
        val block = fonts.paragraph("one\nthree", style, align = HorizontalAlignment.End)

        assertEquals(12f, block.lines[0].left, 0.001f)
    }

    @Test
    fun `a baseline sits an ascent below the top of its line`() {
        val block = fonts.paragraph("one\ntwo", style)
        val metrics = fonts.metrics(style)

        assertEquals(metrics.ascent, block.lines[0].baseline, 0.001f)
        assertEquals(style.lineHeight + metrics.ascent, block.lines[1].baseline, 0.001f)
        assertEquals(block.lines[0].baseline, block.firstBaseline, 0.001f)
    }

    @Test
    fun `a range gets one box per line it touches`() {
        val block = fonts.paragraph("hello there world", style, maxWidth = 60f)

        val word = block.boxesOf(TextRange(6, 11))
        assertEquals(1, word.size)
        assertEquals(0f, word[0].left, 0.001f)
        assertEquals(30f, word[0].right, 0.001f)
        assertEquals(12.5f, word[0].top, 0.001f, "the second line")

        val across = block.boxesOf(TextRange(3, 8))
        assertEquals(2, across.size, "a term that wrapped is two boxes, not one: $across")
    }

    @Test
    fun `a point picks the nearest position on its own line`() {
        val block = fonts.paragraph("hello there world", style, maxWidth = 60f)

        assertEquals(2, block.indexAt(Offset(12f, 4f)), "two characters in on the first line")
        assertEquals(6, block.indexAt(Offset(0f, 14f)), "the start of the second line")
        assertEquals(17, block.indexAt(Offset(999f, 30f)), "past the end of the last line")
    }

    @Test
    fun `the words are the stretches a line may break between`() {
        val block = fonts.paragraph("hello there world", style, maxWidth = 60f)

        assertEquals(listOf(TextRange(0, 5), TextRange(6, 11), TextRange(12, 17)), block.words)
    }

    @Test
    fun `breaking the same paragraph twice breaks it the same way`() {
        val once = fonts.paragraph("one two three four five six", style, maxWidth = 60f)
        val twice = fonts.paragraph("one two three four five six", style, maxWidth = 60f)

        assertEquals(once.lines, twice.lines)
        assertEquals(once.size, twice.size)
    }
}
