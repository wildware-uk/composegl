package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.debug.LayoutOverlay
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `LayoutOverlay` on a real GPU: edges, padding, gaps and ink in the pixels.
 *
 * The headless tests prove which rectangles are asked for. This proves the GL canvas turns them into
 * a blue edge on a box, a green wash over its padding, an orange wash in a gap and a pink edge round
 * paint that sits inside its box, all over a screen that is black until a click turns it on.
 */
class LayoutOverlayGlTest {

    /**
     * A row at 100, 100 with 20 of padding and 40 between two 60-unit boxes: 200 by 100. The second
     * box paints blue inside 15 of its own padding. Clicking the row turns the overlay on.
     */
    private fun screen(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var debug by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.offset(100f, 100f).padding(20f).clickable { debug = true }.testTag("row"),
                horizontalArrangement = Arrangement.spacedBy(40f),
            ) {
                Box(Modifier.size(60f, 60f))
                Box(Modifier.size(60f, 60f).padding(15f).background(Colour.rgb(0x0000FF)))
            }
            LayoutOverlay(debug)
        }
    }

    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    /** y down from the top, as the toolkit counts it; OpenGL hands the bottom row back first. */
    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    /** Each channel clearly on or clearly off, for one-unit edges the shape shader softens. */
    private fun assertMostly(expected: Color, actual: Color, because: String) {
        fun agrees(want: Float, got: Float) = if (want > 0.5f) got > 0.6f else got < 0.45f
        val mostly = agrees(expected.r, actual.r) && agrees(expected.g, actual.g) && agrees(expected.b, actual.b)
        assertTrue(mostly, "$because: expected mostly $expected, got $actual")
    }

    private fun assertBetween(low: Float, high: Float, value: Float, because: String) =
        assertTrue(value in low..high, "$because: $value is not in $low..$high")

    @Test
    fun `a click turns the layout on over the screen`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = screen(backend)
        try {
            frame(ui).use {
                assertMostly(Color.BLACK, it.at(150, 100), "the row's top edge before")
                assertMostly(Color.BLACK, it.at(200, 110), "the row's padding before")
                assertMostly(Color.BLACK, it.at(200, 150), "the gap before")
                assertMostly(Color.BLUE, it.at(250, 135), "the painted blue before")
            }

            ui.click("row")

            frame(ui).use {
                assertEquals(100f, ui.node("row").boundsInRoot.left, "nothing moved")
                // A one-unit edge over black is softened by about a pixel, so it is read as "blue and
                // green, no red" rather than by exact brightness.
                fun assertBlueEdge(pixel: Color, because: String) =
                    assertTrue(pixel.r < 0.1f && pixel.g > 0.35f && pixel.b > 0.5f, "$because: $pixel")
                assertBlueEdge(it.at(150, 100), "the row's top edge")
                assertBlueEdge(it.at(299, 150), "the row's right edge")
                assertBlueEdge(it.at(150, 120), "the first box's top edge")

                val padding = it.at(200, 110)
                assertBetween(0.25f, 0.42f, padding.g, "the padding is washed green")
                assertTrue(padding.r < 0.05f && padding.b < 0.05f, "and only green: $padding")

                val gap = it.at(200, 150)
                assertBetween(0.25f, 0.42f, gap.r, "the gap is washed orange, red")
                assertBetween(0.1f, 0.26f, gap.g, "the gap is washed orange, green")
                assertTrue(gap.b < 0.05f, "and no blue: $gap")

                val ink = it.at(250, 135)
                assertTrue(ink.r > 0.45f && ink.b > 0.6f && ink.g < 0.4f, "pink round the ink inside the second box: $ink")
                assertMostly(Color.BLUE, it.at(250, 150), "the ink itself still shows")
                assertMostly(Color.BLACK, it.at(150, 150), "the first box painted nothing and is not filled")
            }
        } finally {
            ui.close()
            backend.dispose()
        }
    }

    private inline fun <T> Pixmap.use(block: (Pixmap) -> T): T = try {
        block(this)
    } finally {
        dispose()
    }
}
