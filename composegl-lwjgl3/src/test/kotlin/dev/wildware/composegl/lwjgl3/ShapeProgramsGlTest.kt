package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
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
 * The common shape program reads colours and small numbers at `mediump` on an ES device, and keeps
 * positions and texture coordinates whole. These draw the two places a narrower number would show
 * first: the far edge of a wide panel, and a picture read from far into a big page, as a letter
 * on a 2048 glyph page is. Each is
 * set up so that a GPU which really narrows a position or a texture coordinate to 16 bits draws it
 * differently.
 *
 * This machine's GL is Mesa's software renderer, which works every number out at full width
 * whatever it is declared as, so here they cannot catch a `mediump` in the wrong place. The guard
 * that can is `ShapeProgramsTest` in `composegl-render`, which reads the declarations themselves.
 */
class ShapeProgramsGlTest {

    @BeforeEach
    fun requireGl() = assumeTrue(Gl.available, "no display; these tests need a real GL context")

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val green = Colour.rgb(0x40C040)

    /** The frame's green channel, y down from the top, after [draw]. */
    private fun frame(fonts: StbFonts? = null, draw: GlCanvas.() -> Unit): IntArray = Gl.render {
        val canvas = GlCanvas(fonts)
        try {
            Gl.gl.clearColor(0f, 0f, 0f, 1f)
            Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            canvas.draw()
            canvas.end()
            Gl.readPixels(Gl.size, Gl.size).map { (it ushr 8) and 0xFF }.toIntArray()
        } finally {
            canvas.close()
        }
    }

    /** One row of [frame] from x = [from] to [to], y down from the top. */
    private fun row(frame: IntArray, y: Int, from: Int, to: Int) = (from until to).map { frame[y * Gl.size + it] }

    @Test
    fun `a panel two thousand units wide has the same exact edge as a narrow one`() {
        // Its middle a thousand units left of its right edge, and that edge a quarter of a unit
        // off the pixel grid. The pixels at the edge then work it out from local positions of
        // 999.25 and 1000.25, which 16 bits cannot hold: they would round by a quarter of a unit
        // and soften the edge differently. The narrow panel's positions are near 50, held exactly.
        val wide = frame { rect(Rect.of(200.25f - 2000f, 40f, 2000f, 60f), green, Corners.all(12f)) }
        val narrow = frame { rect(Rect.of(100.25f, 40f, 100f, 60f), green, Corners.all(12f)) }

        val across = row(wide, 70, 190, 210)
        assertEquals(row(narrow, 70, 190, 210), across, "the right edge, across the middle row")
        assertEquals(0xC0, across.first(), "solid inside")
        assertEquals(0, across.last(), "nothing outside")
        // The edge is a unit of softening each side of 200.25: pixels 199 and 200 are partly covered.
        assertEquals(2, across.count { it in 1 until 0xC0 }, "two soft pixels: $across")
    }

    /** Straight RGBA, [size] each way, with a 1-pixel black and white checkerboard [cells] across in the bottom-right corner. */
    private fun checkerboard(size: Int, cells: Int): ByteArray {
        val pixels = ByteArray(size * size * 4)
        for (y in size - cells until size) for (x in size - cells until size) {
            val at = (y * size + x) * 4
            val ink: Byte = if ((x + y) % 2 == 0) -1 else 0
            pixels[at] = ink
            pixels[at + 1] = ink
            pixels[at + 2] = ink
            pixels[at + 3] = -1
        }
        return pixels
    }

    @Test
    fun `a picture read from the far corner of a 2048 page is as sharp as from a small one`() {
        // A big glyph page puts a letter's texture coordinates anywhere up to one, and over a half
        // 16 bits hold them only to a texel. A board of single pixels read from the far corner of
        // a 2048 page, sampled smoothly the way glyph pages are, would turn grey if they were
        // narrowed; read from a 32-pixel picture its coordinates are small and exact either way.
        val cells = 32
        fun board(size: Int): IntArray {
            var texture: GlTexture? = null
            try {
                return frame {
                    val page = GlTexture.rgba(size, size, checkerboard(size, cells), smooth = true).also { texture = it }
                    image(page.region(size - cells, size - cells, cells, cells), Rect.of(20f, 20f, cells.toFloat(), cells.toFloat()))
                }
            } finally {
                Gl.render { texture?.close() }
            }
        }
        val far = board(2048)
        val near = board(cells)

        val drawn = (20 until 20 + cells).flatMap { y -> row(near, y, 20, 20 + cells) }
        assertEquals(setOf(0, 0xFF), drawn.toSet(), "the board drawn pixel for pixel: only black and white")
        assertTrue(far.contentEquals(near), "every pixel the same from the far corner of the big page")
    }
}
