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
    fun `the named colours are opaque and are the colour they say`() {
        val named = mapOf(
            Colour.Red to Triple(255, 0, 0),
            Colour.Green to Triple(0, 255, 0),
            Colour.Blue to Triple(0, 0, 255),
            Colour.Yellow to Triple(255, 255, 0),
            Colour.Cyan to Triple(0, 255, 255),
            Colour.Magenta to Triple(255, 0, 255),
            Colour.Orange to Triple(255, 128, 0),
        )

        named.forEach { (colour, channels) ->
            val (red, green, blue) = channels
            assertEquals(255, colour.alpha, "$colour should be opaque")
            assertEquals(red, colour.red, "$colour red")
            assertEquals(green, colour.green, "$colour green")
            assertEquals(blue, colour.blue, "$colour blue")
        }
    }

    @Test
    fun `the greys are grey, and get lighter in the order they are named`() {
        listOf(Colour.DarkGrey, Colour.Grey, Colour.LightGrey).forEach {
            assertEquals(it.red, it.green, "$it is not grey")
            assertEquals(it.green, it.blue, "$it is not grey")
            assertEquals(255, it.alpha, "$it should be opaque")
        }

        assertTrue(Colour.DarkGrey.red < Colour.Grey.red)
        assertTrue(Colour.Grey.red < Colour.LightGrey.red)
    }

    @Test
    fun `transparent is transparent`() {
        assertTrue(Colour.Transparent.isTransparent)
        assertTrue(Colour.White.scaleAlpha(0f).isTransparent)
    }
}
