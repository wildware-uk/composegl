package composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import composegl.ui.geometry.Rect
import composegl.ui.geometry.Size
import composegl.ui.graphics.EdgeMode
import composegl.ui.layout.Padding
import composegl.ui.layout.ScalePolicy
import composegl.ui.layout.Viewport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Nine-patches on a real GPU: the pieces land where the arithmetic said, and the whole panel is
 * still one draw call.
 *
 * The art is nine flat colours, one per cell, so a pixel read back from the frame says which cell
 * of the source it came from. A corner drawn from the wrong part of the texture is the failure
 * this exists to catch, and it is invisible in any test that only counts rectangles.
 */
class GdxNinePatchTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    /** Cell (column, row) of the test art, in reading order. */
    private val cells = arrayOf(
        arrayOf(Color.RED, Color.GREEN, Color.BLUE),
        arrayOf(Color.YELLOW, Color.WHITE, Color.MAGENTA),
        arrayOf(Color.CYAN, Color.ORANGE, Color.PINK),
    )

    /** A 30-pixel square in nine 10-pixel cells, each a different flat colour. */
    private fun art(): Pixmap = Pixmap(30, 30, Pixmap.Format.RGBA8888).apply {
        for (row in 0..2) {
            for (column in 0..2) {
                setColor(cells[row][column])
                fillRectangle(column * 10, row * 10, 10, 10)
            }
        }
    }

    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun assertColour(expected: Color, actual: Color, because: String) {
        val close = kotlin.math.abs(expected.r - actual.r) < 0.02f &&
            kotlin.math.abs(expected.g - actual.g) < 0.02f &&
            kotlin.math.abs(expected.b - actual.b) < 0.02f
        assert(close) { "$because: expected $expected but was $actual" }
    }

    @Test
    fun `every piece comes from the right part of the art, and the panel is one draw call`() {
        val pixmap = art()
        var renderCalls = 0
        val frame = Gl.render {
            val texture = Texture(pixmap)
            val canvas = GdxCanvas()
            try {
                val patch = TextureRegion(texture).ninePatch(slice = Padding.all(10f))
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                patch.drawInto(canvas, Rect.of(50f, 50f, 200f, 200f))
                canvas.end()
                renderCalls = canvas.renderCalls
                Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
            } finally {
                canvas.dispose()
                texture.dispose()
            }
        }

        // The panel runs 50..250. Corners are 10 across, so the middle is 60..240.
        assertColour(Color.RED, frame.at(55, 55), "the top-left corner")
        assertColour(Color.GREEN, frame.at(150, 55), "the top edge, stretched across")
        assertColour(Color.BLUE, frame.at(245, 55), "the top-right corner")
        assertColour(Color.YELLOW, frame.at(55, 150), "the left edge, stretched down")
        assertColour(Color.WHITE, frame.at(150, 150), "the middle, stretched both ways")
        assertColour(Color.MAGENTA, frame.at(245, 150), "the right edge")
        assertColour(Color.CYAN, frame.at(55, 245), "the bottom-left corner")
        assertColour(Color.PINK, frame.at(245, 245), "the bottom-right corner")
        assertColour(Color.BLACK, frame.at(20, 20), "nothing outside the panel")

        assertEquals(1, renderCalls, "nine pieces of one texture are one batch")

        pixmap.dispose()
        frame.dispose()
    }

    @Test
    fun `a tiled edge repeats the art rather than pulling it`() {
        val pixmap = art()
        val frame = Gl.render {
            val texture = Texture(pixmap)
            val canvas = GdxCanvas()
            try {
                val patch = TextureRegion(texture)
                    .ninePatch(slice = Padding.all(10f), topEdge = EdgeMode.Tile)
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                patch.drawInto(canvas, Rect.of(0f, 0f, 100f, 100f))
                canvas.end()
                Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
            } finally {
                canvas.dispose()
                texture.dispose()
            }
        }

        // Eighty units of top edge, tiled ten at a time: each tile is the same green strip, so the
        // colour is the same everywhere. What the tiling changes is that it is never magnified.
        assertColour(Color.GREEN, frame.at(15, 5), "the first tile")
        assertColour(Color.GREEN, frame.at(85, 5), "the eighth tile")
        assertColour(Color.RED, frame.at(5, 5), "and the corner is still a corner")

        pixmap.dispose()
        frame.dispose()
    }

    @Test
    fun `split and pad come out of the atlas file in the right order`() {
        val directory = Files.createTempDirectory("composegl-atlas").toFile()
        val patch = Gl.render {
            val pixmap = art()
            PixmapIO.writePNG(Gdx.files.absolute(directory.resolve("panel.png").absolutePath), pixmap)
            pixmap.dispose()

            // Four different numbers, so a transposed pair cannot pass by accident. LibGDX writes
            // both lines as left, right, top, bottom.
            directory.resolve("panel.atlas").writeText(
                """
                panel.png
                size:30,30
                format:RGBA8888
                filter:Nearest,Nearest
                repeat:none
                panel
                  bounds:0,0,30,30
                  split:4,5,6,7
                  pad:1,2,3,8
                """.trimIndent() + "\n",
            )

            val atlas = TextureAtlas(Gdx.files.absolute(directory.resolve("panel.atlas").absolutePath))
            try {
                atlas.ninePatch("panel")
            } finally {
                atlas.dispose()
            }
        }

        assertEquals(Padding(left = 4f, top = 6f, right = 5f, bottom = 7f), patch.slice)
        assertEquals(Padding(left = 1f, top = 3f, right = 2f, bottom = 8f), patch.padding)
        assertEquals(30, patch.texture.width)

        directory.deleteRecursively()
    }
}
