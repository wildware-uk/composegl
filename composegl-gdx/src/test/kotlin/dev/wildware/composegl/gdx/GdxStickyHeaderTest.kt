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
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.widget.LazyColumn
import dev.wildware.composegl.ui.widget.LazyListState
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * A sectioned lazy column caught while the second header pushes the first off the top, drawn by the
 * real renderer and read back.
 *
 * The screen tests say where the headers are. This says the pinned one is really painted over the
 * row under it, that the part pushed above the list is clipped rather than drawn over the padding,
 * and that the rows of the next section carry on below the header that is pushing.
 */
class GdxStickyHeaderTest {

    @Test
    fun `a pushed header is painted over its rows and clipped at the top edge`() {
        val image = render()

        // Ten of padding. Headers 24 tall, then six rows of 40: header one starts 264 down the list,
        // and 250 down it is 14 below the top, with header zero pushed up to 10 above it.
        assertColour(image, 120, 5, Ink, "the padding, where the pushed-off part of header zero is clipped")
        assertColour(image, 120, 17, First, "what is left of header zero, over the last row under it")
        assertColour(image, 120, 36, Second, "header one, arriving")
        assertColour(image, 120, 60, rowColour(8), "the first row of the second section, below it")
        assertColour(image, 120, 120, rowColour(9), "the row after that")

        Goldens.assertMatches("sticky-headers", image)
    }

    private fun rowColour(index: Int) = if (index % 2 == 0) Sky else Leaf

    private fun render(): BufferedImage = Gl.render {
        val host = UiHost()
        val canvas = GdxCanvas()
        try {
            host.setContent {
                Box(Modifier.fillMaxSize().background(Colour.rgb(Ink.toLong())).padding(10f)) {
                    LazyColumn(Modifier.fillMaxSize(), state = LazyListState(initialPosition = 250f), bars = false) {
                        for (section in 0..1) {
                            val header = if (section == 0) First else Second
                            stickyHeader {
                                Box(Modifier.fillMaxWidth().height(24f).background(Colour.rgb(header.toLong())))
                            }
                            // Colours by index in the whole list, so row 8 is the first of section one.
                            items(6) { row ->
                                val index = section * 7 + 1 + row
                                Box(Modifier.fillMaxWidth().height(40f).background(Colour.rgb(rowColour(index).toLong())))
                            }
                        }
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
        const val First = 0xFFD24C
        const val Second = 0xFF5C8A
        const val Sky = 0x4CC2FF
        const val Leaf = 0x7DDB6A

        val viewport = Viewport(
            design = Size(Side.toFloat(), Side.toFloat()),
            physical = Size(Side.toFloat(), Side.toFloat()),
            policy = ScalePolicy.Fit,
        )
    }
}
