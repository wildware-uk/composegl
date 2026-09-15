package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.backend.TextInput
import dev.wildware.composegl.ui.backend.TextInputSession
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.text.TextFieldValue
import korlibs.event.KeyEvent as KorgeKeyEvent

/**
 * What the player typed, as KorGE reports it, and the text field's end of the platform's typing.
 *
 * **Typed text.** KorGE sends a typed character as a key event of its own kind, `TYPE`, after the
 * platform has applied Shift, dead keys and the keyboard layout. [onKey] turns those into
 * [TextEvent]s. Control characters are dropped: an AWT window sends Backspace, Enter and Tab through
 * here as well as as keys, and inserting them is a bug this project has already shipped once. A soft
 * keyboard or an input method that commits several characters at once arrives as one `str`, and goes
 * on as one event, so a field sees one edit rather than one per character.
 *
 * **The input method.** KorGE 6 does not expose a preedit — the provisional text an input method
 * shows while you are choosing, say, a kanji. Its AWT window listens for key events only, not
 * `InputMethodListener`, and nothing in its common window API carries a composition. So as a
 * [TextInput] this can only keep track of which field is open, which is what a game reads through
 * [session]. Text still arrives, committed, through [onKey]: correct for anything that can be typed
 * a key at a time, and for an input method that commits whole strings, but with no underlined
 * provisional run. When KorGE exposes one, it goes here, the way `GlfwTextInput` does it.
 *
 * @param sink where typed text goes.
 */
class KorgeTextInput(private val sink: InputSink) : TextInput {

    /** The field that has focus and wants typing, or null. */
    var session: TextInputSession? = null
        private set

    /** One KorGE key event. Only a typed character is text; presses and releases are declined. */
    fun onKey(event: KorgeKeyEvent): Boolean {
        if (event.type != KorgeKeyEvent.Type.TYPE) return false
        val text = event.characters().filterNot { it.code < 0x20 || it.code == 0x7F }
        if (text.isEmpty()) return false
        return sink.onText(TextEvent(text))
    }

    override fun start(session: TextInputSession) {
        this.session = session
    }

    /** Nothing to tell KorGE: it keeps no copy of the text. */
    override fun update(value: TextFieldValue) = Unit

    override fun stop(session: TextInputSession) {
        // Not ours: focus went straight from one field to the next and the new one started first.
        if (this.session !== session) return
        this.session = null
    }
}
