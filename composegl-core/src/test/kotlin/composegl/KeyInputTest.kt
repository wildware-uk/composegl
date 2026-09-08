package composegl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyInputTest {

    private val context = ComposeGlContext.createRaster()
    private val surface = ComposeSurface(context, object : HostServices { override val density = 1f })
    private val first = FocusRequester()
    private var a by mutableStateOf(TextFieldValue(""))
    private var b by mutableStateOf(TextFieldValue(""))
    private var nanos = 0L

    @AfterEach
    fun tearDown() = context.dispose()

    private fun frame() {
        nanos += 16_666_667
        surface.update(nanos)
        if (surface.needsRedraw) surface.render(nanos)
    }

    private fun show(content: @Composable () -> Unit) {
        surface.setContent(content)
        surface.setRenderTarget(RenderTarget.Raster(400, 300))
        frame()
    }

    private fun twoFields() = show {
        Column {
            // singleLine, because a multi-line field treats Tab as a character to insert.
            TextField(a, { a = it }, Modifier.focusRequester(first), singleLine = true)
            TextField(b, { b = it }, singleLine = true)
        }
    }

    private fun tap(key: Key, modifiers: PointerKeyboardModifiers = PointerKeyboardModifiers()): Boolean {
        val down = surface.sendKeyEvent(key, down = true, modifiers = modifiers)
        surface.sendKeyEvent(key, down = false, modifiers = modifiers)
        frame()
        return down
    }

    private fun type(text: String) = text.forEach { surface.sendChar(it.code); frame() }

    @Test
    fun `keys are ignored while nothing in the HUD has focus`() {
        twoFields()
        assertFalse(surface.hasKeyboardFocus)
        assertFalse(tap(Key.A), "otherwise WASD would drive a list instead of the player")
        assertFalse(tap(Key.Backspace))
        assertEquals("", a.text)
    }

    @Test
    fun `Backspace deletes a character from the focused field`() {
        twoFields()
        first.requestFocus()
        frame()
        type("abc")
        assertEquals("abc", a.text)

        assertTrue(tap(Key.Backspace), "Compose's own text field handling takes it")
        assertEquals("ab", a.text)
    }

    @Test
    fun `arrows and shift select without any help from us`() {
        twoFields()
        first.requestFocus()
        frame()
        type("hello")

        tap(Key.DirectionLeft)
        assertEquals(4, a.selection.start, "the caret moved, and nothing was deleted")

        tap(Key.DirectionLeft, PointerKeyboardModifiers(isShiftPressed = true))
        assertTrue(a.selection.length > 0, "shift-arrow selects")
    }

    @Test
    fun `Tab moves focus between fields`() {
        twoFields()
        first.requestFocus()
        frame()
        type("one")
        assertEquals("one", a.text)

        assertTrue(tap(Key.Tab), "Compose consumes Tab to move focus")
        type("two")

        assertEquals("one", a.text, "the first field is done")
        assertEquals("two", b.text, "and the second one now has the keys")
        assertTrue(surface.hasKeyboardFocus)
    }

    @Test
    fun `key events before setContent are refused`() {
        assertFalse(surface.sendKeyEvent(Key.A, down = true))
    }

    @Test
    fun `focus lost means keys go back to the game`() {
        twoFields()
        first.requestFocus()
        frame()
        assertTrue(surface.hasKeyboardFocus)

        surface.setContent { Box(Modifier.fillMaxSize()) }
        frame()

        assertFalse(surface.hasKeyboardFocus)
        assertFalse(tap(Key.A))
    }
}
