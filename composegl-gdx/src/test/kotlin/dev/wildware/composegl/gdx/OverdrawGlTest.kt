package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.debug.OverdrawOverlay
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `OverdrawOverlay` on a real GPU: the shading in the pixels, and the counts it shades taken from
 * the GL canvas's own answers.
 *
 * The headless tests prove what is counted and which rectangles are asked for. This proves the GL
 * canvas turns them into blue over a panel painted twice, green over three times and red over five,
 * over a screen whose panels are black so only the shading shows — and that a scale on this canvas,
 * which really takes a picture, counts the picture.
 */
class OverdrawGlTest {

    /**
     * Black panels stacked in the corner at 60, 60: squares of 180, 120, 60, 30 and 15, so the stack
     * is two, three, four and five deep going in. A lone 10-unit panel beside it is painted once.
     * Clicking the toggle turns the overlay on.
     */
    private fun screen(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var debug by remember { mutableStateOf(false) }
        val black = Colour.rgb(0x000000)
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.size(40f, 20f).clickable { debug = true }.testTag("toggle"))
            Box(Modifier.offset(60f, 60f).size(180f, 180f).background(black)) {
                Box(Modifier.size(120f, 120f).background(black)) {
                    Box(Modifier.size(60f, 60f).background(black)) {
                        Box(Modifier.size(30f, 30f).background(black)) {
                            Box(Modifier.size(15f, 15f).background(black))
                        }
                    }
                }
            }
            Box(Modifier.offset(40f, 160f).size(10f, 10f).background(black))
            OverdrawOverlay(debug, cell = 1f)
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

    private fun assertBlack(pixel: Color, because: String) =
        assertTrue(pixel.r < 0.05f && pixel.g < 0.05f && pixel.b < 0.05f, "$because: $pixel")

    @Test
    fun `a click shades the stack by how many times it is painted`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = screen(backend)
        try {
            frame(ui).use {
                assertBlack(it.at(100, 100), "the two-deep panel before")
                assertBlack(it.at(70, 70), "the five-deep corner before")
            }

            ui.click("toggle")

            frame(ui).use {
                assertBlack(it.at(200, 200), "painted once: left alone")
                assertBlack(it.at(20, 280), "painted not at all: left alone")

                val twice = it.at(150, 100)
                assertTrue(twice.b > 0.4f && twice.r < 0.1f && twice.g in 0.1f..0.3f, "painted twice: blue, $twice")

                val three = it.at(100, 100)
                assertTrue(three.g > 0.3f && three.r < 0.1f && three.b < 0.2f, "painted three times: green, $three")

                val four = it.at(82, 82)
                assertTrue(four.r > 0.4f && four.b > 0.3f && four.g < 0.25f, "painted four times: pink, $four")

                val more = it.at(66, 66)
                assertTrue(more.r > 0.4f && more.g < 0.15f && more.b < 0.15f, "painted five times: red, $more")

                assertBlack(it.at(45, 165), "a small lone panel is painted once")
            }
            assertEquals(5, ui.overdraw().deepest, "the count the GL canvas answers to")
        } finally {
            ui.close()
            backend.dispose()
        }
    }

    @Test
    fun `a scale on the GL canvas counts its picture`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(100f, 100f).size(100f, 100f).scale(0.5f).background(Colour.rgb(0x000000)))
                OverdrawOverlay(true, cell = 1f)
            }
        }
        try {
            frame(ui).use {
                val middle = it.at(150, 150)
                assertTrue(middle.b > 0.4f && middle.r < 0.1f, "the picture and its composite: blue, $middle")
                assertBlack(it.at(110, 110), "inside the picture, outside where it is put down: once")
            }
            val map = ui.overdraw()
            assertEquals(2, map.at(150f, 150f))
            assertEquals(1, map.at(110f, 110f))
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
