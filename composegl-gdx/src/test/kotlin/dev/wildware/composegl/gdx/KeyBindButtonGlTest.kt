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
import dev.wildware.composegl.ui.input.InputBinding
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.KeyBindButton
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * A key binding button on a real GPU: the "waiting for a press" state has to be something a player
 * can see on a screen, not only a word in a recording.
 */
class KeyBindButtonGlTest {

    /** The default skin's `button.listening` fill. */
    private val amber = Color(0x4A / 255f, 0x3A / 255f, 0x12 / 255f, 1f)

    private var bound: InputBinding? = null

    /** One binding button, 160 by 40, 100 in from the top left. */
    private fun screen(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var binding by remember { mutableStateOf<InputBinding?>(InputBinding.Keyboard(Key.Space)) }
        KeyBindButton(
            binding = binding,
            onBind = { binding = it; bound = it },
            initialFocus = true,
            modifier = Modifier.offset(100f, 100f).size(160f, 40f).testTag("bind"),
        )
    }

    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    /** y down from the top, as the toolkit counts it; OpenGL hands the bottom row back first. */
    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun near(expected: Color, actual: Color) =
        abs(expected.r - actual.r) < 0.02f && abs(expected.g - actual.g) < 0.02f && abs(expected.b - actual.b) < 0.02f

    /** Inside the frame, left of the label, where only the background is drawn. */
    private fun Pixmap.background() = at(106, 120)

    @Test
    fun `pressing it on the pad turns it amber until a button is pressed`() = Gl.render {
        // Real, drawable glyphs under the name and sizes the stock skin asks for. The headless
        // registry can only measure, and a label is drawn here.
        val fonts = GdxFonts().also {
            it.registerTrueType("default", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(12, 13, 14, 16, 18, 22, 26))
        }
        val backend = GdxBackend(fonts)
        val ui = screen(backend)
        try {
            frame(ui).use {
                assertTrue(!near(amber, it.background()), "showing a binding is not amber, got ${it.background()}")
                assertTrue(it.background().r + it.background().g + it.background().b > 0.05f, "the button is drawn at all")
            }

            ui.pad(GamepadButton.South)
            frame(ui).use { assertTrue(near(amber, it.background()), "listening: expected amber, got ${it.background()}") }

            ui.pad(GamepadButton.North)
            frame(ui).use { assertTrue(!near(amber, it.background()), "bound: back to the plain button, got ${it.background()}") }
            assertEquals(InputBinding.Gamepad(GamepadButton.North), bound)
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
