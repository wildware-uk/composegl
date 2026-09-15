package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.text.TextStyle
import korlibs.image.bitmap.Bitmap
import korlibs.image.format.PNG
import korlibs.io.stream.openSync
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The test fonts and the test emoji, read from the classpath. */
object TestPictures {
    fun bytes(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)) { "$path is missing" }.readBytes()

    /** Noto Sans SC, cut down to the Chinese and Japanese these tests use. */
    fun chinese(): ByteArray = bytes("/fonts/NotoSansSC-Subset.ttf")

    /** Noto Sans KR, cut down to the Korean these tests use. */
    fun korean(): ByteArray = bytes("/fonts/NotoSansKR-Subset.ttf")

    /**
     * Noto's 😀 as a picture: a yellow face, seventy-two pixels square. The tests register it for 🥳,
     * because DejaVu Sans has a plain 😀 of its own, and the main font always wins.
     */
    fun smiley(): Bitmap = PNG.readImage(bytes("/emoji/emoji_u1f600.png").openSync()).mainBitmap
}

/**
 * Characters the main font does not have, measured with no GPU. The same questions the LibGDX
 * backend's `GdxFontFallbackTest` asks, with the same fonts.
 *
 * The main font is DejaVu Sans, which has no Chinese, Japanese or Korean at all. The fallbacks are
 * small cuts of Noto Sans CJK, and the emoji is a real Noto emoji picture.
 */
class KorgeFontFallbackTest {

    private val fonts = KorgeFonts().also {
        it.registerTrueType("test", TestFonts.dejaVu(), listOf(16, 20))
        it.registerTrueType("cjk", TestPictures.chinese(), listOf(16, 20))
        it.registerTrueType("korean", TestPictures.korean(), listOf(16, 20))
    }
    private val style = TextStyle(family = "test", size = 16f)
    private val smiley = TestPictures.smiley()

    private fun width(text: String, family: String = "test", size: Float = 16f) =
        fonts.measure(text, style.copy(family = family, size = size)).size.width

    private fun drawn(text: String, textStyle: TextStyle = style) = (fonts.measure(text, textStyle) as KorgeTextLayout).glyphs

    @Test
    fun `the main font's own emoji glyph wins over a picture, and the test emoji is one it lacks`() {
        val font = fonts.fontFor(style)
        assertTrue(font.getGlyphMetrics(16.0, "😀".codePointAt(0)).existing, "DejaVu Sans draws a plain 😀")
        assertFalse(font.getGlyphMetrics(16.0, "🥳".codePointAt(0)).existing, "but has no 🥳")

        fonts.registerPictures("emoji", mapOf("😀" to smiley, "🥳" to smiley), listOf(16))
        fonts.fallBackTo(listOf("emoji"))

        assertFalse(drawn("😀").single().picture)
        assertTrue(drawn("🥳").single().picture)
    }

    @Test
    fun `a character the main font lacks is measured from the fallback`() {
        fonts.fallBackTo(listOf("cjk"))

        assertEquals(width("玩家", family = "cjk"), width("玩家"))
        assertTrue(width("玩家") > 20f, "two ideographs at 16 should be about 32 wide, were ${width("玩家")}")
    }

    @Test
    fun `without a fallback the same characters are the main font's question mark`() {
        assertNotEquals(width("玩家", family = "cjk"), width("玩家"))
        assertEquals(width("??"), width("玩家"))
    }

    @Test
    fun `a character the main font has is still the main font's`() {
        val before = width("Ace of spades")
        val beforeGlyphs = drawn("Ace").map { it.region }

        fonts.fallBackTo(listOf("cjk"))

        assertEquals(before, width("Ace of spades"))
        assertEquals(beforeGlyphs, drawn("Ace").map { it.region }, "the very same glyphs, not copies of them")
    }

    @Test
    fun `mixed text is the sum of its parts from each font`() {
        fonts.fallBackTo(listOf("cjk", "korean"))

        val mixed = width("Ace 玩家 안녕")
        val parts = width("Ace ") + width("玩家", family = "cjk") + width(" ") + width("안녕", family = "korean")

        assertEquals(parts, mixed, 2f)
    }

    @Test
    fun `fallbacks are tried in order and the first that has the character wins`() {
        fonts.registerPictures("pictures", mapOf("玩" to smiley), listOf(16, 20))

        fonts.fallBackTo(listOf("pictures", "cjk"))
        val pictureFirst = drawn("玩").single()
        fonts.fallBackTo(listOf("cjk", "pictures"))
        val fontFirst = drawn("玩").single()

        assertTrue(pictureFirst.picture)
        assertFalse(fontFirst.picture)
    }

    @Test
    fun `a family's own fallbacks replace the list for everyone`() {
        fonts.fallBackTo(listOf("korean"))
        fonts.fallBackTo("test", listOf("cjk"))

        assertEquals(listOf("cjk"), fonts.fallbacksOf("test"))
        assertEquals(width("玩家", family = "cjk"), width("玩家"))
        assertEquals(listOf("korean"), fonts.fallbacksOf("cjk"))
        assertEquals(emptyList<String>(), fonts.fallbacksOf("korean"), "a family never falls back to itself")
    }

    @Test
    fun `a fallback missing the size asked for says which family and which sizes it has`() {
        fonts.registerTrueType("thin", TestPictures.chinese(), listOf(12))
        fonts.fallBackTo(listOf("thin"))

        val error = assertThrows<IllegalStateException> { width("玩家") }

        assertTrue("thin at 16" in error.message!!, error.message)
        assertTrue("which test falls back to" in error.message!!, error.message)
        assertTrue("[12]" in error.message!!, error.message)
    }

    @Test
    fun `a family made of pictures cannot be the main font`() {
        fonts.registerPictures("emoji", mapOf("🥳" to smiley), listOf(16))

        val error = assertThrows<IllegalStateException> { width("🥳", family = "emoji") }

        assertTrue("only be a fallback" in error.message!!, error.message)
    }

    @Test
    fun `pictures cannot take a name a font already has`() {
        assertThrows<IllegalArgumentException> { fonts.registerPictures("cjk", mapOf("🥳" to smiley), listOf(16)) }
    }

    @Test
    fun `a fallback glyph sits on the main font's baseline`() {
        fonts.fallBackTo(listOf("cjk"))
        val borrowed = drawn("玩").single()
        val own = drawn("玩", style.copy(family = "cjk")).single()

        val mainBaseline = fonts.metrics(style).ascent
        val cjkBaseline = fonts.metrics(style.copy(family = "cjk")).ascent
        // The glyph's picture is the same distance from its baseline in both, so the whole difference
        // in where it is drawn is the difference between the two baselines.
        assertEquals(own.top - cjkBaseline, borrowed.top - mainBaseline, 1f)
        assertEquals(own.width, borrowed.width)
        assertTrue(own.region === borrowed.region, "one rasterisation, shared")
    }

    @Test
    fun `an emoji past U+FFFF is one picture as wide as its advance`() {
        fonts.registerPictures("emoji", mapOf("🥳" to smiley), listOf(16, 20))
        fonts.fallBackTo(listOf("emoji"))

        val glyphs = drawn("🥳")

        assertEquals(1, glyphs.size, "two halves of one character, one glyph")
        val picture = glyphs.single()
        assertTrue(picture.picture)
        assertEquals(16f, picture.height, "as tall as the text size")
        assertEquals(16f, picture.width, "a square picture stays square")
        // A gap of one each side at 16, as the other backends leave.
        assertEquals(18f, width("🥳🥳") - width("🥳"), 0.01f)
        assertEquals(18f, width("🥳"), 0.01f)
    }

    @Test
    fun `an emoji hangs a little below the baseline, like an emoji font's`() {
        fonts.registerPictures("emoji", mapOf("🥳" to smiley), listOf(16, 20))
        fonts.fallBackTo(listOf("emoji"))

        val picture = drawn("🥳").single()
        val baseline = fonts.metrics(style).ascent

        // 0.12 of 16 is two pixels below the baseline.
        assertEquals(baseline + 2f, picture.top + picture.height, 1f)
    }

    @Test
    fun `a picture is packed at every size it was registered at, on the shared atlas`() {
        fonts.registerPictures("emoji", mapOf("🥳" to smiley), listOf(16, 20))
        fonts.fallBackTo(listOf("emoji"))

        val large = drawn("🥳", style.copy(size = 20f)).single()
        assertEquals(20f, large.height)
        assertEquals(0, large.region.page, "beside the white block and the letters")
    }

    @Test
    fun `a picture keeps its colours on the atlas`() {
        fonts.registerPictures("emoji", mapOf("🥳" to smiley), listOf(16))
        fonts.fallBackTo(listOf("emoji"))

        val region = drawn("🥳").single().region
        val page = fonts.atlas.pages[region.page]
        // The face is yellow: somewhere in its middle, red and green are high and blue is low.
        val yellow = (0 until region.height).sumOf { y ->
            (0 until region.width).count { x ->
                val pixel = page.getRgbaRaw(region.x + x, region.y + y)
                pixel.r > 180 && pixel.g > 140 && pixel.b < 100
            }
        }
        assertTrue(yellow > 40, "the packed picture should be yellow, found $yellow yellow pixels")
    }

    @Test
    fun `a shrunk emoji has no holes in its face`() {
        fonts.registerPictures("emoji", mapOf("🥳" to smiley), listOf(16))
        fonts.fallBackTo(listOf("emoji"))

        val region = drawn("🥳").single().region
        val page = fonts.atlas.pages[region.page]
        // The middle row of a round face is solid from edge to edge: no pixel skipped by a careless shrink.
        val middle = region.height / 2
        val alphas = (2 until region.width - 2).map { x -> page.getRgbaRaw(region.x + x, region.y + middle).a }
        assertTrue(alphas.all { it > 200 }, "the middle row should be solid, was $alphas")
    }

    @Test
    fun `the emoji variation selector is not drawn`() {
        fonts.registerPictures("emoji", mapOf("🥳" to smiley), listOf(16, 20))
        fonts.fallBackTo(listOf("emoji"))

        assertEquals(width("🥳"), width("🥳️"))
        assertEquals(1, drawn("🥳️").size)
    }

    @Test
    fun `a picture can be registered with the variation selector in its name`() {
        fonts.registerPictures("emoji", mapOf("🧡️" to smiley), listOf(16))
        fonts.fallBackTo(listOf("emoji"))

        assertTrue(drawn("🧡").single().picture)
    }

    @Test
    fun `a picture for a sequence of characters is refused`() {
        val error = assertThrows<IllegalArgumentException> {
            fonts.registerPictures("emoji", mapOf("👍🏽" to smiley), listOf(16))
        }

        assertTrue("one character" in error.message!!, error.message)
    }

    @Test
    fun `pictures can be registered from encoded files`() {
        fonts.registerEncodedPictures("emoji", mapOf("🥳" to TestPictures.bytes("/emoji/emoji_u1f600.png")), listOf(16))
        fonts.fallBackTo(listOf("emoji"))

        assertTrue(drawn("🥳").single().picture)
    }

    @Test
    fun `Chinese with no spaces still wraps at the width it was given`() {
        fonts.fallBackTo(listOf("cjk"))
        val text = "你好世界玩家聊天欢迎来到游戏"

        val one = fonts.measure(text, style)
        val wrapped = fonts.measure(text, style, maxWidth = one.size.width / 2.5f)

        assertEquals(1, one.lineCount)
        assertTrue(wrapped.lineCount >= 3, "wrapped to ${wrapped.lineCount} lines")
        assertTrue(wrapped.size.width <= one.size.width / 2.5f + 0.5f)
    }

    @Test
    fun `a line limit never cuts an emoji in half`() {
        fonts.registerPictures("emoji", mapOf("🥳" to smiley), listOf(16, 20))
        fonts.fallBackTo(listOf("emoji"))
        // Spaces between, so the toolkit's paragraph has somewhere to break and a second line to cut.
        val text = "🥳🥳 ".repeat(6).trim()

        val glyphs = drawn(text)
        val cut = (fonts.measure(text, style.copy(maxLines = 1), maxWidth = 70f) as KorgeTextLayout).glyphs

        assertEquals(12, glyphs.size)
        assertTrue(cut.size in 2..11, "cut to ${cut.size} glyphs")
        assertTrue(cut.dropLast(1).all { it.picture }, "every glyph before the ellipsis is a whole emoji")
        assertFalse(cut.last().picture, "the ellipsis is a letter")
    }

    @Test
    fun `metrics are the main font's whatever it falls back to`() {
        val before = fonts.metrics(style)

        fonts.fallBackTo(listOf("cjk", "korean"))

        assertEquals(before, fonts.metrics(style))
        assertEquals(fonts.measure("Hg", style).size.height, fonts.measure("Hg 玩家", style).size.height)
        assertEquals(fonts.measure("Hg", style).firstBaseline, fonts.measure("Hg 玩家", style).firstBaseline)
    }

    @Test
    fun `measuring text with fallbacks twice gives the same answer`() {
        fonts.registerPictures("emoji", mapOf("🥳" to smiley), listOf(16, 20))
        fonts.fallBackTo(listOf("cjk", "korean", "emoji"))
        val text = "GG 玩家 안녕 🥳 こんにちは"

        val first = fonts.measure(text, style, maxWidth = 90f)
        val second = fonts.measure(text, style, maxWidth = 90f)

        assertEquals(first.size, second.size)
        assertEquals(first.lineCount, second.lineCount)
    }

    @Test
    fun `the registry lists picture families and their sizes too`() {
        fonts.registerPictures("emoji", mapOf("🥳" to smiley), listOf(20, 16))

        assertEquals(listOf("test", "cjk", "korean", "emoji"), fonts.families())
        assertEquals(listOf(16, 20), fonts.sizesOf("emoji"))
    }

    @Test
    fun `the font behind a style is the one registered, and a missing one is named`() {
        val font = fonts.fontFor(style)
        assertTrue(font === fonts.fontFor(style.copy(size = 20f)), "one font, at two sizes")
        assertTrue(font !== fonts.fontFor(style.copy(family = "cjk")))

        val error = assertThrows<IllegalStateException> { fonts.fontFor(style.copy(size = 17f)) }
        assertTrue("[16, 20]" in error.message!!, error.message)
    }
}
