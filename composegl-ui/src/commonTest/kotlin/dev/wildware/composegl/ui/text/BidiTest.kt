package dev.wildware.composegl.ui.text

import dev.wildware.composegl.ui.layout.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which way text reads, and the order it is drawn in, without a font.
 *
 * Each case is written as the line a reader sees, so a failure reads as "this is what would be on
 * the screen" rather than as a list of levels.
 */
class BidiTest {

    private val ltr = LayoutDirection.Ltr
    private val rtl = LayoutDirection.Rtl

    /** The whole line as it is drawn, run after run. */
    private fun drawn(text: String, direction: LayoutDirection = ltr, from: Int = 0, to: Int = text.length): String {
        val bidi = BidiText(text, direction)
        return bidi.runs(from, to).joinToString("") { visualText(text, it.range.min, it.range.max, it.rightToLeft) }
    }

    @Test
    fun `english is one left to right run and costs no levels`() {
        val bidi = BidiText("Press start", ltr)

        assertTrue(bidi.allLeftToRight)
        assertEquals(listOf(BidiRun(TextRange(0, 11), rightToLeft = false)), bidi.runs(0, 11))
    }

    @Test
    fun `a hebrew word is drawn with its letters reversed`() {
        assertEquals("םולש", drawn("שלום"))
        assertEquals(listOf(BidiRun(TextRange(0, 4), rightToLeft = true)), BidiText("שלום", ltr).runs(0, 4))
    }

    @Test
    fun `a hebrew word inside an english sentence keeps the english either side in order`() {
        val text = "Press שלום to start"
        val runs = BidiText(text, ltr).runs(0, text.length)

        assertEquals(
            listOf(
                BidiRun(TextRange(0, 6), rightToLeft = false),
                BidiRun(TextRange(6, 10), rightToLeft = true),
                BidiRun(TextRange(10, 19), rightToLeft = false),
            ),
            runs,
        )
        assertEquals("Press םולש to start", drawn(text))
    }

    @Test
    fun `a hebrew sentence with an english word reads from the right and punctuation goes to its end`() {
        // The paragraph is Hebrew, so it ends on the left: the full stop after the English word is
        // drawn at the far left, not stuck to the word.
        assertEquals(".world םולש", drawn("שלום world."))
    }

    @Test
    fun `digits inside hebrew keep their own order`() {
        assertEquals("לקש 100 ריחמ", drawn("מחיר 100 שקל"))
    }

    @Test
    fun `a thousands separator between digits stays inside the number`() {
        assertEquals("לקש 1,000", drawn("1,000 שקל", rtl))
    }

    @Test
    fun `arabic digits after arabic letters stay in order`() {
        // Arabic-Indic digits are drawn left to right like any number, inside right-to-left text.
        assertEquals("١٢ رعس", drawn("سعر ١٢"))
    }

    @Test
    fun `a line with no letters reads the way the screen does`() {
        assertEquals("12 - 5", drawn("12 - 5", ltr))
        assertEquals("5 - 12", drawn("12 - 5", rtl))
    }

    @Test
    fun `an english sentence in a right to left screen still reads left to right`() {
        val bidi = BidiText("Hello!", rtl)

        assertTrue(bidi.allLeftToRight, "its first letter decides, and the screen only breaks a tie")
        assertEquals("Hello!", drawn("Hello!", rtl))
    }

    @Test
    fun `brackets in right to left text face the other way`() {
        assertEquals("(םולש)", drawn("(שלום)"))
        assertEquals("Press [םולש]", drawn("Press [שלום]", ltr), "brackets in the english part are left alone")
    }

    @Test
    fun `a mark stays on its letter when the word is reversed`() {
        // Shin with a qamats and a shin dot, lamed, vav with a holam, final mem.
        val pointed = "שָׁלוֹם"
        val reversed = drawn(pointed)

        assertEquals("םוֹלשָׁ", reversed, "each letter reversed with its marks still after it")
    }

    @Test
    fun `an emoji stays in one piece in right to left text`() {
        val text = "שלום 👍🏽"

        assertEquals("👍🏽 םולש", drawn(text))
    }

    @Test
    fun `each paragraph takes its own direction`() {
        val text = "abc\nשלום"
        val bidi = BidiText(text, ltr)

        assertFalse(bidi.isRightToLeftAt(0))
        assertFalse(bidi.isRightToLeftAt(3), "the newline belongs to the line it ends")
        assertTrue(bidi.isRightToLeftAt(4))
        assertEquals("abc", drawn(text, ltr, 0, 3))
        assertEquals("םולש", drawn(text, ltr, 4, 8))
    }

    @Test
    fun `an empty line in a right to left paragraph is an empty right to left run`() {
        val bidi = BidiText("123", rtl)

        assertEquals(listOf(BidiRun(TextRange(0, 0), rightToLeft = true)), bidi.runs(0, 0))
    }

    @Test
    fun `positions across a mixed line are where each character is drawn`() {
        val text = "abc אבג"
        val line = BidiLine(BidiText(text, ltr).runs(0, text.length), 0f) { from, to -> (to - from).toFloat() }

        assertEquals(listOf(0f, 1f, 2f, 3f, 7f, 6f, 5f, 4f), (0..7).map { line.x(it) })
    }

    @Test
    fun `a stretch across both directions is one span per run`() {
        val text = "abc אבג"
        val line = BidiLine(BidiText(text, ltr).runs(0, text.length), 0f) { from, to -> (to - from).toFloat() }
        val spans = mutableListOf<Pair<Float, Float>>()

        line.spans(2, 6) { left, right -> spans += left to right }

        assertEquals(listOf(2f to 4f, 5f to 7f), spans)
    }

    @Test
    fun `whether a label can go to the backend whole`() {
        assertTrue("Hello".readsLeftToRight(ltr))
        assertTrue("Hello".readsLeftToRight(rtl))
        assertTrue("123".readsLeftToRight(ltr))
        assertFalse("123".readsLeftToRight(rtl), "digits alone take the screen's direction")
        assertFalse("שלום".readsLeftToRight(ltr))
        assertFalse("abc\n123".readsLeftToRight(rtl), "the second paragraph has no letter of its own")
        assertTrue("".readsLeftToRight(rtl))
    }

    @Test
    fun `the classes the rules turn on`() {
        assertEquals(BidiClass.L, bidiClassOf('a'.code))
        assertEquals(BidiClass.R, bidiClassOf('ש'.code))
        assertEquals(BidiClass.AL, bidiClassOf('س'.code))
        assertEquals(BidiClass.EN, bidiClassOf('7'.code))
        assertEquals(BidiClass.AN, bidiClassOf('١'.code))
        assertEquals(BidiClass.NSM, bidiClassOf(0x05B8))
        assertEquals(BidiClass.WS, bidiClassOf(' '.code))
        assertEquals(BidiClass.B, bidiClassOf('\n'.code))
        assertEquals(BidiClass.ON, bidiClassOf("👍".codePointAt(0)))
        assertEquals(BidiClass.L, bidiClassOf('日'.code))
    }
}
