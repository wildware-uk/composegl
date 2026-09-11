package uk.wildware.composegl.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import uk.wildware.composegl.ui.backend.TextInput
import uk.wildware.composegl.ui.backend.TextInputSession
import uk.wildware.composegl.ui.text.TextFieldValue

/**
 * Android's own keyboard, driving a text field properly.
 *
 * Without this, an engine hands the toolkit one character per key press and that is all a field
 * ever sees. It is enough for English and wrong for everything else:
 *
 * - **Autocorrect never fires.** Gboard holds a word open, changes its mind about it, and commits
 *   something other than what was typed. It can only do that through an `InputConnection`, so
 *   without one, typing `t`, `e`, `h`, space leaves `teh ` forever.
 * - **Japanese, Chinese and Korean cannot be typed at all.** Those keyboards send provisional text
 *   and then replace it with what the player chose. That is two edits to text already sent, which
 *   a stream of characters cannot express.
 * - **Swipe typing sends nothing**, because a swipe is one committed word rather than letters.
 *
 * This is the piece that was missing. It puts an invisible, focusable `View` in the activity — the
 * thing Android actually attaches a keyboard to — and turns everything the keyboard asks of it into
 * the toolkit's own [uk.wildware.composegl.ui.text.EditCommand]s.
 *
 * ```kotlin
 * // in the activity, once
 * val input = AndroidTextInput(this) { Gdx.app.postRunnable(it) }
 * // …and around the interface
 * ProvideTextInput(input) { Hud() }
 * ```
 *
 * @param activity the activity whose window the keyboard belongs to. The view is added to its
 *   content, at zero size, so it changes nothing about the layout.
 * @param onRenderThread how to get back onto the thread the interface is composed on. The keyboard
 *   calls in on the main thread; a game drawing on its own render thread **must** pass this, or two
 *   threads edit one field. The default runs the work where it arrived, which is correct only for a
 *   game whose loop is the main thread.
 */
class AndroidTextInput(
    private val activity: android.app.Activity,
    private val onRenderThread: (Runnable) -> Unit = { it.run() },
) : TextInput {

    private val main = Handler(Looper.getMainLooper())

    private val manager: InputMethodManager
        get() = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    /**
     * The field the keyboard is pointed at, or null.
     *
     * Written on the main thread and read by the connection on the same one, so it needs no
     * guarding of its own; what crosses threads is the edit, and that goes through [onRenderThread].
     */
    private var current: TextInputSession? = null

    /** What the keyboard has been told the field says. Its own copy, which is why it can go stale. */
    private var mirror: TextFieldValue = TextFieldValue.Empty

    /**
     * What the keyboard's last edit expected the field to become.
     *
     * Without it, every keystroke would restart the keyboard: the field changes because the
     * keyboard changed it, the change comes back through [update], and a restart in the middle of
     * a word throws away the half-composed text the player is looking at. So a value that matches
     * what the keyboard already believes is not news, and nothing happens.
     */
    private var expected: TextFieldValue? = null

    private val view: InputView by lazy {
        InputView(activity).also { added ->
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            content.addView(added, 0, 0)
        }
    }

    override fun start(session: TextInputSession) {
        main.post {
            current = session
            mirror = session.value
            expected = null
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.requestFocus()
            // Restart rather than only show: the keyboard caches what kind of field it is attached
            // to, and moving from a one-line field to a multi-line one changes its action button.
            manager.restartInput(view)
            manager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun update(value: TextFieldValue) {
        main.post {
            if (current == null) return@post
            val itsOwnDoing = value == expected
            mirror = value
            expected = null

            // Every edit, the keyboard's own included. An input method that is not told where the
            // cursor went stops composing and commits each keystroke as final text instead — which
            // is autocorrect gone, suggestions gone, and no way to type Japanese at all. This one
            // call is the difference, and it is the half that is easy to leave out because
            // everything still *looks* like it works without it.
            manager.updateSelection(
                view,
                value.selection.min,
                value.selection.max,
                value.composition?.min ?: -1,
                value.composition?.max ?: -1,
            )

            // The keyboard's own doing, and it already knows. Restarting here would throw away a
            // half-typed Japanese word every time a character was added to it.
            if (itsOwnDoing) return@post

            // The keyboard is holding a version of this text that no longer exists. Telling it to
            // start again is the only honest answer: anything subtler leaves it editing by
            // character offsets into a string that has changed underneath it.
            manager.restartInput(view)
        }
    }

    override fun stop(session: TextInputSession) {
        main.post {
            // Not ours: focus moved straight from one field to another, and the new field's start
            // arrived before this stop. Closing the keyboard here would close the new one's.
            if (current !== session) return@post
            current = null
            expected = null
            manager.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }

    /**
     * The view Android attaches a keyboard to.
     *
     * Zero by zero and invisible. It exists because `InputMethodManager` will not talk to anything
     * that is not a focused `View`, and an engine's surface is not one that answers
     * `onCreateInputConnection`.
     */
    private inner class InputView(context: Context) : View(context) {

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            // A keyboard files what it knows about a field under the view's id. Left unset it is
            // -1, which reads as "no field at all" and is enough for some input methods to give up
            // on composing and commit every keystroke as final text.
            id = generateViewId()
        }

        override fun onCheckIsTextEditor(): Boolean = current != null

        override fun onCreateInputConnection(out: EditorInfo): InputConnection? {
            val session = current ?: return null

            // Auto-correct is what makes a keyboard compose rather than commit: without it Gboard
            // sends every letter as final text, and a word can never be corrected, predicted or
            // — the same machinery — assembled from several keystrokes the way Japanese is.
            val text = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT
            out.inputType = if (session.multiline) text or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE else text
            out.imeOptions = if (session.multiline) {
                EditorInfo.IME_ACTION_NONE
            } else {
                // No full-screen keyboard in landscape: it would cover the game with a text editor
                // Android drew itself, which is not what anybody asked for.
                EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
            }

            val value = mirror
            out.initialSelStart = value.selection.min
            out.initialSelEnd = value.selection.max

            return ComposeGlInputConnection(
                initial = value,
                multiline = session.multiline,
                apply = { commands, resulting ->
                    expected = resulting
                    mirror = resulting
                    onRenderThread(Runnable { session.edit(commands) })
                },
                submit = { onRenderThread(Runnable { session.submit() }) },
            )
        }
    }
}
