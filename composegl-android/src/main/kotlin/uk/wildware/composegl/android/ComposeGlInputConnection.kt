package uk.wildware.composegl.android

import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import uk.wildware.composegl.ui.text.EditCommand
import uk.wildware.composegl.ui.text.TextFieldValue
import uk.wildware.composegl.ui.text.TextRange
import uk.wildware.composegl.ui.text.apply

/**
 * What Android's keyboard talks to, turned into the toolkit's own edits.
 *
 * An `InputConnection` is a two-way thing, and the second way is the half everybody forgets: the
 * keyboard reads the text back. Autocorrect works by looking at the word before the cursor and
 * deciding it is wrong; a Japanese keyboard works by replacing a run it sent a moment ago. Both
 * need this object to answer questions truthfully about text it does not own.
 *
 * So it keeps its own copy, and applies each edit to that copy with **the same
 * [uk.wildware.composegl.ui.text.apply] the field uses**. Not a second implementation that might disagree: one
 * function, two callers, and no way for the keyboard's idea of the text to drift from the field's.
 *
 * Where they can still disagree is when the field refuses something — a character limit, a disabled
 * field — or when the game sets the text from outside. That is why every edit reports back what it
 * believes the result is: [AndroidTextInput] compares, and restarts the keyboard when they differ.
 *
 * @param initial what the field said when the keyboard attached to it.
 * @param apply called with the edits to make and the value this believes will result.
 * @param submit called when the action button is pressed.
 */
internal class ComposeGlInputConnection(
    initial: TextFieldValue,
    private val multiline: Boolean,
    private val apply: (List<EditCommand>, TextFieldValue) -> Unit,
    private val submit: () -> Unit,
) : InputConnection {

    private var value: TextFieldValue = initial

    /**
     * How deep the keyboard's own batch is.
     *
     * A keyboard wraps a set of changes in `beginBatchEdit`/`endBatchEdit` and means them as one:
     * choosing a candidate is "replace this run" and "stop composing" together. Sending the halves
     * separately would tell the game about a value that never existed on screen.
     */
    private var batch = 0
    private val pending = mutableListOf<EditCommand>()

    private fun send(vararg commands: EditCommand): Boolean {
        for (command in commands) {
            value = value.apply(command)
            pending += command
        }
        if (batch == 0) flush()
        return true
    }

    private fun flush() {
        if (pending.isEmpty()) return
        apply(pending.toList(), value)
        pending.clear()
    }

    override fun beginBatchEdit(): Boolean {
        batch++
        return true
    }

    override fun endBatchEdit(): Boolean {
        batch = (batch - 1).coerceAtLeast(0)
        if (batch == 0) flush()
        return batch > 0
    }

    // --- what the keyboard reads ------------------------------------------------------------------

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence {
        val end = value.selection.min
        return value.text.substring((end - length).coerceAtLeast(0), end)
    }

    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence {
        val start = value.selection.max
        return value.text.substring(start, (start + length).coerceAtMost(value.text.length))
    }

    override fun getSelectedText(flags: Int): CharSequence? =
        if (value.selection.collapsed) null else value.selected

    override fun getCursorCapsMode(reqModes: Int): Int =
        TextUtils.getCapsMode(value.text, value.selection.min, reqModes)

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
        ExtractedText().also {
            it.text = value.text
            it.startOffset = 0
            it.partialStartOffset = -1
            it.partialEndOffset = -1
            it.selectionStart = value.selection.min
            it.selectionEnd = value.selection.max
        }

    // --- what the keyboard writes -----------------------------------------------------------------

    /**
     * Provisional text: what has been typed but not chosen yet.
     *
     * The composing run is replaced wholesale each time, because that is what the keyboard means —
     * it is re-sending the whole of what it is currently thinking, not appending to it.
     */
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val typed = text?.toString().orEmpty()
        val target = value.composition ?: value.selection
        val start = target.min
        return send(
            EditCommand.Replace(target, typed),
            EditCommand.SetSelection(caretAfter(start, typed.length, newCursorPosition)),
            EditCommand.SetComposition(if (typed.isEmpty()) null else TextRange(start, start + typed.length)),
        )
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val from = start.coerceIn(0, value.text.length)
        val to = end.coerceIn(0, value.text.length)
        return send(EditCommand.SetComposition(if (from == to) null else TextRange(from, to)))
    }

    override fun finishComposingText(): Boolean = send(EditCommand.SetComposition(null))

    /**
     * The keyboard has made up its mind: this text, for real.
     *
     * Also how autocorrect lands, and how a swiped word arrives — in both cases the committed text
     * is not what the player pressed.
     */
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val committed = text?.toString().orEmpty()
        val target = value.composition ?: value.selection
        val start = target.min
        return send(
            EditCommand.Replace(target, committed),
            EditCommand.SetSelection(caretAfter(start, committed.length, newCursorPosition)),
            EditCommand.SetComposition(null),
        )
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val before = value.selection.min
        val after = value.selection.max
        val commands = mutableListOf<EditCommand>()
        // After first, so removing it does not move the range that comes before it.
        if (afterLength > 0) {
            val to = (after + afterLength).coerceAtMost(value.text.length)
            commands += EditCommand.Replace(TextRange(after, to), "")
        }
        if (beforeLength > 0) {
            val from = (before - beforeLength).coerceAtLeast(0)
            commands += EditCommand.Replace(TextRange(from, before), "")
        }
        return send(*commands.toTypedArray())
    }

    /**
     * The same, counted in code points rather than in `Char`s.
     *
     * The difference is an emoji: one code point, two `Char`s. A keyboard asking to delete one code
     * point and being given one `Char` leaves half a surrogate pair behind.
     */
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val text = value.text
        val before = value.selection.min
        val after = value.selection.max
        val from = text.offsetByCodePoints(before, -beforeLength.coerceAtMost(text.codePointCount(0, before)))
        val available = text.codePointCount(after, text.length)
        val to = text.offsetByCodePoints(after, afterLength.coerceAtMost(available))
        return deleteSurroundingText(before - from, to - after)
    }

    override fun setSelection(start: Int, end: Int): Boolean =
        send(EditCommand.SetSelection(TextRange(start, end)))

    override fun performEditorAction(editorAction: Int): Boolean {
        if (editorAction == EditorInfo.IME_ACTION_UNSPECIFIED && multiline) return false
        submit()
        return true
    }

    /**
     * A key the keyboard chose to send as a key rather than as text.
     *
     * Mostly Backspace and Enter from a hardware keyboard, and from the handful of input methods
     * that still send delete this way. Everything else is already text by the time it gets here.
     */
    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (event == null || event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> send(EditCommand.DeleteBackward)
            KeyEvent.KEYCODE_FORWARD_DEL -> send(EditCommand.DeleteForward)
            KeyEvent.KEYCODE_ENTER -> if (multiline) send(EditCommand.Insert("\n")) else { submit(); true }
            else -> {
                val typed = event.unicodeChar
                if (typed == 0) false else send(EditCommand.Insert(typed.toChar().toString()))
            }
        }
    }

    // --- the ones there is nothing useful to do about ----------------------------------------------
    //
    // Every one of these is optional, and answering false is the documented way to say "this field
    // does not do that". Saying true and doing nothing is what makes a keyboard wait for something
    // that is never going to happen.

    override fun commitCompletion(text: CompletionInfo?): Boolean = false

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false

    override fun performContextMenuAction(id: Int): Boolean = false

    override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = false

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false

    override fun commitContent(
        inputContentInfo: InputContentInfo,
        flags: Int,
        opts: android.os.Bundle?,
    ): Boolean = false

    override fun getHandler(): android.os.Handler? = null

    override fun clearMetaKeyStates(states: Int): Boolean = false

    override fun reportFullscreenMode(enabled: Boolean): Boolean = false

    override fun closeConnection() {
        batch = 0
        flush()
    }

    /**
     * Where Android wants the caret after an insertion.
     *
     * `newCursorPosition` is counted from the *end* of what was just put in when it is one or more,
     * and from the *start* of it when it is zero or less. That asymmetry is in the platform's
     * contract, and a keyboard that asks for 0 — several do — means "leave the caret where the text
     * began", not "at the beginning of the field".
     *
     * Counted against the text *after* the insertion, which has not happened yet when this is
     * called. Nothing is clamped to the current length for that reason — a caret past the end is
     * pulled back by [TextFieldValue] itself, which is the one place that knows how long the text
     * has become.
     */
    private fun caretAfter(start: Int, length: Int, newCursorPosition: Int): TextRange {
        val at = if (newCursorPosition > 0) {
            start + length + (newCursorPosition - 1)
        } else {
            start + newCursorPosition
        }
        return TextRange(at.coerceAtLeast(0))
    }
}
