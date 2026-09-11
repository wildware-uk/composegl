package composegl.ui.graphics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ColourTest {

    @Test
    fun `a colour unpacks the channels it was packed with`() {
        val colour = Colour.argb(0x80FF8040)

        assertEquals(0x80, colour.alpha)
        assertEquals(0xFF, colour.red)
        assertEquals(0x80, colour.green)
        assertEquals(0x40, colour.blue)
    }

    @Test
    fun `rgb is opaque`() {
        assertEquals(255, Colour.rgb(0x336699).alpha)
    }

    @Test
    fun `scaling alpha multiplies, so nested opacity composes`() {
        val half = Colour.White.scaleAlpha(0.5f)
        val quarter = half.scaleAlpha(0.5f)

        assertEquals(127, half.alpha)
        assertEquals(63, quarter.alpha)
        assertEquals(255, quarter.red, "only the alpha changes")
    }

    @Test
    fun `scaling alpha clamps rather than overflowing`() {
        assertEquals(255, Colour.White.scaleAlpha(4f).alpha)
        assertEquals(0, Colour.White.scaleAlpha(-1f).alpha)
    }

    @Test
    fun `lerp ends where it should and clamps outside`() {
        val black = Colour.Black
        val white = Colour.White

        assertEquals(black, black.lerp(white, 0f))
        assertEquals(white, black.lerp(white, 1f))
        assertEquals(127, black.lerp(white, 0.5f).red)
        assertEquals(white, black.lerp(white, 9f), "past the end is the end")
    }

    @Test
    fun `transparent is transparent`() {
        assertTrue(Colour.Transparent.isTransparent)
        assertTrue(Colour.White.scaleAlpha(0f).isTransparent)
    }
}
