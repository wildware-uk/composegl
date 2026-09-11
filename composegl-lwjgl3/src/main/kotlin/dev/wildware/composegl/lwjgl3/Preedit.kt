package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.text.EditCommand
import dev.wildware.composegl.ui.text.TextFieldValue
import dev.wildware.composegl.ui.text.TextRange

/**
 * Turning what an input method is currently thinking into edits to a field.
 *
 * Kept apart from [GlfwTextInput] on purpose. The other half of that class is a native callback
 * with a pointer in it and cannot be run without a window, a display and an input method installed;
 * this half is the part with the reasoning in it, and it is ordinary Kotlin that a test can call.
 *
 * A preedit is the run of provisional text under the caret — the `ni` you have typed on the way to
 * `に`, shown underlined because it is not a decision yet. GLFW hands it over whole every time it
 * changes, so each of these replaces the run rather than adding to it.
 */
internal object Preedit {

    /**
     * The input method is thinking, and this is what it currently has.
     *
     * @param text the whole provisional run, which replaces whatever the last one was.
     * @param caretInCodePoints where the caret sits inside that run, counted the way the platform
     *   counts it — in code points, not in `Char`s. The difference is an emoji or a rarer CJK
     *   character: one code point, two `Char`s, and a caret placed by the wrong count lands inside
     *   a surrogate pair.
     */
    fun compose(text: String, caretInCodePoints: Int, value: TextFieldValue): List<EditCommand> {
        if (text.isEmpty()) return clear(value)
        val target = value.composition ?: value.selection
        val start = target.min
        val caret = start + charOffset(text, caretInCodePoints)
        return listOf(
            EditCommand.Replace(target, text),
            EditCommand.SetSelection(TextRange(caret)),
            EditCommand.SetComposition(TextRange(start, start + text.length)),
        )
    }

    /**
     * The input method has stopped thinking: either it was cancelled, or it has made up its mind.
     *
     * Both look the same from here, and both mean the provisional run goes away. What was chosen
     * arrives separately as ordinary typed characters — GLFW sends a commit through the character
     * callback, the same path a plain key press takes — so this must not try to keep the text. A
     * field that kept it would show the half-typed romaji as well as the kanji it became.
     */
    fun clear(value: TextFieldValue): List<EditCommand> {
        val composing = value.composition ?: return emptyList()
        return listOf(
            EditCommand.Replace(composing, ""),
            EditCommand.SetSelection(TextRange(composing.min)),
            EditCommand.SetComposition(null),
        )
    }

    /** How far into the string a code-point count reaches, in `Char`s. */
    private fun charOffset(text: String, codePoints: Int): Int {
        val available = text.codePointCount(0, text.length)
        return text.offsetByCodePoints(0, codePoints.coerceIn(0, available))
    }
}
