package dev.wildware.composegl.ui.backend

import dev.wildware.composegl.ui.text.EditCommand
import dev.wildware.composegl.ui.text.TextFieldValue

/**
 * The platform's own text input, for the half of typing a key event cannot express.
 *
 * A key event says "the K key went down". That is enough for English and not enough for anything
 * else. Typing Japanese is two steps — the keys you press become a run of provisional text, and
 * then you choose what it turns into — and a phone's autocorrect is the same shape: the keyboard
 * holds a word open, changes its mind about it, and commits something that is not what was typed.
 * Neither can be expressed as a stream of characters, because both go back and edit what they
 * already sent.
 *
 * So a platform that has an input method gets to drive the field directly, through
 * [TextInputSession], instead of only being allowed to push characters at it. What it sends is the
 * same [EditCommand] list the keyboard and the clipboard send, so nothing downstream learns that an
 * input method exists.
 *
 * A game does not implement this. A backend does, and provides it through
 * [dev.wildware.composegl.ui.widget.ProvideTextInput]:
 *
 * ```kotlin
 * ProvideTextInput(AndroidTextInput(activity)) { Hud() }
 * ```
 *
 * Not the same thing as [SoftKeyboard], which is only about the keyboard being on screen and how
 * much it is covering. A desktop has no soft keyboard and may still have an input method; a phone
 * has both. They are separate because they are answered by different parts of a platform.
 */
interface TextInput {

    /**
     * A field has taken focus and wants an input method pointed at it.
     *
     * The same session object comes back to [stop], so a backend can ignore a stop for a session
     * that is not the one it is serving — which is what happens when focus moves straight from one
     * field to another and the new field starts before the old one stops.
     */
    fun start(session: TextInputSession)

    /**
     * The field's value changed from somewhere other than the input method — a key, a paste, a
     * click, or the game setting it.
     *
     * An input method keeps its own copy of the text and will happily edit a version that no longer
     * exists, so it has to be told. This is the call that stops a phone's autocorrect from
     * replacing a word the player has already deleted.
     */
    fun update(value: TextFieldValue)

    /** The field lost focus, or went away. */
    fun stop(session: TextInputSession)

    companion object {

        /**
         * No input method at all.
         *
         * What a desktop gets today, and what everything got before this existed: the field is
         * driven by key and character events alone, which is correct for any language you can type
         * one key at a time.
         */
        val None: TextInput = object : TextInput {
            override fun start(session: TextInputSession) = Unit
            override fun update(value: TextFieldValue) = Unit
            override fun stop(session: TextInputSession) = Unit
        }
    }
}

/**
 * One field, open to the platform's input method for as long as it has focus.
 *
 * **Call it on the thread the interface is composed on.** An input method runs on the platform's own
 * thread — on Android, the main thread, which is not the render thread — and a field's editing state
 * is not guarded. A [TextInput] that lives on a different thread from the game loop must hand its
 * work across; `AndroidTextInput` takes the game's own "run this on the render thread" for exactly
 * that reason.
 */
interface TextInputSession {

    /** What the field says right now, so an input method can work out what it is editing. */
    val value: TextFieldValue

    /** Whether Enter makes a line. Decides which key the input method's action button becomes. */
    val multiline: Boolean

    /**
     * Applies changes to the field, in order, as one edit.
     *
     * A list rather than one at a time because an input method sends a batch and means it as a
     * batch: choosing a candidate is "replace this range, and stop composing", and a field that
     * saw the first half on its own would tell the game about a value that never existed.
     */
    fun edit(commands: List<EditCommand>)

    /** The input method's action button was pressed — Done, Go, Search. */
    fun submit()
}
