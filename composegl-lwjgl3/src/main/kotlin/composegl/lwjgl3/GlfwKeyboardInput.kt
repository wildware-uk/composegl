package composegl.lwjgl3

import composegl.ui.input.InputSink
import composegl.ui.input.Key
import composegl.ui.input.KeyEvent
import composegl.ui.input.KeyEventType
import composegl.ui.input.Modifiers
import composegl.ui.input.TextEvent
import org.lwjgl.glfw.GLFW

/**
 * GLFW's keyboard, translated into the toolkit's.
 *
 * Two streams, kept apart on purpose. A **key** is a physical thing going down and coming up, and
 * is what a shortcut, an arrow and Escape are made of. **Text** is what the platform decided the
 * player typed, after Shift, after a dead key, after a Chinese input method thought about it for a
 * while. One Shift and one A make two key events and one character; a Japanese phrase makes twenty
 * key events and three characters, much later. A toolkit that conflates them inserts a square where
 * Backspace should have been, which is a bug this project has already shipped once.
 *
 * Control characters are dropped from the text stream rather than passed on, because GLFW's
 * character callback does not send them and a backend that invented them would be reintroducing
 * exactly that bug.
 *
 * The methods below are the whole translation and can be called directly, which is how they are
 * tested. [attachTo] wires them to a real window.
 *
 * @param sink where the translated events go.
 */
class GlfwKeyboardInput(private val sink: InputSink) {

    init {
        // The toolkit has no platform to ask, so a backend tells it. The difference between
        // Command-C and Control-C is something only a desktop JVM is in a position to know.
        Modifiers.isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")
    }

    fun attachTo(window: GlfwWindow) {
        GLFW.glfwSetKeyCallback(window.handle) { _, key, _, action, mods -> key(key, action, mods) }
        GLFW.glfwSetCharCallback(window.handle) { _, codepoint -> typed(codepoint) }
    }

    /**
     * One key going down, repeating or coming up.
     *
     * @param glfwKey a `GLFW_KEY_*` constant.
     * @param action `GLFW_PRESS`, `GLFW_REPEAT` or `GLFW_RELEASE`.
     * @param mods GLFW's modifier bitfield, as the callback reports it.
     */
    fun key(glfwKey: Int, action: Int, mods: Int): Boolean {
        val key = named(glfwKey)
        val type = if (action == GLFW.GLFW_RELEASE) KeyEventType.Up else KeyEventType.Down
        // GLFW tells us outright when it is repeating a held key, so the toolkit never has to
        // guess, and the player's own key-repeat settings are the ones that apply.
        val repeat = action == GLFW.GLFW_REPEAT
        return sink.onKey(KeyEvent(key, type, modifiers(mods), repeat))
    }

    /**
     * One character the platform has committed to.
     *
     * @param codepoint a Unicode code point, which may be outside the basic plane — an emoji
     *   arrives as one of these and takes two chars to hold.
     */
    fun typed(codepoint: Int): Boolean {
        // Nothing below a space is text. GLFW does not send these, and a backend that did would be
        // asking a text field to insert a backspace.
        if (codepoint < 0x20 || codepoint == 0x7F) return false
        return sink.onText(TextEvent(codepointToString(codepoint)))
    }

    private fun codepointToString(codepoint: Int): String =
        String(Character.toChars(codepoint))

    private fun modifiers(mods: Int): Modifiers {
        var bits = 0
        if (mods and GLFW.GLFW_MOD_SHIFT != 0) bits = bits or Modifiers.SHIFT
        if (mods and GLFW.GLFW_MOD_CONTROL != 0) bits = bits or Modifiers.CONTROL
        if (mods and GLFW.GLFW_MOD_ALT != 0) bits = bits or Modifiers.ALT
        if (mods and GLFW.GLFW_MOD_SUPER != 0) bits = bits or Modifiers.META
        return Modifiers(bits)
    }

    /**
     * A GLFW key, as the toolkit names it.
     *
     * Both halves of a modifier — left Shift and right Shift — come through as the one name, since
     * no interface has ever wanted to know which. Anything not listed is [Key.Unknown] rather than
     * dropped, so a strange keyboard produces an event the game can still look at.
     */
    private fun named(glfwKey: Int): Key = keys[glfwKey] ?: Key.Unknown

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
            letters.forEachIndexed { index, key -> put(GLFW.GLFW_KEY_A + index, key) }
            digits.forEachIndexed { index, key ->
                put(GLFW.GLFW_KEY_0 + index, key)
                // The number pad types the same digits, and nothing in an interface has ever
                // wanted to know which row they came from.
                put(GLFW.GLFW_KEY_KP_0 + index, key)
            }
            functions.forEachIndexed { index, key -> put(GLFW.GLFW_KEY_F1 + index, key) }

            put(GLFW.GLFW_KEY_LEFT, Key.Left)
            put(GLFW.GLFW_KEY_RIGHT, Key.Right)
            put(GLFW.GLFW_KEY_UP, Key.Up)
            put(GLFW.GLFW_KEY_DOWN, Key.Down)
            put(GLFW.GLFW_KEY_HOME, Key.Home)
            put(GLFW.GLFW_KEY_END, Key.End)
            put(GLFW.GLFW_KEY_PAGE_UP, Key.PageUp)
            put(GLFW.GLFW_KEY_PAGE_DOWN, Key.PageDown)

            put(GLFW.GLFW_KEY_ENTER, Key.Enter)
            put(GLFW.GLFW_KEY_KP_ENTER, Key.Enter)
            put(GLFW.GLFW_KEY_ESCAPE, Key.Escape)
            put(GLFW.GLFW_KEY_TAB, Key.Tab)
            put(GLFW.GLFW_KEY_SPACE, Key.Space)
            put(GLFW.GLFW_KEY_BACKSPACE, Key.Backspace)
            put(GLFW.GLFW_KEY_DELETE, Key.Delete)
            put(GLFW.GLFW_KEY_INSERT, Key.Insert)

            put(GLFW.GLFW_KEY_LEFT_SHIFT, Key.Shift)
            put(GLFW.GLFW_KEY_RIGHT_SHIFT, Key.Shift)
            put(GLFW.GLFW_KEY_LEFT_CONTROL, Key.Control)
            put(GLFW.GLFW_KEY_RIGHT_CONTROL, Key.Control)
            put(GLFW.GLFW_KEY_LEFT_ALT, Key.Alt)
            put(GLFW.GLFW_KEY_RIGHT_ALT, Key.Alt)
            put(GLFW.GLFW_KEY_LEFT_SUPER, Key.Meta)
            put(GLFW.GLFW_KEY_RIGHT_SUPER, Key.Meta)
            put(GLFW.GLFW_KEY_CAPS_LOCK, Key.CapsLock)

            put(GLFW.GLFW_KEY_MINUS, Key.Minus)
            put(GLFW.GLFW_KEY_EQUAL, Key.Equals)
            put(GLFW.GLFW_KEY_LEFT_BRACKET, Key.LeftBracket)
            put(GLFW.GLFW_KEY_RIGHT_BRACKET, Key.RightBracket)
            put(GLFW.GLFW_KEY_BACKSLASH, Key.Backslash)
            put(GLFW.GLFW_KEY_SEMICOLON, Key.Semicolon)
            put(GLFW.GLFW_KEY_APOSTROPHE, Key.Apostrophe)
            put(GLFW.GLFW_KEY_GRAVE_ACCENT, Key.Grave)
            put(GLFW.GLFW_KEY_COMMA, Key.Comma)
            put(GLFW.GLFW_KEY_PERIOD, Key.Period)
            put(GLFW.GLFW_KEY_SLASH, Key.Slash)
        }
    }
}
