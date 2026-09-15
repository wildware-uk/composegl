package dev.wildware.composegl.render

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AtlasFontsTest {

    /**
     * A font per family that draws only [characters], each a box half the size wide, advancing by
     * [advance] times the size — so a measured width says which font a character came from.
     */
    private class FakeRasteriser : GlyphRasteriser {
        val families = HashMap<String, Pair<String, Float>>()

        override fun face(family: String, size: Int): RasterFace? =
            families[family]?.let { (characters, advance) -> FakeFace(characters, advance, size) }
    }

    private class FakeFace(private val characters: String, private val advanceBy: Float, private val size: Int) : RasterFace {
        override val ascent = size * 0.75f
        override val descent = size * 0.25f
        override val capHeight = size * 0.7f

        override fun has(codepoint: Int) = codepoint == ' '.code || characters.contains(codepoint.toChar())

        override fun advance(codepoint: Int) = size * advanceBy

        override fun draw(codepoint: Int, into: GlyphBitmap): Boolean {
            into.xOffset = 1f
            into.yOffset = -ascent
            if (codepoint == ' '.code) into.resize(0, 0, GlyphKind.Coverage) else into.resize(size / 2, size, GlyphKind.Coverage)
            return true
        }
    }

    private class TestFonts(val rasteriser: FakeRasteriser = FakeRasteriser()) : AtlasFonts(rasteriser, atlasOwner = "TestFonts") {
        fun font(family: String, characters: String, advance: Float, sizes: List<Int> = listOf(10)) {
            rasteriser.families[family] = characters to advance
            registerFont(family, sizes)
        }
    }

    private fun style(family: String = "body") = TextStyle(family = family, size = 10f)

    private fun width(fonts: AtlasFonts, text: String, family: String = "body") = fonts.measure(text, style(family)).size.width

    /** A 2 by 2 picture of solid yellow. */
    private val smiley = RgbaImage(2, 2, ByteArray(16) { if (it % 4 == 2) 0 else -1 })

    @Test
    fun `the main font wins for a character it has`() {
        val fonts = TestFonts().apply {
            font("body", "AB", advance = 0.5f)
            font("wide", "AB", advance = 1f)
            fallBackTo(listOf("wide"))
        }
        assertEquals(10f, width(fonts, "AB"))
    }

    @Test
    fun `a character the main font lacks comes from the first fallback that has it`() {
        val fonts = TestFonts().apply {
            font("body", "A", advance = 0.5f)
            font("first", "X", advance = 1f)
            font("second", "BX", advance = 2f)
            fallBackTo(listOf("first", "second"))
        }
        // A from body (5), B from second (20), X from first (10).
        assertEquals(35f, width(fonts, "ABX"))
    }

    @Test
    fun `a character nobody has is the main font's question mark`() {
        val fonts = TestFonts().apply { font("body", "A?", advance = 0.5f) }
        val layout = fonts.measure("Z", style()) as AtlasTextLayout
        assertEquals(5f, layout.size.width)
        assertEquals(1, layout.placed.size)
    }

    @Test
    fun `a variation selector is invisible`() {
        val fonts = TestFonts().apply { font("body", "A", advance = 0.5f) }
        assertEquals(width(fonts, "A"), width(fonts, "A\uFE0F"))
        assertEquals(width(fonts, "A"), width(fonts, "A\uFE0E"))
    }

    @Test
    fun `one family can fall back somewhere else than everyone`() {
        val fonts = TestFonts().apply {
            font("body", "A", advance = 0.5f)
            font("title", "A", advance = 0.5f)
            font("latin", "B", advance = 1f)
            font("greek", "B", advance = 2f)
            fallBackTo(listOf("latin"))
            fallBackTo("title", listOf("greek", "title"))
        }
        assertEquals(15f, width(fonts, "AB"))
        assertEquals(25f, width(fonts, "AB", "title"))
        assertEquals(listOf("greek"), fonts.fallbacksOf("title"), "a family never falls back to itself")
    }

    @Test
    fun `a picture is as tall as the text in its own colours and a little below the baseline`() {
        val fonts = TestFonts().apply {
            font("body", "A", advance = 0.5f, sizes = listOf(16))
            registerDecodedPictures("emoji", mapOf("\uD83D\uDE00\uFE0F" to smiley), listOf(16))
            fallBackTo(listOf("emoji"))
        }
        val layout = fonts.measure("\uD83D\uDE00", TextStyle(family = "body", size = 16f)) as AtlasTextLayout
        val glyph = layout.placed.single().glyph

        assertTrue(glyph.colour)
        assertEquals(16f to 16f, glyph.width to glyph.height)
        // A gap of a sixteenth of the size either side, and the bottom 12% of the size under the line.
        assertEquals(18f, glyph.advance)
        assertEquals(-14f, glyph.yOffset)
    }

    @Test
    fun `glyphs share the page the white block is on`() {
        val fonts = TestFonts().apply { font("body", "A", advance = 0.5f) }
        val layout = fonts.measure("A", style()) as AtlasTextLayout
        assertSame(fonts.atlas.white.page, layout.placed.single().glyph.page)
    }

    @Test
    fun `a family nobody registered is named with the ones that were`() {
        val fonts = TestFonts().apply { font("body", "A", advance = 0.5f) }
        val thrown = assertFailsWith<IllegalStateException> { fonts.measure("A", style("missing")) }
        assertTrue("body" in thrown.message.orEmpty(), thrown.message)
    }

    @Test
    fun `pictures cannot be the main font`() {
        val fonts = TestFonts().apply { registerDecodedPictures("emoji", mapOf("\uD83D\uDE00" to smiley), listOf(10)) }
        val thrown = assertFailsWith<IllegalStateException> { fonts.measure("A", style("emoji")) }
        assertTrue("only be a fallback" in thrown.message.orEmpty(), thrown.message)
    }

    @Test
    fun `a picture for a joined sequence is refused`() {
        val fonts = TestFonts()
        assertFailsWith<IllegalArgumentException> {
            fonts.registerDecodedPictures("emoji", mapOf("\uD83D\uDC68\u200D\uD83D\uDC69" to smiley), listOf(10))
        }
    }

    @Test
    fun `text wraps at a word and a line limit ends in an ellipsis`() {
        val fonts = TestFonts().apply { font("body", "abcdefghij…", advance = 0.5f) }
        val wrapped = fonts.measure("abc def ghi", style(), maxWidth = 40f)
        assertEquals(2, wrapped.lineCount)

        val cut = fonts.measure("abc def ghi", TextStyle(family = "body", size = 10f, maxLines = 1), maxWidth = 40f) as AtlasTextLayout
        assertEquals(1, cut.lineCount)
        assertTrue(cut.size.width <= 40f)
    }

    @Test
    fun `codepoints of some text are sorted and joined into runs`() {
        assertEquals(listOf(' '.code..' '.code, 'a'.code..'c'.code, 'x'.code..'x'.code), AtlasFonts.codepointsOf("xcab a"))
    }

    @Test
    fun `a ring copy of text leaves the pictures out`() {
        val fonts = TestFonts().apply {
            font("body", "A", advance = 0.5f, sizes = listOf(16))
            registerDecodedPictures("emoji", mapOf("\uD83D\uDE00" to smiley), listOf(16))
            fallBackTo(listOf("emoji"))
        }
        val device = RecordingDevice()
        val canvas = RenderCanvas(device, fonts)
        val layout = fonts.measure("A\uD83D\uDE00", TextStyle(family = "body", size = 16f))
        canvas.begin(Viewport.oneToOne(Size(100f, 100f)))
        canvas.rect(Rect.of(0f, 0f, 10f, 10f), Colour.Blue)
        canvas.text(layout, 0f, 0f, Colour.Red)
        canvas.textRing(layout, 0f, 0f, Colour.Black)
        canvas.end()

        val draw = device.draws.single()
        assertEquals(4, draw.quads, "a panel, two glyphs, and a ring of one: one texture, one call")
        // The picture keeps its colours: white, not the text's red.
        assertEquals(listOf(1f, 1f, 1f, 1f), draw.fill(8))
        assertEquals(listOf(1f, 0f, 0f, 1f), draw.fill(4))
    }
}
