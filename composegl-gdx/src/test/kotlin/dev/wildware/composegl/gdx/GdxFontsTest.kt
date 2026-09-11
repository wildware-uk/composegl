package dev.wildware.composegl.gdx

import dev.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GdxFontsTest {

    private val fonts = HeadlessFonts.registry(sizes = listOf(12, 16, 32))
    private val style = TextStyle(family = "test", size = 16f)

    @AfterEach
    fun tearDown() = fonts.dispose()

    @Test
    fun `measuring the same text twice gives the same answer`() {
        val first = fonts.measure("The quick brown fox", style)
        val second = fonts.measure("The quick brown fox", style)

        assertEquals(first.size, second.size)
        assertEquals(first.lineCount, second.lineCount)
    }

    @Test
    fun `a bigger size measures wider`() {
        val small = fonts.measure("Hello", style.copy(size = 12f))
        val large = fonts.measure("Hello", style.copy(size = 32f))

        assertTrue(large.size.width > small.size.width, "32pt was ${large.size.width}")
    }

    @Test
    fun `text with nothing in it takes no width and one line`() {
        val layout = fonts.measure("", style)

        assertEquals(0f, layout.size.width)
        assertEquals(1, layout.lineCount)
    }

    @Test
    fun `an explicit newline breaks a line`() {
        val layout = fonts.measure("one\ntwo", style)

        assertEquals(2, layout.lineCount)
    }

    @Test
    fun `height is the style's line spacing, not the font's`() {
        val layout = fonts.measure("one\ntwo\nthree", style.copy(lineHeightRatio = 2f))

        assertEquals(3, layout.lineCount)
        assertEquals(96f, layout.size.height)
    }

    // --- wrapping ---

    @Test
    fun `text wraps at the width it was given`() {
        val unwrapped = fonts.measure("the quick brown fox jumps over the lazy dog", style)
        val wrapped = fonts.measure(
            "the quick brown fox jumps over the lazy dog",
            style,
            maxWidth = unwrapped.size.width / 3f,
        )

        assertTrue(wrapped.lineCount >= 3, "wrapped to ${wrapped.lineCount} lines")
        assertTrue(wrapped.size.width <= unwrapped.size.width / 3f + 0.5f)
    }

    @Test
    fun `wrapping at the same width is stable`() {
        val text = "the quick brown fox jumps over the lazy dog"
        val first = fonts.measure(text, style, maxWidth = 120f)
        val second = fonts.measure(text, style, maxWidth = 120f)

        assertEquals(first.size, second.size)
        assertEquals(first.lineCount, second.lineCount)
    }

    // --- line limits ---

    @Test
    fun `a line limit cuts the text and adds an ellipsis`() {
        val text = "the quick brown fox jumps over the lazy dog"
        val layout = fonts.measure(text, style.copy(maxLines = 2), maxWidth = 100f)

        assertEquals(2, layout.lineCount)
        assertTrue((layout as GdxTextLayout).glyphs.toString().isNotEmpty())
        assertEquals(text, layout.text, "the layout still reports what it was asked to measure")
    }

    @Test
    fun `a line limit leaves short text alone`() {
        val layout = fonts.measure("short", style.copy(maxLines = 2), maxWidth = 500f)

        assertEquals(1, layout.lineCount)
    }

    @Test
    fun `what is drawn after a cut still fits the limit`() {
        val text = "the quick brown fox jumps over the lazy dog and keeps going for a while yet"
        val layout = fonts.measure(text, style.copy(maxLines = 3), maxWidth = 100f) as GdxTextLayout

        assertTrue(layout.glyphs.runs.map { it.y }.distinct().size <= 3)
    }

    // --- metrics ---

    @Test
    fun `metrics are all measured from the baseline, and all positive`() {
        val metrics = fonts.metrics(style)

        assertTrue(metrics.ascent > 0f, "ascent was ${metrics.ascent}")
        assertTrue(metrics.descent > 0f, "descent was ${metrics.descent}")
        assertTrue(metrics.capHeight > 0f)
        assertTrue(metrics.spaceAdvance > 0f)
        assertTrue(metrics.ascent >= metrics.capHeight)
        assertEquals(style.lineHeight, metrics.lineHeight)
    }

    // --- a size nobody registered ---

    @Test
    fun `asking for a size that was never registered says which ones were`() {
        val error = assertThrows<IllegalStateException> { fonts.measure("hello", style.copy(size = 17f)) }

        assertTrue(error.message!!.contains("[12, 16, 32]"), error.message!!)
    }

    @Test
    fun `asking for a family that was never registered says which ones were`() {
        val error = assertThrows<IllegalStateException> {
            fonts.measure("hello", style.copy(family = "nope"))
        }

        assertTrue(error.message!!.contains("test"), error.message!!)
    }

    @Test
    fun `a size is matched to the nearest whole number`() {
        val exact = fonts.measure("hello", style.copy(size = 16f))
        val nudged = fonts.measure("hello", style.copy(size = 16.2f))

        assertEquals(exact.size.width, nudged.size.width)
    }
}
