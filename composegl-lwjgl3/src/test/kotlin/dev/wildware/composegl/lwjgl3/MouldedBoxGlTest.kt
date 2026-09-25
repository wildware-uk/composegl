package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11

/**
 * An outline outside the box and shade falling inside it, judged by the pixels of a real GPU.
 *
 * Both are one shader asking how far a pixel is from the edge of a rounded box, so both are wrong
 * in the same way if the sign of a number is wrong: an outline eating the fill, a shade drawn as a
 * shadow. These read the pixels either side of the edge and say which happened.
 */
class MouldedBoxGlTest {

    @BeforeEach
    fun requireGl() = assumeTrue(Gl.available, "no display; these tests need a real GL context")

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val green = Colour.rgb(0x40C040)
    private val ink = Colour.rgb(0x201008)

    /** The box every test draws: well inside the screen, with room outside it for an outline. */
    private val box = Rect.of(40f, 40f, 120f, 80f)

    private fun draw(content: GlCanvas.() -> Unit): IntArray = Gl.render {
        val canvas = GlCanvas(null)
        try {
            Gl.gl.clearColor(0f, 0f, 0f, 1f)
            Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            canvas.content()
            canvas.end()
            Gl.readPixels(Gl.size, Gl.size)
        } finally {
            canvas.close()
        }
    }

    private fun IntArray.at(x: Int, y: Int): Int = this[y * Gl.size + x]

    private fun IntArray.red(x: Int, y: Int) = at(x, y) shr 16 and 0xFF

    private fun IntArray.green(x: Int, y: Int) = at(x, y) shr 8 and 0xFF

    private fun near(expected: Colour, actual: Int) =
        kotlin.math.abs(expected.red - (actual shr 16 and 0xFF)) < 24 &&
            kotlin.math.abs(expected.green - (actual shr 8 and 0xFF)) < 24 &&
            kotlin.math.abs(expected.blue - (actual and 0xFF)) < 24

    @Test
    fun `an outline outside the box takes nothing off the fill`() {
        val inside = draw {
            rect(box, green, corner = 10f)
            border(box, ink, width = 6f, corner = 10f)
        }
        val outside = draw {
            rect(box, green, corner = 10f)
            borderOutside(box, ink, width = 6f, corner = 10f)
        }

        assertTrue(near(ink, inside.at(42, 80)), "the plain border is drawn inside the box's own edge")
        assertTrue(near(green, outside.at(42, 80)), "the outside one leaves that pixel filled")
        assertTrue(near(ink, outside.at(37, 80)), "and lands beyond the edge instead")
        assertTrue(near(Colour.Black, outside.at(30, 80)), "but no further than its width")
    }

    @Test
    fun `an outline outside a box the same size as another is the same ring moved out`() {
        val frame = draw { borderOutside(box, ink, width = 4f, corner = 0f) }

        assertTrue(near(ink, frame.at(38, 80)), "outside the left edge")
        assertTrue(near(Colour.Black, frame.at(60, 80)), "and nothing inside the box, which was never filled")
    }

    @Test
    fun `shade falls inside the box rather than outside it`() {
        val frame = draw {
            rect(box, green, corner = 10f)
            innerShade(box, ink, depth = 12f, corner = 10f)
        }

        assertTrue(frame.green(44, 80) < frame.green(100, 80), "the inside edge is darker than the middle")
        assertTrue(near(Colour.Black, frame.at(36, 80)), "and nothing was cast outside the box")
    }

    @Test
    fun `an offset gathers the shade along one edge`() {
        val fromBelow = draw {
            rect(box, green, corner = 10f)
            // Offset down the screen: the shade comes from below, so it gathers along the top.
            innerShade(box, ink, depth = 14f, corner = 10f, offsetY = 14f)
        }

        val top = fromBelow.green(100, 44)
        val bottom = fromBelow.green(100, 116)
        assertTrue(top < bottom - 20, "the top inside edge is the shaded one: top $top, bottom $bottom")
    }

    @Test
    fun `a whole moulded button is still one draw call`() {
        var calls = 0
        Gl.render {
            val canvas = GlCanvas(null)
            try {
                Gl.gl.clearColor(0f, 0f, 0f, 1f)
                Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.rect(box, green, Corners.all(10f))
                canvas.innerShade(box, Colour.White.scaleAlpha(0.35f), depth = 6f, corners = Corners.all(10f), offsetY = 6f)
                canvas.innerShade(box, ink.scaleAlpha(0.5f), depth = 6f, corners = Corners.all(10f), offsetY = -6f)
                canvas.borderOutside(box, ink, width = 4f, corners = Corners.all(10f))
                canvas.end()
                calls = canvas.drawCalls
            } finally {
                canvas.close()
            }
        }

        assertEquals(1, calls, "fill, two shades and an outline all batch into one draw")
    }

    @Test
    fun `a gradient of three colours shows the middle one in the middle`() {
        val fonts = StbFonts().apply {
            register("body", javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf")!!.readBytes(), listOf(16))
        }
        val top = Colour.rgb(0xFF0000)
        val middle = Colour.rgb(0x00FF00)
        val bottom = Colour.rgb(0x0000FF)
        val frame = Gl.render {
            val canvas = GlCanvas(fonts)
            try {
                Gl.gl.clearColor(0f, 0f, 0f, 1f)
                Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.rect(box, Brush.evenly(listOf(top, middle, bottom)))
                canvas.end()
                Gl.readPixels(Gl.size, Gl.size)
            } finally {
                canvas.close()
                fonts.close()
            }
        }

        assertTrue(near(top, frame.at(100, 42)), "red along the top")
        assertTrue(near(middle, frame.at(100, 80)), "green across the middle, which two colours could never do")
        assertTrue(near(bottom, frame.at(100, 118)), "blue along the bottom")
    }

    @Test
    fun `a run of stops still batches with the panel behind it`() {
        val fonts = StbFonts().apply {
            register("body", javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf")!!.readBytes(), listOf(16))
        }
        var calls = 0
        Gl.render {
            val canvas = GlCanvas(fonts)
            try {
                Gl.gl.clearColor(0f, 0f, 0f, 1f)
                Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.rect(Rect.of(10f, 10f, 200f, 160f), ink)
                canvas.rect(box, Brush.evenly(listOf(green, Colour.White, ink)), Corners.all(8f))
                canvas.end()
                calls = canvas.drawCalls
            } finally {
                canvas.close()
                fonts.close()
            }
        }

        assertEquals(1, calls, "the strip is on the page solid colour comes from")
    }

    @Test
    fun `a stop placed off centre puts its colour where it was asked for`() {
        val fonts = StbFonts().apply {
            register("body", javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf")!!.readBytes(), listOf(16))
        }
        val white = Colour.White
        val frame = Gl.render {
            val canvas = GlCanvas(fonts)
            try {
                Gl.gl.clearColor(0f, 0f, 0f, 1f)
                Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                // White only in the top fifth, then green the rest of the way down.
                canvas.rect(
                    box,
                    Brush.ramp(Brush.Stop(0f, white), Brush.Stop(0.2f, green), Brush.Stop(1f, green)),
                )
                canvas.end()
                Gl.readPixels(Gl.size, Gl.size)
            } finally {
                canvas.close()
                fonts.close()
            }
        }

        // Still on its way from white at the top, where an evenly spaced run would only be starting.
        assertTrue(frame.at(100, 44) and 0xFF > 150, "close to white at the top: %06X".format(frame.at(100, 44)))
        assertTrue(near(green, frame.at(100, 70)), "green by a third of the way down, not halfway")
        assertTrue(near(green, frame.at(100, 115)), "and green to the bottom")
        assertTrue(white.blue > 0, "the run began at white")
    }
}
