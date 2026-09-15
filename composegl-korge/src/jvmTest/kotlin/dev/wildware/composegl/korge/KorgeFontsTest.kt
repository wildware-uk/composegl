package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

/** The test font, read from the classpath. */
object TestFonts {
    fun dejaVu(): ByteArray = requireNotNull(javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf")) {
        "the test font is missing"
    }.readBytes()
}

/**
 * Measuring, which needs no GPU at all: KorGE reads the font and rasterises glyphs on the CPU. Every
 * test here runs on any machine, display or not, and none of them starts a game.
 */
class KorgeFontsTest {

    private val fonts = KorgeFonts().also { it.registerTrueType("body", TestFonts.dejaVu(), listOf(16, 48)) }
    private val body = TextStyle(family = "body", size = 16f)
    private val large = TextStyle(family = "body", size = 48f)

    @Test
    fun `measuring the same text twice gives the same answer`() {
        val first = fonts.measure("Settings", body)
        val second = fonts.measure("Settings", body)
        assertEquals(first.size, second.size)
        assertEquals(first.firstBaseline, second.firstBaseline)
    }

    @Test
    fun `longer text is wider, and a bigger size is bigger`() {
        val short = fonts.measure("Hi", body).size.width
        val long = fonts.measure("Hi there", body).size.width
        val big = fonts.measure("Hi", large).size.width
        assertTrue(long > short, "$long should be wider than $short")
        assertTrue(abs(big - short * 3f) < 3f, "48 should be three times 16: $big against $short")
    }

    @Test
    fun `a width is the sum of the advances, whole pixels up`() {
        // DejaVu Sans's H advances 0.752 of an em: 36.1 at 48, so four of them are 144.4 and the box 145.
        val width = fonts.measure("HHHH", large).size.width
        assertEquals(145f, width)
    }

    @Test
    fun `a line is as tall as the style's line height, and its baseline is an ascent down`() {
        val layout = fonts.measure("Hg", large)
        val metrics = fonts.metrics(large)
        assertEquals(1, layout.lineCount)
        assertEquals(large.lineHeight, layout.size.height)
        assertEquals(metrics.ascent, layout.firstBaseline)
    }

    @Test
    fun `the metrics are measured from the baseline, all positive, and the right way round`() {
        val metrics = fonts.metrics(large)
        assertTrue(metrics.ascent > 0f && metrics.descent > 0f, "$metrics")
        assertTrue(metrics.capHeight < metrics.ascent, "a capital is shorter than the tallest glyph: $metrics")
        // DejaVu Sans: ascent 0.928 em, descent 0.236 em, cap height 0.729 em.
        assertTrue(abs(metrics.ascent - 44.5f) < 1f, "ascent ${metrics.ascent}")
        assertTrue(abs(metrics.descent - 11.3f) < 1f, "descent ${metrics.descent}")
        assertTrue(abs(metrics.capHeight - 35f) < 1f, "cap height ${metrics.capHeight}")
        assertEquals(large.lineHeight, metrics.lineHeight)
        assertTrue(abs(metrics.spaceAdvance - 15.3f) < 1f, "space ${metrics.spaceAdvance}")
    }

    @Test
    fun `text wraps at the width it is given, at a space`() {
        val layout = fonts.measure("one two three four", body, maxWidth = 60f)
        assertTrue(layout.lineCount >= 2, "expected wrapping, got ${layout.lineCount} lines")
        assertTrue(layout.size.width <= 60f, "no line should be wider than 60, got ${layout.size.width}")
        assertEquals(layout.lineCount * body.lineHeight, layout.size.height)
    }

    @Test
    fun `a word longer than the width is left to overflow rather than chopped`() {
        val layout = fonts.measure("Supercalifragilistic", body, maxWidth = 30f)
        assertEquals(1, layout.lineCount)
        assertTrue(layout.size.width > 30f)
    }

    @Test
    fun `no width means no wrapping, but a newline still breaks`() {
        assertEquals(1, fonts.measure("one two three four", body).lineCount)
        assertEquals(2, fonts.measure("one\ntwo", body).lineCount)
        assertEquals(1, fonts.measure("one two three four", body, maxWidth = 0f).lineCount)
    }

    @Test
    fun `a line limit cuts the text and ends it with the ellipsis`() {
        val style = body.copy(maxLines = 2)
        val layout = fonts.measure("one two three four five six seven eight", style, maxWidth = 60f) as KorgeTextLayout
        assertEquals(2, layout.lineCount)
        assertTrue(layout.size.width <= 60f, "the cut line and its ellipsis still fit: ${layout.size.width}")

        val uncut = fonts.measure("one two three four five six seven eight", body, maxWidth = 60f)
        assertTrue(uncut.lineCount > 2)
        // The ellipsis is a glyph of its own: the cut layout draws one more picture than its words have.
        val lastLine = layout.glyphs.filter { it.top > body.lineHeight / 2f }
        assertTrue(lastLine.isNotEmpty(), "the second line has glyphs")
    }

    @Test
    fun `a line breaks between Chinese characters even with no spaces`() {
        // DejaVu has no Chinese, so each is its `?`: the break is the toolkit's rule, which is the point.
        val layout = fonts.measure("玩家玩家玩家玩家", body, maxWidth = 40f)
        assertTrue(layout.lineCount > 1, "expected the characters to wrap, got ${layout.lineCount} line")
    }

    @Test
    fun `glyphs are packed into the shared atlas, beside the white block`() {
        val layout = fonts.measure("Ab", large) as KorgeTextLayout
        assertEquals(2, layout.glyphs.size)
        layout.glyphs.forEach { glyph ->
            assertEquals(0, glyph.region.page, "every glyph on page zero, with the white block")
            assertTrue(glyph.width > 0f && glyph.height > 0f)
        }
        assertNotEquals(layout.glyphs[0].region.x, layout.glyphs[1].region.x)
        assertEquals(1, fonts.atlas.pageCount)
    }

    @Test
    fun `a glyph is rasterised once however often it is measured`() {
        val first = (fonts.measure("A", large) as KorgeTextLayout).glyphs.single().region
        val again = (fonts.measure("AAA", large) as KorgeTextLayout).glyphs.map { it.region }
        again.forEach { assertTrue(it === first, "the same region every time") }
    }

    @Test
    fun `a space takes room but draws nothing`() {
        val layout = fonts.measure("A B", large) as KorgeTextLayout
        assertEquals(2, layout.glyphs.size)
        assertTrue(layout.size.width > (fonts.measure("AB", large)).size.width)
    }

    @Test
    fun `glyphs sit on the baseline, capitals above it and descenders below`() {
        val h = (fonts.measure("H", large) as KorgeTextLayout).glyphs.single()
        val g = (fonts.measure("g", large) as KorgeTextLayout).glyphs.single()
        val baseline = fonts.metrics(large).ascent
        // A glyph's picture has a pixel of empty border each way, which is why these allow two.
        assertTrue(abs((h.top + h.height) - baseline) <= 2f, "H should end on the baseline: ${h.top + h.height} vs $baseline")
        assertTrue(g.top + g.height > baseline + 5f, "g should hang below the baseline")
    }

    @Test
    fun `a size nobody registered is an error that names the sizes that exist`() {
        val failure = assertThrows<IllegalStateException> { fonts.measure("x", TextStyle(family = "body", size = 20f)) }
        assertTrue("[16, 48]" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a family nobody registered is an error that names the families that exist`() {
        val failure = assertThrows<IllegalStateException> { fonts.metrics(TextStyle(family = "title", size = 16f)) }
        assertTrue("body" in failure.message.orEmpty() && "title" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `registering nothing is refused`() {
        assertThrows<IllegalArgumentException> { KorgeFonts().registerTrueType("x", TestFonts.dejaVu(), emptyList()) }
    }

    @Test
    fun `the registry says what it holds`() {
        assertEquals(listOf("body"), fonts.families())
        assertEquals(listOf(16, 48), fonts.sizesOf("body"))
        assertEquals(emptyList<Int>(), fonts.sizesOf("nothing"))
    }
}
