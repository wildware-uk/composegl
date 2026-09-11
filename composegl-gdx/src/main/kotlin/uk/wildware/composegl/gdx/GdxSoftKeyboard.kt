package uk.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import uk.wildware.composegl.ui.backend.SoftKeyboard

/**
 * The phone's on-screen keyboard, as the toolkit's.
 *
 * One call each way. On desktop `setOnscreenKeyboardVisible` does nothing at all, which is the
 * right answer there — the keyboard is already on the table — so a game wires this up once and
 * never asks what it is running on.
 *
 * What this deliberately does not do is the other half of the problem, which is that a keyboard
 * takes the bottom third of the screen and a field near the bottom ends up behind it. Moving the
 * interface out of the keyboard's way needs the keyboard's height, and LibGDX does not report it;
 * that part is waiting on a real device to try it on.
 *
 * @param input the engine's, or null to take `Gdx.input` when it is asked. Taking it lazily
 *   matters: a game builds its interface before `Gdx.input` exists.
 */
class GdxSoftKeyboard(private val input: Input? = null) : SoftKeyboard {

    private val engine: Input? get() = input ?: Gdx.input

    /**
     * Whether the keyboard was last asked to show.
     *
     * What was asked for, not what is on screen: a player can dismiss the keyboard themselves with
     * the system's own back gesture and nothing tells us. It is right for deciding whether to ask
     * again and wrong for anything that has to be true.
     */
    override var isVisible: Boolean = false
        private set

    override fun show() {
        isVisible = true
        engine?.setOnscreenKeyboardVisible(true)
    }

    override fun hide() {
        isVisible = false
        engine?.setOnscreenKeyboardVisible(false)
    }
}
