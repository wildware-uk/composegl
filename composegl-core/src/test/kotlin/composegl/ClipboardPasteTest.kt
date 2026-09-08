package composegl

import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Copy and paste, through the keyboard, the way a person does it.
 *
 * This is the riskiest path in core. Compose's desktop artifact carries clipboard payloads as AWT
 * `Transferable`s and reads them back with AWT data flavors, and core is not allowed to name AWT —
 * so `ClipEntryText` bridges it reflectively. That bridge cannot be checked by reading it; either
 * Ctrl+V puts the text in the field or the whole idea is wrong.
 */
class ClipboardPasteTest {

    private val ui = HeadlessSurface(400, 200)
    private val focus = FocusRequester()
    private var value by mutableStateOf(TextFieldValue(""))

    @AfterEach
    fun tearDown() = ui.close()

    private fun showFocusedField(initial: String = "") {
        value = TextFieldValue(initial, TextRange(initial.length))
        ui.setContent {
            TextField(value, { value = it }, Modifier.focusRequester(focus), singleLine = true)
        }
        focus.requestFocus()
        ui.frame()
    }

    private fun press(key: Key) {
        val ctrl = PointerKeyboardModifiers(isCtrlPressed = true)
        ui.surface.sendKeyEvent(key, down = true, modifiers = ctrl)
        ui.surface.sendKeyEvent(key, down = false, modifiers = ctrl)
        ui.frame(3)
    }

    @Test
    fun `Ctrl+V pastes what the host has on its clipboard`() {
        ui.host.clipboardText = "from the game"
        showFocusedField()

        press(Key.V)

        assertEquals("from the game", value.text, "paste must reach the field through the host clipboard")
    }

    @Test
    fun `Ctrl+A then Ctrl+C puts the field's text on the host clipboard`() {
        showFocusedField("callsign")

        press(Key.A)
        press(Key.C)

        assertEquals("callsign", ui.host.clipboardText, "copy must reach the host clipboard")
    }

    @Test
    fun `cut empties the field and fills the clipboard`() {
        showFocusedField("delete me")

        press(Key.A)
        press(Key.X)

        assertEquals("delete me", ui.host.clipboardText)
        assertEquals("", value.text)
    }

    @Test
    fun `pasting an empty clipboard changes nothing`() {
        ui.host.clipboardText = null
        showFocusedField("kept")

        press(Key.V)

        assertEquals("kept", value.text)
    }

    @Test
    fun `a round trip through the clipboard survives non-ASCII text`() {
        showFocusedField("naïve 😀 Ω")

        press(Key.A)
        press(Key.C)
        assertEquals("naïve 😀 Ω", ui.host.clipboardText, "the reflective bridge must not mangle text")
    }
}
