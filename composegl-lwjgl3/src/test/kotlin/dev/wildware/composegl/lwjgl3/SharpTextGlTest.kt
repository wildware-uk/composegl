package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11

/**
 * Text on a screen at twice the design size, judged by its pixels: each letter is made at the
 * screen's size, so its edges are as hard as the font's own at that size, not a small letter stretched.
 */
class SharpTextGlTest {

    private fun bytes(path: String) = requireNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }.readBytes()

    /** The frame, as brightness 0 to 255, y down from the top. */
    private fun draw(scale: Float, size: Int, text: String): IntArray = Gl.render {
        val fonts = StbFonts().apply { register("body", bytes("/fonts/DejaVuSans.ttf"), listOf(18, 24, 48, 60)) }
        val canvas = GlCanvas(fonts)
        try {
            val design = Gl.size / scale
            Gl.gl.clearColor(0f, 0f, 0f, 1f)
            Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(Viewport(Size(design, design), Size(Gl.size.toFloat(), Gl.size.toFloat()), ScalePolicy.Fit))
            canvas.text(fonts.measure(text, TextStyle(family = "body", size = size.toFloat())), 20f / scale, 20f / scale, Colour.White)
            canvas.end()
            Gl.readPixels(Gl.size, Gl.size).map { it and 0xFF }.toIntArray()
        } finally {
            canvas.close()
            fonts.close()
        }
    }

    /** Pixels neither background nor ink, along every row the letter crosses. */
    private fun softPixels(frame: IntArray): Int = frame.count { it in 24..231 }

    /** The inked box, cut out, so letters drawn a pixel apart still compare. */
    private fun ink(frame: IntArray): List<List<Int>> {
        val rows = (0 until Gl.size).filter { y -> (0 until Gl.size).any { frame[y * Gl.size + it] > 0 } }
        val columns = (0 until Gl.size).filter { x -> (0 until Gl.size).any { frame[it * Gl.size + x] > 0 } }
        return rows.map { y -> columns.map { x -> frame[y * Gl.size + x] } }
    }

    @Test
    fun `a letter at twice the size has the edges of the font drawn at that size`() {
        val scaled = draw(scale = 2f, size = 24, text = "l")
        val native = draw(scale = 1f, size = 48, text = "l")

        val soft = softPixels(scaled)
        // A vertical stroke 48 pixels tall: a soft pixel at most each side per row, and the rounded ends.
        assertTrue(soft <= softPixels(native) + 4, "the stroke has $soft soft pixels, the font at 48 has ${softPixels(native)}")
        assertEquals(ink(native), ink(scaled), "the letter should be exactly the font's own ink at 48")
    }

    /** The frame with the text nudged [offset] design units down, so it lands differently between pixels. */
    private fun drawOffset(scale: Float, size: Int, text: String, offset: Float): IntArray = Gl.render {
        val fonts = StbFonts().apply { register("body", bytes("/fonts/DejaVuSans.ttf"), listOf(18, 24, 48, 60)) }
        val canvas = GlCanvas(fonts)
        try {
            val design = Gl.size / scale
            Gl.gl.clearColor(0f, 0f, 0f, 1f)
            Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(Viewport(Size(design, design), Size(Gl.size.toFloat(), Gl.size.toFloat()), ScalePolicy.Fit))
            canvas.text(fonts.measure(text, TextStyle(family = "body", size = size.toFloat())), 20f / scale, 20f / scale + offset, Colour.White)
            canvas.end()
            Gl.readPixels(Gl.size, Gl.size).map { it and 0xFF }.toIntArray()
        } finally {
            canvas.close()
            fonts.close()
        }
    }

    @Test
    fun `a hyphen on a screen a third of the design keeps its stroke wherever it lands`() {
        // A 60-pixel hyphen shrunk by the GPU to a third is two texels wide: whether its row of texels
        // is sampled at all depends on where the glyph falls between pixels, so it is asked for at
        // eight positions. A copy made at the screen's own size has the stroke at every one.
        val inked = (0 until 8).map { step ->
            drawOffset(scale = 0.3f, size = 60, text = "-", offset = step / 8f * (1f / 0.3f)).count { it > 0 }
        }
        val native = drawOffset(scale = 1f, size = 18, text = "-", offset = 0f).count { it > 0 }

        assertTrue(inked.min() >= native / 2, "the hyphen is drawn at every position: $inked, the font at 18 inks $native")
    }
}
