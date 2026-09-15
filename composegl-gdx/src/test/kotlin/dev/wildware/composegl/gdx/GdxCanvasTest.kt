package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Matrix4
import dev.wildware.composegl.ui.backend.FakeTexture
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.effect.Uniform
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.NineRegions
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.skew
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The parts of the renderer only a GPU can answer for: how many times it talked to the driver, and
 * what came out in the pixels.
 */
class GdxCanvasTest {

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

    /** What one drawn frame came out as. */
    private class Frame(val pixels: Pixmap, val drawCalls: Int)

    /** Draws [content] on the GL thread, then reads the frame back. */
    private fun draw(content: GdxCanvas.() -> Unit): Frame = Gl.render {
        val batch = SpriteBatch()
        val canvas = GdxCanvas(batch)
        try {
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            canvas.content()
            canvas.end()
            Frame(Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size), canvas.drawCalls)
        } finally {
            canvas.dispose()
            batch.dispose()
        }
    }

    /**
     * The colour at a point in the toolkit's coordinates: y down from the top.
     *
     * A frame read back from OpenGL arrives bottom row first, so the row is turned over here
     * rather than in every assertion.
     */
    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    /**
     * Colours are compared with a tolerance.
     *
     * Exact equality is the wrong question to ask of a rasteriser. LibGDX squeezes a colour into a
     * float and loses the bottom bit of alpha doing it, blending rounds, and llvmpipe is entitled
     * to differ from a real driver by one. A test that fails over that is testing arithmetic.
     */
    private fun assertColour(expected: Color, actual: Color, because: String = "") {
        val close = kotlin.math.abs(expected.r - actual.r) < 0.02f &&
            kotlin.math.abs(expected.g - actual.g) < 0.02f &&
            kotlin.math.abs(expected.b - actual.b) < 0.02f
        assertTrue(close, "$because expected about $expected, got $actual")
    }

    @Test
    fun `a rectangle lands where the toolkit said, with y down from the top`() {
        val frame = draw { rect(Rect.of(10f, 20f, 100f, 50f), red) }

        assertColour(Color.RED, frame.pixels.at(50, 30))
        assertColour(Color.BLACK, frame.pixels.at(50, 10), "20 down should still be background")
        assertColour(Color.BLACK, frame.pixels.at(50, 80), "70 down is past the bottom")
    }

    @Test
    fun `a layer lands exactly where drawing straight onto the screen would have`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) { rect(Rect.of(60f, 60f, 60f, 60f), red) }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds)
        }

        assertColour(Color.RED, frame.pixels.at(90, 90), "the middle of the rectangle")
        assertColour(Color.BLACK, frame.pixels.at(50, 50), "inside the layer but outside the rectangle")
        assertColour(Color.BLACK, frame.pixels.at(20, 20), "outside the layer altogether")
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

        val overlap = frame.pixels.at(90, 90)
        assertTrue(overlap.r < 0.06f, "the red should be hidden under the blue, but the overlap is ${overlap.r} red")
        assertTrue(
            kotlin.math.abs(overlap.b - 0.5f) < 0.1f,
            "expected half the blue, got ${overlap.b}",
        )
    }

    @Test
    fun `a layer inside a layer draws the same as one on its own`() {
        val inner = Rect.of(60f, 60f, 60f, 60f)
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val outer = layer(bounds) {
                val nested = layer(inner) { rect(inner, red) }
                drawLayer(checkNotNull(nested) { "this driver gave us no layer" }, inner)
            }
            drawLayer(checkNotNull(outer) { "this driver gave us no layer" }, bounds)
        }

        assertColour(Color.RED, frame.pixels.at(90, 90), "the middle of the rectangle")
        assertColour(Color.BLACK, frame.pixels.at(50, 50), "outside it")
    }

    @Test
    fun `a clip inside a layer cuts in the layer's own pixels`() {
        // The scissor has a different origin and a different height inside a layer. Getting that
        // wrong cuts the wrong half off, which is invisible until somebody clips inside an effect.
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) {
                pushClip(Rect.of(40f, 40f, 60f, 120f))
                rect(bounds, red)
                popClip()
            }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds)
        }

        assertColour(Color.RED, frame.pixels.at(70, 100), "the left half, which the clip kept")
        assertColour(Color.BLACK, frame.pixels.at(130, 100), "the right half, which it cut")
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

        assertColour(Color.BLUE, frame.pixels.at(50, 50), "the layer")
        assertColour(Color.RED, frame.pixels.at(200, 180), "drawn after it, inside the clip")
        assertColour(Color.BLACK, frame.pixels.at(200, 220), "drawn after it, outside the clip")
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
        assertColour(Color.RED, frame.pixels.at(30, 30), "the caller's own drawing still works")
    }

    @Test
    fun `an effect draws where the picture would have gone`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) { rect(bounds, red) }
            drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds, passThrough)
        }

        assertColour(Color.RED, frame.pixels.at(100, 100), "the middle of the effect")
        assertColour(Color.RED, frame.pixels.at(42, 42), "its top-left corner")
        assertColour(Color.BLACK, frame.pixels.at(30, 100), "left of it")
        assertColour(Color.BLACK, frame.pixels.at(170, 100), "right of it")
        assertColour(Color.BLACK, frame.pixels.at(100, 170), "below it")
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

        assertColour(Color.RED, frame.pixels.at(100, 40), "the painted half")
        assertColour(Color.BLACK, frame.pixels.at(100, 160), "the empty half")
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

        assertColour(Color.BLUE, frame.pixels.at(100, 100), "the shader's own colour, not the picture's")
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

        val lit = frame.pixels.at(100, 100).r
        assertTrue(kotlin.math.abs(lit - 0.5f) < 0.1f, "expected half the red, got $lit")
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

        assertColour(Color.RED, frame.pixels.at(50, 50), "the effect")
        assertColour(Color.BLUE, frame.pixels.at(200, 200), "an ordinary rectangle drawn after it")
    }

    @Test
    fun `a shader that will not compile says so, with its name and the driver's words`() {
        // Skipped here rather than inside `draw`, which is where every other test lets it happen.
        // The skip is thrown, and this is the one test that wraps `draw` in `assertThrows` — which
        // catches it, decides it is the wrong exception, and turns "no display" into a failure.
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
    fun `a hundred nodes cost a handful of draw calls`() {
        val frame = draw {
            repeat(100) { index ->
                val edge = (index % 10) * 20f
                rect(Rect.of(edge, edge, 15f, 15f), red)
                border(Rect.of(edge, edge, 15f, 15f), blue, 1f)
            }
        }

        assertTrue(frame.drawCalls <= 2, "a hundred nodes took ${frame.drawCalls} draw calls")
    }

    @Test
    fun `a fan fills the shape it describes`() {
        // The hub, then three corners: the top-left half of a square, cut across the diagonal.
        val frame = draw {
            fan(floatArrayOf(0f, 0f, 200f, 0f, 200f, 200f, 0f, 200f), red)
        }

        assertColour(Color.RED, frame.pixels.at(60, 40), "above the diagonal, inside the fan")
        assertColour(Color.RED, frame.pixels.at(160, 180), "and below it, in the fan's second triangle")
        assertColour(Color.BLACK, frame.pixels.at(300, 40), "outside it, where nothing was drawn")
    }

    @Test
    fun `a fan is drawn at its colour's own opacity`() {
        val frame = draw {
            rect(Rect.of(0f, 0f, 200f, 200f), red)
            fan(floatArrayOf(0f, 0f, 200f, 0f, 200f, 200f, 0f, 200f), blue.scaleAlpha(0.5f))
        }

        assertColour(Color(0.5f, 0f, 0.5f, 1f), frame.pixels.at(60, 40), "half of each is what half opacity means.")
    }

    @Test
    fun `a fan obeys the clip`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 50f, 50f))
            fan(floatArrayOf(0f, 0f, 400f, 0f, 400f, 400f, 0f, 400f), red)
            popClip()
        }

        assertColour(Color.RED, frame.pixels.at(25, 10))
        assertColour(Color.BLACK, frame.pixels.at(200, 10), "the clip did not hold")
    }

    @Test
    fun `a clip stops drawing outside it`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 50f, 50f))
            rect(Rect.of(0f, 0f, 400f, 400f), red)
            popClip()
        }

        assertColour(Color.RED, frame.pixels.at(25, 25))
        assertColour(Color.BLACK, frame.pixels.at(100, 100), "the clip did not hold")
    }

    @Test
    fun `nested clips intersect`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 200f, 200f))
            pushClip(Rect.of(100f, 100f, 200f, 200f))
            rect(Rect.of(0f, 0f, 400f, 400f), red)
            popClip()
            popClip()
        }

        assertColour(Color.RED, frame.pixels.at(150, 150), "the overlap should be drawn")
        assertColour(Color.BLACK, frame.pixels.at(50, 50), "outside the inner clip")
        assertColour(Color.BLACK, frame.pixels.at(250, 250), "outside the outer clip")
    }

    @Test
    fun `a clip is lifted when it is popped`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 50f, 50f))
            popClip()
            rect(Rect.of(100f, 100f, 50f, 50f), blue)
        }

        assertColour(Color.BLUE, frame.pixels.at(120, 120))
    }

    @Test
    fun `opacity multiplies down the tree`() {
        val frame = draw {
            pushAlpha(0.5f)
            pushAlpha(0.5f)
            rect(Rect.of(0f, 0f, 100f, 100f), red)
            popAlpha()
            popAlpha()
        }

        // A quarter of full red over black, give or take the driver's rounding.
        val drawn = frame.pixels.at(50, 50)
        assertTrue(drawn.r in 0.2f..0.3f, "expected about a quarter red, got ${drawn.r}")
    }

    @Test
    fun `a game's own batch comes back exactly as it was lent out`() {
        Gl.render {
            val batch = SpriteBatch()
            val canvas = GdxCanvas(batch)
            val projection = Matrix4().setToOrtho2D(0f, 0f, 7f, 11f)
            batch.projectionMatrix = projection
            batch.color = Color.GREEN
            try {
                canvas.begin(viewport)
                canvas.rect(Rect.of(0f, 0f, 10f, 10f), red)
                canvas.raw { lent ->
                    assertTrue(lent is SpriteBatch, "raw() handed over a ${lent::class}")
                }
                canvas.end()

                assertArrayEquals(projection.values, batch.projectionMatrix.values)
                assertEquals(Color.GREEN, batch.color)
                assertTrue(!batch.isDrawing)
            } finally {
                canvas.dispose()
                batch.dispose()
            }
        }
    }

    /**
     * A raw block draws at the node when it is handed the node, and where it is told to otherwise.
     *
     * Both halves in one frame, because the point is that they agree. The left square goes through
     * the destination overload and draws itself at its own origin; the right one goes through plain
     * `raw` and converts by hand with [dev.wildware.composegl.ui.graphics.UiCanvas.rawX] and
     * `rawY`. They are the same arithmetic, written twice, and the picture is what says so.
     */
    @Test
    fun `a raw block draws at the node it was handed`() {
        val aimed = Rect.of(20f, 30f, 40f, 10f)
        val byHand = Rect.of(80f, 30f, 40f, 10f)

        val frame = Gl.render {
            val batch = SpriteBatch()
            val canvas = GdxCanvas(batch)
            val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888).apply { setColor(Color.WHITE); fill() }
            val white = Texture(pixmap)
            try {
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                assertTrue(canvas.movesRawOrigin, "it said it would move the origin")
                // Its own coordinates: a block that thinks it is at the origin, because that is
                // what a painter laid out somewhere else does.
                canvas.raw(aimed) { lent ->
                    (lent as SpriteBatch).draw(white, 0f, 0f, aimed.width, aimed.height)
                }
                // The same square, positioned the way a caller had to before the overload existed.
                canvas.raw { lent ->
                    (lent as SpriteBatch).draw(
                        white,
                        canvas.rawX(byHand.left),
                        canvas.rawY(byHand.bottom),
                        byHand.width,
                        byHand.height,
                    )
                }
                canvas.end()
                Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
            } finally {
                white.dispose()
                pixmap.dispose()
                canvas.dispose()
                batch.dispose()
            }
        }

        assertColour(Color.WHITE, frame.at(40, 35), "the block's own origin is the node's corner")
        assertColour(Color.BLACK, frame.at(40, 20), "and nothing above it")
        assertColour(Color.BLACK, frame.at(10, 35), "and nothing to the left of it")
        assertColour(Color.WHITE, frame.at(100, 35), "the by-hand conversion lands in the same row")
        assertColour(Color.BLACK, frame.at(100, 20), "and is no taller than it asked to be")
    }

    @Test
    fun `raw is refused rather than handing over something unusable`() {
        Gl.render {
            val canvas = GdxCanvas()
            try {
                canvas.begin(viewport)
                val error = runCatching { canvas.raw { } }.exceptionOrNull()
                canvas.end()
                assertTrue(error is IllegalStateException, "expected a refusal, got $error")
            } finally {
                canvas.dispose()
            }
        }
    }

    @Test
    fun `nine separately-cut patch pieces are refused as what they are, not as a foreign texture`() {
        // The skip has to happen out here: inside `assertThrows`, a skip is an exception that is
        // not the expected one, so a machine with no display would report a failure instead.
        assumeTrue(Gl.available, "no display; this test needs a real GL context")

        val pieces = NineRegions(
            topLeft = FakeTexture(6, 6), top = FakeTexture(1, 6), topRight = FakeTexture(6, 6),
            left = FakeTexture(6, 1), centre = FakeTexture(1, 1), right = FakeTexture(6, 1),
            bottomLeft = FakeTexture(6, 6), bottom = FakeTexture(1, 6), bottomRight = FakeTexture(6, 6),
        )

        val thrown = assertThrows<IllegalArgumentException> {
            Gl.render {
                val canvas = GdxCanvas()
                try {
                    canvas.begin(viewport)
                    canvas.image(pieces, Rect.of(0f, 0f, 10f, 10f))
                } finally {
                    canvas.dispose()
                }
            }
        }

        // The same sentence and the same exception the toolkit's own refusals throw: a host that
        // catches one of them catches all of them.
        assertEquals(NineRegions.NotOnePicture, thrown.message)
    }

    // --- the shader ---

    @Test
    fun `a rounded corner is cut away`() {
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), red, corner = 30f) }

        assertColour(Color.BLACK, frame.pixels.at(52, 52), "the corner should be cut away.")
        assertColour(Color.RED, frame.pixels.at(100, 100), "the middle should be filled.")
        assertColour(Color.RED, frame.pixels.at(100, 52), "the flat top should be filled.")
    }

    @Test
    fun `a rounded corner is not a staircase`() {
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), red, corner = 30f) }

        // Along the corner's diagonal there should be partly covered pixels. Without them the
        // curve is a staircase, which is what a rounded corner drawn by hand always looks like.
        val partial = (0..30).count { step ->
            val shade = frame.pixels.at(50 + step, 50 + step).r
            shade > 0.05f && shade < 0.95f
        }
        assertTrue(partial > 0, "no softened pixels anywhere along the corner")
    }

    @Test
    fun `a corner is still soft when the interface is scaled up`() {
        // Half the design resolution on the same screen: everything is drawn at twice the size.
        val scaled = Viewport(
            design = Size(Gl.size / 2f, Gl.size / 2f),
            physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
            policy = ScalePolicy.Fit,
        )
        val frame = Gl.render {
            val canvas = GdxCanvas()
            try {
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                canvas.begin(scaled)
                canvas.rect(Rect.of(25f, 25f, 50f, 50f), red, corner = 15f)
                canvas.end()
                Frame(Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size), canvas.drawCalls)
            } finally {
                canvas.dispose()
            }
        }

        // The same corner, now 30 screen pixels across. The softened edge must still be about one
        // screen pixel wide rather than one design unit — two, blurred.
        val partial = (0..60).count { step ->
            val shade = frame.pixels.at(50 + step, 50 + step).r
            shade > 0.05f && shade < 0.95f
        }
        assertTrue(partial in 1..4, "softened over $partial pixels; expected about one")
    }

    @Test
    fun `a square corner stays square`() {
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), red, corner = 0f) }

        assertColour(Color.RED, frame.pixels.at(51, 51), "a zero radius should fill its corner.")
        assertColour(Color.BLACK, frame.pixels.at(48, 48))
    }

    @Test
    fun `each corner is cut by its own radius`() {
        // Only the top-left is rounded. Without four radii reaching the shader every corner is
        // drawn the same, so either all four are cut or none are.
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), red, Corners(topLeft = 30f)) }

        assertColour(Color.BLACK, frame.pixels.at(52, 52), "the top-left should be cut away.")
        assertColour(Color.RED, frame.pixels.at(147, 52), "the top-right should be square.")
        assertColour(Color.RED, frame.pixels.at(147, 147), "the bottom-right should be square.")
        assertColour(Color.RED, frame.pixels.at(52, 147), "the bottom-left should be square.")
    }

    @Test
    fun `top and bottom are the screen's even though the batch counts y upwards`() {
        // The one place the flip could quietly swap two corners: a tab rounded along its top that
        // came out rounded along its bottom would still pass a test that only looked at one.
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), red, Corners.top(30f)) }

        assertColour(Color.BLACK, frame.pixels.at(52, 52), "top-left cut")
        assertColour(Color.BLACK, frame.pixels.at(147, 52), "top-right cut")
        assertColour(Color.RED, frame.pixels.at(52, 147), "bottom-left square")
        assertColour(Color.RED, frame.pixels.at(147, 147), "bottom-right square")
    }

    @Test
    fun `a border follows the corners it is given`() {
        val frame = draw { border(Rect.of(50f, 50f, 100f, 100f), blue, width = 6f, corners = Corners.right(30f)) }

        assertColour(Color.BLUE, frame.pixels.at(52, 52), "the square top-left is part of the ring.")
        assertColour(Color.BLACK, frame.pixels.at(147, 52), "the rounded top-right is cut away.")
        assertColour(Color.BLACK, frame.pixels.at(100, 100), "and the middle is still empty.")
    }

    @Test
    fun `a tab and a plain panel beside it are one draw call`() {
        // Radii are per vertex, so boxes with different corners still batch together.
        val frame = draw {
            rect(Rect.of(10f, 10f, 60f, 24f), red, Corners.top(8f))
            rect(Rect.of(10f, 34f, 200f, 80f), blue, corner = 4f)
            rect(Rect.of(80f, 10f, 60f, 24f), red, Corners(3f, 9f, 0f, 1f))
        }

        assertEquals(1, frame.drawCalls)
    }

    @Test
    fun `the canvas says it rounds corners one by one`() {
        assertTrue(GdxCanvas().roundsCornersSeparately)
    }

    @Test
    fun `a border is a ring, not a filled box`() {
        val frame = draw { border(Rect.of(50f, 50f, 100f, 100f), blue, width = 6f) }

        assertColour(Color.BLUE, frame.pixels.at(52, 100), "the left edge should be drawn.")
        assertColour(Color.BLACK, frame.pixels.at(100, 100), "the middle should be empty.")
    }

    @Test
    fun `a shadow reaches beyond the box and fades`() {
        val frame = draw { shadow(Rect.of(100f, 100f, 100f, 100f), Colour.argb(0xFF000000), spread = 20f) }

        // Nothing to see against black, so measure against a lit background instead.
        val lit = draw {
            rect(Rect.of(0f, 0f, 400f, 400f), Colour.rgb(0xFFFFFF))
            shadow(Rect.of(100f, 100f, 100f, 100f), Colour.argb(0xFF000000), spread = 20f)
        }

        val near = lit.pixels.at(96, 150).r
        val far = lit.pixels.at(85, 150).r
        assertTrue(near < 0.9f, "the shadow should darken just outside the box, got $near")
        assertTrue(far > near, "the shadow should fade with distance: $near then $far")
        assertTrue(frame.drawCalls >= 1)
    }

    @Test
    fun `a panel with a corner, a border and a shadow is one draw call`() {
        val frame = draw {
            val panel = Rect.of(100f, 100f, 200f, 120f)
            shadow(panel, Colour.argb(0x80000000), spread = 12f, corner = 10f)
            rect(panel, blue, corner = 10f)
            border(panel, red, width = 2f, corner = 10f)
        }

        assertEquals(1, frame.drawCalls)
    }

    @Test
    fun `text is drawn where the toolkit said, growing downwards`() {
        val frame = Gl.render {
            val batch = SpriteBatch()
            val canvas = GdxCanvas(batch)
            val fonts = GdxFonts()
            try {
                fonts.registerTrueType("body", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(48))
                val style = TextStyle(family = "body", size = 48f)
                val layout = fonts.measure("HHHH", style)
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.text(layout, Offset(20f, 20f), red)
                canvas.end()
                Frame(Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size), canvas.drawCalls)
            } finally {
                fonts.dispose()
                canvas.dispose()
                batch.dispose()
            }
        }

        val lit = (20 until 90).flatMap { y -> (20 until 200).map { x -> frame.pixels.at(x, y) } }
            .count { it.r > 0.5f }
        assertTrue(lit > 100, "expected letters below the top-left corner, lit $lit pixels")
        assertColour(Color.BLACK, frame.pixels.at(200, 300), "nothing should be drawn down there")
    }

    @Test
    fun `the baseline is where the layout said it is`() {
        // The contract the whole of text layout rests on: drawing matches what was measured. A
        // capital letter sits on the baseline, so the bottom of an H is where it was promised.
        val measured = Gl.render {
            val batch = SpriteBatch()
            val canvas = GdxCanvas(batch)
            val fonts = GdxFonts()
            try {
                fonts.registerTrueType("body", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(48))
                val layout = fonts.measure("HHHH", TextStyle(family = "body", size = 48f))
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.text(layout, Offset(20f, 20f), red)
                canvas.end()
                Measured(Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size), layout.firstBaseline)
            } finally {
                fonts.dispose()
                canvas.dispose()
                batch.dispose()
            }
        }

        val bottom = (20..200).last { y -> (20 until 200).any { x -> measured.pixels.at(x, y).r > 0.5f } }
        val promised = 20f + measured.firstBaseline
        assertTrue(
            kotlin.math.abs(bottom - promised) <= 2f,
            "the letters end at $bottom but the layout promised a baseline at $promised",
        )
    }

    /** A frame and the baseline the layout that drew it claimed. */
    private class Measured(val pixels: Pixmap, val firstBaseline: Float)

    @Test
    fun `a picture is drawn where the toolkit said`() {
        val frame = Gl.render {
            val batch = SpriteBatch()
            val canvas = GdxCanvas(batch)
            val pixmap = Pixmap(2, 2, Pixmap.Format.RGBA8888).apply {
                setColor(Color.BLUE)
                fill()
            }
            val texture = Texture(pixmap)
            try {
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.image(GdxTexture(texture), Rect.of(10f, 10f, 40f, 40f))
                canvas.end()
                Frame(Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size), canvas.drawCalls)
            } finally {
                texture.dispose()
                pixmap.dispose()
                canvas.dispose()
                batch.dispose()
            }
        }

        assertColour(Color.BLUE, frame.pixels.at(30, 30))
        assertColour(Color.BLACK, frame.pixels.at(60, 60))
    }

    @Test
    fun `a picture is drawn the right way up`() {
        // Two rows, two colours. Every earlier picture test used a single flat colour, which is
        // exactly the texture that cannot tell you your texture coordinates are upside down.
        val frame = Gl.render {
            val batch = SpriteBatch()
            val canvas = GdxCanvas(batch)
            val pixmap = Pixmap(1, 2, Pixmap.Format.RGBA8888).apply {
                setColor(Color.RED)
                drawPixel(0, 0)
                setColor(Color.BLUE)
                drawPixel(0, 1)
            }
            val texture = Texture(pixmap)
            try {
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.image(GdxTexture(texture), Rect.of(10f, 10f, 40f, 40f))
                canvas.end()
                Frame(Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size), canvas.drawCalls)
            } finally {
                texture.dispose()
                pixmap.dispose()
                canvas.dispose()
                batch.dispose()
            }
        }

        assertColour(Color.RED, frame.pixels.at(30, 15), "the texture's first row belongs at the top")
        assertColour(Color.BLUE, frame.pixels.at(30, 45), "and its last row at the bottom")
    }

    /**
     * The resources are built the first time something is drawn, which puts their cost in the
     * first frame a player sees. [GdxCanvas.warmUp] is how a game moves it to a loading screen.
     */
    @Test
    fun `warming up builds the batch without drawing anything`() {
        Gl.render {
            val canvas = GdxCanvas()
            try {
                assertFalse(canvas.warmedUp, "a fresh canvas has built nothing")

                canvas.warmUp()

                assertTrue(canvas.warmedUp, "warming up built the mesh and the shader")
                assertEquals(0, canvas.drawCalls, "warming up draws nothing")
                // Twice is a no-op rather than a second mesh.
                canvas.warmUp()
                assertTrue(canvas.warmedUp)
            } finally {
                canvas.dispose()
            }
        }
    }

    @Test
    fun `an unbalanced clip is caught at the end of the frame`() {
        Gl.render {
            val batch = SpriteBatch()
            val canvas = GdxCanvas(batch)
            try {
                canvas.begin(viewport)
                canvas.pushClip(Rect.of(0f, 0f, 10f, 10f))
                val error = runCatching { canvas.end() }.exceptionOrNull()
                assertTrue(error is IllegalStateException, "expected a complaint, got $error")
            } finally {
                canvas.dispose()
                batch.dispose()
            }
        }
    }

    // --- slanting a layer ---

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

        assertColour(Color.RED, frame.pixels.at(100, 100), "the middle is still covered")
        assertColour(Color.RED, frame.pixels.at(190, 50), "the top leans out past the upright box")
        assertColour(Color.BLACK, frame.pixels.at(45, 50), "and leaves the box's top-left corner bare")
        assertColour(Color.RED, frame.pixels.at(50, 150), "the bottom still reaches its left edge")
        assertColour(Color.BLACK, frame.pixels.at(45, 120), "the left edge leans in halfway down too")
    }

    @Test
    fun `the top of a layer on four corners is still the top`() {
        // Red over blue, captured and slanted. A backend that paired the corners with the wrong
        // texture coordinates would draw it upside down or mirrored.
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

        assertColour(Color.RED, frame.pixels.at(150, 50), "the red half is on top")
        assertColour(Color.BLUE, frame.pixels.at(80, 150), "and the blue half underneath")
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

        assertColour(Color(0.5f, 0f, 0f, 1f), frame.pixels.at(100, 100), "half red over black")
    }

    @Test
    fun `a layer on four corners inside another layer keeps what the outer one drew first`() {
        // Blue drawn into the outer picture and still waiting in the batch when the inner picture
        // is made. Making a new FrameBuffer binds the screen, so a canvas that did not flush first
        // would put the blue on the screen instead, upright, with the outer picture missing it.
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
                // The outer picture slanted too, so blue left upright on the screen shows.
                floatArrayOf(80f, 40f, 240f, 40f, 200f, 200f, 40f, 200f),
            )
        }

        assertColour(Color.BLUE, frame.pixels.at(200, 50), "the outer picture has its blue in it")
        assertColour(Color.BLACK, frame.pixels.at(45, 45), "and none was left upright on the screen")
    }

    @Test
    fun `a slanted node in a laid out tree is drawn slanted on the screen`() {
        // The whole path: a composed screen, a measure, a draw pass, this canvas and the pixels.
        // A 120 square inset 60 from the corner, slanted forty-five degrees top forward about its
        // middle, so its top edge runs from 120 to 240 and its bottom edge from 0 to 120.
        val host = UiHost()
        val drawn = try {
            host.setContent {
                Box(Modifier.padding(60f)) {
                    Box(Modifier.size(120f).skew(x = -45f).background(red))
                }
            }
            host.frame(0L)
            MeasurePass().run(host.root, Constraints.atMost(Gl.size.toFloat(), Gl.size.toFloat()))
            draw { DrawPass(this).draw(host.root) }
        } finally {
            host.dispose()
        }

        assertColour(Color.RED, drawn.pixels.at(120, 120), "the middle does not move")
        assertColour(Color.RED, drawn.pixels.at(225, 65), "the top leans out past the laid-out box")
        assertColour(Color.BLACK, drawn.pixels.at(65, 65), "leaving its top-left corner bare")
        assertColour(Color.RED, drawn.pixels.at(15, 175), "the bottom leans out the other way")
        assertColour(Color.BLACK, drawn.pixels.at(170, 175), "leaving its bottom-right corner bare")
    }

    // --- turning a picture, and blending it ---

    /** Draws [content] with a two-row picture to hand — red over blue — and reads the frame back. */
    private fun drawPicture(content: GdxCanvas.(GdxTexture) -> Unit): Frame = Gl.render {
        val batch = SpriteBatch()
        val canvas = GdxCanvas(batch)
        val pixmap = Pixmap(1, 2, Pixmap.Format.RGBA8888).apply {
            setColor(Color.RED)
            drawPixel(0, 0)
            setColor(Color.BLUE)
            drawPixel(0, 1)
        }
        val texture = Texture(pixmap)
        try {
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            canvas.content(GdxTexture(texture))
            canvas.end()
            Frame(Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size), canvas.drawCalls)
        } finally {
            texture.dispose()
            pixmap.dispose()
            canvas.dispose()
            batch.dispose()
        }
    }

    @Test
    fun `half a turn puts the top of the picture at the bottom`() {
        val frame = drawPicture { image(it, Rect.of(10f, 10f, 40f, 40f), degrees = 180f) }

        assertColour(Color.BLUE, frame.pixels.at(30, 15), "half a turn puts the last row on top")
        assertColour(Color.RED, frame.pixels.at(30, 45), "and the first row at the bottom")
    }

    @Test
    fun `a quarter turn clockwise sends the top of the picture to the right`() {
        // The convention, asserted rather than described: positive degrees turn the way the axes
        // do, and y grows downwards here. The other backend is asserted to agree.
        val frame = drawPicture { image(it, Rect.of(10f, 10f, 40f, 40f), degrees = 90f) }

        assertColour(Color.RED, frame.pixels.at(45, 30), "the picture's first row ends up on the right")
        assertColour(Color.BLUE, frame.pixels.at(15, 30), "and its last row on the left")
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

        assertColour(Color(0f, 0f, 0.5f, 1f), frame.pixels.at(30, 15), "half a blue row over black:")
        assertColour(Color(0.5f, 0f, 0f, 1f), frame.pixels.at(30, 45), "and half a red one:")
    }

    @Test
    fun `part of a picture can be turned`() {
        // The sub-rectangle and the angle together, which is a sprite sheet's frame at an angle —
        // the ordinary case, and the one neither argument alone covers. Only the picture's red row
        // is asked for, so a canvas that dropped `source` would put blue down one side.
        val frame = drawPicture {
            image(it, Rect.of(10f, 10f, 40f, 40f), degrees = 90f, source = Rect.of(0f, 0f, 1f, 1f))
        }

        assertColour(Color.RED, frame.pixels.at(20, 30), "the red row fills it:")
        assertColour(Color.RED, frame.pixels.at(40, 30), "all the way across:")
    }

    @Test
    fun `fourteen turned pictures cost one draw call`() {
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

        assertColour(Color(0.5f, 0.5f, 0.5f, 1f), frame.pixels.at(40, 40), "two halves added:")
        assertColour(Color(0.25f, 0.25f, 0.25f, 1f), frame.pixels.at(130, 40), "source-over covers:")
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
    fun `things stay see-through after a raw block`() {
        // SpriteBatch.end() switches blending off on its way out. Before the canvas put it back,
        // everything an interface drew after a raw block came out flat and opaque — a tooltip over
        // a panel, a fade, a shadow, all of it — and nothing said so.
        val ghost = Colour.argb(0x80FFFFFF)
        val frame = draw {
            rect(Rect.of(10f, 10f, 60f, 60f), Colour.Black)
            raw { }
            rect(Rect.of(10f, 10f, 60f, 60f), ghost)
        }

        assertColour(Color(0.5f, 0.5f, 0.5f, 1f), frame.pixels.at(40, 40), "half white over black:")
    }

    @Test
    fun `raw leaves the blend mode the caller asked for still in force`() {
        // SpriteBatch sets its own blending and switches it off again, so without the canvas
        // tidying up after itself the quads after a raw block blend the wrong way.
        val half = Colour.rgb(0x404040)
        val frame = draw {
            pushBlend(BlendMode.Additive)
            rect(Rect.of(10f, 10f, 60f, 60f), half)
            raw { }
            rect(Rect.of(10f, 10f, 60f, 60f), half)
            popBlend()
        }

        assertColour(Color(0.5f, 0.5f, 0.5f, 1f), frame.pixels.at(40, 40), "still adding after raw:")
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
            Color(0.5f, 0.5f, 0.5f, 1f),
            frame.pixels.at(40, 40),
            "a blurred group inside a pushBlend glows like an unblurred one:",
        )
    }

    @Test
    fun `a layer draws plainly inside, however the blend was set outside it`() {
        val half = Colour.rgb(0x404040)
        val bounds = Rect.of(10f, 10f, 60f, 60f)
        val frame = draw {
            pushBlend(BlendMode.Additive)
            val picture = layer(bounds) {
                rect(bounds, half)
                rect(bounds, half)
            }
            if (picture != null) drawLayer(picture, bounds)
            popBlend()
        }

        assertColour(
            Color(0.25f, 0.25f, 0.25f, 1f),
            frame.pixels.at(40, 40),
            "inside the layer the two halves covered rather than added:",
        )
    }
}
