package uk.wildware.composegl.lwjgl3

import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryUtil
import uk.wildware.composegl.ui.backend.TextInput
import uk.wildware.composegl.ui.backend.TextInputSession
import uk.wildware.composegl.ui.text.TextFieldValue

/**
 * The desktop's own input method, driving a text field.
 *
 * A character callback says "the letter か arrived". That is the end of the story, and the story
 * has a middle: typing Japanese is `k`, `a`, look at what the input method is offering, choose.
 * The middle is the **preedit** — provisional text, drawn underlined, which the input method
 * rewrites as you go and finally replaces with what you picked. Chinese and Korean work the same
 * way, and so does anything with a candidate window.
 *
 * GLFW reports it. LWJGL exposed it in 3.4, which is why this exists now and did not before.
 *
 * ```kotlin
 * val input = GlfwTextInput(window)
 * ProvideTextInput(input) { Hud() }
 * ```
 *
 * Commits are deliberately **not** handled here. When the player chooses a candidate, GLFW sends
 * it through the ordinary character callback, so it arrives as typed text like any other and
 * [GlfwKeyboardInput] already carries it. All this class does is the provisional run either side
 * of that.
 *
 * @param window the window whose input method to listen to. One instance per window: GLFW's
 *   callbacks are per-window, and registering a second would silently replace the first.
 */
class GlfwTextInput(private val window: GlfwWindow) : TextInput {

    /** The field the input method is pointed at, or null when nothing has focus. */
    private var current: TextInputSession? = null

    init {
        GLFW.glfwSetPreeditCallback(window.handle) { _, count, string, _, _, _, caret ->
            onPreedit(count, string, caret)
        }
        // The player switched their input method off — from Japanese back to direct input, say.
        // Whatever was provisional is never going to be chosen now, so it has to go.
        GLFW.glfwSetIMEStatusCallback(window.handle) { _ -> reset() }
    }

    override fun start(session: TextInputSession) {
        current = session
        // A run left over from the last field would otherwise be attributed to this one, at
        // whatever offsets it happened to have.
        GLFW.glfwResetPreeditText(window.handle)
    }

    override fun update(value: TextFieldValue) {
        // Something other than the input method changed the text — a paste, a click, the game.
        // Anything provisional was written against the old text, so it cannot be carried over.
        if (value.composition == null) GLFW.glfwResetPreeditText(window.handle)
    }

    override fun stop(session: TextInputSession) {
        // Not ours: focus went straight from one field to the next, and the new field started
        // before the old one stopped. Resetting here would clear the new field's preedit.
        if (current !== session) return
        current = null
        GLFW.glfwResetPreeditText(window.handle)
    }

    /**
     * What the input method is thinking, as a pointer to code points.
     *
     * GLFW owns that memory and reuses it, so it is read here and now rather than kept.
     */
    private fun onPreedit(count: Int, string: Long, caret: Int) {
        val session = current ?: return
        val text = if (count <= 0 || string == MemoryUtil.NULL) "" else read(string, count)
        session.edit(Preedit.compose(text, caret, session.value))
    }

    private fun reset() {
        val session = current ?: return
        session.edit(Preedit.clear(session.value))
    }

    /** GLFW counts a preedit in code points, and a `String` is built from `Char`s. */
    private fun read(string: Long, count: Int): String {
        val points = MemoryUtil.memIntBuffer(string, count)
        val out = StringBuilder(count)
        for (index in 0 until count) out.appendCodePoint(points[index])
        return out.toString()
    }
}
