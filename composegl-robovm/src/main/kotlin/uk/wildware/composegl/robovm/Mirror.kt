package uk.wildware.composegl.robovm

import uk.wildware.composegl.ui.text.EditCommand
import uk.wildware.composegl.ui.text.TextFieldValue
import uk.wildware.composegl.ui.text.TextRange

/**
 * Making the toolkit's field say what UIKit's field says.
 *
 * Split from [UiKitTextInput] because this is the half with the reasoning in it, and the other
 * half is UIKit calls that cannot run anywhere but an iPhone. This runs in a test.
 *
 * The strategy is deliberately the opposite of Android's. There, an `InputConnection` is told what
 * to do and the toolkit keeps the only real copy of the text. Here the platform gives us a whole
 * `UITextField` — a real one, hidden — and doing the editing ourselves would mean reimplementing
 * everything it already does: autocorrect, swipe, dictation, and the marked text that typing
 * Japanese is made of. So it edits, and the toolkit follows.
 *
 * Following means turning "it now says this" into the smallest edit that makes it so.
 */
internal object Mirror {

    /**
     * What to do to [value] so that it reads [text], with the caret at [selection].
     *
     * @param composition the marked range — UIKit's `markedTextRange` — or null when nothing is
     *   provisional. That is the underline under a half-typed kanji, and the field draws it.
     */
    fun commands(
        value: TextFieldValue,
        text: String,
        selection: TextRange,
        composition: TextRange? = null,
    ): List<EditCommand> {
        val sameText = value.text == text
        val sameSelection = value.selection == selection
        val sameComposition = value.composition == composition
        if (sameText && sameSelection && sameComposition) return emptyList()

        val commands = mutableListOf<EditCommand>()
        // Replacing only the part that differs, rather than the whole string, so that a field
        // holding a thousand characters does not rebuild all of them because one arrived.
        if (!sameText) commands += difference(value.text, text)
        if (!sameSelection || !sameText) commands += EditCommand.SetSelection(selection)
        if (!sameComposition || !sameText) commands += EditCommand.SetComposition(composition)
        return commands
    }

    /**
     * The one replacement that turns [before] into [after].
     *
     * Found by walking in from both ends. What is left in the middle is what changed — which for
     * a keystroke is one character, for an autocorrection is one word, and for a paste is the lot.
     */
    private fun difference(before: String, after: String): EditCommand.Replace {
        var start = 0
        val shortest = minOf(before.length, after.length)
        while (start < shortest && before[start] == after[start]) start++

        var fromEnd = 0
        while (
            fromEnd < shortest - start &&
            before[before.length - 1 - fromEnd] == after[after.length - 1 - fromEnd]
        ) fromEnd++

        return EditCommand.Replace(
            TextRange(start, before.length - fromEnd),
            after.substring(start, after.length - fromEnd),
        )
    }
}
