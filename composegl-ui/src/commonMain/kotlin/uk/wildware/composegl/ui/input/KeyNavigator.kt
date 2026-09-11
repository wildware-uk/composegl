package uk.wildware.composegl.ui.input

import uk.wildware.composegl.ui.focus.FocusDirection
import uk.wildware.composegl.ui.focus.FocusManager

/**
 * A keyboard, driving the interface on its own.
 *
 * The keyboard twin of [GamepadNavigator], and the same three rules underneath: Tab and the arrows
 * move focus, Enter and Space press what focus is on, Escape goes back. Nothing here repeats on a
 * clock, because a keyboard repeats its own keys and the platform says so with
 * [KeyEvent.repeat] — reimplementing that would fight the player's own key-repeat settings.
 *
 * Meant to be asked **after** a [KeyRouter]. A text field that swallowed Left to move its caret has
 * already consumed the event by then, so the caret moves and focus stays put; a screen where
 * nothing wanted Left gets focus moved instead. That ordering is the whole of the rule "a widget
 * takes the keys it needs and lets the rest through".
 *
 * Press and release go through the same [FocusManager] activation the pad uses, so a button's
 * pressed state means one thing whatever is driving it, and a key going down while another is
 * already held does not start a second press.
 *
 * @param arrowsMoveFocus false for a screen where the arrows belong to the game — a map, a
 *   character mover — leaving Tab as the only way to walk the interface.
 * @param onBack what Escape and the hardware Back key do. Usually "close this screen".
 */
class KeyNavigator(
    private val focus: FocusManager,
    private val arrowsMoveFocus: Boolean = true,
    private val onBack: () -> Unit = {},
) {

    fun onKey(event: KeyEvent): Boolean = when (event.type) {
        KeyEventType.Down -> down(event)
        KeyEventType.Up -> up(event)
    }

    private fun down(event: KeyEvent): Boolean = when (event.key) {
        // Shift-Tab walks backwards, which is the one shortcut every keyboard user already knows.
        Key.Tab -> focus.moveFocus(if (event.modifiers.shift) FocusDirection.Previous else FocusDirection.Next)
        Key.Up -> arrow(FocusDirection.Up)
        Key.Down -> arrow(FocusDirection.Down)
        Key.Left -> arrow(FocusDirection.Left)
        Key.Right -> arrow(FocusDirection.Right)
        // A repeat of Enter is the platform saying the key is still down, not a second press. The
        // press has already started, so saying so again would be a second click on one keystroke.
        Key.Enter, Key.Space -> if (event.repeat) true else focus.pressFocused()
        Key.Escape, Key.Back -> {
            onBack()
            true
        }
        else -> false
    }

    private fun up(event: KeyEvent): Boolean = when (event.key) {
        Key.Enter, Key.Space -> focus.releaseFocused()
        else -> false
    }

    private fun arrow(direction: FocusDirection): Boolean =
        if (arrowsMoveFocus) focus.moveFocus(direction) else false
}
