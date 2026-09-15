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

    private class TestFonts(
        val rasteriser: FakeRasteriser = FakeRasteriser(),
        lineBreaking: LineBreaking = LineBreaking.Words,
        wholePixelWidths: Boolean = false,
    ) : AtlasFonts(rasteriser, atlasOwner = "TestFonts", lineBreaking = lineBreaking, wholePixelWidths = wholePixelWidths) {
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
    fun `paragraph breaking leaves a word too long for the width to overflow`() {
        val words = TestFonts().apply { font("body", "abcdefghij", advance = 0.55f) }
        val paragraphs = TestFonts(lineBreaking = AtlasFonts.LineBreaking.Paragraph).apply { font("body", "abcdefghij", advance = 0.55f) }

        assertTrue(words.measure("abcdefghij", style(), maxWidth = 20f).lineCount > 1, "broken where it had to be")
        val whole = paragraphs.measure("abcdefghij", style(), maxWidth = 20f)
        assertEquals(1, whole.lineCount)
        assertEquals(2, paragraphs.measure("abc def ghij", style(), maxWidth = 45f).lineCount)
        assertEquals(2, paragraphs.measure("abc\ndef", style()).lineCount)
    }

    @Test
    fun `paragraph breaking ends a cut line with the ellipsis`() {
        val fonts = TestFonts(lineBreaking = AtlasFonts.LineBreaking.Paragraph).apply { font("body", "abcdefghij…", advance = 0.5f) }
        val cut = fonts.measure("abc def ghi", TextStyle(family = "body", size = 10f, maxLines = 1), maxWidth = 40f) as AtlasTextLayout

        assertEquals(1, cut.lineCount)
        assertTrue(cut.size.width <= 40f, "${cut.size}")
        val uncut = fonts.measure("abc def ghi", style(), maxWidth = 40f) as AtlasTextLayout
        assertTrue(uncut.lineCount > 1)
        assertTrue(cut.placed.size < 9, "cut short of the whole text's nine glyphs: ${cut.placed.size}")
    }

    @Test
    fun `whole pixel widths round a layout up`() {
        val exact = TestFonts().apply { font("body", "abc", advance = 0.55f) }
        val rounded = TestFonts(wholePixelWidths = true).apply { font("body", "abc", advance = 0.55f) }

        assertEquals(16.5f, width(exact, "abc"), 0.001f)
        assertEquals(17f, width(rounded, "abc"))
    }

    @Test
    fun `a canvas with no fonts of its own still draws text from the pages it was measured onto`() {
        val fonts = TestFonts().apply { font("body", "A", advance = 0.5f, sizes = listOf(16)) }
        val device = RecordingDevice()
        val canvas = RenderCanvas(device)
        val layout = fonts.measure("A", TextStyle(family = "body", size = 16f))
        canvas.begin(Viewport.oneToOne(Size(100f, 100f)))
        canvas.rect(Rect.of(0f, 0f, 10f, 10f), Colour.Blue)
        canvas.text(layout, 0f, 0f, Colour.Red)
        canvas.end()

        assertEquals(2, device.draws.size, "its own white texture, then the glyph page")
    }

    @Test
    fun `closing a canvas gives back the atlas pages uploaded to its device and leaves the fonts usable`() {
        val fonts = TestFonts().apply { font("body", "A", advance = 0.5f, sizes = listOf(16)) }
        val device = RecordingDevice()
        val canvas = RenderCanvas(device, fonts)
        canvas.begin(Viewport.oneToOne(Size(100f, 100f)))
        canvas.text(fonts.measure("A", TextStyle(family = "body", size = 16f)), 0f, 0f, Colour.Red)
        canvas.end()
        val page = device.draws.single().texture
        canvas.close()

        assertEquals(listOf<DeviceResource>(page), device.deleted)
        assertEquals(1, fonts.measure("A", TextStyle(family = "body", size = 16f)).lineCount)
    }

    @Test
    fun `a glyph is drawn with texture coordinates at its spot's edges over the page`() {
        val fonts = TestFonts().apply { font("body", "A", advance = 0.5f, sizes = listOf(16)) }
        val device = RecordingDevice()
        val canvas = RenderCanvas(device, fonts)
        val layout = fonts.measure("A", TextStyle(family = "body", size = 16f)) as AtlasTextLayout
        canvas.begin(Viewport.oneToOne(Size(100f, 100f)))
        canvas.text(layout, 0f, 0f, Colour.Red)
        canvas.end()

        val glyph = layout.placed.single().glyph
        val size = checkNotNull(glyph.page).size.toFloat()
        val draw = device.draws.single()
        val u = ShapeVertex.Attributes.single { it.name == "a_texCoord0" }.offset
        // The second corner is the top-left and the fourth the bottom-right.
        assertEquals(glyph.x / size, draw.at(1, u))
        assertEquals(glyph.y / size, draw.at(1, u + 1))
        assertEquals((glyph.x + glyph.width) / size, draw.at(3, u))
        assertEquals((glyph.y + glyph.height) / size, draw.at(3, u + 1))
    }

    @Test
    fun `solid colour is sampled from the middle of the white block and never at its edge`() {
        val fonts = TestFonts().apply { font("body", "A", advance = 0.5f, sizes = listOf(16)) }
        val device = RecordingDevice()
        val canvas = RenderCanvas(device, fonts)
        canvas.begin(Viewport.oneToOne(Size(100f, 100f)))
        canvas.rect(Rect.of(0f, 0f, 10f, 10f), Colour.Blue)
        canvas.end()

        val white = fonts.atlas.white
        val size = white.page.size.toFloat()
        val draw = device.draws.single()
        assertSame(white.page.texture(device), draw.texture, "the atlas page and not a texture of its own")
        val u = ShapeVertex.Attributes.single { it.name == "a_texCoord0" }.offset
        val middle = GlyphAtlas.WhiteBlock / 2f
        for (corner in 0 until 4) {
            val x = draw.at(corner, u) * size
            val y = draw.at(corner, u + 1) * size
            assertEquals(white.x + middle, x, "across, corner $corner")
            assertEquals(white.y + middle, y, "down, corner $corner")
            // Smooth sampling reads a texel either way: the sample stays that far inside the block.
            assertTrue(x - 1f >= white.x && x + 1f <= white.x + GlyphAtlas.WhiteBlock, "a texel in from the edge across")
            assertTrue(y - 1f >= white.y && y + 1f <= white.y + GlyphAtlas.WhiteBlock, "a texel in from the edge down")
        }
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
    @Test
    fun `a font registered late keeps the glyphs already made`() {
        val fonts = TestFonts().apply { font("body", "AB", advance = 0.5f) }
        val before = (fonts.measure("AB", style()) as AtlasTextLayout).placed.map { it.glyph }

        fonts.font("late", "C", advance = 1f)
        val after = (fonts.measure("AB", style()) as AtlasTextLayout).placed.map { it.glyph }

        assertEquals(before.size, after.size)
        before.indices.forEach { assertSame(before[it], after[it], "glyph $it was made a second time") }
    }

    @Test
    fun `closed fonts refuse to measure and name no families`() {
        val fonts = TestFonts().apply { font("body", "A", advance = 0.5f) }
        fonts.close()

        val thrown = assertFailsWith<IllegalStateException> { fonts.measure("A", style()) }
        assertTrue("none" in thrown.message.orEmpty(), thrown.message)
        assertTrue(fonts.families().isEmpty())
    }
}
