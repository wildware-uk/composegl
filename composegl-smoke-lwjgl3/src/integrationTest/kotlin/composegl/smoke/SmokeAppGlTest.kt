package composegl.smoke

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW

/**
 * The sample app driven through the same functions its GLFW callbacks call — so this exercises
 * the real input path, not a parallel one.
 */
class SmokeAppGlTest {

    @Test
    fun `a click through the GLFW path reaches a Compose button`() {
        var clicks = 0
        SmokeApp(400, 300, visible = false).use { app ->
            app.start {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Button(onClick = { clicks++ }, modifier = Modifier.align(Alignment.TopStart)) {
                        Box(Modifier.size(60.dp, 20.dp))
                    }
                }
            }
            app.frame()

            app.onCursorPos(30.0, 20.0)
            assertTrue(app.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS), "press on the button")
            assertTrue(app.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE))
            assertEquals(1, clicks)

            app.onCursorPos(390.0, 290.0)
            assertFalse(
                app.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS),
                "empty space belongs to whatever is behind the UI",
            )
            app.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)
            assertEquals(1, clicks)
        }
    }

    @Test
    fun `typing through the GLFW char callback reaches a text field`() {
        var value by mutableStateOf(TextFieldValue(""))
        val focus = FocusRequester()
        SmokeApp(400, 300, visible = false).use { app ->
            app.start {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    TextField(value, { value = it }, Modifier.focusRequester(focus), singleLine = true)
                }
            }
            app.frame()
            focus.requestFocus()
            app.frame()

            // The key event says a key went down; the char callback is what inserts the letter.
            app.onKey(GLFW.GLFW_KEY_H, GLFW.GLFW_PRESS)
            assertTrue(app.onChar('h'.code))
            app.frame()
            assertTrue(app.onChar('i'.code))
            app.frame()
            assertEquals("hi", value.text)

            assertTrue(app.onKey(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_PRESS))
            app.onKey(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_RELEASE)
            app.frame()
            assertEquals("h", value.text)
        }
    }

    @Test
    fun `a static UI stops redrawing`() {
        SmokeApp(200, 200, visible = false).use { app ->
            app.start { Box(Modifier.fillMaxSize().background(Color.Red)) }
            repeat(20) { app.frame() }
            assertEquals(1L, app.stats.composeRenders, "20 frames, one render")
            assertEquals(20L, app.stats.frames)
        }
    }
}
