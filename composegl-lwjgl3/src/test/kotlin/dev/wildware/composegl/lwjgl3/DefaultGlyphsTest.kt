package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.testing.DefaultGlyphs
import dev.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The defaults the toolkit ships and the characters this backend bakes, held against each other.
 *
 * A missing glyph is silent: the atlas has no square for it, the layout leaves a hole, and the game
 * shows an empty box. Nobody finds out until somebody looks at a screenshot — which is exactly how
 * `ItemMarks.Arrows` came to default to `▼` while the baked set had only `▲` in it, putting a blank
 * square where the "this is worse" answer goes.
 */
class DefaultGlyphsTest {

    private val ttf = javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf")!!.readBytes()

    @Test
    fun `every character a widget draws by default is one this backend can bake`() {
        val missing = DefaultGlyphs.Codepoints
            .filterNot { codepoint -> StbFonts.Codepoints.any { codepoint in it } }
            .map { DefaultGlyphs.describe(it) }

        assertEquals(emptyList<String>(), missing, "defaults StbFonts.Codepoints cannot make")
    }

    @Test
    fun `and one the font really has so it is a shape and not a blank square`() {
        val fonts = StbFonts().apply { register("body", ttf, listOf(16)) }
        val body = TextStyle(family = "body", size = 16f)
        // A character the font lacks comes back as a question mark, which has a width of its own —
        // so the comparison is against that rather than against nothing.
        val unknown = fonts.measure("中", body).size.width

        val blank = DefaultGlyphs.BeyondAscii
            .filter { codepoint -> StbFonts.Codepoints.any { codepoint in it } }
            .filter { fonts.measure(text(it), body).size.width == unknown }
            .map { DefaultGlyphs.describe(it) }

        assertEquals(emptyList<String>(), blank, "baked, but the font has no shape for them")
    }

    @Test
    fun `the list is the real defaults rather than a list somebody wrote down`() {
        // Cheap proof that it is asking the widgets: the item card's marks are in it, and they are
        // the ones this test exists for.
        assertTrue(DefaultGlyphs.All.contains("▼"), "the item card's 'worse' mark")
        assertTrue(DefaultGlyphs.All.contains("▲"), "and its 'better' one")
    }

    private fun text(codepoint: Int) = String(Character.toChars(codepoint))
}
