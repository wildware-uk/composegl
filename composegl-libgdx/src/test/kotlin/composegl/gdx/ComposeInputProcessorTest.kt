package composegl.gdx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.PointerButton
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bridge is tested against a real Compose surface drawing into a CPU bitmap — no window, no
 * GL, no LibGDX application. That is enough to prove the parts that go wrong in practice:
 * coordinate scaling on an HDPI display, and which events the game gets to see.
 */
class ComposeInputProcessorTest {

    private val context = ComposeGlContext.createRaster()
    private val surface = ComposeSurface(context, object : HostServices { override val density = 1f })
    private var scale = 1f
    private var modifiers = PointerKeyboardModifiers()
    private val input = ComposeInputProcessor(
        surface = surface,
        scale = { scale },
        pointerType = { PointerType.Mouse },
        modifiers = { modifiers },
    )
    private var nanos = 0L

    @AfterEach
    fun tearDown() = context.dispose()

    /** A 400x300 HUD with a 100x100 button in the top-left corner. */
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

    private fun cornerButton(onClick: () -> Unit = {}) = show {
        Box(Modifier.fillMaxSize()) {
            Button(onClick = onClick, modifier = Modifier.align(Alignment.TopStart)) {
                Box(Modifier.size(60.dp))
            }
        }
    }

    @Test
    fun `a click on a button is consumed and never reaches the game`() {
        var clicks = 0
        cornerButton { clicks++ }

        assertTrue(input.touchDown(30, 20, 0, Input.Buttons.LEFT))
        assertTrue(input.touchUp(30, 20, 0, Input.Buttons.LEFT))
        assertEquals(1, clicks)
    }

    @Test
    fun `a click on empty HUD falls through to the game`() {
        cornerButton()
        assertFalse(input.touchDown(390, 290, 0, Input.Buttons.LEFT))
        assertFalse(input.touchUp(390, 290, 0, Input.Buttons.LEFT))
    }

    @Test
    fun `coordinates are scaled from window pixels to framebuffer pixels`() {
        var clicks = 0
        cornerButton { clicks++ }
        scale = 2f

        // On a 2x display the same button sits at half the window coordinates.
        assertTrue(input.touchDown(15, 10, 0, Input.Buttons.LEFT), "15,10 window = 30,20 framebuffer")
        assertTrue(input.touchUp(15, 10, 0, Input.Buttons.LEFT))
        assertEquals(1, clicks)

        // And what would have hit the button unscaled now lands past it.
        assertFalse(input.touchDown(195, 145, 0, Input.Buttons.LEFT), "195,145 window = 390,290 framebuffer")
    }

    @Test
    fun `keyTyped is what puts characters into a text field`() {
        var value by mutableStateOf(TextFieldValue(""))
        val focus = FocusRequester()
        show {
            TextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.focusRequester(focus),
            )
        }
        focus.requestFocus()
        frame()

        assertTrue(input.keyTyped('h'))
        frame()
        assertTrue(input.keyTyped('i'))
        frame()
        assertEquals("hi", value.text)

        assertTrue(input.keyDown(Input.Keys.BACKSPACE), "and Backspace is a key event, not a character")
        input.keyUp(Input.Keys.BACKSPACE)
        frame()
        assertEquals("h", value.text)
    }

    @Test
    fun `LibGDX delivers control characters through keyTyped, and they must not be inserted`() {
        var value by mutableStateOf(TextFieldValue(""))
        val focus = FocusRequester()
        show { TextField(value, { value = it }, Modifier.focusRequester(focus)) }
        focus.requestFocus()
        frame()

        input.keyTyped('h')
        frame()
        assertEquals("h", value.text)

        // This is what LibGDX actually sends when Backspace is pressed: the key event *and*
        // keyTyped('\b'). Committing the control character puts an unprintable glyph in the
        // field — the "squares" a real user sees, one per repeat while the key is held.
        input.keyDown(Input.Keys.BACKSPACE)
        input.keyTyped('\b')
        input.keyUp(Input.Keys.BACKSPACE)
        frame()

        assertEquals("", value.text, "Backspace should delete, not insert")
    }

    @Test
    fun `holding Backspace clears the field, one delete per repeat`() {
        // Caret at the end, where it is after someone has typed. TextFieldValue defaults it to 0,
        // and Backspace at position 0 correctly does nothing.
        var value by mutableStateOf(TextFieldValue("player one", TextRange(10)))
        val focus = FocusRequester()
        show { TextField(value, { value = it }, Modifier.focusRequester(focus)) }
        focus.requestFocus()
        frame()

        // Exactly what LibGDX sends: keyDown once, then a keyTyped per repeat and nothing else.
        // See DefaultLwjgl3Input.keyCallback, the GLFW_REPEAT branch.
        input.keyDown(Input.Keys.BACKSPACE)
        input.keyTyped('\b')        // the character synthesised with the press
        frame()
        assertEquals("player on", value.text, "the press deletes exactly one character")

        repeat(9) {
            input.keyTyped('\b')    // each repeat
            frame()
        }
        input.keyUp(Input.Keys.BACKSPACE)
        frame()

        assertEquals("", value.text, "holding Backspace should empty the field")
    }

    @Test
    fun `a repeat only counts once the key is actually held`() {
        var value by mutableStateOf(TextFieldValue("ab", TextRange(2)))
        val focus = FocusRequester()
        show { TextField(value, { value = it }, Modifier.focusRequester(focus)) }
        focus.requestFocus()
        frame()

        // Press and release without holding: one delete, not two.
        input.keyDown(Input.Keys.BACKSPACE)
        input.keyTyped('\b')
        input.keyUp(Input.Keys.BACKSPACE)
        frame()

        assertEquals("a", value.text, "a tap must not delete twice")
    }

    @Test
    fun `every control character LibGDX can send is refused`() {
        var value by mutableStateOf(TextFieldValue(""))
        val focus = FocusRequester()
        show { TextField(value, { value = it }, Modifier.focusRequester(focus)) }
        focus.requestFocus()
        frame()

        // Backspace, tab, enter, escape, delete: LibGDX routes all of them through keyTyped.
        // With no key held, none of them is a repeat, so none should become text either.
        listOf('\b', '\t', '\r', '\n', '\u001b', '\u007f').forEach {
            input.keyTyped(it)
            frame()
        }

        assertEquals("", value.text, "no control character should reach the text field as text")
    }

    @Test
    fun `keys the HUD is not listening for go to the game`() {
        cornerButton()
        assertFalse(input.keyDown(Input.Keys.W), "nothing focused, so WASD belongs to the game")
        assertFalse(input.keyTyped('w'))
    }

    @Test
    fun `an unmapped key is dropped rather than sent as something else`() {
        cornerButton()
        assertFalse(input.keyDown(Input.Keys.WORLD_1))
        assertFalse(input.keyUp(Input.Keys.WORLD_1))
    }

    @Test
    fun `a cancelled touch gives the pointer back to the game`() {
        cornerButton()
        assertTrue(input.touchDown(30, 20, 0, Input.Buttons.LEFT))
        assertTrue(input.touchDragged(200, 150, 0), "captured until it is released or cancelled")

        assertFalse(input.touchCancelled(200, 150, 0, Input.Buttons.LEFT))
        assertFalse(input.touchDragged(200, 150, 0), "the capture is gone")
    }

    @Test
    fun `scroll uses the last pointer position`() {
        show {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.size(100.dp)
                        .align(Alignment.TopStart)
                        .verticalScroll(rememberScrollState()),
                ) { Box(Modifier.size(width = 100.dp, height = 1000.dp)) }
            }
        }

        input.mouseMoved(50, 50)
        assertTrue(input.scrolled(0f, 1f), "the wheel over a scrollable is Compose's")

        input.mouseMoved(390, 290)
        assertFalse(input.scrolled(0f, 1f), "the wheel over empty HUD is the game's")
    }

    @Test
    fun `mouse buttons map across`() {
        assertEquals(PointerButton.Primary, composeButtonFor(Input.Buttons.LEFT))
        assertEquals(PointerButton.Secondary, composeButtonFor(Input.Buttons.RIGHT))
        assertEquals(PointerButton.Tertiary, composeButtonFor(Input.Buttons.MIDDLE))
        assertEquals(PointerButton.Back, composeButtonFor(Input.Buttons.BACK))
        assertEquals(PointerButton.Forward, composeButtonFor(Input.Buttons.FORWARD))
        assertNull(composeButtonFor(-1), "a drag changes no button")
    }

    @Test
    fun `modifiers reach Compose`() {
        var value by mutableStateOf(TextFieldValue(""))
        val focus = FocusRequester()
        show {
            TextField(value, { value = it }, Modifier.focusRequester(focus))
        }
        focus.requestFocus()
        frame()
        input.keyTyped('a')
        input.keyTyped('b')
        frame()

        modifiers = modifiersOf(shift = true)
        input.keyDown(Input.Keys.LEFT)
        input.keyUp(Input.Keys.LEFT)
        frame()

        assertTrue(value.selection.length > 0, "shift-arrow must select, so the modifier got through")
    }
}
