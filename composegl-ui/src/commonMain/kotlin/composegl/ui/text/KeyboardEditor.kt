package composegl.ui.text

import composegl.ui.backend.Clipboard
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
 * @param clipboard where cut and copy put things and paste takes them from. The default keeps
 *   nothing, so the keys are safe to press in a game that has not wired one up.
 * @param maxLength how many characters the field will hold, or zero for no limit. Typing at the
 *   limit does nothing; a paste fills whatever room is left rather than being refused whole.
 */
class KeyboardEditor(
    private val multiline: Boolean = false,
    private val clipboard: Clipboard = Clipboard.None,
    private val maxLength: Int = 0,
) {

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

            Key.C -> if (shortcut) copy(value) else null
            Key.X -> if (shortcut) cut(value) else null
            Key.V -> if (shortcut) paste(value) else null

            Key.Enter -> if (multiline) insert(value, "\n") else null

            else -> null
        }
    }

    /**
     * Copy leaves the field exactly as it was, which is why it still counts as handled: the key was
     * used, and nothing behind the field should also act on it.
     */
    private fun copy(value: TextFieldValue): TextFieldValue? {
        if (value.selection.collapsed) return null
        clipboard.write(value.selected)
        return value
    }

    private fun cut(value: TextFieldValue): TextFieldValue? {
        if (value.selection.collapsed) return null
        clipboard.write(value.selected)
        return value.apply(EditCommand.Insert(""))
    }

    /**
     * Paste, with whatever the platform had on the clipboard made safe first.
     *
     * A clipboard holds anything: a paragraph copied from a web page, a tab-separated row from a
     * spreadsheet, a stray carriage return from a file written on Windows. A single-line field has
     * nowhere to put a line break, so each one becomes a space rather than being dropped — dropped
     * line breaks run two words together, and a player pasting an address into a name box would
     * rather see the words apart than `NorthgateSector Four`.
     */
    private fun paste(value: TextFieldValue): TextFieldValue? {
        val pasted = clipboard.read() ?: return null
        val clean = pasted.sanitised(multiline)
        if (clean.isEmpty()) return null
        return insert(value, clean)
    }

    /**
     * Puts [text] in, as far as the field has room for it.
     *
     * A field that is full swallows nothing and reports nothing happened, so the key falls through
     * to whatever is around it. A paste that is too long is trimmed rather than refused: filling the
     * last four letters of a name is more use than being told no.
     */
    private fun insert(value: TextFieldValue, text: String): TextFieldValue? {
        if (maxLength <= 0) return value.apply(EditCommand.Insert(text))

        val room = maxLength - (value.text.length - value.selection.length)
        if (room <= 0) return null
        val fitted = if (text.length <= room) text else text.take(room).withoutADanglingHalf()
        if (fitted.isEmpty()) return null
        return value.apply(EditCommand.Insert(fitted))
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
        return insert(value, text)
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

/**
 * Pasted text, with everything a field cannot hold taken out of it.
 *
 * Line breaks survive in a field that has lines, as one `\n` however the platform wrote them. A tab
 * becomes a space everywhere, because nothing here knows what a tab stop would be.
 */
private fun String.withoutADanglingHalf(): String =
    if (isNotEmpty() && last().isHighSurrogate()) dropLast(1) else this

private fun String.sanitised(multiline: Boolean): String {
    val lines = replace("\r\n", "\n").replace('\r', '\n')
    val flattened = if (multiline) lines else lines.replace('\n', ' ')
    return flattened.map { if (it == '\n' || !it.isControlCharacter()) it else ' ' }.joinToString("")
}
