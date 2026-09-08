package composegl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class TypingHost : HostServices {
    override val density = 1f
    var softKeyboard: Boolean? = null
    override fun showSoftKeyboard(visible: Boolean) { softKeyboard = visible }
}

class TextInputTest {

    private val context = ComposeGlContext.createRaster()
    private val host = TypingHost()
    private val surface = ComposeSurface(context, host)
    private val focus = FocusRequester()
    private var value by mutableStateOf(TextFieldValue(""))
    private var nanos = 0L

    @AfterEach
    fun tearDown() = context.dispose()

    private fun frame() {
        nanos += 16_666_667
        surface.update(nanos)
        if (surface.needsRedraw) surface.render(nanos)
    }

    private fun showTextField() {
        surface.setContent {
            TextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.focusRequester(focus),
            )
        }
        surface.setRenderTarget(RenderTarget.Raster(300, 120))
        frame()
    }

    private fun type(text: String) {
        text.forEach {
            assertTrue(surface.sendChar(it.code), "sendChar('$it') should have been accepted")
            frame()
        }
    }

    @Test
    fun `typing into a focused text field inserts the characters`() {
        showTextField()
        assertFalse(surface.sendChar('a'.code), "nothing focused, so the game should get the key")

        focus.requestFocus()
        frame()
        assertTrue(surface.isTextInputActive)
        assertEquals(true, host.softKeyboard, "focusing a field asks the host for a keyboard")

        type("hi")
        assertEquals("hi", value.text)
        assertEquals(2, value.selection.start, "the caret should be after the text")
    }

    @Test
    fun `characters outside the basic plane arrive as one keystroke`() {
        showTextField()
        focus.requestFocus()
        frame()

        surface.sendChar(0x1F600) // grinning face
        frame()
        assertEquals("😀", value.text)
    }

    @Test
    fun `losing focus closes the session and hides the keyboard`() {
        showTextField()
        focus.requestFocus()
        frame()
        type("x")

        surface.setContent { Box(Modifier.fillMaxSize()) }
        repeat(3) { frame() }

        assertFalse(surface.isTextInputActive)
        assertEquals(false, host.softKeyboard)
        assertFalse(surface.sendChar('y'.code))
    }

    @Test
    fun `sendChar before setContent is refused rather than throwing`() {
        assertFalse(surface.sendChar('a'.code))
    }
}
