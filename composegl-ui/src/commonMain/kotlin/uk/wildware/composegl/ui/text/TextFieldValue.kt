package uk.wildware.composegl.ui.text

/**
 * What is in a text field: the words, where the caret is, and what an IME is still deciding.
 *
 * This is the whole editing model, and it is deliberately pure. No node, no keyboard, no window, no
 * engine — a value in and a value out. Every bug that reached a player in the previous version of
 * this project lived in the seam between a toolkit and a keyboard, and the reason that seam could
 * hide a bug is that nobody could write a test for it without opening a window. This can be tested
 * to death on any machine, so it is.
 *
 * Every value is legal by construction. A selection that would fall outside the text is pulled
 * back to the end of it, and one that would cut a surrogate pair in half — the two `Char`s that
 * make up an emoji — is moved off the seam. There is no way to build an out-of-range one, which is
 * why nothing downstream has to check.
 *
 * @param text the content. Plain characters only: a backend must never send a control character
 *   here, and a newline is only ever in a field that asked for one.
 * @param selection where the player is. A [TextRange] with both ends the same is a caret.
 * @param composition what an IME is in the middle of, to be drawn underlined, or null when nothing
 *   is being composed. It is not part of what the game has been told the field says.
 */
class TextFieldValue private constructor(
    val text: String,
    val selection: TextRange,
    val composition: TextRange?,
) {

    fun copy(
        text: String = this.text,
        selection: TextRange = this.selection,
        composition: TextRange? = this.composition,
    ): TextFieldValue = invoke(text, selection, composition)

    /** What the player would see highlighted. Empty when the selection is a caret. */
    val selected: String get() = text.substring(selection.min, selection.max)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is TextFieldValue &&
                text == other.text &&
                selection == other.selection &&
                composition == other.composition)

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + selection.hashCode()
        result = 31 * result + composition.hashCode()
        return result
    }

    override fun toString(): String =
        "TextFieldValue(text='$text', selection=$selection, composition=$composition)"

    companion object {
        /**
         * The only way to make one, so there is only one place the rules live.
         *
         * Reads as a constructor — `TextFieldValue("hello")` — but it is a function, because a
         * constructor cannot change what it was handed and these values have to be made legal.
         */
        operator fun invoke(
            text: String = "",
            selection: TextRange = TextRange(text.length),
            composition: TextRange? = null,
        ): TextFieldValue = TextFieldValue(
            text = text,
            selection = text.legalise(selection),
            composition = composition?.let { text.legalise(it) },
        )

        val Empty = TextFieldValue()
    }
}

/**
 * One change to a field.
 *
 * These are what a keyboard, a clipboard and an IME all turn into, so the thing they all talk to is
 * a list of these rather than a widget. That is what makes "a held backspace deletes one character
 * per repeat" a test rather than a hope.
 */
sealed interface EditCommand {

    /**
     * Types [text] where the player is, replacing whatever is selected.
     *
     * The caret ends up after what was typed, and nothing is being composed any more: committing
     * text is exactly what ends a composition.
     */
    data class Insert(val text: String) : EditCommand

    /** Puts [text] in place of [range], wherever the player happens to be. */
    data class Replace(val range: TextRange, val text: String) : EditCommand

    /**
     * Backspace: the selection if there is one, otherwise the character before the caret.
     *
     * "Character" means what a player would point at, not a `Char` and not a code point: an emoji
     * with a skin tone on it is one backspace, and so is a family, and so is a flag. See
     * [graphemeBefore] for exactly how far those rules go.
     */
    data object DeleteBackward : EditCommand

    /** Delete: the selection if there is one, otherwise the character after the caret. */
    data object DeleteForward : EditCommand

    /** Moves the player, without changing a word. */
    data class SetSelection(val selection: TextRange) : EditCommand

    /** Marks what an IME is still deciding, or null to say it has stopped. */
    data class SetComposition(val composition: TextRange?) : EditCommand
}

/** The one that matters: a value and a change, in; the value afterwards, out. */
fun TextFieldValue.apply(command: EditCommand): TextFieldValue = when (command) {
    is EditCommand.Insert -> replace(selection, command.text)
    is EditCommand.Replace -> replace(command.range, command.text)
    is EditCommand.SetSelection -> copy(selection = command.selection)
    is EditCommand.SetComposition -> copy(composition = command.composition)

    EditCommand.DeleteBackward ->
        if (!selection.collapsed) replace(selection, "")
        else if (selection.min == 0) this
        else replace(TextRange(text.graphemeBefore(selection.min), selection.min), "")

    EditCommand.DeleteForward ->
        if (!selection.collapsed) replace(selection, "")
        else if (selection.max == text.length) this
        else replace(TextRange(selection.max, text.graphemeAfter(selection.max)), "")
}

/** A whole keystroke's worth, in order. A key can mean more than one change — paste over a word. */
fun TextFieldValue.apply(commands: List<EditCommand>): TextFieldValue =
    commands.fold(this) { value, command -> value.apply(command) }

/**
 * The one edit everything else is written in terms of.
 *
 * The caret lands after what was put in, which is where a player expects it whether they typed a
 * letter, pasted a paragraph or deleted a word. Changing the text ends any composition: what an IME
 * was deciding no longer describes anything that is there.
 */
private fun TextFieldValue.replace(range: TextRange, with: String): TextFieldValue {
    val legal = text.legalise(range)
    val changed = text.substring(0, legal.min) + with + text.substring(legal.max)
    return TextFieldValue(changed, TextRange(legal.min + with.length))
}

/**
 * A range this string can actually have: inside it, and not through the middle of a character.
 *
 * A caret between the two `Char`s of an emoji is moved to before it. Half a surrogate pair is not a
 * character, and a field that lets one be selected eventually shows a player a square.
 */
private fun String.legalise(range: TextRange): TextRange {
    val start = boundaryAt(range.start.coerceIn(0, length))
    val end = boundaryAt(range.end.coerceIn(0, length))
    return if (start == range.start && end == range.end) range else TextRange(start, end)
}

/** [index], moved back if it is sitting between the two halves of one character. */
private fun String.boundaryAt(index: Int): Int =
    if (index in 1..<length && this[index - 1].isHighSurrogate() && this[index].isLowSurrogate()) index - 1
    else index

/** Where the character before [index] starts: one back, or two for a surrogate pair. */
internal fun String.startOfCharBefore(index: Int): Int =
    if (index >= 2 && this[index - 2].isHighSurrogate() && this[index - 1].isLowSurrogate()) index - 2
    else index - 1

/** Where the character after [index] ends. */
internal fun String.endOfCharAfter(index: Int): Int =
    if (index + 1 < length && this[index].isHighSurrogate() && this[index + 1].isLowSurrogate()) index + 2
    else index + 1
