package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Stable
import dev.wildware.composegl.ui.focus.FocusDirection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the player is currently doing to a node, as state a widget can read.
 *
 * Held by the widget, not by the node: a widget `remember`s one, hands it to
 * [dev.wildware.composegl.ui.modifier.interaction], and reads it while composing. Because the two booleans are
 * Compose state, reading one subscribes to it, so a button that draws itself darker while pressed
 * recomposes when it is pressed and at no other time.
 *
 * Both are counters underneath rather than flags, which matters with more than one finger: two
 * fingers on the same node, one lifted, still leaves it pressed.
 */
@Stable
class InteractionState {

    private var hovers by mutableStateOf(0)
    private var presses by mutableStateOf(0)
    private var focused by mutableStateOf(false)

    /** A pointer is over this node, with nothing held down. A mouse thing; touch never hovers. */
    val isHovered: Boolean get() = hovers > 0

    /**
     * A pointer went down on this node and has not let go.
     *
     * False while a drag that started here has wandered off the node, and true again if it comes
     * back — which is what every other toolkit does, and what players expect when they slide a
     * finger off a button they have changed their mind about.
     */
    val isPressed: Boolean get() = presses > 0

    /**
     * This node is where keys and pad presses go.
     *
     * Exactly one node in a screen has it. A game draws a focus ring from this, and on a console
     * that ring is the cursor — the only thing telling the player where they are.
     */
    val isFocused: Boolean get() = focused

    internal fun enter() { hovers++ }

    internal fun leave() { if (hovers > 0) hovers-- }

    internal fun press() { presses++ }

    internal fun release() { if (presses > 0) presses-- }

    internal fun focus() { focused = true }

    internal fun unfocus() { focused = false }

    /** Nothing is touching this node any more, whatever the counters think. For cancellation. */
    internal fun clear() {
        hovers = 0
        presses = 0
    }

    override fun toString(): String =
        "InteractionState(hovered=$isHovered, pressed=$isPressed, focused=$isFocused)"
}

/**
 * Raw pointer events for one node, in that node's own coordinates.
 *
 * The escape hatch under the widgets: a drag handle, a map you can pan, a minigame. Return true to
 * say the event was used, which stops it reaching anything underneath and, for a press, captures
 * the pointer so the rest of the gesture comes here too.
 *
 * A handler written inline is a new object every recomposition and so never compares equal —
 * `remember` it, exactly as with `drawBehind`.
 */
fun interface PointerHandler {
    fun onPointer(event: PointerEvent): Boolean
}

/**
 * Keys for one node, while it has focus.
 *
 * Return true to say the key was used, which stops it going any further — not to the node's
 * parent, and not to the game. A text field returns true for the arrows it moves its caret with
 * and false for Escape, which is how one dialogue closes and the caret stays where it is.
 *
 * A handler written inline is a new object every recomposition and so never compares equal —
 * `remember` it, exactly as with `onPointer`.
 */
fun interface KeyHandler {
    fun onKey(event: KeyEvent): Boolean
}

/**
 * Pad events for one node, while it has focus, offered before the pad navigates.
 *
 * A key binding screen is the reason: while it is waiting for "the next button", South is not
 * "press" and the d-pad is not "move", they are answers. Return true to say the event was used,
 * and the navigator never sees it — no click, no focus move, no back.
 *
 * Like a key it starts at the focused node and walks outwards. Return true for an up only when the
 * down was yours too, or the navigator is left holding a button that never comes up.
 *
 * A pad being unplugged is told to every handler on the same walk as a [GamepadEvent.Disconnected],
 * and what they answer is ignored: it is the moment to let go of a button or a stick still held,
 * whose release will never come. A pad being plugged in is never passed on.
 *
 * A handler written inline is a new object every recomposition and so never compares equal —
 * `remember` it, exactly as with [KeyHandler].
 */
fun interface GamepadHandler {
    fun onGamepad(event: GamepadEvent): Boolean
}

/**
 * A direction, offered to the focused node before focus moves off it.
 *
 * What a slider, a set of tabs or a scrolling list needs: Left on a slider is a smaller number,
 * not the control to its left. Return true to say the direction was used, and focus stays put.
 *
 * The same hook serves the keyboard and the pad, because the arrow keys and the stick both ask
 * focus to move and this is asked first — a widget claims its axis once and works on both.
 *
 * The direction is in the node's own axes, as a pointer handler's position is: inside a
 * `Modifier.mirror` the player's Right arrives as Left, so a mirrored slider's knob still moves
 * the way the arrow points on screen.
 *
 * A handler written inline is a new object every recomposition and so never compares equal —
 * `remember` it, exactly as with [PointerHandler].
 */
fun interface DirectionHandler {
    fun onDirection(direction: FocusDirection): Boolean
}

/**
 * The pad's South button or Enter, let go on the focused node, offered before it is a click.
 *
 * What drag and drop needs from a pad: South on a slot picks the item up, and South on another
 * slot puts it down. Return true and that was the whole of it — no click. Return false and the
 * node's `clickable`, if it has one, is clicked as usual.
 *
 * Only the focused node itself is asked, never its ancestors, and only for a press that is still a
 * click: a long press or a repeat that already spent it is not offered.
 */
fun interface ActivateHandler {
    fun onActivate(): Boolean
}

/**
 * Committed text for one node, while it has focus.
 *
 * Separate from [KeyHandler] because the two are genuinely different things: Shift and A are two
 * keys and one character, and a Chinese input method is many keys and one character much later. A
 * text field reads its characters here and its caret keys there.
 *
 * Unlike a key, text does not bubble. It belongs to the thing being typed into and nothing else.
 */
fun interface TextHandler {
    fun onText(event: TextEvent): Boolean
}
