package uk.wildware.composegl.lwjgl3

import uk.wildware.composegl.ui.effect.ShaderEffect
import uk.wildware.composegl.ui.effect.ShaderSource
import uk.wildware.composegl.ui.effect.Uniform
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.layout.ScalePolicy
import uk.wildware.composegl.ui.layout.Viewport
import uk.wildware.composegl.ui.text.TextStyle
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

    /** The plainest effect there is: the picture, unchanged, faded by whatever is in force. */
    private val passThrough = ShaderEffect(
        ShaderSource("pass-through", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord) * u_alpha; }"),
    )
    private val blue = Colour.rgb(0x0000FF)
    private val body = TextStyle(family = "body", size = 48f)

    /** What one drawn frame came out as. */
    private class Frame(private val pixels: IntArray, val drawCalls: Int) {

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
            Frame(Gl.readPixels(Gl.size, Gl.size), canvas.drawCalls)
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

        assertTrue(frame.drawCalls <= 2, "a hundred boxes should batch, took ${frame.drawCalls}")
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

        assertEquals(1, frame.drawCalls, "a box and a word should not need two draw calls")
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
                Frame(Gl.readPixels(Gl.size, Gl.size), canvas.drawCalls)
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
    fun `a layer lands exactly where drawing straight onto the screen would have`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) { rect(Rect.of(60f, 60f, 60f, 60f), red) }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds)
        }

        assertColour(red, frame.at(90, 90), "the middle of the rectangle")
        assertColour(Colour.Black, frame.at(50, 50), "inside the layer but outside the rectangle")
        assertColour(Colour.Black, frame.at(20, 20), "outside the layer altogether")
    }

    @Test
    fun `a layer fades as one object rather than as a pile of parts`() {
        // The whole reason layers exist. Two overlapping opaque boxes at half opacity: drawn
        // straight, the overlap is two fades stacked and the red shows through the blue. Through a
        // layer they are one object, so the overlap is only the blue.
        val bounds = Rect.of(0f, 0f, 200f, 200f)
        val frame = draw {
            pushAlpha(0.5f)
            val picture = layer(bounds) {
                rect(Rect.of(20f, 20f, 100f, 100f), red)
                rect(Rect.of(60f, 60f, 100f, 100f), blue)
            }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds)
            popAlpha()
        }

        val overlap = frame.at(90, 90)
        val lit = overlap shr 16 and 0xFF
        assertTrue(lit < 16, "the red should be hidden under the blue, but the overlap is $lit red")
        val blueness = overlap and 0xFF
        assertTrue(abs(blueness - 128) < 24, "expected half the blue, got $blueness")
    }

    @Test
    fun `a layer inside a layer draws the same as one on its own`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val outer = layer(bounds) {
                val inner = layer(Rect.of(60f, 60f, 60f, 60f)) {
                    rect(Rect.of(60f, 60f, 60f, 60f), red)
                }
                drawLayer(checkNotNull(inner) { "this driver gave us no layer" }, Rect.of(60f, 60f, 60f, 60f))
            }
            drawLayer(checkNotNull(outer) { "this driver gave us no layer" }, bounds)
        }

        assertColour(red, frame.at(90, 90), "the middle of the rectangle")
        assertColour(Colour.Black, frame.at(50, 50), "outside it")
    }

    @Test
    fun `a clip inside a layer cuts in the layer's own pixels`() {
        // The scissor has a different origin and a different height inside a layer. Getting that
        // wrong cuts the wrong half off, which is invisible until somebody clips inside an effect.
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) {
                pushClip(Rect.of(40f, 40f, 60f, 120f))
                rect(Rect.of(40f, 40f, 120f, 120f), red)
                popClip()
            }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds)
        }

        assertColour(red, frame.at(70, 100), "the left half, which the clip kept")
        assertColour(Colour.Black, frame.at(130, 100), "the right half, which it cut")
    }

    @Test
    fun `the frame carries on normally after a layer`() {
        // A layer binds a framebuffer, moves the viewport, changes the projection and turns the
        // scissor off. Everything after it is drawn on the assumption that all four came back.
        val bounds = Rect.of(0f, 0f, 100f, 100f)
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 300f, 200f))
            val picture = layer(bounds) { rect(bounds, blue) }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds)
            rect(Rect.of(150f, 150f, 100f, 100f), red)
            popClip()
        }

        assertColour(blue, frame.at(50, 50), "the layer")
        assertColour(red, frame.at(200, 180), "drawn after it, inside the clip")
        assertColour(Colour.Black, frame.at(200, 220), "drawn after it, outside the clip")
    }

    @Test
    fun `a layer nobody could draw is refused rather than half drawn`() {
        var asked = false
        val frame = draw {
            val picture = layer(Rect.of(0f, 0f, 0f, 50f)) { asked = true }
            assertEquals(null, picture, "an empty layer has no picture")
            rect(Rect.of(10f, 10f, 50f, 50f), red)
        }

        assertTrue(!asked, "nothing should have been drawn for a layer that was refused")
        assertColour(red, frame.at(30, 30), "the caller's own drawing still works")
    }

    @Test
    fun `an effect draws where the picture would have gone`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) { rect(bounds, red) }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds, passThrough)
        }

        assertColour(red, frame.at(100, 100), "the middle of the effect")
        assertColour(red, frame.at(42, 42), "its top-left corner")
        assertColour(Colour.Black, frame.at(30, 100), "left of it")
        assertColour(Colour.Black, frame.at(170, 100), "right of it")
        assertColour(Colour.Black, frame.at(100, 170), "below it")
    }

    @Test
    fun `an effect is handed the picture the interface drew`() {
        // Half the layer painted, half not. A shader that samples and passes through has to come
        // out as the same two halves, the right way up.
        val bounds = Rect.of(0f, 0f, 200f, 200f)
        val frame = draw {
            val picture = layer(bounds) { rect(Rect.of(0f, 0f, 200f, 100f), red) }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds, passThrough)
        }

        assertColour(red, frame.at(100, 40), "the painted half")
        assertColour(Colour.Black, frame.at(100, 160), "the empty half")
    }

    @Test
    fun `a shader reads the uniforms it was given`() {
        val bounds = Rect.of(0f, 0f, 200f, 200f)
        val tint = ShaderEffect(
            source = ShaderSource(
                "tint",
                """
                uniform vec4 u_tint;
                void main() {
                    gl_FragColor = vec4(u_tint.rgb * u_tint.a, u_tint.a) * u_alpha;
                }
                """.trimIndent(),
            ),
            uniforms = mapOf("u_tint" to Uniform.of(blue)),
        )
        val frame = draw {
            val picture = layer(bounds) { rect(bounds, red) }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds, tint)
        }

        assertColour(blue, frame.at(100, 100), "the shader's own colour, not the picture's")
    }

    @Test
    fun `an effect is faded by the opacity in force`() {
        val bounds = Rect.of(0f, 0f, 200f, 200f)
        val frame = draw {
            pushAlpha(0.5f)
            val picture = layer(bounds) { rect(bounds, red) }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds, passThrough)
            popAlpha()
        }

        val lit = frame.at(100, 100) shr 16 and 0xFF
        assertTrue(abs(lit - 128) < 24, "expected half the red, got $lit")
    }

    @Test
    fun `the frame carries on normally after an effect`() {
        // The shader leaves a different program bound and a different blend set. Whatever the
        // interface draws next is drawn on the assumption that both came back.
        val bounds = Rect.of(0f, 0f, 100f, 100f)
        val frame = draw {
            val picture = layer(bounds) { rect(bounds, red) }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds, passThrough)
            rect(Rect.of(150f, 150f, 100f, 100f), blue)
        }

        assertColour(red, frame.at(50, 50), "the effect")
        assertColour(blue, frame.at(200, 200), "an ordinary rectangle drawn after it")
    }

    @Test
    fun `a shader that will not compile says so, with its name and the driver's words`() {
        assumeTrue(Gl.available, "no display; this test needs a real GL context")
        val broken = ShaderEffect(ShaderSource("broken", "void main() { this is not GLSL }"))

        val thrown = assertThrows<IllegalArgumentException> {
            draw {
                val bounds = Rect.of(0f, 0f, 100f, 100f)
                val picture = layer(bounds) { rect(bounds, red) }
                drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds, broken)
            }
        }

        assertTrue(
            thrown.message.orEmpty().contains("broken"),
            "the message should name the shader, but it was: ${thrown.message}",
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
