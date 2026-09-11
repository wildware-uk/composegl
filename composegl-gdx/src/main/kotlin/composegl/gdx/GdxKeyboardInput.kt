package composegl.gdx

import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import composegl.ui.input.InputSink
import composegl.ui.input.Key
import composegl.ui.input.KeyEvent
import composegl.ui.input.KeyEventType
import composegl.ui.input.Modifiers
import composegl.ui.input.TextEvent

/**
 * LibGDX's keyboard, translated into the toolkit's.
 *
 * Two streams, kept apart on purpose. A **key** is a physical thing going down and coming up, and
 * is what a shortcut, an arrow and Escape are made of. **Text** is what the platform decided the
 * player typed, after Shift, after a dead key, after an input method thought about it. One Shift
 * and one A make two key events and one character. A toolkit that conflates them inserts a square
 * where Backspace should have been, which is a bug this project has already shipped once — so
 * control characters are filtered out of the text stream here, where LibGDX puts them.
 *
 * Two things LibGDX does not tell us, worked out here rather than left for the toolkit:
 *
 * - **Which modifiers are held.** LibGDX reports Shift as a key like any other, so the keys that
 *   are down are tracked and every event is stamped with them.
 * - **Whether a key is repeating.** A held key arrives as another `keyDown` with nothing to
 *   distinguish it, so a `keyDown` for a key already down is read as a repeat. That is what stops
 *   a held Enter firing a button over and over.
 *
 * Meant to sit in an `InputMultiplexer` beside [GdxPointerInput], which is how one game has both
 * without either knowing about the other.
 *
 * @param sink where the translated events go.
 */
class GdxKeyboardInput(private val sink: InputSink) : InputAdapter() {

    init {
        // The toolkit has no platform to ask, so a backend tells it. The difference between
        // Command-C and Control-C is something only a desktop JVM is in a position to know.
        Modifiers.isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")
    }

    /** Which keys are down. What tells a repeat from a fresh press, and what makes the modifiers. */
    private val held = mutableSetOf<Int>()

    override fun keyDown(keycode: Int): Boolean {
        val repeat = !held.add(keycode)
        return sink.onKey(KeyEvent(named(keycode), KeyEventType.Down, modifiers(), repeat))
    }

    override fun keyUp(keycode: Int): Boolean {
        held.remove(keycode)
        return sink.onKey(KeyEvent(named(keycode), KeyEventType.Up, modifiers()))
    }

    override fun keyTyped(character: Char): Boolean {
        // LibGDX sends Backspace, Tab, Enter and Escape through here as control characters. Passing
        // them on would ask a text field to insert them, which is exactly the bug this avoids.
        if (character.code < 0x20 || character.code == 0x7F) return false
        return sink.onText(TextEvent(character.toString()))
    }

    /**
     * Abandons every held key.
     *
     * Call it when the window loses focus. The platform will not necessarily report the release of
     * a key that was down when the window went away, and a Shift that is still held five minutes
     * later turns every click into a shift-click.
     */
    fun releaseAll() {
        held.toList().forEach { keycode ->
            held.remove(keycode)
            sink.onKey(KeyEvent(named(keycode), KeyEventType.Up, modifiers()))
        }
    }

    private fun modifiers(): Modifiers {
        var bits = 0
        if (Input.Keys.SHIFT_LEFT in held || Input.Keys.SHIFT_RIGHT in held) bits = bits or Modifiers.SHIFT
        if (Input.Keys.CONTROL_LEFT in held || Input.Keys.CONTROL_RIGHT in held) bits = bits or Modifiers.CONTROL
        if (Input.Keys.ALT_LEFT in held || Input.Keys.ALT_RIGHT in held) bits = bits or Modifiers.ALT
        if (Input.Keys.SYM in held) bits = bits or Modifiers.META
        return Modifiers(bits)
    }

    /**
     * A LibGDX key, as the toolkit names it.
     *
     * Both halves of a modifier come through as the one name, since no interface has ever wanted to
     * know which. Anything not listed is [Key.Unknown] rather than dropped, so a strange keyboard
     * still produces an event the game can look at.
     */
    private fun named(keycode: Int): Key = keys[keycode] ?: Key.Unknown

    private companion object {

        // Listed rather than worked out from the codes: the toolkit's numbering is its own
        // business, and a backend that did arithmetic on it would break the day it changed.
        val letters = listOf(
            Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L,
            Key.M, Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X,
            Key.Y, Key.Z,
        )

        val digits = listOf(
            Key.Digit0, Key.Digit1, Key.Digit2, Key.Digit3, Key.Digit4,
            Key.Digit5, Key.Digit6, Key.Digit7, Key.Digit8, Key.Digit9,
        )

        val functions = listOf(
            Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6,
            Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
        )

        val keys: Map<Int, Key> = buildMap {
            letters.forEachIndexed { index, key -> put(Input.Keys.A + index, key) }
            digits.forEachIndexed { index, key ->
                put(Input.Keys.NUM_0 + index, key)
                // The number pad types the same digits, and nothing in an interface has ever
                // wanted to know which row they came from.
                put(Input.Keys.NUMPAD_0 + index, key)
            }
            functions.forEachIndexed { index, key -> put(Input.Keys.F1 + index, key) }

            put(Input.Keys.LEFT, Key.Left)
            put(Input.Keys.RIGHT, Key.Right)
            put(Input.Keys.UP, Key.Up)
            put(Input.Keys.DOWN, Key.Down)
            put(Input.Keys.HOME, Key.Home)
            put(Input.Keys.END, Key.End)
            put(Input.Keys.PAGE_UP, Key.PageUp)
            put(Input.Keys.PAGE_DOWN, Key.PageDown)

            put(Input.Keys.ENTER, Key.Enter)
            put(Input.Keys.NUMPAD_ENTER, Key.Enter)
            put(Input.Keys.ESCAPE, Key.Escape)
            put(Input.Keys.TAB, Key.Tab)
            put(Input.Keys.SPACE, Key.Space)
            put(Input.Keys.BACKSPACE, Key.Backspace)
            put(Input.Keys.FORWARD_DEL, Key.Delete)
            put(Input.Keys.INSERT, Key.Insert)

            put(Input.Keys.SHIFT_LEFT, Key.Shift)
            put(Input.Keys.SHIFT_RIGHT, Key.Shift)
            put(Input.Keys.CONTROL_LEFT, Key.Control)
            put(Input.Keys.CONTROL_RIGHT, Key.Control)
            put(Input.Keys.ALT_LEFT, Key.Alt)
            put(Input.Keys.ALT_RIGHT, Key.Alt)
            put(Input.Keys.SYM, Key.Meta)

            put(Input.Keys.MINUS, Key.Minus)
            put(Input.Keys.EQUALS, Key.Equals)
            put(Input.Keys.LEFT_BRACKET, Key.LeftBracket)
            put(Input.Keys.RIGHT_BRACKET, Key.RightBracket)
            put(Input.Keys.BACKSLASH, Key.Backslash)
            put(Input.Keys.SEMICOLON, Key.Semicolon)
            put(Input.Keys.APOSTROPHE, Key.Apostrophe)
            put(Input.Keys.GRAVE, Key.Grave)
            put(Input.Keys.COMMA, Key.Comma)
            put(Input.Keys.PERIOD, Key.Period)
            put(Input.Keys.SLASH, Key.Slash)

            // Android's and a console's hardware back button. Escape means the same thing, and the
            // toolkit names them separately only so a game can tell them apart if it wants to.
            put(Input.Keys.BACK, Key.Back)
        }
    }
}
