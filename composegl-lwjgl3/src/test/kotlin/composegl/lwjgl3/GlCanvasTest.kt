package composegl.lwjgl3

import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.geometry.Size
import composegl.ui.graphics.Colour
import composegl.ui.layout.ScalePolicy
import composegl.ui.layout.Viewport
import composegl.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.lwjgl.opengl.GL11
import kotlin.math.abs

/**
 * The parts of the renderer only a GPU can answer for: how many times it talked to the driver, and
 * what came out in the pixels.
 *
 * These are the LibGDX backend's questions asked again of a renderer that shares none of its code.
 * An answer that differs between the two is the toolkit having assumed something about one of them.
 */
class GlCanvasTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val body = TextStyle(family = "body", size = 48f)

    /** What one drawn frame came out as. */
    private class Frame(private val pixels: IntArray, val renderCalls: Int) {

        /** The colour at a point in the toolkit's coordinates: y down from the top. */
        fun at(x: Int, y: Int): Int = pixels[y * Gl.size + x]
    }

    /** Draws [content] into the shared context and reads the frame back. */
    private fun draw(fonts: StbFonts? = null, content: GlCanvas.(StbFonts?) -> Unit): Frame = Gl.render {
        val canvas = GlCanvas(fonts)
        try {
            GL11.glClearColor(0f, 0f, 0f, 1f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            canvas.content(fonts)
            canvas.end()
            Frame(Gl.readPixels(Gl.size, Gl.size), canvas.renderCalls)
        } finally {
            canvas.close()
        }
    }

    private fun fonts(): StbFonts = StbFonts().apply {
        register("body", javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf")!!.readBytes(), listOf(48))
    }

    private fun assertColour(expected: Colour, actual: Int, message: String) {
        val close = abs(expected.red - (actual shr 16 and 0xFF)) < 24 &&
            abs(expected.green - (actual shr 8 and 0xFF)) < 24 &&
            abs(expected.blue - (actual and 0xFF)) < 24
        assertTrue(close, "$message: expected %06X, got %06X".format(expected.argb and 0xFFFFFF, actual))
    }

    @Test
    fun `a rectangle lands where the toolkit said, with y down from the top`() {
        val frame = draw { rect(Rect.of(10f, 10f, 100f, 40f), red) }

        assertColour(red, frame.at(50, 20), "inside the rectangle")
        assertColour(Colour.Black, frame.at(50, 80), "below it, where nothing was drawn")
    }

    @Test
    fun `a hundred nodes cost a handful of draw calls`() {
        val frame = draw {
            repeat(100) { index ->
                rect(Rect.of(index % 10 * 20f, index / 10 * 20f, 18f, 18f), red, corner = 4f)
            }
        }

        assertTrue(frame.renderCalls <= 2, "a hundred boxes should batch, took ${frame.renderCalls}")
    }

    @Test
    fun `a panel and the label on it are one draw call`() {
        // The whole reason the glyph atlas carries a white block: solid colour and letters come
        // from the same texture, so nothing has to be flushed between them.
        val fonts = fonts()
        val frame = draw(fonts) {
            rect(Rect.of(10f, 10f, 300f, 80f), blue, corner = 12f)
            text(it!!.measure("Hi", body), Offset(24f, 24f), red)
        }

        assertEquals(1, frame.renderCalls, "a box and a word should not need two draw calls")
    }

    @Test
    fun `a picture is drawn the right way up`() {
        // Two rows, two colours. A flat colour is exactly the texture that cannot tell you your
        // texture coordinates are upside down.
        val frame = Gl.render {
            val canvas = GlCanvas()
            val rows = byteArrayOf(
                0xFF.toByte(), 0, 0, 0xFF.toByte(),
                0, 0, 0xFF.toByte(), 0xFF.toByte(),
            )
            val texture = GlTexture.rgba(1, 2, rows, smooth = false)
            try {
                GL11.glClearColor(0f, 0f, 0f, 1f)
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.image(texture, Rect.of(10f, 10f, 40f, 40f))
                canvas.end()
                Frame(Gl.readPixels(Gl.size, Gl.size), canvas.renderCalls)
            } finally {
                texture.close()
                canvas.close()
            }
        }

        assertColour(red, frame.at(30, 15), "the texture's first row belongs at the top")
        assertColour(blue, frame.at(30, 45), "and its last row at the bottom")
    }

    @Test
    fun `a fan fills the shape it describes`() {
        // The hub, then three corners: the top-left half of a square, cut across the diagonal.
        val frame = draw {
            fan(floatArrayOf(0f, 0f, 200f, 0f, 200f, 200f, 0f, 200f), red)
        }

        assertColour(red, frame.at(60, 40), "above the diagonal, inside the fan")
        assertColour(red, frame.at(160, 180), "and below it, in the fan's second triangle")
        assertColour(Colour.Black, frame.at(300, 40), "outside it, where nothing was drawn")
    }

    @Test
    fun `a fan is drawn at its colour's own opacity`() {
        val frame = draw {
            rect(Rect.of(0f, 0f, 200f, 200f), red)
            fan(floatArrayOf(0f, 0f, 200f, 0f, 200f, 200f, 0f, 200f), blue.scaleAlpha(0.5f))
        }

        val over = frame.at(60, 40)
        assertTrue(
            abs((over shr 16 and 0xFF) - 128) < 24 && abs((over and 0xFF) - 128) < 24,
            "a fan at half opacity should half cover what is under it, got %06X".format(over and 0xFFFFFF),
        )
    }

    @Test
    fun `a fan obeys the clip and the alpha stack`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 100f, 100f))
            fan(floatArrayOf(0f, 0f, 300f, 0f, 300f, 300f, 0f, 300f), red)
            popClip()
        }

        assertColour(red, frame.at(50, 20), "inside the clip")
        assertColour(Colour.Black, frame.at(200, 20), "outside it")
    }

    @Test
    fun `a clip stops drawing outside it`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 100f, 100f))
            rect(Rect.of(0f, 0f, 300f, 300f), red)
            popClip()
        }

        assertColour(red, frame.at(50, 50), "inside the clip")
        assertColour(Colour.Black, frame.at(150, 50), "outside it")
    }

    @Test
    fun `nested clips intersect`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 200f, 200f))
            pushClip(Rect.of(100f, 0f, 200f, 200f))
            rect(Rect.of(0f, 0f, 400f, 400f), red)
            popClip()
            popClip()
        }

        assertColour(Colour.Black, frame.at(50, 50), "left of the inner clip")
        assertColour(red, frame.at(150, 50), "in the overlap")
        assertColour(Colour.Black, frame.at(250, 50), "right of the outer clip")
    }

    @Test
    fun `a clip is lifted when it is popped`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 50f, 50f))
            popClip()
            rect(Rect.of(100f, 100f, 50f, 50f), red)
        }

        assertColour(red, frame.at(120, 120), "the scissor should be gone")
    }

    @Test
    fun `opacity multiplies down the tree`() {
        val frame = draw {
            pushAlpha(0.5f)
            pushAlpha(0.5f)
            rect(Rect.of(10f, 10f, 100f, 100f), red)
            popAlpha()
            popAlpha()
        }

        // A quarter of full red over black, give or take a rounding difference.
        val lit = frame.at(50, 50) shr 16 and 0xFF
        assertTrue(abs(lit - 64) < 24, "expected about a quarter of red, got $lit")
    }

    @Test
    fun `the baseline is where the layout said it is`() {
        // The contract the whole of text layout rests on: drawing matches what was measured. A
        // capital letter sits on the baseline, so the bottom of an H is where it was promised.
        val fonts = fonts()
        val layout = fonts.measure("HHHH", body)
        val frame = draw(fonts) { text(layout, Offset(20f, 20f), red) }

        val bottom = (20..200).last { y -> (20 until 200).any { x -> (frame.at(x, y) shr 16 and 0xFF) > 128 } }
        val promised = 20f + layout.firstBaseline
        assertTrue(
            abs(bottom - promised) <= 2f,
            "the letters end at $bottom but the layout promised a baseline at $promised",
        )
    }

    @Test
    fun `raw hands over the frame, already in design coordinates`() {
        var seen: GlFrame? = null
        draw { raw { seen = it as GlFrame } }

        val frame = checkNotNull(seen) { "raw never called back" }
        assertEquals(viewport, frame.viewport)
        // The projection maps the design width onto the clip cube's two units.
        assertEquals(2f / viewport.design.width, frame.projection[0])
    }

    @Test
    fun `an unbalanced clip is caught at the end of the frame`() {
        // The skip has to happen out here: inside `assertThrows`, a skip is an exception that is
        // not the expected one, so a machine with no display would report a failure instead.
        assumeTrue(Gl.available, "no display; this test needs a real GL context")
        assertThrows<IllegalStateException> {
            Gl.render {
                val canvas = GlCanvas()
                try {
                    canvas.begin(viewport)
                    canvas.pushClip(Rect.of(0f, 0f, 10f, 10f))
                    canvas.end()
                } finally {
                    canvas.close()
                }
            }
        }
    }
}
