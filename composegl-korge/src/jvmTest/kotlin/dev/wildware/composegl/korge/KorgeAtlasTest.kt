package dev.wildware.composegl.korge

import dev.wildware.composegl.render.GlyphAtlas
import dev.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * KorGE's glyphs on the shared renderer's atlas, which is pixels in memory, so all of it is asked
 * here with no GPU. How the atlas packs is composegl-render's `GlyphAtlasTest`; this is what
 * [KorgeFonts] puts on it.
 */
class KorgeAtlasTest {

    private fun fonts(pageSize: Int = 1024) = KorgeFonts(pageSize).also { it.registerTrueType("body", TestFonts.dejaVu(), listOf(12, 48)) }

    @Test
    fun `the white block is on page zero and opaque white`() {
        val fonts = fonts()
        val white = fonts.atlas.white
        assertSame(fonts.atlas.page(0), white.page)
        for (y in white.y until white.y + GlyphAtlas.WhiteBlock) {
            for (x in white.x until white.x + GlyphAtlas.WhiteBlock) {
                assertEquals(0xFFFFFFFF.toInt(), white.page.rgbaAt(x, y), "white at $x, $y")
            }
        }
    }

    @Test
    fun `glyphs and the white block have empty pixels between them`() {
        val fonts = fonts()
        val glyphs = (fonts.measure("abcdefghijklmnopqrstuvwxyz", TextStyle(family = "body", size = 12f)) as KorgeTextLayout).placed.map { it.glyph }
        val boxes = glyphs.map { listOf(it.x, it.y, it.width.toInt(), it.height.toInt()) } +
            listOf(listOf(fonts.atlas.white.x, fonts.atlas.white.y, GlyphAtlas.WhiteBlock, GlyphAtlas.WhiteBlock))
        for (a in boxes.indices) for (b in boxes.indices) {
            if (a == b) continue
            val (ax, ay, aw, ah) = boxes[a]
            val (bx, by, bw, bh) = boxes[b]
            // Strictly apart: a pixel of nothing between any two, as the KorGE atlas always kept.
            val apart = ax + aw < bx || bx + bw < ax || ay + ah < by || by + bh < ay
            assertTrue(apart, "(${ax}, ${ay}) and (${bx}, ${by}) touch")
        }
    }

    @Test
    fun `a full page starts another`() {
        val fonts = fonts(pageSize = 64)
        fonts.measure("ABCDEFGH", TextStyle(family = "body", size = 48f))
        assertTrue(fonts.atlas.pageCount > 1, "eight glyphs at 48 cannot fit on one 64 page")
    }

    @Test
    fun `a glyph bigger than a page is refused, saying so`() {
        val failure = assertThrows<IllegalStateException> { fonts(pageSize = 32).measure("W", TextStyle(family = "body", size = 48f)) }
        assertTrue("not big enough" in failure.message.orEmpty(), failure.message)
        assertTrue("KorgeFonts" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `coverage is written as white, with the ink in its alpha`() {
        val fonts = fonts()
        val glyph = (fonts.measure("H", TextStyle(family = "body", size = 48f)) as KorgeTextLayout).placed.single().glyph
        val page = checkNotNull(glyph.page)
        val pixels = (0 until glyph.height.toInt()).flatMap { y -> (0 until glyph.width.toInt()).map { x -> page.rgbaAt(glyph.x + x, glyph.y + y) } }

        assertTrue(pixels.all { it ushr 8 == 0xFFFFFF }, "every pixel white, so the text's colour multiplies it")
        assertTrue(pixels.any { it and 0xFF == 0xFF }, "solid ink in the stems")
        assertTrue(pixels.any { it and 0xFF == 0 }, "and none in the border round the glyph")
    }
}
