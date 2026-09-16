package dev.wildware.composegl.gdx

import dev.wildware.composegl.testing.DefaultGlyphs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The defaults the toolkit ships and the characters this backend bakes, held against each other.
 *
 * The same check the raw OpenGL backend makes, because the mistake is the backend's to make: a
 * character outside the baked set is an empty box on screen and nothing anywhere says so. It is how
 * `ItemMarks.Arrows` came to default to `▼` while no shipped atlas had `▼` in it.
 */
class DefaultGlyphsTest {

    /** What LibGDX's own `DEFAULT_CHARS` is certain to cover, so this only judges what we add to it. */
    private val ascii = 0x20..0x7E

    @Test
    fun `every character a widget draws by default is one this backend bakes`() {
        val baked = GdxFonts.Typography.map { it.code }.toSet()
        val missing = DefaultGlyphs.Codepoints
            .filterNot { it in ascii || it in baked }
            .map { DefaultGlyphs.describe(it) }

        assertEquals(emptyList<String>(), missing, "defaults GdxFonts.Typography does not add")
    }
}
