package composegl.ui.input

/**
 * One thing that can answer "back".
 *
 * Return true if it was used. A dialogue that closes returns true; a screen that has nothing to go
 * back from returns false and lets whatever is behind it decide.
 */
fun interface BackHandler {
    fun onBack(): Boolean
}

/**
 * Who answers the Back button, innermost first.
 *
 * The pad's East and Back buttons are not keys and do not bubble up a tree, so there has to be
 * somewhere for "close this" to live. This is it: things that can be closed put themselves on the
 * stack while they are open, and [back] asks the most recent one first.
 *
 * A game owns one of these and wires it to whatever its platform calls back:
 *
 * ```kotlin
 * val backs = BackStack()
 * val pad = GamepadNavigator(focus, onBack = { if (!backs.back()) leaveScreen() })
 * ```
 *
 * Escape does not come through here, because it does not need to: a key already walks outwards from
 * the focused node, and focus inside a dialogue is inside that dialogue's own subtree, so the
 * innermost one is asked first for nothing.
 */
class BackStack {

    private val handlers = mutableListOf<BackHandler>()

    val size: Int get() = handlers.size

    fun add(handler: BackHandler) {
        handlers += handler
    }

    fun remove(handler: BackHandler) {
        handlers -= handler
    }

    /** Asks each handler, newest first, until one says it used it. */
    fun back(): Boolean {
        for (index in handlers.indices.reversed()) {
            if (handlers[index].onBack()) return true
        }
        return false
    }
}
