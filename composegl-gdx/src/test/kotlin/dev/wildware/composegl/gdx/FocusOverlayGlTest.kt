package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.debug.FocusOverlay
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.hitShape
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `FocusOverlay` on a real GPU: arrows, the focused edge, tints and holes in the pixels.
 *
 * The headless tests prove which shapes are asked for. This proves the GL canvas turns them into a
 * cyan arrow between two boxes, a white edge round the focused one, a yellow wash over a round
 * button and a red one in its corners, over a screen that is black until a click turns it on.
 */
class FocusOverlayGlTest {

    /**
     * Two focusable boxes side by side at y 100, the left one focused; a round clickable at 100, 240;
     * and a square toggle at 300, 300 that turns the overlay on without taking focus.
     */
    private fun screen(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var debug by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.offset(100f, 100f).size(60f, 60f).focusable(initial = true).testTag("a"))
            Box(Modifier.offset(240f, 100f).size(60f, 60f).focusable().testTag("b"))
            Box(Modifier.offset(100f, 240f).size(80f, 80f).hitShape(Shapes.Circle).clickable { })
            Box(Modifier.offset(300f, 300f).size(60f, 60f).clickable { debug = true }.testTag("toggle"))
            FocusOverlay(debug)
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
    fun `a click turns focus and hit areas on over the screen`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = screen(backend)
        try {
            frame(ui).use {
                assertBlack(it.at(200, 130), "between the boxes before")
                assertBlack(it.at(140, 280), "the round button before")
                assertBlack(it.at(130, 100), "a's top edge before")
            }

            ui.click("toggle")

            frame(ui).use {
                val arrow = it.at(200, 130)
                assertTrue(arrow.r < 0.2f && arrow.g > 0.7f && arrow.b > 0.7f, "a cyan arrow from a to b: $arrow")
                val edge = it.at(130, 101)
                assertTrue(edge.r > 0.7f && edge.g > 0.7f && edge.b > 0.7f, "a white edge round the focused box: $edge")

                val tint = it.at(140, 280)
                assertTrue(tint.r in 0.2f..0.4f && tint.g in 0.18f..0.36f && tint.b < 0.05f, "the round button washed yellow: $tint")
                val hole = it.at(103, 243)
                assertTrue(hole.r in 0.25f..0.48f && hole.g < 0.1f && hole.b in 0.03f..0.18f, "its corner washed red: $hole")
                assertBlack(it.at(200, 200), "nothing focusable or clickable here")
            }

            // The pad moves focus, and the next frame's arrow points back the other way.
            ui.pad(GamepadButton.DpadRight)
            frame(ui).use {
                val edge = it.at(270, 101)
                assertTrue(edge.r > 0.7f && edge.g > 0.7f && edge.b > 0.7f, "the white edge moved to b: $edge")
                val arrow = it.at(200, 130)
                assertTrue(arrow.g > 0.7f && arrow.b > 0.7f, "and an arrow still runs between them: $arrow")
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
