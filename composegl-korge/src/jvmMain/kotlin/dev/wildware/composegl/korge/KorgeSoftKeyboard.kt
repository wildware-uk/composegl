package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.backend.SoftKeyboard
import korlibs.render.GameWindow

/**
 * The phone's on-screen keyboard, as KorGE's window raises it.
 *
 * One call each way. On desktop KorGE does nothing, which is the right answer there — the keyboard
 * is already on the table — so a game wires this up once and never asks what it is running on.
 *
 * **Its height.** A launcher adds [heightPixels] to its viewport's safe area so the interface lays
 * out above the keyboard. KorGE 6 does not report how tall the keyboard is on any platform — its
 * window has `showSoftKeyboard` and `isSoftKeyboardVisible` and nothing about size — so the height is
 * [height]'s to answer: a game that can ask its platform (Android's window insets, iOS's keyboard
 * notification) passes that in, and anything else leaves it at zero, which lays out exactly as if
 * there were no keyboard. Zero while the keyboard is down, whatever [height] says.
 *
 * @param window the game's window, asked for each time rather than held.
 */
class KorgeSoftKeyboard(private val window: () -> GameWindow?) : SoftKeyboard {

    /** How many real pixels of the bottom of the window the keyboard covers while it is up. */
    var height: () -> Float = { 0f }

    /**
     * Whether the keyboard was last asked to show: what was asked for, not what is on screen, since
     * a player can dismiss it with the system's own gesture and nothing says so.
     */
    override var isVisible: Boolean = false
        private set

    override val heightPixels: Float
        get() = if (isVisible) height().coerceAtLeast(0f) else 0f

    override fun show() {
        isVisible = true
        window()?.showSoftKeyboard()
    }

    override fun hide() {
        isVisible = false
        window()?.hideSoftKeyboard()
    }
}
