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

class TextInputTest {

    private val ui = HeadlessSurface(300, 120)
    private val focus = FocusRequester()
    private var value by mutableStateOf(TextFieldValue(""))

    @AfterEach
    fun tearDown() = ui.close()

    private fun showTextField() = ui.setContent {
        TextField(value, { value = it }, Modifier.focusRequester(focus))
    }

    private fun focusTheField() {
        focus.requestFocus()
        ui.frame()
    }

    @Test
    fun `typing into a focused text field inserts the characters`() {
        showTextField()
        assertFalse(ui.surface.sendChar('a'.code), "nothing focused, so the game should get the key")

        focusTheField()
        assertTrue(ui.surface.isTextInputActive)
        assertEquals(true, ui.host.softKeyboard, "focusing a field asks the host for a keyboard")

        ui.type("hi")
        assertEquals("hi", value.text)
        assertEquals(2, value.selection.start, "the caret should be after the text")
    }

    @Test
    fun `characters outside the basic plane arrive as one keystroke`() {
        showTextField()
        focusTheField()

        ui.surface.sendChar(0x1F600) // grinning face
        ui.frame()
        assertEquals("😀", value.text)
    }

    @Test
    fun `losing focus closes the session and hides the keyboard`() {
        showTextField()
        focusTheField()
        ui.type("x")

        ui.setContent { Box(Modifier.fillMaxSize()) }
        ui.frame(3)

        assertFalse(ui.surface.isTextInputActive)
        assertEquals(false, ui.host.softKeyboard)
        assertFalse(ui.surface.sendChar('y'.code))
    }

    @Test
    fun `sendChar before setContent is refused rather than throwing`() {
        assertFalse(ui.surface.sendChar('a'.code))
    }
}
