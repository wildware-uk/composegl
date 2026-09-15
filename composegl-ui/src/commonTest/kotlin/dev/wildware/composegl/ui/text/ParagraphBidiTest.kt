package dev.wildware.composegl.ui.text

import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A paragraph that mixes directions, asked where things are.
 *
 * At size 10 the monospace provider makes every character six pixels wide, so `abc אבג` is 42 wide:
 * `abc ` across the first 24, and the Hebrew across the last 18 drawn reversed — its first letter
 * at the far right.
 */
class ParagraphBidiTest {

    private val fonts = MonospaceFontProvider()
    private val style = TextStyle(size = 10f)
    private val mixed = "abc אבג"

    @Test
    fun `a mixed line is two runs in the order they are drawn`() {
        val block = fonts.paragraph(mixed, style)

        assertEquals(
            listOf(BidiRun(TextRange(0, 4), rightToLeft = false), BidiRun(TextRange(4, 7), rightToLeft = true)),
            block.runsOf(0),
        )
        assertEquals("abc גבא", block.layoutOf(0).text, "handed to the backend already in drawing order")
        assertEquals("גבא", block.textOf(block.runsOf(0)[1]))
    }

    @Test
    fun `every position is where that character is drawn`() {
        val block = fonts.paragraph(mixed, style)

        assertEquals(listOf(0f, 6f, 12f, 18f, 42f, 36f, 30f, 24f), (0..7).map { block.xOf(it) })
        assertEquals(Offset(42f, 0f), block.offsetOf(4))
    }

    @Test
    fun `a range across both directions is one box per run`() {
        val block = fonts.paragraph(mixed, style)

        assertEquals(
            listOf(Rect(12f, 0f, 24f, 12.5f), Rect(30f, 0f, 42f, 12.5f)),
            block.boxesOf(TextRange(2, 6)),
        )
    }

    @Test
    fun `a point over the hebrew finds the character drawn there`() {
        val block = fonts.paragraph(mixed, style)

        assertEquals(4, block.indexAt(Offset(41f, 2f)), "the right edge is the Hebrew's first letter")
        assertEquals(7, block.indexAt(Offset(25f, 2f)), "its left edge is its end")
        assertEquals(2, block.indexAt(Offset(11f, 2f)))
    }

    @Test
    fun `english asks nothing new`() {
        val block = fonts.paragraph("hello there", style)

        assertEquals(listOf(BidiRun(TextRange(0, 11), rightToLeft = false)), block.runsOf(0))
        assertFalse(block.isRightToLeft(0))
        assertEquals(listOf(Rect(6f, 0f, 30f, 12.5f)), block.boxesOf(TextRange(1, 5)))
    }

    @Test
    fun `start lines in a right to left screen sit against the right of the block`() {
        val rtl = fonts.paragraph("שלום\nא", style, direction = LayoutDirection.Rtl)
        val ltr = fonts.paragraph("שלום\nא", style, direction = LayoutDirection.Ltr)

        assertEquals(listOf(0f, 18f), rtl.lines.map { it.left })
        assertEquals(listOf(0f, 0f), ltr.lines.map { it.left })
        assertEquals(listOf(0f, 18f), fonts.paragraph("שלום\nא", style, align = HorizontalAlignment.End).lines.map { it.left })
    }

    @Test
    fun `each line knows which way its paragraph reads`() {
        val block = fonts.paragraph("hello\nשלום", style)

        assertFalse(block.isRightToLeft(0))
        assertTrue(block.isRightToLeft(1))
    }

    @Test
    fun `a cut off hebrew line has its ellipsis on the left and its words after it`() {
        val block = fonts.paragraph("שלום עולם גדול", style.copy(maxLines = 1), maxWidth = 60f)
        val line = block.lines.single()

        assertTrue(line.ellipsised)
        assertEquals("…םלוע םולש", block.layoutOf(0).text)
        assertEquals(60f, block.xOf(0), "the first letter is at the right, after the six-pixel ellipsis and nine letters")
        assertEquals(listOf(Rect(36f, 0f, 60f, 12.5f)), block.boxesOf(TextRange(0, 4)))
    }

    @Test
    fun `digits alone take the screen's direction`() {
        val block = fonts.paragraph("12 - 5", style, direction = LayoutDirection.Rtl)

        assertEquals("5 - 12", block.runsOf(0).joinToString("") { block.textOf(it) })
    }
}
