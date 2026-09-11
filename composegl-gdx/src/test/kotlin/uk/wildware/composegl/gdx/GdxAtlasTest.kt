package uk.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.layout.ScalePolicy
import uk.wildware.composegl.ui.layout.Viewport
import uk.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * One texture for the whole interface.
 *
 * The thing being tested is a number: how many times a frame talked to the driver. That number is
 * the difference between an interface a game can afford and one it cannot, and it is invisible in
 * a screenshot.
 */
class GdxAtlasTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val white = Colour.White

    /** Registers three sizes, then draws boxes and words alternately through [content]. */
    private fun frame(content: GdxCanvas.(GdxFonts) -> Unit): Int = Gl.render {
        val fonts = GdxFonts()
        fonts.registerTrueType("body", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(12, 16, 24))
        val canvas = GdxCanvas(atlas = fonts.atlas)
        try {
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            canvas.content(fonts)
            canvas.end()
            canvas.drawCalls
        } finally {
            canvas.dispose()
            fonts.dispose()
        }
    }

    @Test
    fun `boxes and words at three sizes are one draw call`() {
        val calls = frame { fonts ->
            // The shape a screen actually has: a panel, a label, another panel, another label, at
            // sizes that used to be three separate textures.
            listOf(12f, 16f, 24f).forEachIndexed { index, size ->
                val y = 20f + index * 60f
                rect(Rect.of(10f, y, 300f, 40f), white, corner = 8f)
                border(Rect.of(10f, y, 300f, 40f), white, width = 1f, corner = 8f)
                shadow(Rect.of(10f, y, 300f, 40f), white, spread = 4f, corner = 8f)
                val style = TextStyle(family = "body", size = size)
                text(fonts.measure("Mixed sizes", style), Offset(20f, y + 8f), white)
            }
        }

        assertEquals(1, calls, "one texture, one batch, however many widgets")
    }

    @Test
    fun `a game's own picture costs exactly one change`() {
        val calls = Gl.render {
            val fonts = GdxFonts()
            fonts.registerTrueType("body", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(16))
            val canvas = GdxCanvas(atlas = fonts.atlas)
            val pixmap = Pixmap(2, 2, Pixmap.Format.RGBA8888).apply { fill() }
            val texture = Texture(pixmap)
            try {
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.rect(Rect.of(0f, 0f, 50f, 50f), white)
                canvas.text(fonts.measure("label", TextStyle(family = "body", size = 16f)), Offset.Zero, white)
                // The one thing that is not on the shared page.
                canvas.image(GdxTexture(texture), Rect.of(60f, 60f, 40f, 40f))
                canvas.end()
                canvas.drawCalls
            } finally {
                texture.dispose()
                pixmap.dispose()
                canvas.dispose()
                fonts.dispose()
            }
        }

        assertEquals(2, calls, "the interface, then the game's picture")
    }

    @Test
    fun `three sizes of two families still fit on one page`() {
        val pages = Gl.render {
            val fonts = GdxFonts()
            val file = Gdx.files.internal("fonts/DejaVuSans.ttf")
            fonts.registerTrueType("body", file, listOf(12, 16, 20))
            fonts.registerTrueType("display", file, listOf(34))
            try {
                fonts.atlas.pageCount
            } finally {
                fonts.dispose()
            }
        }

        assertEquals(1, pages, "more than one page and the saving goes away")
    }

    @Test
    fun `a canvas with no atlas still draws, on a white pixel of its own`() {
        val calls = Gl.render {
            val canvas = GdxCanvas()
            try {
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.rect(Rect.of(0f, 0f, 50f, 50f), white)
                canvas.end()
                canvas.drawCalls
            } finally {
                canvas.dispose()
            }
        }

        assertEquals(1, calls)
    }

    @Test
    fun `the white texel is solid, so a rectangle is the colour it was asked for`() {
        val frame = Gl.render {
            val fonts = GdxFonts()
            fonts.registerTrueType("body", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(16))
            val canvas = GdxCanvas(atlas = fonts.atlas)
            try {
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.rect(Rect.of(50f, 50f, 200f, 200f), Colour.rgb(0x00FF00))
                canvas.end()
                Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
            } finally {
                canvas.dispose()
                fonts.dispose()
            }
        }

        // Sampling the middle of a packed block rather than its edge: a texel picked from the
        // padding between two packed things would come back part transparent.
        val colour = Color(frame.getPixel(150, Gl.size - 1 - 150))
        assertTrue(colour.g > 0.98f && colour.r < 0.02f, "expected solid green, was $colour")
        frame.dispose()
    }

    @Test
    fun `an unregistered size still says what is registered`() {
        val message = Gl.render {
            val fonts = GdxFonts()
            fonts.registerTrueType("body", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(12, 16))
            try {
                runCatching { fonts.fontFor(TextStyle(family = "body", size = 99f)) }
                    .exceptionOrNull()?.message.orEmpty()
            } finally {
                fonts.dispose()
            }
        }

        assertTrue(message.contains("[12, 16]"), message)
    }

}
