package dev.wildware.composegl.webgl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.WebGLRenderingContext as GL
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A screen driven by `uiTest` with WebGL as its backend, judged by the pixels on the canvas.
 *
 * [BrowserUiTest] proves the page's own DOM wiring. This proves the other half: that the harness every
 * toolkit test uses hands a browser's WebGL canvas the frame, so a click, a key or a pad button a test
 * sends shows up drawn in a browser tab the same as it does on desktop OpenGL.
 */
class UiTestWebGlTest {

    private val red = Colour.rgb(0xFF0000)
    private val green = Colour.rgb(0x00FF00)
    private val blue = Colour.rgb(0x0000FF)

    /** Two squares side by side, each red until it is pressed and then green. */
    private suspend fun screen(test: (UiTest, WebGlBackend) -> Unit) {
        val element = pageCanvas(240, 120)
        val backend = WebGlBackend(element, testFonts(), preserveDrawingBuffer = true)
        try {
            uiTest(Size(240f, 120f), backend) {
                Row {
                    listOf("left", "right").forEach { tag ->
                        var on by remember { mutableStateOf(false) }
                        Box(
                            Modifier.size(120f, 120f)
                                .background(if (on) green else red)
                                .focusable(initial = tag == "left")
                                .clickable { on = true }
                                .testTag(tag),
                        ) { Box(Modifier.size(10f, 10f).background(blue)) }
                    }
                }
            }.use { ui -> test(ui, backend) }
        } finally {
            backend.close()
            element.remove()
        }
    }

    /** Clears the canvas, draws one frame of [ui] and reads the pixel at [x], [y] back. */
    private fun UiTest.pixel(backend: WebGlBackend, x: Int, y: Int): Int {
        backend.gl.clearColor(0f, 0f, 0f, 1f)
        backend.gl.clear(GL.COLOR_BUFFER_BIT)
        render()
        return readFrame(backend.gl, 240, 120)[y * 240 + x]
    }

    private fun assertColour(expected: Colour, actual: Int, because: String) {
        val close = kotlin.math.abs(expected.red - red(actual)) < 8 &&
            kotlin.math.abs(expected.green - green(actual)) < 8 &&
            kotlin.math.abs(expected.blue - blue(actual)) < 8
        assertTrue(close, "$because: expected $expected, got #${actual.toString(16).padStart(6, '0')}")
    }

    @Test
    fun `a click sent by the harness turns the square green on the WebGL canvas`() = runTest(timeout = 2.minutes) {
        screen { ui, backend ->
            assertColour(red, ui.pixel(backend, 60, 60), "before the click")
            assertColour(blue, ui.pixel(backend, 4, 4), "the corner square is drawn over it, top left up")

            ui.click("left")

            assertColour(green, ui.pixel(backend, 60, 60), "after the click")
            assertColour(red, ui.pixel(backend, 180, 60), "the other square was not pressed")
        }
    }

    @Test
    fun `keys and the pad sent by the harness press the focused square`() = runTest(timeout = 2.minutes) {
        screen { ui, backend ->
            ui.key(Key.Enter)
            assertColour(green, ui.pixel(backend, 60, 60), "enter pressed the focused left square")
            assertColour(red, ui.pixel(backend, 180, 60), "and only that one")

            ui.pad(GamepadButton.DpadRight)
            ui.assertFocused("right")
            ui.pad(GamepadButton.South)
            assertColour(green, ui.pixel(backend, 180, 60), "south pressed the right square")
        }
    }
}
