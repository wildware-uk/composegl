package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.debug.RedrawOverlay
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `RedrawOverlay` on a real GPU: a red border in the pixels round the box a click changed, fading
 * away once the change is old, and never round the box beside it that did not change.
 */
class RedrawOverlayGlTest {

    /**
     * Two dark boxes, 120 by 80, at 60, 100 and 220, 100. Clicking the first swaps its colour between
     * two blues; the second never changes. The overlay is on from the start.
     */
    private fun screen(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var lighter by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.offset(60f, 100f).size(120f, 80f)
                    .background(if (lighter) Colour.rgb(0x000080) else Colour.rgb(0x000060))
                    .clickable { lighter = !lighter }
                    .testTag("changing"),
            )
            Box(Modifier.offset(220f, 100f).size(120f, 80f).background(Colour.rgb(0x000060)).testTag("still"))
            RedrawOverlay(true, holdMillis = 500)
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

    private fun assertNotRed(pixel: Color, because: String) = assertTrue(pixel.r < 0.1f, "$because: $pixel")

    @Test
    fun `a click flashes the box it changed and the flash fades`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = screen(backend)
        try {
            frame(ui).use {
                assertNotRed(it.at(61, 140), "the left edge before any change")
                assertNotRed(it.at(221, 140), "the still box's edge")
            }

            ui.click("changing")

            frame(ui).use {
                val edge = it.at(61, 140)
                assertTrue(edge.r > 0.6f && edge.g < 0.35f, "a red edge round the changed box: $edge")
                val top = it.at(120, 101)
                assertTrue(top.r > 0.6f, "along its top too: $top")
                val inside = it.at(120, 140)
                assertNotRed(inside, "the inside is left alone")
                assertTrue(inside.b > 0.4f, "and shows the new blue: $inside")
                assertNotRed(it.at(221, 140), "the box that did not change has no edge")
            }

            ui.advanceBy(250)
            frame(ui).use {
                val edge = it.at(61, 140)
                assertTrue(edge.r in 0.2f..0.7f, "half faded after half the hold: $edge")
            }

            ui.advanceBy(400)
            frame(ui).use { assertNotRed(it.at(61, 140), "gone once the hold has passed") }
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
