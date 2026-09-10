package composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Matrix4
import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.geometry.Size
import composegl.ui.graphics.Colour
import composegl.ui.layout.ScalePolicy
import composegl.ui.layout.Viewport
import composegl.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    private val blue = Colour.rgb(0x0000FF)

    /** What one drawn frame came out as. */
    private class Frame(val pixels: Pixmap, val renderCalls: Int)

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
            Frame(Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size), canvas.renderCalls)
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

    @Test
    fun `a rectangle lands where the toolkit said, with y down from the top`() {
        val frame = draw { rect(Rect.of(10f, 20f, 100f, 50f), red) }

        assertEquals(Color.RED, frame.pixels.at(50, 30))
        assertEquals(Color.BLACK, frame.pixels.at(50, 10), "20 down should still be background")
        assertEquals(Color.BLACK, frame.pixels.at(50, 80), "70 down is past the bottom")
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

        assertTrue(frame.renderCalls <= 2, "a hundred nodes took ${frame.renderCalls} draw calls")
    }

    @Test
    fun `a clip stops drawing outside it`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 50f, 50f))
            rect(Rect.of(0f, 0f, 400f, 400f), red)
            popClip()
        }

        assertEquals(Color.RED, frame.pixels.at(25, 25))
        assertEquals(Color.BLACK, frame.pixels.at(100, 100), "the clip did not hold")
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

        assertEquals(Color.RED, frame.pixels.at(150, 150), "the overlap should be drawn")
        assertEquals(Color.BLACK, frame.pixels.at(50, 50), "outside the inner clip")
        assertEquals(Color.BLACK, frame.pixels.at(250, 250), "outside the outer clip")
    }

    @Test
    fun `a clip is lifted when it is popped`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 50f, 50f))
            popClip()
            rect(Rect.of(100f, 100f, 50f, 50f), blue)
        }

        assertEquals(Color.BLUE, frame.pixels.at(120, 120))
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
    fun `the batch is left as it was handed over`() {
        Gl.render {
            val batch = SpriteBatch()
            val canvas = GdxCanvas(batch)
            val projection = Matrix4().setToOrtho2D(0f, 0f, 7f, 11f)
            batch.projectionMatrix = projection
            batch.color = Color.GREEN
            try {
                canvas.begin(viewport)
                canvas.rect(Rect.of(0f, 0f, 10f, 10f), red)
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
                Frame(Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size), canvas.renderCalls)
            } finally {
                fonts.dispose()
                canvas.dispose()
                batch.dispose()
            }
        }

        val lit = (20 until 90).flatMap { y -> (20 until 200).map { x -> frame.pixels.at(x, y) } }
            .count { it.r > 0.5f }
        assertTrue(lit > 100, "expected letters below the top-left corner, lit $lit pixels")
        assertEquals(Color.BLACK, frame.pixels.at(200, 300), "nothing should be drawn down there")
    }

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
                Frame(Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size), canvas.renderCalls)
            } finally {
                texture.dispose()
                pixmap.dispose()
                canvas.dispose()
                batch.dispose()
            }
        }

        assertEquals(Color.BLUE, frame.pixels.at(30, 30))
        assertEquals(Color.BLACK, frame.pixels.at(60, 60))
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
}
