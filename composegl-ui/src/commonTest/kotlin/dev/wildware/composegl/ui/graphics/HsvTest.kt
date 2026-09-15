package dev.wildware.composegl.ui.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Hue, saturation and value to a colour and back, and colours written and read as hex. */
class HsvTest {

    @Test
    fun `the corners of the wheel are the pure colours`() {
        assertEquals(Colour.Red, Hsv(0f, 1f, 1f).toColour())
        assertEquals(Colour.Yellow, Hsv(60f, 1f, 1f).toColour())
        assertEquals(Colour.Green, Hsv(120f, 1f, 1f).toColour())
        assertEquals(Colour.Cyan, Hsv(180f, 1f, 1f).toColour())
        assertEquals(Colour.Blue, Hsv(240f, 1f, 1f).toColour())
        assertEquals(Colour.Magenta, Hsv(300f, 1f, 1f).toColour())
        assertEquals(Colour.Red, Hsv(360f, 1f, 1f).toColour(), "360 is back round at red")
    }

    @Test
    fun `no saturation is grey and no value is black whatever the hue`() {
        assertEquals(Colour.White, Hsv(200f, 0f, 1f).toColour())
        assertEquals(Colour.Black, Hsv(200f, 1f, 0f).toColour())
        assertEquals(Colour(255, 128, 128, 128), Hsv(90f, 0f, 0.5f).toColour())
    }

    @Test
    fun `alpha carries through`() {
        assertEquals(0x80, Hsv(0f, 1f, 1f, alpha = 128 / 255f).toColour().alpha)
        assertEquals(128 / 255f, Hsv.of(Colour.Red.withAlpha(128)).alpha)
    }

    @Test
    fun `every colour goes to hsv and back unchanged`() {
        for (red in 0..255 step 17) for (green in 0..255 step 15) for (blue in 0..255 step 51) {
            val colour = Colour(255, red, green, blue)
            assertEquals(colour, Hsv.of(colour).toColour())
        }
    }

    @Test
    fun `a colour reads as its hue saturation and value`() {
        val orange = Hsv.of(Colour.Orange)
        assertEquals(30f, orange.hue, 0.5f)
        assertEquals(1f, orange.saturation)
        assertEquals(1f, orange.value)
    }

    @Test
    fun `a grey takes the hue it is given and black the saturation`() {
        assertEquals(210f, Hsv.of(Colour.Grey, hue = 210f).hue)
        assertEquals(0.4f, Hsv.of(Colour.Black, saturation = 0.4f).saturation)
    }

    @Test
    fun `hex is written the way a skin writes it`() {
        assertEquals("#FF8000", Colour.Orange.toHex())
        assertEquals("#80FF8000", Colour.Orange.withAlpha(0x80).toHex())
        assertEquals("#FF8000", Colour.Orange.withAlpha(0x80).toHex(alpha = false))
        assertEquals("#FFFF8000", Colour.Orange.toHex(alpha = true))
    }

    @Test
    fun `hex is read in every form a player types`() {
        assertEquals(Colour.Orange, Colour.fromHex("#FF8000"))
        assertEquals(Colour.Orange, Colour.fromHex("ff8000"))
        assertEquals(Colour.Orange, Colour.fromHex("  #ff8000 "))
        assertEquals(Colour.Orange.withAlpha(0x80), Colour.fromHex("#80FF8000"))
        assertEquals(Colour.rgb(0xFFAA00), Colour.fromHex("#FA0"))
    }

    @Test
    fun `anything that is not a colour reads as nothing`() {
        assertNull(Colour.fromHex(""))
        assertNull(Colour.fromHex("#"))
        assertNull(Colour.fromHex("#FF80"))
        assertNull(Colour.fromHex("#GG8000"))
        assertNull(Colour.fromHex("#FF80001"))
    }
}
