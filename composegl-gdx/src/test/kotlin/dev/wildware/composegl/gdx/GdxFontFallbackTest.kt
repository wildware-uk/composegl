package dev.wildware.composegl.gdx

import dev.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Characters the main font does not have, measured with no GPU.
 *
 * The main font here is DejaVu Sans, which has no Chinese, Japanese or Korean at all. The fallbacks
 * are small cuts of Noto Sans CJK holding only the characters these tests use, and the emoji is a
 * real Noto emoji picture.
 */
class GdxFontFallbackTest {

    private val fonts = HeadlessFonts.registry(sizes = listOf(16, 20))
    private val style = TextStyle(family = "test", size = 16f)
    private val smiley = HeadlessFonts.smiley()

    init {
        HeadlessFonts.add(fonts, "cjk", listOf(16, 20), file = "NotoSansSC-Subset.ttf", characters = Chinese + Japanese)
        HeadlessFonts.add(fonts, "korean", listOf(16, 20), file = "NotoSansKR-Subset.ttf", characters = Korean)
    }

    @AfterEach
    fun tearDown() {
        smiley.dispose()
        fonts.dispose()
    }

    private fun width(text: String, family: String = "test", size: Float = 16f) =
        fonts.measure(text, style.copy(family = family, size = size)).size.width

    private fun placed(text: String, textStyle: TextStyle = style) = (fonts.measure(text, textStyle) as GdxTextLayout).placed

    private fun drawn(text: String, textStyle: TextStyle = style) = placed(text, textStyle).map { it.glyph }

    @Test
    fun `a character the main font lacks is measured from the fallback`() {
        fonts.fallBackTo(listOf("cjk"))

        assertEquals(width("玩家", family = "cjk"), width("玩家"))
        assertTrue(width("玩家") > 20f, "two ideographs at 16 should be about 32 wide, were ${width("玩家")}")
    }

    @Test
    fun `without a fallback the same characters are not what the fallback font measures`() {
        assertNotEquals(width("玩家", family = "cjk"), width("玩家"))
    }

    @Test
    fun `a character the main font has is still the main font's`() {
        val before = width("Ace of spades")
        val beforeGlyphs = drawn("Ace")

        fonts.fallBackTo(listOf("cjk"))

        assertEquals(before, width("Ace of spades"))
        assertEquals(beforeGlyphs, drawn("Ace"), "the very same glyphs, not copies of them")
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

        assertTrue(pictureFirst.colour)
        assertFalse(fontFirst.colour)
    }

    @Test
    fun `a family's own fallbacks replace the list for everyone`() {
        fonts.fallBackTo(listOf("korean"))
        fonts.fallBackTo("test", listOf("cjk"))

        assertEquals(listOf("cjk"), fonts.fallbacksOf("test"))
        assertEquals(width("玩家", family = "cjk"), width("玩家"))
        assertEquals(listOf("korean"), fonts.fallbacksOf("cjk"))
    }

    @Test
    fun `a fallback missing the size asked for says which family and which sizes it has`() {
        HeadlessFonts.add(fonts, "thin", listOf(12), file = "NotoSansSC-Subset.ttf")
        fonts.fallBackTo(listOf("thin"))

        val error = assertThrows<IllegalStateException> { width("玩家") }

        assertTrue("thin at 16" in error.message!!, error.message)
        assertTrue("which test falls back to" in error.message!!, error.message)
        assertTrue("[12]" in error.message!!, error.message)
    }

    @Test
    fun `a family made of pictures cannot be the main font`() {
        fonts.registerPictures("emoji", mapOf("😀" to smiley), listOf(16))

        val error = assertThrows<IllegalStateException> { width("😀", family = "emoji") }

        assertTrue("only be a fallback" in error.message!!, error.message)
    }

    @Test
    fun `a fallback glyph sits on the main font's baseline`() {
        fonts.fallBackTo(listOf("cjk"))
        val borrowed = placed("玩").single()
        val own = placed("玩", style.copy(family = "cjk")).single()

        val mainBaseline = fonts.metrics(style).ascent
        val cjkBaseline = fonts.metrics(style.copy(family = "cjk")).ascent
        // The bottom of the glyph is the same distance below its baseline in both, so the whole
        // difference in where it is drawn is the difference between the two baselines.
        assertEquals(own.top + (mainBaseline - cjkBaseline), borrowed.top, 1f)
        assertEquals(own.width, borrowed.width)
        assertEquals(own.glyph.advance, borrowed.glyph.advance)
    }

    @Test
    fun `an emoji past U+FFFF is one picture as wide as its advance`() {
        fonts.registerPictures("emoji", mapOf("😀" to smiley), listOf(16, 20))
        fonts.fallBackTo(listOf("emoji"))

        val glyphs = drawn("😀")

        assertEquals(1, glyphs.size, "two halves of one character, one glyph")
        val picture = glyphs.single()
        assertTrue(picture.colour)
        assertEquals(16f, picture.height, "as tall as the text size")
        assertEquals(16f, picture.width, "a square picture stays square")
        // The advance is what a second one adds.
        assertEquals(18f, width("😀😀") - width("😀"), 0.01f)
    }

    @Test
    fun `a picture is baked at every size it was registered at`() {
        fonts.registerPictures("emoji", mapOf("😀" to smiley), listOf(16, 20))
        fonts.fallBackTo(listOf("emoji"))

        assertEquals(20f, drawn("😀", style.copy(size = 20f)).single().height)
    }

    @Test
    fun `the emoji variation selector is not drawn`() {
        fonts.registerPictures("emoji", mapOf("😀" to smiley), listOf(16, 20))
        fonts.fallBackTo(listOf("emoji"))

        assertEquals(width("😀"), width("😀️"))
        assertEquals(1, drawn("😀️").size)
    }

    @Test
    fun `a picture can be registered with the variation selector in its name`() {
        fonts.registerPictures("emoji", mapOf("❤️" to smiley), listOf(16))
        fonts.fallBackTo(listOf("emoji"))

        assertTrue(drawn("❤").single().colour)
    }

    @Test
    fun `a picture for a sequence of characters is refused`() {
        val error = assertThrows<IllegalArgumentException> {
            fonts.registerPictures("emoji", mapOf("👍🏽" to smiley), listOf(16))
        }

        assertTrue("one character" in error.message!!, error.message)
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
        fonts.registerPictures("emoji", mapOf("😀" to smiley), listOf(16, 20))
        fonts.fallBackTo(listOf("emoji"))
        val text = "😀".repeat(12)

        val glyphs = drawn(text)
        val cutGlyphs = (fonts.measure(text, style.copy(maxLines = 1), maxWidth = 60f) as GdxTextLayout).placed.map { it.glyph }

        assertEquals(12, glyphs.size)
        assertTrue(cutGlyphs.size in 2..11, "cut to ${cutGlyphs.size} glyphs")
        assertTrue(cutGlyphs.dropLast(1).all { it.colour }, "every glyph before the ellipsis is a whole emoji")
        // The test font has no ellipsis, so it is the missing glyph — but one glyph, not half an
        // emoji's worth of boxes.
        assertFalse(cutGlyphs.last().colour)
    }

    @Test
    fun `metrics are the main font's whatever it falls back to`() {
        val before = fonts.metrics(style)

        fonts.fallBackTo(listOf("cjk", "korean"))

        assertEquals(before, fonts.metrics(style))
        assertEquals(fonts.measure("Hg", style).size.height, fonts.measure("Hg 玩家", style).size.height)
    }

    @Test
    fun `measuring text with fallbacks twice gives the same answer`() {
        fonts.registerPictures("emoji", mapOf("😀" to smiley), listOf(16, 20))
        fonts.fallBackTo(listOf("cjk", "korean", "emoji"))
        val text = "GG 玩家 안녕 😀 こんにちは"

        val first = fonts.measure(text, style, maxWidth = 90f)
        val second = fonts.measure(text, style, maxWidth = 90f)

        assertEquals(first.size, second.size)
        assertEquals(first.lineCount, second.lineCount)
    }

    @Test
    fun `registering pictures marks the atlas for upload`() {
        fonts.registerPictures("emoji", mapOf("😀" to smiley), listOf(16))
        fonts.fallBackTo(listOf("emoji"))

        // Written onto a page in memory with no GPU anywhere; a page uploads what changed on it
        // when it is next drawn, which composegl-render's GlyphAtlasTest holds it to.
        val picture = drawn("😀").single()
        val page = checkNotNull(picture.page) { "the picture should be on an atlas page" }
        val middle = page.rgbaAt(picture.x + picture.width.toInt() / 2, picture.y + picture.height.toInt() / 2)
        assertTrue(middle and 0xFF != 0, "the picture's own pixels should be on the page, not a blank spot")
    }

    private companion object {
        const val Chinese = "你好世界玩家聊天欢迎来到游戏"
        const val Japanese = "こんにちは"
        const val Korean = "안녕하세요"
    }
}
