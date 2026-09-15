package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.VirtualCursor
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * `VirtualCursor` on a real GPU: the arrow a pad pushes around comes out as pixels, with its tip on
 * the cursor, a dark edge round a white body, and a different body over something it could click.
 */
class VirtualCursorGlTest {

    private val grey = Colour.rgb(0x808080)
    private val blue = Colour.rgb(0x2050C0)

    /** A grey map with one blue button, 250 to 350 across and 180 to 220 down. */
    private fun map(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        VirtualCursor(enabled = true) {
            Box(Modifier.size(Gl.size.toFloat(), Gl.size.toFloat()).background(grey)) {
                Box(Modifier.offset(250f, 180f).size(100f, 40f).background(blue).clickable { })
            }
        }
    }

    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun assertColour(expected: Colour, actual: Color, because: String) {
        val close = abs(expected.red / 255f - actual.r) < 0.06f &&
            abs(expected.green / 255f - actual.g) < 0.06f &&
            abs(expected.blue / 255f - actual.b) < 0.06f
        assertTrue(close, "$because: expected about $expected, got $actual")
    }

    @Test
    fun `the arrow is drawn at the cursor and turns gold over a button`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = map(backend)
        try {
            ui.pad(GamepadButton.Start)
            val start = ui.cursor.position
            frame(ui).use { pixels ->
                val x = start.x.toInt()
                val y = start.y.toInt()
                assertColour(Colour.White, pixels.at(x + 2, y + 10), "the arrow's body, just below its tip")
                assertColour(Colour.Black, pixels.at(x - 1, y + 10), "its edge, just left of the body")
                assertColour(grey, pixels.at(x + 12, y + 2), "the map beside the arrow, untouched")
                assertColour(grey, pixels.at(x - 40, y - 40), "and nowhere else")
            }

            ui.holdStick(1f, 0.1f, millis = 150)
            val over = ui.cursor.position
            assertTrue(ui.cursor.overTarget, "settled on the button at $over")
            frame(ui).use { pixels ->
                assertColour(Colour.rgb(0xFFD54A), pixels.at(over.x.toInt() + 2, over.y.toInt() + 10), "gold over a button")
                assertColour(grey, pixels.at(start.x.toInt() + 2, start.y.toInt() + 10), "and gone from where it was")
                Goldens.assertMatches(
                    "virtual-cursor",
                    imageOf(160, 100) { x, y -> Color.rgb888(pixels.at(220 + x, 150 + y)) },
                )
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
