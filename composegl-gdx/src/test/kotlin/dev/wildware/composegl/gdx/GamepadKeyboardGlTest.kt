package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideGamepadKeyboard
import dev.wildware.composegl.ui.widget.TextField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The pad keyboard on a real GPU: it is on the screen when a pad lands on a field, the key with
 * focus is lit, and it is gone again after B. Judged by the pixels, through the default skin.
 */
class GamepadKeyboardGlTest {

    private val side = Gl.size.toFloat()

    private fun screen(backend: GdxBackend): UiTest = uiTest(Size(side, side), backend) {
        ProvideGamepadKeyboard(modifier = Modifier.width(380f)) {
            var name by remember { mutableStateOf("") }
            Column {
                Button("START", onClick = {}, initialFocus = true)
                TextField(name, onValueChange = { name = it }, modifier = Modifier.width(200f).testTag("name"))
            }
        }
    }

    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    /** y down from the top; OpenGL hands the bottom row back first. */
    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun assertColour(expected: Color, actual: Color, because: String) {
        val close = abs(expected.r - actual.r) < 0.03f &&
            abs(expected.g - actual.g) < 0.03f &&
            abs(expected.b - actual.b) < 0.03f
        assertTrue(close, "$because: expected about $expected, got $actual")
    }

    /** The default skin's colours, as `default.json` writes them. */
    private val panel = Color.valueOf("1A1F28")
    private val focusedKey = Color.valueOf("2F53A8")
    private val key = Color.valueOf("232A35")

    @Test
    fun `the keyboard is drawn when a pad lands on the field and gone after B`() = Gl.render {
        // Real glyphs: the keys have labels, and the headless registry has no pages to draw them from.
        val fonts = GdxFonts()
        fonts.registerTrueType("default", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(14, 16))
        val backend = GdxBackend(fonts)
        val ui = screen(backend)
        try {
            // A point just inside the bottom edge, in the middle: where the panel's padding will be.
            val bottom = Gl.size - 5
            val middle = Gl.size / 2
            frame(ui).use { assertColour(Color.BLACK, it.at(middle, bottom), "no keyboard before the pad") }

            ui.pad(GamepadButton.DpadDown)

            val first = ui.node("gamepad-keyboard.key.1").boundsInRoot
            val second = ui.node("gamepad-keyboard.key.2").boundsInRoot
            assertEquals(side, ui.node("gamepad-keyboard").boundsInRoot.bottom)
            frame(ui).use {
                assertColour(panel, it.at(middle, bottom), "the panel along the bottom")
                // Inside each key's border and clear of its label, which is drawn in the middle.
                assertColour(focusedKey, it.at(first.left.toInt() + 4, first.top.toInt() + 4), "the focused key is lit")
                assertColour(key, it.at(second.left.toInt() + 4, second.top.toInt() + 4), "the next key is not")
            }

            ui.pad(GamepadButton.East)

            frame(ui).use { assertColour(Color.BLACK, it.at(middle, bottom), "closed by B") }
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
