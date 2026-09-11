package composegl.ui.input

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the player is currently doing to a node, as state a widget can read.
 *
 * Held by the widget, not by the node: a widget `remember`s one, hands it to
 * [composegl.ui.modifier.interaction], and reads it while composing. Because the two booleans are
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

    internal fun enter() { hovers++ }

    internal fun leave() { if (hovers > 0) hovers-- }

    internal fun press() { presses++ }

    internal fun release() { if (presses > 0) presses-- }

    /** Nothing is touching this node any more, whatever the counters think. For cancellation. */
    internal fun clear() {
        hovers = 0
        presses = 0
    }

    override fun toString(): String = "InteractionState(hovered=$isHovered, pressed=$isPressed)"
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
