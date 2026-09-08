package composegl.lwjgl3

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL32C

/**
 * The second adapter, against a real driver. Same claims as the LibGDX one, proved the same way.
 */
class ComposeOverlayGlTest {

    private val window = GlfwTestWindow(400, 300)
    private var overlay: ComposeOverlay? = null

    @AfterEach
    fun tearDown() {
        overlay?.close()
        ComposeLwjgl.dispose()
        window.close()
    }

    private fun overlay(content: @androidx.compose.runtime.Composable () -> Unit): ComposeOverlay {
        window.open()
        return ComposeOverlay(window.handle).also {
            overlay = it
            it.setContent(content)
        }
    }

    private fun ComposeOverlay.frame() {
        window.clear(0f, 0f, 0.5f)
        update()
        draw()
    }

    @Test
    fun `the HUD is drawn upright over the game, and the game survives it`() {
        val ui = overlay {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(60.dp).background(Color.Red).align(Alignment.TopStart))
            }
        }
        repeat(3) { ui.frame() }

        assertEquals(0xFFFF0000.toInt(), window.pixelAt(5, 5), "the marker should be at the screen top-left")
        assertEquals(0xFF000080.toInt(), window.pixelAt(395, 295), "the game's clear colour should show through")
        assertEquals(GL32C.GL_NO_ERROR, window.glError())

        repeat(12) { ui.frame() }
        assertEquals(1L, ui.stats.composeRenders, "a static HUD renders once")
    }

    @Test
    fun `raw GL still works after a Compose render`() {
        val ui = overlay { Box(Modifier.fillMaxSize().background(Color(0x40FFFFFF))) }
        repeat(3) { ui.frame() }

        GL32C.glEnable(GL32C.GL_SCISSOR_TEST)
        GL32C.glScissor(0, 0, 20, 20)
        GL32C.glClearColor(0f, 1f, 0f, 1f)
        GL32C.glClear(GL32C.GL_COLOR_BUFFER_BIT)
        GL32C.glDisable(GL32C.GL_SCISSOR_TEST)

        assertEquals(0xFF00FF00.toInt(), window.pixelAt(5, 295), "the state firewall let raw GL through")
        assertEquals(GL32C.GL_NO_ERROR, window.glError())
    }

    @Test
    fun `clicks and typing go through the GLFW callbacks`() {
        var clicks = 0
        var value by mutableStateOf(TextFieldValue(""))
        val focus = FocusRequester()
        val ui = overlay {
            Box(Modifier.fillMaxSize()) {
                Button(onClick = { clicks++ }, modifier = Modifier.align(Alignment.TopStart)) {
                    Box(Modifier.size(60.dp, 20.dp))
                }
                TextField(
                    value,
                    { value = it },
                    Modifier.align(Alignment.BottomStart).focusRequester(focus),
                    singleLine = true,
                )
            }
        }
        ui.frame()

        ui.onCursorPos(30.0, 20.0)
        assertTrue(ui.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0), "press on the button")
        assertTrue(ui.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0))
        assertEquals(1, clicks)

        ui.onCursorPos(395.0, 10.0)
        assertFalse(
            ui.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0),
            "empty HUD space belongs to the game",
        )
        ui.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0)

        focus.requestFocus()
        ui.frame()
        assertTrue(ui.hasKeyboardFocus)
        ui.onChar('h'.code)
        ui.frame()
        ui.onChar('i'.code)
        ui.frame()
        assertEquals("hi", value.text)

        assertTrue(ui.onKey(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_PRESS, 0))
        ui.onKey(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_RELEASE, 0)
        ui.frame()
        assertEquals("h", value.text)
    }

    @Test
    fun `installed callbacks pass on what Compose did not take`() {
        window.open()
        var gameClicks = 0
        GLFW.glfwSetMouseButtonCallback(window.handle) { _, _, _, _ -> gameClicks++ }

        val ui = ComposeOverlay(window.handle).also { overlay = it }
        ui.setContent {
            Box(Modifier.fillMaxSize()) {
                Button(onClick = {}, modifier = Modifier.align(Alignment.TopStart)) {
                    Box(Modifier.size(60.dp, 20.dp))
                }
            }
        }
        ui.installCallbacks()
        ui.frame()

        ui.onCursorPos(30.0, 20.0)
        ui.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0)
        assertEquals(0, gameClicks, "the button took it")

        // The chaining lives in the installed callback, so call it the way GLFW would.
        val callback = GLFW.glfwSetMouseButtonCallback(window.handle, null)
        ui.onCursorPos(395.0, 10.0)
        callback!!.invoke(window.handle, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0)
        assertEquals(1, gameClicks, "empty space fell through to the game's own callback")
    }
}
