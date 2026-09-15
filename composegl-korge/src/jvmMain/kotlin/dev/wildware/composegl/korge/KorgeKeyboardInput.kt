package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.Modifiers
import korlibs.event.Key as KorgeKey
import korlibs.event.KeyEvent as KorgeKeyEvent

/**
 * KorGE's keys, translated into the toolkit's.
 *
 * Keys only. KorGE sends a key going down, a key coming up and a character typed as three kinds of
 * the one event; the third is text, not a key, and [KorgeTextInput] has it. Keeping them apart is
 * what stops a text field inserting a square where Backspace should have been.
 *
 * Two things worked out here rather than left for the toolkit:
 *
 * - **Whether a key is repeating.** A held key arrives as another press with nothing to tell it
 *   apart, so a press for a key already down is a repeat. That stops a held Enter firing a button
 *   over and over.
 * - **Which modifiers are held.** Both what this has seen go down and what the window stamps on the
 *   event, so a Shift pressed before the window had focus still counts, and a Shift let go while it
 *   was away does not linger once [releaseAll] has run.
 *
 * @param sink where the translated events go.
 */
class KorgeKeyboardInput(private val sink: InputSink) {

    init {
        // The toolkit has no platform to ask, so a backend tells it: Command-C or Control-C.
        Modifiers.isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")
    }

    /** Which keys are down. What tells a repeat from a fresh press. */
    private val held = mutableSetOf<KorgeKey>()

    /** One KorGE key event. A typed character is declined: it is text. */
    fun onKey(event: KorgeKeyEvent): Boolean = when (event.type) {
        KorgeKeyEvent.Type.DOWN -> {
            val repeat = !held.add(event.key)
            sink.onKey(KeyEvent(named(event.key), KeyEventType.Down, modifiers(event), repeat))
        }
        KorgeKeyEvent.Type.UP -> {
            held.remove(event.key)
            sink.onKey(KeyEvent(named(event.key), KeyEventType.Up, modifiers(event)))
        }
        KorgeKeyEvent.Type.TYPE -> false
    }

    /**
     * Lets go of every held key. Call it when the window loses focus: the platform will not
     * necessarily report the release of a key that was down when it went away.
     */
    fun releaseAll() {
        held.toList().forEach { key ->
            held.remove(key)
            sink.onKey(KeyEvent(named(key), KeyEventType.Up, modifiers(null)))
        }
    }

    private fun modifiers(event: KorgeKeyEvent?): Modifiers {
        var bits = 0
        if (KorgeKey.SHIFT in held || event?.shift == true) bits = bits or Modifiers.SHIFT
        if (KorgeKey.CONTROL in held || event?.ctrl == true) bits = bits or Modifiers.CONTROL
        if (KorgeKey.ALT in held || event?.alt == true) bits = bits or Modifiers.ALT
        if (KorgeKey.META in held || KorgeKey.SUPER in held || event?.meta == true) bits = bits or Modifiers.META
        return Modifiers(bits)
    }

    companion object {

        /**
         * A KorGE key, as the toolkit names it. Anything not listed is [Key.Unknown] rather than
         * dropped, so a strange keyboard still produces an event the game can look at.
         */
        fun named(key: KorgeKey): Key = keys[key] ?: Key.Unknown

        private val keys: Map<KorgeKey, Key> = buildMap {
            // Listed rather than worked out from names: KorGE's enum and the toolkit's numbering are
            // each their own business.
            val letters = listOf(
                KorgeKey.A to Key.A, KorgeKey.B to Key.B, KorgeKey.C to Key.C, KorgeKey.D to Key.D,
                KorgeKey.E to Key.E, KorgeKey.F to Key.F, KorgeKey.G to Key.G, KorgeKey.H to Key.H,
                KorgeKey.I to Key.I, KorgeKey.J to Key.J, KorgeKey.K to Key.K, KorgeKey.L to Key.L,
                KorgeKey.M to Key.M, KorgeKey.N to Key.N, KorgeKey.O to Key.O, KorgeKey.P to Key.P,
                KorgeKey.Q to Key.Q, KorgeKey.R to Key.R, KorgeKey.S to Key.S, KorgeKey.T to Key.T,
                KorgeKey.U to Key.U, KorgeKey.V to Key.V, KorgeKey.W to Key.W, KorgeKey.X to Key.X,
                KorgeKey.Y to Key.Y, KorgeKey.Z to Key.Z,
            )
            letters.forEach { (from, to) -> put(from, to) }

            val digits = listOf(Key.Digit0, Key.Digit1, Key.Digit2, Key.Digit3, Key.Digit4, Key.Digit5, Key.Digit6, Key.Digit7, Key.Digit8, Key.Digit9)
            val row = listOf(KorgeKey.N0, KorgeKey.N1, KorgeKey.N2, KorgeKey.N3, KorgeKey.N4, KorgeKey.N5, KorgeKey.N6, KorgeKey.N7, KorgeKey.N8, KorgeKey.N9)
            val pad = listOf(KorgeKey.KP_0, KorgeKey.KP_1, KorgeKey.KP_2, KorgeKey.KP_3, KorgeKey.KP_4, KorgeKey.KP_5, KorgeKey.KP_6, KorgeKey.KP_7, KorgeKey.KP_8, KorgeKey.KP_9)
            // The number pad types the same digits, and nothing in an interface has ever wanted to
            // know which row they came from. (AWT sends the pad's digits as NUMPAD0, which is N0.)
            digits.forEachIndexed { index, key ->
                put(row[index], key)
                put(pad[index], key)
            }

            val functions = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
            val korgeFunctions = listOf(KorgeKey.F1, KorgeKey.F2, KorgeKey.F3, KorgeKey.F4, KorgeKey.F5, KorgeKey.F6, KorgeKey.F7, KorgeKey.F8, KorgeKey.F9, KorgeKey.F10, KorgeKey.F11, KorgeKey.F12)
            functions.forEachIndexed { index, key -> put(korgeFunctions[index], key) }

            put(KorgeKey.LEFT, Key.Left)
            put(KorgeKey.RIGHT, Key.Right)
            put(KorgeKey.UP, Key.Up)
            put(KorgeKey.DOWN, Key.Down)
            put(KorgeKey.KP_LEFT, Key.Left)
            put(KorgeKey.KP_RIGHT, Key.Right)
            put(KorgeKey.KP_UP, Key.Up)
            put(KorgeKey.KP_DOWN, Key.Down)
            put(KorgeKey.HOME, Key.Home)
            put(KorgeKey.END, Key.End)
            put(KorgeKey.PAGE_UP, Key.PageUp)
            put(KorgeKey.PAGE_DOWN, Key.PageDown)

            put(KorgeKey.ENTER, Key.Enter)
            put(KorgeKey.KP_ENTER, Key.Enter)
            put(KorgeKey.ESCAPE, Key.Escape)
            put(KorgeKey.TAB, Key.Tab)
            put(KorgeKey.SPACE, Key.Space)
            put(KorgeKey.BACKSPACE, Key.Backspace)
            put(KorgeKey.DELETE, Key.Delete)
            put(KorgeKey.INSERT, Key.Insert)

            // KorGE has one name for each modifier: LEFT_SHIFT and RIGHT_SHIFT are both SHIFT.
            put(KorgeKey.SHIFT, Key.Shift)
            put(KorgeKey.CONTROL, Key.Control)
            put(KorgeKey.ALT, Key.Alt)
            put(KorgeKey.META, Key.Meta)
            put(KorgeKey.SUPER, Key.Meta)
            put(KorgeKey.CAPS_LOCK, Key.CapsLock)

            // Punctuation, under every name a KorGE window sends it by: AWT says QUOTE and
            // BACKQUOTE and OPEN_BRACKET where another platform says APOSTROPHE and GRAVE_ACCENT.
            put(KorgeKey.MINUS, Key.Minus)
            put(KorgeKey.EQUAL, Key.Equals)
            put(KorgeKey.LEFT_BRACKET, Key.LeftBracket)
            put(KorgeKey.OPEN_BRACKET, Key.LeftBracket)
            put(KorgeKey.RIGHT_BRACKET, Key.RightBracket)
            put(KorgeKey.CLOSE_BRACKET, Key.RightBracket)
            put(KorgeKey.BACKSLASH, Key.Backslash)
            put(KorgeKey.SEMICOLON, Key.Semicolon)
            put(KorgeKey.APOSTROPHE, Key.Apostrophe)
            put(KorgeKey.QUOTE, Key.Apostrophe)
            put(KorgeKey.GRAVE_ACCENT, Key.Grave)
            put(KorgeKey.BACKQUOTE, Key.Grave)
            put(KorgeKey.GRAVE, Key.Grave)
            put(KorgeKey.COMMA, Key.Comma)
            put(KorgeKey.PERIOD, Key.Period)
            put(KorgeKey.SLASH, Key.Slash)

            // Android's and a TV remote's back button. Escape means the same thing.
            put(KorgeKey.BACK, Key.Back)
        }
    }
}
