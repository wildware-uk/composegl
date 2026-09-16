package dev.wildware.composegl.debug

import dev.wildware.composegl.ui.graphics.Colour
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The small pieces the windows are remembered and written with: the store's text, the readout beside
 * a slider, and the hex beside a colour. Each one has an awkward case that a window would only show
 * as a wrong number on the screen.
 */
class DebugWindowStoreTest {

    @Test
    fun `what a store writes is what it reads back`() {
        val values = mapOf(
            "window:Physics" to "20.0;30.0;auto;auto;open",
            "section:Physics:Advanced" to "open",
        )

        assertEquals(values, DebugWindowStoreText.read(DebugWindowStoreText.write(values)))
    }

    @Test
    fun `a title with an equals sign or a line break in it survives the trip`() {
        val values = mapOf(
            "window:a=b" to "1.0;2.0;auto;auto;open",
            "window:two\nlines" to "3.0;4.0;auto;auto;collapsed",
            "window:back\\slash" to "5.0;6.0;auto;auto;open",
        )

        assertEquals(values, DebugWindowStoreText.read(DebugWindowStoreText.write(values)))
    }

    @Test
    fun `rubbish in the file is ignored line by line`() {
        val read = DebugWindowStoreText.read("window:Physics=20.0;30.0;auto;auto;open\nnonsense\n\n")

        assertEquals(mapOf("window:Physics" to "20.0;30.0;auto;auto;open"), read)
    }

    @Test
    fun `a readout has as many places as the step it moves in`() {
        assertEquals("9.80", formatTweak(9.8f, 0f))
        assertEquals("9.8", formatTweak(9.81f, 0.1f))
        assertEquals("0.05", formatTweak(0.05f, 0.05f))
        assertEquals("12", formatTweak(12f, 1f))
        assertEquals("-3.5", formatTweak(-3.46f, 0.1f))
        assertEquals("0.0", formatTweak(-0.001f, 0.1f), "a number that rounds to nothing is not minus nothing")
    }

    @Test
    fun `a colour is written the way a skin file writes it`() {
        assertEquals("#204060FF", hexOf(Colour.rgb(0x204060), alpha = true))
        assertEquals("#204060", hexOf(Colour.rgb(0x204060), alpha = false))
        assertEquals("#0A0B0C80", hexOf(Colour.argb(0x800A0B0C), alpha = true))
    }
}
