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
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * A screen driven by `uiTest` on a real GPU, judged by the pixels that came out.
 *
 * The headless tests read what was drawn off a recording. These are the proof that the same
 * harness hands a real backend's canvas the frame, so a click a test sends shows up on a screen and
 * not only in a list of draw calls.
 */
class UiTestGlTest {

    private val red = Colour.rgb(0xFF0000)
    private val green = Colour.rgb(0x00FF00)

    /** A square that goes from red to green when it is pressed, 100 in from the top left. */
    private fun switch(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var on by remember { mutableStateOf(false) }
        Box(
            Modifier.offset(100f, 100f).size(100f, 100f)
                .background(if (on) green else red)
                .focusable(initial = true)
                .clickable { on = true }
                .testTag("switch"),
        )
    }

    /** Clears, draws one frame of [ui] through the GL canvas, and reads the frame back. */
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

    @Test
    fun `a click sent by the harness turns the square green on the screen`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = switch(backend)
        try {
            frame(ui).use { assertColour(Color.RED, it.at(150, 150), "before the click") }

            ui.click("switch")

            frame(ui).use {
                assertColour(Color.GREEN, it.at(150, 150), "after the click")
                assertColour(Color.BLACK, it.at(50, 50), "nothing drawn outside the square")
            }
        } finally {
            ui.close()
            backend.dispose()
        }
    }

    @Test
    fun `south on the pad presses the focused square the same way`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = switch(backend)
        try {
            ui.pad(GamepadButton.South)

            frame(ui).use { assertColour(Color.GREEN, it.at(150, 150), "after South") }
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
