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
import dev.wildware.composegl.ui.layout.GridCells
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.widget.LazyGridState
import dev.wildware.composegl.ui.widget.LazyVerticalGrid
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * A lazy grid scrolled part of the way into a row, drawn by the real renderer and read back.
 *
 * The screen tests say which cells exist and where. This says it reaches the screen: the cells of
 * row ten onwards are painted in their columns, the row cut by the top edge is clipped to the grid
 * rather than spilling over the padding, and the gaps stay empty.
 */
class GdxLazyGridTest {

    @Test
    fun `a scrolled lazy grid paints its rows in place and clips the one cut by the edge`() {
        val image = render()

        // Ten of padding, 68-wide cells with 8 between: columns at 10, 86 and 162. Rows of 48 and
        // gaps of 8 are 56 apart, and the grid is 580 down: row ten starts at 10 - 20 = -10.
        assertColour(image, 44, 30, colourOf(30), "the part of row ten still in the window")
        assertColour(image, 44, 5, Ink, "the part of row ten above the window, which is clipped")
        assertColour(image, 120, 70, colourOf(34), "row eleven, second column")
        assertColour(image, 44, 98, Ink, "the gap below row eleven")
        assertColour(image, 82, 70, Ink, "the gap between the first two columns")
        assertColour(image, 196, 182, colourOf(41), "row thirteen, third column")
        assertColour(image, 196, 222, colourOf(44), "row fourteen, cut by the bottom edge")
        assertColour(image, 196, 235, Ink, "and clipped there too")

        Goldens.assertMatches("lazy-grid", image)
    }

    private fun colourOf(index: Int) = Palette[index % Palette.size]

    private fun render(): BufferedImage = Gl.render {
        val host = UiHost()
        val canvas = GdxCanvas()
        try {
            host.setContent {
                Box(Modifier.fillMaxSize().background(Colour.rgb(Ink.toLong())).padding(10f)) {
                    LazyVerticalGrid(
                        count = 300,
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        state = LazyGridState(initialPosition = 580f),
                        spacing = 8f,
                        bars = false,
                    ) { index ->
                        Box(Modifier.fillMaxWidth().height(48f).background(Colour.rgb(colourOf(index).toLong())))
                    }
                }
            }
            var nanos = 0L
            repeat(6) {
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

        val Palette = intArrayOf(0x4CC2FF, 0xE6EDF5, 0xFF7A59, 0x7DDB6A, 0xB98CFF, 0xFFD24C, 0xFF5C8A)

        val viewport = Viewport(
            design = Size(Side.toFloat(), Side.toFloat()),
            physical = Size(Side.toFloat(), Side.toFloat()),
            policy = ScalePolicy.Fit,
        )
    }
}
