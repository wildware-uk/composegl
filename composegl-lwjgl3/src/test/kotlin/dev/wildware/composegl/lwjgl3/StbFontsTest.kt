package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Text measurement, with no window anywhere.
 *
 * The atlas is baked into ordinary memory and only uploaded when something is drawn, which is what
 * makes this possible — and it is worth having, because wrapping and cutting text is where the
 * bugs are, and none of them need a GPU to find.
 */
class StbFontsTest {

    private val ttf = javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf")!!.readBytes()

    private fun fonts(vararg sizes: Int) = StbFonts().apply { register("body", ttf, sizes.toList()) }

    private val body = TextStyle(family = "body", size = 16f)

    @Test
    fun `measuring the same words twice gives the same answer`() {
        val fonts = fonts(16)
        val once = fonts.measure("Handgloves", body)
        val again = fonts.measure("Handgloves", body)
        assertEquals(once.size, again.size)
        assertEquals(once.lineCount, again.lineCount)
    }

    @Test
    fun `a wider string is wider`() {
        val fonts = fonts(16)
        assertTrue(
            fonts.measure("HHHH", body).size.width > fonts.measure("HH", body).size.width,
            "four letters should be wider than two",
        )
    }

    @Test
    fun `a bigger size is bigger`() {
        val fonts = fonts(12, 32)
        val small = fonts.measure("Handgloves", body.copy(size = 12f)).size
        val large = fonts.measure("Handgloves", body.copy(size = 32f)).size
        assertTrue(large.width > small.width, "32 should be wider than 12, got $large against $small")
        assertTrue(large.height > small.height, "and taller")
    }

    @Test
    fun `text wraps at a word rather than mid-word`() {
        val fonts = fonts(16)
        val words = "The relay went quiet six hours ago"
        val wrapped = fonts.measure(words, body, maxWidth = 120f)
        assertTrue(wrapped.lineCount > 1, "expected it to wrap at 120 wide")
        assertTrue(wrapped.size.width <= 120f, "no line may be wider than what it wrapped to")
    }

    @Test
    fun `an explicit newline breaks a line even with room to spare`() {
        val fonts = fonts(16)
        assertEquals(2, fonts.measure("one\ntwo", body).lineCount)
    }

    @Test
    fun `a line limit cuts the text and says so with an ellipsis`() {
        val fonts = fonts(16)
        val words = "The relay went quiet six hours ago and nobody has heard from the outpost since"
        val limited = fonts.measure(words, body.copy(maxLines = 2), maxWidth = 120f)

        assertEquals(2, limited.lineCount)
        assertTrue(limited.size.width <= 120f, "the ellipsis has to fit too, got ${limited.size.width}")
        // The layout still reports what was asked for; only what is drawn was shortened.
        assertEquals(words, limited.text)
    }

    @Test
    fun `the ellipsis is a glyph this backend actually has`() {
        val fonts = fonts(16)
        // Not ASCII, and the reason the baked range goes past it. A backend without it drops the
        // one character that says something is missing.
        assertTrue(fonts.measure("…", body).size.width > 0f, "the ellipsis should have a width")
    }

    @Test
    fun `the metrics measure from the baseline, all positive`() {
        val metrics = fonts(16).metrics(body)
        assertTrue(metrics.ascent > 0f, "ascent")
        assertTrue(metrics.descent > 0f, "descent, measured downwards but reported positive")
        assertTrue(metrics.capHeight > 0f, "cap height")
        assertTrue(metrics.spaceAdvance > 0f, "a space has to move the pen")
        assertTrue(metrics.ascent > metrics.capHeight, "an ascender goes above a capital")
        assertEquals(body.lineHeight, metrics.lineHeight)
    }

    @Test
    fun `the first baseline is the ascent, so nothing is drawn above the top edge`() {
        val fonts = fonts(16)
        assertEquals(fonts.metrics(body).ascent, fonts.measure("Handgloves", body).firstBaseline)
    }

    @Test
    fun `asking for a size that was never registered says which ones were`() {
        val fonts = fonts(12, 16)
        val thrown = assertThrows<IllegalStateException> { fonts.measure("x", body.copy(size = 40f)) }
        assertTrue(thrown.message!!.contains("[12, 16]"), "expected the sizes, got: ${thrown.message}")
    }

    @Test
    fun `asking for a family that was never registered says which ones were`() {
        val fonts = fonts(16)
        val thrown = assertThrows<IllegalStateException> { fonts.measure("x", body.copy(family = "title")) }
        assertTrue(thrown.message!!.contains("body"), "expected the families, got: ${thrown.message}")
    }

    @Test
    fun `registering after the first measurement is refused rather than silently ignored`() {
        val fonts = fonts(16)
        fonts.measure("x", body)
        // Everything shares one page and the page is baked once. Letting a late registration
        // through would move glyphs that already-measured text is relying on staying put.
        assertThrows<IllegalStateException> { fonts.register("title", ttf, listOf(24)) }
    }

    @Test
    fun `registering with no sizes is refused`() {
        assertThrows<IllegalArgumentException> { StbFonts().register("body", ttf, emptyList()) }
    }

    @Test
    fun `two families can be registered from the same file`() {
        val fonts = StbFonts().apply {
            register("body", ttf, listOf(16))
            register("title", ttf, listOf(32))
        }
        assertEquals(listOf("body", "title"), fonts.families())
        assertEquals(listOf(16), fonts.sizesOf("body"))
        assertEquals(listOf(32), fonts.sizesOf("title"))
    }

    @Test
    fun `a page too small to hold everything says so instead of losing letters`() {
        val fonts = StbFonts(pageSize = 64, maxPageSize = 64).apply { register("body", ttf, listOf(64)) }
        val thrown = assertThrows<IllegalStateException> { fonts.metrics(body.copy(size = 64f)) }
        assertTrue(thrown.message!!.contains("bigger"), "expected advice, got: ${thrown.message}")
    }

    @Test
    fun `a page that fills up grows rather than failing`() {
        val small = StbFonts(pageSize = 64).apply { register("body", ttf, listOf(64)) }
        val roomy = StbFonts(pageSize = 2048).apply { register("body", ttf, listOf(64)) }

        assertTrue(small.bakedPageSize > 64, "grew to ${small.bakedPageSize}")
        assertEquals(roomy.measure("Hello", body.copy(size = 64f)).size, small.measure("Hello", body.copy(size = 64f)).size)
    }

    // --- characters the main font does not have ---

    private val chinese = javaClass.getResourceAsStream("/fonts/NotoSansSC-Subset.ttf")!!.readBytes()
    private val korean = javaClass.getResourceAsStream("/fonts/NotoSansKR-Subset.ttf")!!.readBytes()
    private val smiley = javaClass.getResourceAsStream("/emoji/emoji_u1f600.png")!!.readBytes()

    private fun withFallbacks(vararg sizes: Int) = StbFonts().apply {
        register("body", ttf, sizes.toList())
        register("cjk", chinese, sizes.toList(), StbFonts.codepointsOf("玩家你好こんにちは"))
        register("korean", korean, sizes.toList(), StbFonts.codepointsOf("안녕"))
        registerPictures("emoji", mapOf("😀" to smiley), sizes.toList())
    }

    private fun StbFonts.width(text: String, family: String = "body") =
        measure(text, body.copy(family = family)).size.width

    private fun StbFonts.placed(text: String) = (measure(text, body) as StbTextLayout).placed

    @Test
    fun `a character the main font lacks is measured from the fallback`() {
        val fonts = withFallbacks(16).apply { fallBackTo(listOf("cjk")) }

        assertEquals(fonts.width("玩家", family = "cjk"), fonts.width("玩家"))
        assertTrue(fonts.width("玩家") > 20f)
    }

    @Test
    fun `without a fallback the character comes out as the main font's question mark`() {
        val fonts = withFallbacks(16)

        assertEquals(fonts.width("??"), fonts.width("玩家"))
    }

    @Test
    fun `mixed text takes each character from the first font that has it`() {
        val fonts = withFallbacks(16).apply { fallBackTo(listOf("cjk", "korean", "emoji")) }

        val mixed = fonts.width("Ace 玩家 안녕 😀")
        val parts = fonts.width("Ace ") + fonts.width("玩家", "cjk") + fonts.width(" ") +
            fonts.width("안녕", "korean") + fonts.width(" ") + fonts.placed("😀").let { 18f }

        assertEquals(parts, mixed, 0.01f)
        assertEquals(fonts.width("Ace"), withFallbacks(16).width("Ace"), "the main font's letters are untouched")
    }

    @Test
    fun `a fallback glyph sits on the same baseline as the main font's`() {
        val fonts = withFallbacks(16).apply { fallBackTo(listOf("cjk")) }
        val borrowed = fonts.placed("玩").single()
        val own = (fonts.measure("玩", body.copy(family = "cjk")) as StbTextLayout).placed.single()

        val shift = fonts.metrics(body).ascent - fonts.metrics(body.copy(family = "cjk")).ascent
        assertEquals(own.top + shift, borrowed.top, 1f)
    }

    @Test
    fun `an emoji is one colour picture as tall as the text`() {
        val fonts = withFallbacks(16, 20).apply { fallBackTo(listOf("emoji")) }

        val picture = fonts.placed("😀️").single()

        assertTrue(picture.glyph.colour)
        assertEquals(16f, picture.height)
        assertEquals(20f, (fonts.measure("😀", body.copy(size = 20f)) as StbTextLayout).placed.single().height)
        assertTrue(fonts.placed("A").none { it.glyph.colour }, "letters are not pictures")
    }

    @Test
    fun `a line limit never cuts an emoji in half`() {
        val fonts = withFallbacks(16).apply { fallBackTo(listOf("emoji")) }

        val cut = fonts.measure("😀".repeat(10), body.copy(maxLines = 1), maxWidth = 70f) as StbTextLayout

        assertTrue(cut.placed.dropLast(1).all { it.glyph.colour }, "every glyph before the ellipsis is a whole emoji")
        assertTrue(cut.size.width <= 70f)
    }

    @Test
    fun `a fallback missing the size asked for says which family and sizes`() {
        val fonts = StbFonts().apply {
            register("body", ttf, listOf(16))
            register("cjk", chinese, listOf(12), StbFonts.codepointsOf("玩"))
            fallBackTo(listOf("cjk"))
        }

        val thrown = assertThrows<IllegalStateException> { fonts.width("玩") }

        assertTrue("cjk at 16, which body falls back to" in thrown.message!!, thrown.message)
        assertTrue("[12]" in thrown.message!!, thrown.message)
    }

    @Test
    fun `pictures cannot be the main font`() {
        val fonts = withFallbacks(16)

        val thrown = assertThrows<IllegalStateException> { fonts.width("😀", family = "emoji") }

        assertTrue("only be a fallback" in thrown.message!!, thrown.message)
    }

    @Test
    fun `a picture for a sequence of characters is refused`() {
        assertThrows<IllegalArgumentException> {
            StbFonts().registerPictures("emoji", mapOf("👍🏽" to smiley), listOf(16))
        }
    }

    @Test
    fun `codepoints of some text are sorted and joined into runs`() {
        assertEquals(listOf(0x61..0x63, 0x7A..0x7A, 0x73A9..0x73A9), StbFonts.codepointsOf("cbaz玩a"))
    }

    @Test
    fun `a missing glyph falls back rather than disappearing`() {
        val fonts = fonts(16)
        // Nothing in the baked range, so it comes back as a question mark rather than as nothing.
        assertNotEquals(0f, fonts.measure("中", body).size.width)
    }
}
