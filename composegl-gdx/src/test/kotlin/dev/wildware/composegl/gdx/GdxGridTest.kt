package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Grid
import dev.wildware.composegl.ui.layout.GridCells
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * A composed grid, drawn by the real renderer and read back as pixels.
 *
 * The layout tests say where the cells are as numbers. This says the numbers reach the screen:
 * that each cell's colour is painted in its own cell, that the gaps between them are left alone,
 * and that the hole at the end of a short last row stays empty.
 */
class GdxGridTest {

    @Test
    fun `a grid paints every cell in its own place and leaves the gaps empty`() {
        val image = render()

        // Ten of padding, 68-wide cells, 8 between: columns start at 10, 86 and 162; rows of 48
        // start at 10, 66 and 122.
        val columns = intArrayOf(10, 86, 162)
        val rows = intArrayOf(10, 66, 122)
        Palette.forEachIndexed { index, colour ->
            val x = columns[index % 3] + 34
            val y = rows[index / 3] + 24
            assertColour(image, x, y, colour, "the middle of cell $index")
        }

        assertColour(image, 82, 34, Ink, "the gap between the first two columns")
        assertColour(image, 44, 62, Ink, "the gap between the first two rows")
        assertColour(image, 120, 146, Ink, "where an eighth cell would be, in the short last row")
        assertColour(image, 196, 146, Ink, "and a ninth")
        assertColour(image, 44, 200, Ink, "below the last row")

        Goldens.assertMatches("grid", image)
    }

    private fun render(): BufferedImage = Gl.render {
        val host = UiHost()
        val canvas = GdxCanvas()
        try {
            host.setContent {
                Box(Modifier.fillMaxSize().background(Colour.rgb(Ink.toLong())).padding(10f)) {
                    Grid(GridCells.Fixed(3), Modifier.fillMaxWidth(), spacing = 8f) {
                        Palette.forEach { colour ->
                            Box(Modifier.fillMaxWidth().height(48f).background(Colour.rgb(colour.toLong())))
                        }
                    }
                }
            }
            var nanos = 0L
            repeat(4) {
                nanos += 16_666_667L
                host.settle(viewport, nanos = nanos)
            }

            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            DrawPass(canvas).draw(host.root)
            canvas.end()

            Pixmap.createFromFrameBuffer(0, 0, Side, Side).let { frame ->
                // OpenGL hands back the bottom row first.
                imageOf(Side, Side) { x, y -> frame.getPixel(x, Side - 1 - y) ushr 8 }
                    .also { frame.dispose() }
            }
        } finally {
            canvas.dispose()
            host.dispose()
        }
    }

    private fun assertColour(image: BufferedImage, x: Int, y: Int, expected: Int, what: String) {
        val actual = image.getRGB(x, y) and 0xFFFFFF
        val close = (0..2).all { shift ->
            abs((actual shr (shift * 8) and 0xFF) - (expected shr (shift * 8) and 0xFF)) <= 8
        }
        assertTrue(close, "$what at ($x, $y): expected #%06X but was #%06X".format(expected, actual))
    }

    private companion object {
        const val Side = 240

        const val Ink = 0x12161D

        /** Seven cells: two full rows of three and one on its own. */
        val Palette = intArrayOf(0x4CC2FF, 0xE6EDF5, 0xFF7A59, 0x7DDB6A, 0xB98CFF, 0xFFD24C, 0xFF5C8A)

        val viewport = Viewport(
            design = Size(Side.toFloat(), Side.toFloat()),
            physical = Size(Side.toFloat(), Side.toFloat()),
            policy = ScalePolicy.Fit,
        )
    }
}
