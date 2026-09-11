package composegl.ui.text

import composegl.ui.input.Key
import composegl.ui.input.KeyEvent
import composegl.ui.input.KeyEventType
import composegl.ui.input.TextEvent

/**
 * A keyboard, turned into edits.
 *
 * This is the seam that broke twice in the previous version of this project, so it is worth being
 * precise about what the two streams are. A **key** is a physical thing going down and coming up,
 * and is what an arrow, Backspace and a shortcut are made of. **Text** is what the platform decided
 * the player typed, after shift, after a dead key, after an input method thought about it.
 *
 * Both bugs lived in the gap between those two ideas:
 *
 * - Backspace also arrives as a *character* on most platforms, and inserting it put a square in the
 *   field. So every control character is refused here, whatever a backend claims about filtering
 *   them — two locks on a door that has been walked through before.
 * - A held key repeats, and on some platforms a repeat arrives with nothing to say it is not a
 *   fresh press while on others it is flagged. Either way a repeat is a keystroke: holding
 *   backspace deletes, one character per repeat.
 *
 * What it deliberately does not do is decide what happens to a key it does not want. `onKey`
 * returns null for Tab, Escape, Enter in a single-line field and every shortcut it has no use for,
 * and the widget lets those carry on outwards to the focus manager, the dialogue and the game. A
 * field that swallows Tab is a field a pad cannot leave.
 *
 * @param multiline whether Enter makes a line here or belongs to whatever is around the field.
 */
class KeyboardEditor(private val multiline: Boolean = false) {

    /**
     * @return the value after this key, or null if the key was not the field's to deal with.
     */
    fun onKey(event: KeyEvent, value: TextFieldValue): TextFieldValue? {
        if (event.type != KeyEventType.Down) return null

        val shift = event.modifiers.shift
        val shortcut = event.modifiers.isPrimary

        return when (event.key) {
            Key.Backspace -> value.apply(EditCommand.DeleteBackward)
            Key.Delete -> value.apply(EditCommand.DeleteForward)

            Key.Left -> value.move(if (shortcut) Movement.WordLeft else Movement.Left, shift)
            Key.Right -> value.move(if (shortcut) Movement.WordRight else Movement.Right, shift)

            Key.Home -> value.move(if (shortcut) Movement.TextStart else Movement.LineStart, shift)
            Key.End -> value.move(if (shortcut) Movement.TextEnd else Movement.LineEnd, shift)

            Key.A -> if (shortcut) value.selectAll() else null

            Key.Enter -> if (multiline) value.apply(EditCommand.Insert("\n")) else null

            else -> null
        }
    }

    /**
     * @return the value with [event] typed into it, or null if there was nothing to type.
     *
     * A control character is nothing to type. So is an empty string, which is what a platform sends
     * when a dead key is waiting for the letter it will become.
     */
    fun onText(event: TextEvent, value: TextFieldValue): TextFieldValue? {
        val text = event.text
        if (text.isEmpty() || text.any { it.isControlCharacter() }) return null
        return value.apply(EditCommand.Insert(text))
    }
}

/**
 * True for the characters that are commands rather than words.
 *
 * The C0 range, delete, and the C1 range that arrives from some platforms as a "character". A
 * newline is one of these: a field that wants newlines gets them from Enter, where the decision
 * about whether it is allowed one can actually be made.
 */
private fun Char.isControlCharacter(): Boolean = code < 0x20 || code in 0x7F..0x9F
