package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.backend.SoftKeyboard
import korlibs.render.GameWindow

/**
 * The phone's on-screen keyboard, as KorGE's window raises it.
 *
 * One call each way. On desktop KorGE does nothing, which is the right answer there — the keyboard
 * is already on the table — so a game wires this up once and never asks what it is running on.
 *
 * @param window the game's window, asked for each time rather than held.
 */
class KorgeSoftKeyboard(private val window: () -> GameWindow?) : SoftKeyboard {

    /**
     * Whether the keyboard was last asked to show: what was asked for, not what is on screen, since
     * a player can dismiss it with the system's own gesture and nothing says so.
     */
    override var isVisible: Boolean = false
        private set

    override fun show() {
        isVisible = true
        window()?.showSoftKeyboard()
    }

    override fun hide() {
        isVisible = false
        window()?.hideSoftKeyboard()
    }
}
