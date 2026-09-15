package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.skin.SkinOverride
import dev.wildware.composegl.ui.skin.StateStyle
import dev.wildware.composegl.ui.skin.Style
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Divider
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Dividers on a real GPU: the line lands on exactly the pixels its layout box says, in the skin's
 * colour, and not one row either side of it.
 */
class DividerGlTest {

    /** How many times the square beside the vertical line was pressed. */
    private var pressed = 0

    /**
     * A 200-wide column 20 in from the top left: a 20-tall gap, a horizontal line four thick, then
     * a 60-tall row with a vertical line four thick, 40 along.
     *
     * So the horizontal line covers x 20..220, y 40..44, and the vertical one x 60..64, y 44..104.
     */
    private fun screen(backend: GdxBackend, skin: Skin?): UiTest =
        uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
            val content = @androidx.compose.runtime.Composable {
                Column(Modifier.offset(20f, 20f).width(200f)) {
                    Box(Modifier.size(10f, 20f))
                    Divider(Modifier.testTag("across"), thickness = 4f)
                    Row(Modifier.height(60f)) {
                        Box(Modifier.size(40f, 10f))
                        Divider(Modifier.testTag("down"), vertical = true, thickness = 4f)
                        // A square rather than a Button: the test fonts have nothing the skin's
                        // label would ask for.
                        Box(
                            Modifier.size(40f, 40f).focusable(initial = true).clickable { pressed++ }
                                .testTag("ok"),
                        )
                    }
                }
            }
            if (skin == null) content() else SkinOverride(skin) { content() }
        }

    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    /** y down from the top, as the toolkit counts it; OpenGL hands the bottom row back first. */
    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun assertColour(expected: Color, actual: Color, because: String) {
        val close = abs(expected.r - actual.r) < 0.02f &&
            abs(expected.g - actual.g) < 0.02f &&
            abs(expected.b - actual.b) < 0.02f
        assertTrue(close, "$because: expected about $expected, got $actual")
    }

    /** More magenta than black: an edge pixel the line covers most of. */
    private fun assertMostlyLine(actual: Color, because: String) {
        assertTrue(actual.r > 0.5f && actual.b > 0.5f && actual.g < 0.1f, "$because: expected mostly magenta, got $actual")
    }

    private fun Colour.gdx() = Color(red / 255f, green / 255f, blue / 255f, 1f)

    private val magenta = Colour.rgb(0xFF00FF)

    @Test
    fun `both lines are drawn in the skin's colour on exactly their own pixels`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val skin = Skin(styles = mapOf("divider" to Style(StateStyle(background = SkinDrawable.Fill(magenta)))))
        val ui = screen(backend, skin)
        try {
            frame(ui).use {
                val line = magenta.gdx()
                // The shader softens a shape's outermost pixel, so the inside rows are the exact
                // colour and the edge rows only have to be mostly line.
                assertColour(line, it.at(120, 41), "inside the horizontal line")
                assertColour(line, it.at(120, 42), "inside the horizontal line")
                assertMostlyLine(it.at(120, 40), "its top row")
                assertMostlyLine(it.at(120, 43), "its bottom row")
                assertColour(line, it.at(22, 42), "near its left end")
                assertColour(line, it.at(217, 42), "near its right end")
                assertColour(Color.BLACK, it.at(120, 38), "above the line")
                assertColour(Color.BLACK, it.at(120, 45), "below the line")
                assertColour(Color.BLACK, it.at(223, 42), "past the column's width")
                assertColour(Color.BLACK, it.at(17, 42), "before the column starts")

                assertColour(line, it.at(61, 70), "inside the vertical line")
                assertColour(line, it.at(62, 101), "near its bottom")
                assertMostlyLine(it.at(60, 70), "its left column")
                assertMostlyLine(it.at(63, 70), "its right column")
                assertColour(Color.BLACK, it.at(58, 70), "left of the vertical line")
                assertColour(Color.BLACK, it.at(66, 70), "right of the vertical line")
                assertColour(Color.BLACK, it.at(62, 107), "below the row")
            }
        } finally {
            ui.close()
            backend.dispose()
        }
    }

    @Test
    fun `with no skin of the game's own the line is the default skin's divider colour`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = screen(backend, skin = null)
        try {
            val expected = (Skin.Default.resolve("divider").background as SkinDrawable.Fill).colour
            frame(ui).use { assertColour(expected.gdx(), it.at(120, 41), "the default divider") }
        } finally {
            ui.close()
            backend.dispose()
        }
    }

    @Test
    fun `pressing the button beside a divider leaves the line where it was`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val skin = Skin(styles = mapOf("divider" to Style(StateStyle(background = SkinDrawable.Fill(magenta)))))
        val ui = screen(backend, skin)
        try {
            ui.pad(GamepadButton.South)
            ui.click("ok")
            assertTrue(pressed == 2, "the pad and the mouse both pressed the square, not the line: $pressed")

            frame(ui).use {
                assertColour(magenta.gdx(), it.at(61, 70), "the vertical line after input")
                assertColour(magenta.gdx(), it.at(120, 42), "the horizontal line after input")
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
