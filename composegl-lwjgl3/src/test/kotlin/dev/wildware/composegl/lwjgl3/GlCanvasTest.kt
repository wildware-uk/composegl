package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.backend.FakeTexture
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.effect.Uniform
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.NineRegions
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    /** A frame the host cut for itself: nine handles, and so not a picture this canvas can draw. */
    private val pieces = NineRegions(
        topLeft = FakeTexture(6, 6), top = FakeTexture(1, 6), topRight = FakeTexture(6, 6),
        left = FakeTexture(6, 1), centre = FakeTexture(1, 1), right = FakeTexture(6, 1),
        bottomLeft = FakeTexture(6, 6), bottom = FakeTexture(1, 6), bottomRight = FakeTexture(6, 6),
    )

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
    fun `each corner is cut by its own radius`() {
        // Only the top-left is rounded. Without four radii reaching the shader every corner is
        // drawn the same, so either all four are cut or none are.
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), red, Corners(topLeft = 30f)) }

        assertColour(Colour.Black, frame.at(52, 52), "the top-left is cut away")
        assertColour(red, frame.at(147, 52), "the top-right is square")
        assertColour(red, frame.at(147, 147), "the bottom-right is square")
        assertColour(red, frame.at(52, 147), "the bottom-left is square")
    }

    @Test
    fun `top and bottom are the screen's even though the batch counts y upwards`() {
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), red, Corners.top(30f)) }

        assertColour(Colour.Black, frame.at(52, 52), "top-left cut")
        assertColour(Colour.Black, frame.at(147, 52), "top-right cut")
        assertColour(red, frame.at(52, 147), "bottom-left square")
        assertColour(red, frame.at(147, 147), "bottom-right square")
    }

    @Test
    fun `a border follows the corners it is given`() {
        val frame = draw { border(Rect.of(50f, 50f, 100f, 100f), blue, width = 6f, corners = Corners.right(30f)) }

        assertColour(blue, frame.at(52, 52), "the square top-left is part of the ring")
        assertColour(Colour.Black, frame.at(147, 52), "the rounded top-right is cut away")
        assertColour(Colour.Black, frame.at(100, 100), "and the middle is still empty")
    }

    @Test
    fun `a gradient is cut by each corner's own radius`() {
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), Brush.vertical(red, red), Corners.top(30f)) }

        assertColour(Colour.Black, frame.at(52, 52), "top-left cut")
        assertColour(Colour.Black, frame.at(147, 52), "top-right cut")
        assertColour(red, frame.at(52, 147), "bottom-left square")
        assertColour(red, frame.at(147, 147), "bottom-right square")
    }

    @Test
    fun `a tab and a plain panel beside it are one draw call`() {
        val frame = draw {
            rect(Rect.of(10f, 10f, 60f, 24f), red, Corners.top(8f))
            rect(Rect.of(10f, 34f, 200f, 80f), blue, corner = 4f)
            rect(Rect.of(80f, 10f, 60f, 24f), red, Corners(3f, 9f, 0f, 1f))
        }

        assertEquals(1, frame.drawCalls, "radii are per vertex, so different corners still batch")
    }

    /**
     * What the escape hatch says about itself, and whether the projection it hands over agrees.
     *
     * There is no drawing object on this backend — the drawing object is OpenGL — so what `raw`
     * really hands over is a projection, and the whole of "where do I draw" lives in that matrix.
     * Asserting it directly is asserting the thing itself; a pixel test here would be a test of
     * whatever GL the test itself wrote.
     */
    @Test
    fun `the projection a raw block is handed puts its own origin at the node`() {
        val node = Rect.of(20f, 30f, 40f, 10f)
        val design = Gl.size.toFloat()

        Gl.render {
            val canvas = GlCanvas()
            try {
                canvas.begin(viewport)
                assertTrue(canvas.handsOverRaw, "OpenGL is always there to hand over")
                assertTrue(canvas.movesRawOrigin, "and the projection really is translated")

                // The y this backend wants is measured up from the bottom, which is why `rawY` is
                // not the identity here. It was, until this test; a block that believed the default
                // drew its art flipped about the middle of the frame.
                assertEquals(design, canvas.rawY(0f), "the top of the design space is the far edge")
                assertEquals(0f, canvas.rawY(design), "and the bottom of it is the origin")
                assertEquals(node.left, canvas.rawX(node.left), "x points the same way either way")

                var plain: FloatArray? = null
                var aimed: FloatArray? = null
                canvas.raw { lent -> plain = (lent as GlFrame).projection }
                canvas.raw(node) { lent -> aimed = (lent as GlFrame).projection }
                canvas.end()

                val byHand = clip(checkNotNull(plain), canvas.rawX(node.left), canvas.rawY(node.bottom))
                val itsOwn = clip(checkNotNull(aimed), 0f, 0f)
                assertEquals(byHand.first, itsOwn.first, 1e-5f, "the block's own x origin is the node's left")
                assertEquals(byHand.second, itsOwn.second, 1e-5f, "and its y origin is the node's bottom edge")
            } finally {
                canvas.close()
            }
        }
    }

    /** A point through an orthographic projection, which is a scale and a shift on each axis. */
    private fun clip(projection: FloatArray, x: Float, y: Float) =
        (x * projection[0] + projection[12]) to (y * projection[5] + projection[13])

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

    // --- gradients ---

    private val box = Rect.of(100f, 100f, 200f, 160f)

    /** What [brush] should paint at the middle of the pixel at [x], [y], over [under]. */
    private fun expected(brush: Brush, x: Int, y: Int, under: Colour = Colour.Black): Colour {
        val painted = brush.colourAt(x + 0.5f, y + 0.5f, box)
        return under.lerp(painted.withAlpha(255), painted.alphaFraction)
    }

    private fun red(pixel: Int) = pixel shr 16 and 0xFF

    @Test
    fun `this canvas says it draws gradients`() {
        assertTrue(GlCanvas().drawsGradients)
    }

    @Test
    fun `a vertical gradient runs from its first colour at the top to its last at the bottom`() {
        val brush = Brush.vertical(red, blue)
        val frame = draw { rect(box, brush) }

        assertColour(red, frame.at(200, 101), "the top edge")
        assertColour(blue, frame.at(200, 258), "the bottom edge")
        assertColour(expected(brush, 200, 180), frame.at(200, 180), "halfway down")
        assertEquals(frame.at(110, 150), frame.at(290, 150), "nothing changes across")
        assertColour(Colour.Black, frame.at(200, 96), "nothing above the box")
    }

    @Test
    fun `a horizontal gradient runs left to right`() {
        val brush = Brush.horizontal(red, blue)
        val frame = draw { rect(box, brush) }

        assertColour(red, frame.at(101, 180), "the left edge")
        assertColour(blue, frame.at(298, 180), "the right edge")
        assertColour(expected(brush, 150, 180), frame.at(150, 180), "a quarter across")
    }

    @Test
    fun `an angled gradient turns clockwise and meets the corners`() {
        val brush = Brush.linear(red, blue, degrees = 45f)
        val frame = draw { rect(box, brush) }

        assertColour(red, frame.at(101, 101), "top-left is the start")
        assertColour(blue, frame.at(298, 258), "bottom-right is the end")
        assertColour(expected(brush, 298, 101), frame.at(298, 101), "top-right is halfway")
    }

    @Test
    fun `a radial gradient is its centre colour in the middle and its edge colour at the sides`() {
        val brush = Brush.radial(red, blue)
        val frame = draw { rect(box, brush) }

        assertColour(red, frame.at(200, 180), "the middle")
        assertColour(blue, frame.at(101, 180), "the left side")
        assertColour(blue, frame.at(102, 102), "a corner stays at the edge colour")
        assertColour(expected(brush, 250, 180), frame.at(250, 180), "halfway out")
    }

    @Test
    fun `a gradient to transparent fades without going dark`() {
        val brush = Brush.vertical(red, Colour.Transparent)
        val frame = draw {
            rect(Rect.of(0f, 0f, 400f, 400f), Colour.White)
            rect(box, brush)
        }

        val pixel = frame.at(200, 180)
        assertColour(expected(brush, 200, 180, under = Colour.White), pixel, "halfway down")
        assertTrue(red(pixel) > 240, "red stays full over white while it fades, got %06X".format(pixel))
    }

    @Test
    fun `a gradient is cut by its corner like any other box`() {
        val frame = draw { rect(box, Brush.vertical(red, blue), corner = 40f) }

        assertColour(Colour.Black, frame.at(102, 102), "the corner is cut away")
        assertColour(red, frame.at(200, 101), "the flat top is filled")
    }

    @Test
    fun `a gradient fades with the opacity in force`() {
        val frame = draw {
            pushAlpha(0.5f)
            rect(box, Brush.vertical(red, red))
            popAlpha()
        }

        assertColour(Colour.rgb(0x800000), frame.at(200, 180), "half of red over black")
    }

    @Test
    fun `a gradient panel batches with the flat boxes round it`() {
        val frame = draw {
            rect(Rect.of(0f, 0f, 400f, 400f), Colour.White)
            rect(box, Brush.radial(red, blue), corner = 10f)
            border(box, Colour.Black, width = 6f, corner = 10f)
            rect(Rect.of(10f, 10f, 20f, 20f), red)
        }

        assertEquals(1, frame.drawCalls)
        // Inside the band rather than on either softened edge of it.
        assertColour(Colour.Black, frame.at(200, 102), "the border after it still draws as a border")
        assertColour(red, frame.at(20, 20), "and a flat box after that is still flat")
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
    fun `a layer put on four corners slants what was drawn into it`() {
        // A red square captured upright, put down with its top slid forty to the right. The two
        // probes that matter are the ones an upright composite would get backwards.
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) { rect(bounds, red) }
            drawLayerOnto(
                checkNotNull(picture) { "this driver gave us no layer" },
                bounds,
                floatArrayOf(80f, 40f, 200f, 40f, 160f, 160f, 40f, 160f),
            )
        }

        assertColour(red, frame.at(100, 100), "the middle is still covered")
        assertColour(red, frame.at(190, 50), "the top leans out past the upright box")
        assertColour(Colour.Black, frame.at(45, 50), "and leaves the box's top-left corner bare")
        assertColour(red, frame.at(50, 150), "the bottom still reaches its left edge")
        assertColour(Colour.Black, frame.at(45, 120), "the left edge leans in halfway down too")
    }

    @Test
    fun `the top of a layer on four corners is still the top`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) {
                rect(Rect.of(40f, 40f, 120f, 60f), red)
                rect(Rect.of(40f, 100f, 120f, 60f), blue)
            }
            drawLayerOnto(
                checkNotNull(picture) { "this driver gave us no layer" },
                bounds,
                floatArrayOf(80f, 40f, 200f, 40f, 160f, 160f, 40f, 160f),
            )
        }

        assertColour(red, frame.at(150, 50), "the red half is on top")
        assertColour(blue, frame.at(80, 150), "and the blue half underneath")
    }

    @Test
    fun `a layer on four corners fades with the opacity in force`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) { rect(bounds, red) }
            pushAlpha(0.5f)
            drawLayerOnto(
                checkNotNull(picture) { "this driver gave us no layer" },
                bounds,
                floatArrayOf(80f, 40f, 200f, 40f, 160f, 160f, 40f, 160f),
            )
            popAlpha()
        }

        assertColour(Colour.rgb(0x800000), frame.at(100, 100), "half red over black")
        assertColour(Colour.Black, frame.at(45, 50), "and nothing where the slant left the box")
    }

    @Test
    fun `a layer on four corners inside another layer keeps what the outer one drew first`() {
        // Blue drawn into the outer picture and still waiting in the batch when the inner picture
        // is made. A canvas that let making the inner picture change the framebuffer would put the
        // blue on the screen instead, upright and in the wrong place.
        val outer = Rect.of(40f, 40f, 160f, 160f)
        val inner = Rect.of(80f, 80f, 80f, 80f)
        val frame = draw {
            val picture = layer(outer) {
                rect(outer, blue)
                val nested = layer(inner) { rect(inner, red) }
                drawLayerOnto(
                    checkNotNull(nested) { "this driver gave us no layer" },
                    inner,
                    floatArrayOf(100f, 80f, 180f, 80f, 160f, 160f, 80f, 160f),
                )
            }
            drawLayerOnto(
                checkNotNull(picture) { "this driver gave us no layer" },
                outer,
                floatArrayOf(40f, 40f, 200f, 40f, 200f, 200f, 40f, 200f),
            )
        }

        assertColour(red, frame.at(120, 120), "the inner picture is inside the outer one")
        assertColour(blue, frame.at(50, 50), "and the outer one's own drawing is still in it")
        assertColour(blue, frame.at(85, 85), "including where the inner one leant away")
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
    fun `nine separately-cut patch pieces are refused as what they are, not as a foreign texture`() {
        // The skip has to happen out here, above the assertion, for the same reason as below.
        assumeTrue(Gl.available, "no display; this test needs a real GL context")

        val thrown = assertThrows<IllegalArgumentException> {
            Gl.render {
                val canvas = GlCanvas()
                try {
                    canvas.begin(viewport)
                    canvas.image(pieces, Rect.of(0f, 0f, 10f, 10f))
                } finally {
                    canvas.close()
                }
            }
        }

        // The same sentence and the same exception the toolkit's own refusals throw: a host that
        // catches one of them catches all of them.
        assertEquals(NineRegions.NotOnePicture, thrown.message)
    }

    /**
     * The resources are built the first time something is drawn, which puts their cost in the
     * first frame a player sees. [GlCanvas.warmUp] is how a game moves it to a loading screen.
     */
    @Test
    fun `warming up builds the batch without drawing anything`() {
        Gl.render {
            val canvas = GlCanvas()
            try {
                assertFalse(canvas.warmedUp, "a fresh canvas has built nothing")

                canvas.warmUp()

                assertTrue(canvas.warmedUp, "warming up built the buffer and the shader")
                assertEquals(0, canvas.drawCalls, "warming up draws nothing")
                // Twice is a no-op rather than a second buffer.
                canvas.warmUp()
                assertTrue(canvas.warmedUp)
            } finally {
                canvas.close()
            }
        }
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

    // --- turning a picture, and blending it ---

    /** Two rows, red over blue: a flat colour cannot tell you it came out the wrong way round. */
    private fun stripes(): GlTexture = GlTexture.rgba(
        1,
        2,
        byteArrayOf(0xFF.toByte(), 0, 0, 0xFF.toByte(), 0, 0, 0xFF.toByte(), 0xFF.toByte()),
        smooth = false,
    )

    /** Draws [content] with a picture to hand, and reads the frame back. */
    private fun drawPicture(content: GlCanvas.(GlTexture) -> Unit): Frame = Gl.render {
        val canvas = GlCanvas()
        val texture = stripes()
        try {
            GL11.glClearColor(0f, 0f, 0f, 1f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            canvas.content(texture)
            canvas.end()
            Frame(Gl.readPixels(Gl.size, Gl.size), canvas.drawCalls)
        } finally {
            texture.close()
            canvas.close()
        }
    }

    @Test
    fun `half a turn puts the top of the picture at the bottom`() {
        val frame = drawPicture { image(it, Rect.of(10f, 10f, 40f, 40f), degrees = 180f) }

        assertColour(blue, frame.at(30, 15), "the top row after half a turn is the picture's last")
        assertColour(red, frame.at(30, 45), "and its first row is now at the bottom")
    }

    @Test
    fun `a quarter turn clockwise sends the top of the picture to the right`() {
        // The convention, asserted rather than described: positive degrees turn the way the axes
        // do, and y grows downwards here.
        val frame = drawPicture { image(it, Rect.of(10f, 10f, 40f, 40f), degrees = 90f) }

        assertColour(red, frame.at(45, 30), "the picture's first row ends up on the right")
        assertColour(blue, frame.at(15, 30), "and its last row on the left")
    }

    @Test
    fun `no turn draws exactly what the upright call draws`() {
        val turned = drawPicture { image(it, Rect.of(10f, 10f, 40f, 40f), degrees = 0f) }
        val upright = drawPicture { image(it, Rect.of(10f, 10f, 40f, 40f)) }

        assertEquals(upright.at(30, 15), turned.at(30, 15))
        assertEquals(upright.at(30, 45), turned.at(30, 45))
    }

    @Test
    fun `a turned picture fades with the opacity in force`() {
        // The alpha stack reaches the turned call the same way it reaches the upright one. A
        // widget fading a tilted card out would otherwise stay solid all the way to invisible.
        val frame = drawPicture {
            pushAlpha(0.5f)
            image(it, Rect.of(10f, 10f, 40f, 40f), degrees = 180f)
            popAlpha()
        }

        assertColour(Colour.rgb(0x000080), frame.at(30, 15), "half a blue row over black")
        assertColour(Colour.rgb(0x800000), frame.at(30, 45), "and half a red one")
    }

    @Test
    fun `part of a picture can be turned`() {
        // The sub-rectangle and the angle together, which is a sprite sheet's frame at an angle —
        // the ordinary case, and the one neither argument alone covers. Only the picture's red row
        // is asked for, so a canvas that dropped `source` would put blue down one side.
        val frame = drawPicture {
            image(it, Rect.of(10f, 10f, 40f, 40f), degrees = 90f, source = Rect.of(0f, 0f, 1f, 1f))
        }

        assertColour(red, frame.at(20, 30), "the red row fills it")
        assertColour(red, frame.at(40, 30), "all the way across")
    }

    @Test
    fun `fourteen turned pictures cost one draw call`() {
        // The whole reason turning lives on the quad rather than in the projection: no GL state
        // changes, so a sunburst is one draw call and not fourteen.
        val frame = drawPicture { picture ->
            repeat(14) { ray ->
                image(picture, Rect.of(100f, 98f, 80f, 4f), degrees = ray * 360f / 14f, pivotX = 0f)
            }
        }

        assertEquals(1, frame.drawCalls, "a sunburst should batch with itself")
    }

    @Test
    fun `an additive group really adds`() {
        val half = Colour.rgb(0x404040)
        val frame = draw {
            rect(Rect.of(10f, 10f, 60f, 60f), half)
            pushBlend(BlendMode.Additive)
            rect(Rect.of(10f, 10f, 60f, 60f), half)
            popBlend()
            rect(Rect.of(100f, 10f, 60f, 60f), half)
            rect(Rect.of(100f, 10f, 60f, 60f), half)
        }

        assertColour(Colour.rgb(0x808080), frame.at(40, 40), "two halves added come out twice as bright")
        assertColour(half, frame.at(130, 40), "and source-over just covers what is underneath")
    }

    @Test
    fun `a group costs two batch boundaries however many quads are in it`() {
        val counts = listOf(2, 20).map { many ->
            draw {
                rect(Rect.of(0f, 0f, 10f, 10f), red)
                pushBlend(BlendMode.Additive)
                repeat(many) { rect(Rect.of(it * 12f, 20f, 10f, 10f), red) }
                popBlend()
                rect(Rect.of(0f, 200f, 10f, 10f), red)
            }.drawCalls
        }

        assertEquals(listOf(3, 3), counts, "the cost is per group, not per quad")
    }

    @Test
    fun `a group with nothing in it costs the flush the push forced, and no more`() {
        // Honest about what eager blending costs. The mode is set the moment it is pushed rather
        // than remembered until something draws, because a shader effect changes the same piece of
        // GL state behind the batch's back and a remembered value would then be wrong. The price
        // is this: a push sends whatever was already queued, so an empty group can split one draw
        // call into two. One, not one per quad, and never a lost pixel.
        val without = draw {
            rect(Rect.of(0f, 0f, 10f, 10f), red)
            rect(Rect.of(20f, 0f, 10f, 10f), red)
        }
        val with = draw {
            rect(Rect.of(0f, 0f, 10f, 10f), red)
            pushBlend(BlendMode.Additive)
            popBlend()
            rect(Rect.of(20f, 0f, 10f, 10f), red)
        }

        assertEquals(1, without.drawCalls)
        assertEquals(2, with.drawCalls, "one extra, whatever the group would have held")
    }

    @Test
    fun `a shader effect does not leave the wrong blending behind it`() {
        // The batch does not remember what it last set, and this is why: the effect changes the
        // blend function itself. A canvas that believed its own cache would skip putting it back,
        // and everything drawn after an effect would blend wrongly.
        val half = Colour.rgb(0x404040)
        val frame = draw {
            pushBlend(BlendMode.Additive)
            val picture = layer(Rect.of(100f, 100f, 40f, 40f)) {
                rect(Rect.of(100f, 100f, 40f, 40f), Colour.rgb(0x101010))
            }
            if (picture != null) drawLayer(picture, Rect.of(100f, 100f, 40f, 40f), passThrough)
            rect(Rect.of(10f, 10f, 60f, 60f), half)
            rect(Rect.of(10f, 10f, 60f, 60f), half)
            popBlend()
        }

        assertColour(
            Colour.rgb(0x808080),
            frame.at(40, 40),
            "the mode in force before the effect is still in force after it",
        )
    }

    @Test
    fun `a layer draws plainly inside, however the blend was set outside it`() {
        // Adding into a layer and then compositing the result is not adding onto the screen: the
        // layer starts as transparent black, so the second quad would double inside the picture
        // and the picture would land twice as bright as the caller asked for.
        val half = Colour.rgb(0x404040)
        val frame = draw {
            pushBlend(BlendMode.Additive)
            val picture = layer(Rect.of(10f, 10f, 60f, 60f)) {
                rect(Rect.of(10f, 10f, 60f, 60f), half)
                rect(Rect.of(10f, 10f, 60f, 60f), half)
            }
            if (picture != null) drawLayer(picture, Rect.of(10f, 10f, 60f, 60f))
            popBlend()
        }

        assertColour(half, frame.at(40, 40), "inside the layer the two halves covered, not added")
    }

    @Test
    fun `a shader effect composites the way the blend stack says`() {
        // An effect draws its own quad rather than going through the batch, so it has to be told
        // the mode. Until it was, `pushBlend(Additive) { drawLayer(picture, box, blur) }` came out
        // as paint — no exception, no log, and `supports` still answering yes. That is the case
        // the documentation points a caller at.
        val half = Colour.rgb(0x404040)
        val bounds = Rect.of(10f, 10f, 60f, 60f)
        val frame = draw {
            rect(bounds, half)
            pushBlend(BlendMode.Additive)
            val picture = layer(bounds) { rect(bounds, half) }
            if (picture != null) drawLayer(picture, bounds, passThrough)
            popBlend()
        }

        assertColour(
            Colour.rgb(0x808080),
            frame.at(40, 40),
            "a blurred group inside a pushBlend glows like an unblurred one",
        )
    }

    @Test
    fun `a blend left pushed is caught at the end of the frame`() {
        assumeTrue(Gl.available, "no display; this test needs a real GL context")
        assertThrows<IllegalStateException> {
            Gl.render {
                val canvas = GlCanvas()
                try {
                    canvas.begin(viewport)
                    canvas.pushBlend(BlendMode.Additive)
                    canvas.end()
                } finally {
                    canvas.close()
                }
            }
        }
    }

    // --- tinting ---

    @Test
    fun `a tint multiplies what is drawn under it and nothing after it`() {
        val frame = draw {
            pushTint(Colour.rgb(0xFF8000))
            rect(Rect.of(10f, 10f, 60f, 60f), Colour.rgb(0x808080))
            popTint()
            rect(Rect.of(100f, 10f, 60f, 60f), Colour.rgb(0x808080))
        }

        assertColour(Colour.rgb(0x804000), frame.at(40, 40), "grey through orange")
        assertColour(Colour.rgb(0x808080), frame.at(130, 40), "after the pop it is grey")
    }

    @Test
    fun `half a tint is halfway to the colour rather than half as opaque`() {
        val frame = draw {
            pushTint(Colour.Red.scaleAlpha(0.5f))
            rect(Rect.of(10f, 10f, 60f, 60f), Colour.White)
            popTint()
        }

        assertColour(Colour.rgb(0xFF8080), frame.at(40, 40), "white halfway to red")
    }

    @Test
    fun `a tinted layer is tinted once and not again on the way back`() {
        val bounds = Rect.of(10f, 10f, 60f, 60f)
        val frame = draw {
            pushTint(Colour.rgb(0x808080))
            val picture = layer(bounds) { rect(bounds, Colour.White) }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds)
            popTint()
        }

        assertColour(Colour.rgb(0x808080), frame.at(40, 40), "half, not a quarter")
    }

    @Test
    fun `a shader effect is handed a picture that is already tinted`() {
        val bounds = Rect.of(10f, 10f, 60f, 60f)
        val frame = draw {
            pushTint(Colour.Green)
            val picture = layer(bounds) { rect(bounds, Colour.White) }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds, passThrough)
            popTint()
        }

        assertColour(Colour.Green, frame.at(40, 40), "through the shader and still green")
    }

    @Test
    fun `a tint costs no draw call`() {
        val frame = draw {
            rect(Rect.of(0f, 0f, 10f, 10f), red)
            pushTint(Colour.Grey)
            rect(Rect.of(20f, 0f, 10f, 10f), red)
            popTint()
            rect(Rect.of(40f, 0f, 10f, 10f), red)
        }

        assertEquals(1, frame.drawCalls, "the tint rides on each vertex, so the batch never breaks")
    }

    @Test
    fun `a picture's own tint and the one in force both apply`() {
        val frame = drawPicture {
            pushTint(Colour.rgb(0x808080))
            image(it, Rect.of(10f, 10f, 40f, 40f), tint = Colour.rgb(0xFF00FF))
            popTint()
        }

        assertColour(Colour.rgb(0x800000), frame.at(30, 15), "the red row, halved")
        assertColour(Colour.rgb(0x000080), frame.at(30, 45), "the blue row, halved")
    }

    @Test
    fun `it says it tints`() {
        assertTrue(GlCanvas().tints)
    }
}
