package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.debug.Inspector
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Inspector` on a real GPU: the hover outline, the padding wash, the pinned outline and the panel,
 * in the pixels.
 *
 * The headless tests prove which rectangles and which words are asked for. This proves the GL
 * canvas turns them into a blue edge, a green band over padding, an orange edge once clicked, and a
 * dark panel in the top corner, over a grey screen.
 */
class InspectorGlTest {

    /** A grey screen with an 80 by 60 box at 40, 200, painted dark inside 10 of its own padding. */
    private fun screen(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        Inspector(enabled = true) {
            Box(Modifier.fillMaxSize().background(Colour.rgb(0x808080))) {
                Box(Modifier.offset(40f, 200f).size(80f, 60f).padding(10f).background(Colour.rgb(0x303030)).testTag("target"))
            }
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

    @Test
    fun `hovering outlines a box and a click pins it in orange`() = Gl.render {
        // A font that can be drawn, under the name the stock skin asks for: the panel is text.
        val fonts = GdxFonts().also { it.registerTrueType("default", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(16)) }
        val backend = GdxBackend(fonts)
        val ui = screen(backend)
        try {
            frame(ui).use {
                val edge = it.at(40, 230)
                assertTrue(edge.r in 0.45f..0.55f && edge.b in 0.45f..0.55f, "the box's left edge is grey before: $edge")
                val band = it.at(45, 230)
                assertTrue(band.r in 0.45f..0.55f && band.g in 0.45f..0.55f, "its padding is grey before: $band")
                val panel = it.at(Gl.size - 8 - 4, 30)
                assertTrue(panel.r < 0.15f && panel.g < 0.15f && panel.b < 0.15f, "the panel is dark in the corner: $panel")
            }

            ui.moveTo("target")

            frame(ui).use {
                val edge = it.at(40, 230)
                assertTrue(edge.b > 0.6f && edge.r < 0.35f, "a blue edge on the box: $edge")
                val band = it.at(45, 230)
                assertTrue(band.g > band.r + 0.1f && band.g > 0.5f, "its padding washed green: $band")
                val middle = it.at(80, 230)
                assertTrue(middle.b > middle.r + 0.02f && middle.r < 0.25f, "the dark paint washed a little blue: $middle")
                val outside = it.at(20, 230)
                assertTrue(outside.r in 0.45f..0.55f && outside.b in 0.45f..0.55f, "nothing outside the box: $outside")
            }

            ui.click("target")
            // Off the box, so what is left on it is the pin alone.
            ui.moveTo(Offset(20f, 20f))

            frame(ui).use {
                val edge = it.at(40, 230)
                assertTrue(edge.r > 0.7f && edge.g in 0.3f..0.7f && edge.b < 0.3f, "an orange edge on the pinned box: $edge")
                val inner = it.at(41, 230)
                assertTrue(inner.r > 0.7f && inner.b < 0.3f, "two units wide: $inner")
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
