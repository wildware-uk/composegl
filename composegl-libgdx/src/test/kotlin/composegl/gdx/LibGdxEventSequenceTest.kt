package composegl.gdx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badlogic.gdx.Input
import composegl.ComposeGlContext
import composegl.ComposeSurface
import composegl.HostServices
import composegl.RenderTarget
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whole gestures, replayed exactly as LibGDX emits them.
 *
 * The tests next door call one method at a time with tidy arguments, and that is how two bugs got
 * shipped: LibGDX's real protocol is untidy. It synthesises a character alongside a key press,
 * reports every key repeat as *only* another `keyTyped`, and expects a release for a press nobody
 * wanted. None of that shows up when you call the API the way its author imagined it.
 *
 * So each test here is a sequence a person actually produces, written from
 * `DefaultLwjgl3Input.keyCallback` rather than from ComposeGL's own head. If a change breaks one
 * of these, it broke typing.
 */
class LibGdxEventSequenceTest {

    private val context = ComposeGlContext.createRaster()
    private val surface = ComposeSurface(context, object : HostServices { override val density = 1f })
    private val input = ComposeInputProcessor(
        surface = surface,
        scale = { 1f },
        pointerType = { PointerType.Mouse },
        modifiers = { PointerKeyboardModifiers() },
    )
    private var nanos = 0L

    @AfterEach
    fun tearDown() = context.dispose()

    private fun show(content: @Composable () -> Unit) {
        surface.setContent(content)
        surface.setRenderTarget(RenderTarget.Raster(400, 300))
        frame()
    }

    private fun frame() {
        nanos += 16_666_667
        surface.update(nanos)
        if (surface.needsRedraw) surface.render(nanos)
    }

    // --- What LibGDX actually sends. See DefaultLwjgl3Input.keyCallback.

    /** A press: keyDown, then the character LibGDX synthesises for keys that have one. */
    private fun press(keycode: Int, character: Char?) {
        input.keyDown(keycode)
        character?.let { input.keyTyped(it) }
        frame()
    }

    /** A repeat while the key is held: only ever another keyTyped. There is no second keyDown. */
    private fun repeatKey(character: Char) {
        input.keyTyped(character)
        frame()
    }

    private fun release(keycode: Int) {
        input.keyUp(keycode)
        frame()
    }

    private fun tap(keycode: Int, character: Char?) {
        press(keycode, character)
        release(keycode)
    }

    private fun field(initial: String = ""): () -> TextFieldValue {
        var value by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
        val focus = FocusRequester()
        // singleLine, like a HUD name field. A multi-line field inserts a newline on Enter, which
        // is correct and would make the control-character test below wrong for the right reason.
        show { TextField(value, { value = it }, Modifier.focusRequester(focus), singleLine = true) }
        focus.requestFocus()
        frame()
        return { value }
    }

    @Test
    fun `tapping a letter types it once`() {
        val value = field()
        tap(Input.Keys.H, 'h')
        assertEquals("h", value().text)
    }

    @Test
    fun `holding a letter repeats it`() {
        val value = field()
        press(Input.Keys.H, 'h')
        repeat(4) { repeatKey('h') }
        release(Input.Keys.H)
        assertEquals("hhhhh", value().text, "one from the press, one per repeat")
    }

    @Test
    fun `tapping Backspace deletes exactly one character`() {
        val value = field("abc")
        tap(Input.Keys.BACKSPACE, '\b')
        assertEquals("ab", value().text)
    }

    @Test
    fun `holding Backspace empties the field`() {
        val value = field("player one")
        press(Input.Keys.BACKSPACE, '\b')
        repeat(20) { repeatKey('\b') }
        release(Input.Keys.BACKSPACE)
        assertEquals("", value().text)
    }

    @Test
    fun `no keystroke ever inserts an unprintable character`() {
        val value = field()
        // Every one of these arrives as a key event *and* a control character.
        tap(Input.Keys.BACKSPACE, '\b')
        tap(Input.Keys.ENTER, '\r')
        tap(Input.Keys.ESCAPE, '\u001b')
        tap(Input.Keys.TAB, '\t')
        assertEquals("", value().text, "control characters are keys, never text")
    }

    @Test
    fun `shift and a letter give a capital`() {
        val value = field()
        input.keyDown(Input.Keys.SHIFT_LEFT)
        // LibGDX applies the keyboard layout itself, so the character arrives capitalised.
        tap(Input.Keys.A, 'A')
        input.keyUp(Input.Keys.SHIFT_LEFT)
        frame()
        assertEquals("A", value().text)
    }

    @Test
    fun `a click the HUD did not want still leaves it working afterwards`() {
        var clicks = 0
        show {
            Box(Modifier.fillMaxSize()) {
                Button(onClick = { clicks++ }, modifier = Modifier.align(Alignment.TopStart)) {
                    Box(Modifier.size(60.dp, 20.dp))
                }
            }
        }

        // On empty HUD space: not consumed, and released like any other press.
        assertFalse(input.touchDown(390, 290, 0, Input.Buttons.LEFT))
        input.touchUp(390, 290, 0, Input.Buttons.LEFT)
        frame()

        // The in-world panel's bug would show up here as the same stuck pointer.
        assertTrue(input.touchDown(30, 20, 0, Input.Buttons.LEFT), "the button must still take a press")
        input.touchUp(30, 20, 0, Input.Buttons.LEFT)
        frame()
        assertEquals(1, clicks)
    }

    @Test
    fun `a cancelled drag does not strand the pointer`() {
        var clicks = 0
        show {
            Box(Modifier.fillMaxSize()) {
                Button(onClick = { clicks++ }, modifier = Modifier.align(Alignment.TopStart)) {
                    Box(Modifier.size(60.dp, 20.dp))
                }
            }
        }

        input.touchDown(30, 20, 0, Input.Buttons.LEFT)
        input.touchDragged(390, 290, 0)
        input.touchCancelled(390, 290, 0, Input.Buttons.LEFT)
        frame()

        assertTrue(input.touchDown(30, 20, 0, Input.Buttons.LEFT), "after a cancel the button still works")
        input.touchUp(30, 20, 0, Input.Buttons.LEFT)
        frame()
        assertEquals(1, clicks)
    }
}
