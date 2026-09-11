package dev.wildware.composegl.gdx

import com.badlogic.gdx.Input
import com.badlogic.gdx.Input.Buttons
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.KeyboardEditor
import dev.wildware.composegl.ui.text.TextFieldValue
import dev.wildware.composegl.ui.text.TextGestures
import dev.wildware.composegl.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Recorded LibGDX input, replayed into a text field.
 *
 * This suite is the most valuable thing that survived the previous version of this project. Both
 * bugs that reached a player lived between LibGDX's callbacks and a text field, and neither could
 * be reproduced by a unit test of either end on its own — so this is the whole path: the engine's
 * callbacks, the adapter, the toolkit's events, the editing model, the text a player would see.
 *
 * The sequences are the ones a real keyboard and a real mouse produce, written down as LibGDX
 * delivers them: a tap is three callbacks, a held key is many, and shift is a key like any other.
 */
class LibGdxEventSequenceTest {

    /** A field that edits itself from whatever arrives, which is what the widget will do. */
    private class Field(multiline: Boolean = false) : InputSink {

        private val editor = KeyboardEditor(multiline)

        val gestures = TextGestures(indexAt = { point ->
            ((point.x + Width / 2f) / Width).toInt().coerceIn(0, 20)
        })

        var value = TextFieldValue("", TextRange(0))

        /** Every key that arrived, so a test can assert what the *adapter* said as well. */
        val keys = mutableListOf<KeyEvent>()

        override fun onKey(event: KeyEvent): Boolean {
            keys += event
            val after = editor.onKey(event, value) ?: return false
            value = after
            return true
        }

        override fun onText(event: TextEvent): Boolean {
            val after = editor.onText(event, value) ?: return false
            value = after
            return true
        }

        override fun onPointer(event: PointerEvent): Boolean {
            val after = gestures.onPointer(event, value) ?: return false
            value = after
            return true
        }

        override fun onGamepad(event: GamepadEvent) = false

        companion object {
            /** The pretend width of a character, so a click lands on a character a test can name. */
            const val Width = 10f
        }
    }

    private val field = Field()
    private val keyboard = GdxKeyboardInput(field)

    private val pointer = GdxPointerInput(
        field,
        { Viewport(design = Size(800f, 600f), physical = Size(800f, 600f), policy = ScalePolicy.Fit) },
        hdpiScale = { 1f },
        clock = { clock },
    )

    private var clock = 0L

    // --- a tap ---------------------------------------------------------------------------------------

    @Test
    fun `a tap is a key down, a character, and a key up`() {
        keyboard.keyDown(Input.Keys.A)
        keyboard.keyTyped('a')
        keyboard.keyUp(Input.Keys.A)

        assertEquals("a", field.value.text)
        assertEquals(TextRange(1), field.value.selection)
    }

    @Test
    fun `a tap of backspace deletes once, and its character inserts nothing`() {
        field.value = TextFieldValue("abc", TextRange(3))

        keyboard.keyDown(Input.Keys.BACKSPACE)
        // LibGDX sends this too. In the previous version of this project it was inserted, and a
        // square appeared in the field.
        keyboard.keyTyped('\b')
        keyboard.keyUp(Input.Keys.BACKSPACE)

        assertEquals("ab", field.value.text)
    }

    // --- a held key ------------------------------------------------------------------------------------

    @Test
    fun `a held backspace deletes once per repeat`() {
        field.value = TextFieldValue("abcdef", TextRange(6))

        // What a desktop actually sends: the first press, then a repeat every few frames, each with
        // its own character. The repeat is the thing v1 ignored, and holding backspace did nothing.
        keyboard.keyDown(Input.Keys.BACKSPACE)
        keyboard.keyTyped('\b')
        repeat(3) {
            keyboard.keyDown(Input.Keys.BACKSPACE)
            keyboard.keyTyped('\b')
        }
        keyboard.keyUp(Input.Keys.BACKSPACE)

        assertEquals("ab", field.value.text, "four presses, four characters gone")
        assertTrue(field.keys.filter { it.repeat }.size == 3, "and three of them said they were repeats")
    }

    @Test
    fun `a held letter types once per repeat`() {
        keyboard.keyDown(Input.Keys.A)
        keyboard.keyTyped('a')
        repeat(4) {
            keyboard.keyDown(Input.Keys.A)
            keyboard.keyTyped('a')
        }
        keyboard.keyUp(Input.Keys.A)

        assertEquals("aaaaa", field.value.text)
    }

    @Test
    fun `a held arrow keeps moving and does not type`() {
        field.value = TextFieldValue("abcdef", TextRange(6))

        keyboard.keyDown(Input.Keys.LEFT)
        repeat(2) { keyboard.keyDown(Input.Keys.LEFT) }
        keyboard.keyUp(Input.Keys.LEFT)

        assertEquals("abcdef", field.value.text)
        assertEquals(TextRange(3), field.value.selection)
    }

    // --- shift -----------------------------------------------------------------------------------------

    @Test
    fun `shift is a key of its own and the platform decides what it types`() {
        keyboard.keyDown(Input.Keys.SHIFT_LEFT)
        keyboard.keyDown(Input.Keys.A)
        keyboard.keyTyped('A')
        keyboard.keyUp(Input.Keys.A)
        keyboard.keyUp(Input.Keys.SHIFT_LEFT)

        assertEquals("A", field.value.text, "one capital, not a capital and a lower case")
    }

    @Test
    fun `shift and an arrow selects`() {
        field.value = TextFieldValue("hello", TextRange(0))

        keyboard.keyDown(Input.Keys.SHIFT_LEFT)
        repeat(5) { keyboard.keyDown(Input.Keys.RIGHT) }
        keyboard.keyUp(Input.Keys.SHIFT_LEFT)

        assertEquals("hello", field.value.selected)
        assertTrue(field.keys.filter { it.key == dev.wildware.composegl.ui.input.Key.Right }.all { it.modifiers.shift })
    }

    @Test
    fun `a shift left behind by a lost window does not linger`() {
        keyboard.keyDown(Input.Keys.SHIFT_LEFT)
        keyboard.releaseAll()

        keyboard.keyDown(Input.Keys.A)
        keyboard.keyTyped('a')

        assertFalse(field.keys.last { it.key == dev.wildware.composegl.ui.input.Key.A }.modifiers.shift)
    }

    // --- a drag ------------------------------------------------------------------------------------------

    @Test
    fun `a drag selects what it crosses`() {
        field.value = TextFieldValue("hello world", TextRange(0))

        pointer.touchDown(0, 8, 0, Buttons.LEFT)
        clock += 16
        pointer.touchDragged(50, 8, 0)
        clock += 16
        pointer.touchUp(50, 8, 0, Buttons.LEFT)

        assertEquals("hello", field.value.selected)
    }

    @Test
    fun `a cancelled drag stops following the pointer`() {
        field.value = TextFieldValue("hello world", TextRange(0))

        pointer.touchDown(0, 8, 0, Buttons.LEFT)
        clock += 16
        pointer.touchDragged(50, 8, 0)
        clock += 16
        // The platform took the gesture away — a system swipe, a lost window.
        pointer.touchCancelled(50, 8, 0, Buttons.LEFT)
        clock += 16
        pointer.touchDragged(110, 8, 0)

        assertEquals("hello", field.value.selected, "the selection is where the drag was abandoned")
        assertFalse(field.gestures.isDragging)
    }

    @Test
    fun `a drag out of the window keeps selecting`() {
        field.value = TextFieldValue("hello world", TextRange(0))

        pointer.touchDown(60, 8, 0, Buttons.LEFT)
        clock += 16
        pointer.touchDragged(4000, 900, 0)

        assertEquals("world", field.value.selected, "below and right of the field is its end")
    }

    // --- everything at once -------------------------------------------------------------------------------

    @Test
    fun `a player types a name, fixes it, and selects a word`() {
        keyboard.keyDown(Input.Keys.SHIFT_LEFT)
        keyboard.keyTyped('R')
        keyboard.keyUp(Input.Keys.SHIFT_LEFT)
        "yland".forEach { keyboard.keyTyped(it) }
        assertEquals("Ryland", field.value.text)

        // Two too many: hold backspace.
        keyboard.keyDown(Input.Keys.BACKSPACE)
        keyboard.keyTyped('\b')
        keyboard.keyDown(Input.Keys.BACKSPACE)
        keyboard.keyTyped('\b')
        keyboard.keyUp(Input.Keys.BACKSPACE)
        assertEquals("Ryla", field.value.text)

        "nd Vos".forEach { keyboard.keyTyped(it) }
        assertEquals("Ryland Vos", field.value.text)

        // A double click on the surname.
        pointer.touchDown(85, 8, 0, Buttons.LEFT)
        pointer.touchUp(85, 8, 0, Buttons.LEFT)
        clock += 80
        pointer.touchDown(85, 8, 0, Buttons.LEFT)
        pointer.touchUp(85, 8, 0, Buttons.LEFT)

        assertEquals("Vos", field.value.selected)
    }
}
