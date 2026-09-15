package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.modifier.ClickableElement
import dev.wildware.composegl.ui.node.UiNode

/** Something waiting on a tree's clocks, once a frame, while a press is held. */
internal fun interface FrameWaiter {
    fun onFrame(clocks: Clocks)
}

/**
 * One press on a `clickable`, from going down to coming up, and the timed parts of it.
 *
 * Shared by the pointer and by focus, so a long press and a held repeat mean one thing whether a
 * mouse, a finger, Enter or the pad's South button is holding the node down. The owner says where
 * the press is ([inside]) and how it ended ([finish] or [cancel]); this decides what that means.
 *
 * Only a press on something timed — a long press, a repeat — waits on the tree at all, so a plain
 * button held down costs exactly what it did before this existed.
 */
internal class PressGesture(private val node: UiNode) : FrameWaiter {

    private val tree = node.tree
    private val element = node.resolved.click

    /** When the press went down, on its own clock. What a double click is measured from. */
    val pressedAt: Long

    /** Whether the press is over the node, which is the only time anything timed may fire. */
    var inside = true
        set(value) {
            field = value
            // Leaving is a change of mind, and a long press is not allowed to happen to a node the
            // player has already moved away from — not even if they come back to it.
            if (!value) longPressRefused = true
        }

    private var longPressRefused = false
    private var longPressed = false
    private var repeats = 0
    private var nextRepeatAt = 0L

    init {
        val clocks = tree?.clocks
        val clock = element?.clock
        if (clocks != null && clock != null) {
            // An unregistered clock is never advanced, and a hold on it would never end.
            clocks.register(clock)
            pressedAt = clocks.time(clock)
        } else {
            pressedAt = 0L
        }
        val repeat = element?.repeat
        if (repeat != null) nextRepeatAt = pressedAt + repeat.initialDelayMillis * NanosPerMilli
        if (element?.isTimed == true) tree?.wait(this)
    }

    /** Whether the press has already done its thing, so the release is not a click as well. */
    private val spent: Boolean get() = longPressed || repeats > 0

    override fun onFrame(clocks: Clocks) {
        // The node's latest element rather than the one it was pressed with: a quantity picker that
        // disables + at its most, or a slot the game locks mid-hold, has to stop where it is.
        val click = node.resolved.click
        // A node taken out of the tree mid-hold is not somewhere a long press can happen any more.
        if (click == null || node.tree == null) return stop()
        // Disabled is a pause, like sliding off: it may be enabled again before the press comes up.
        if (!click.enabled) return
        val now = clocks.time(click.clock)

        val onLongPress = click.onLongPress
        if (onLongPress != null && !longPressed && !longPressRefused && inside &&
            now - pressedAt >= click.longPressMillis * NanosPerMilli
        ) {
            longPressed = true
            onLongPress()
        }

        val repeat = click.repeat
        if (repeat != null && inside && now >= nextRepeatAt) {
            repeats++
            click.onClick()
            nextRepeatAt += repeat.intervalMillis * NanosPerMilli
            // Behind after a stall: step from now, rather than once a frame until it catches up.
            if (nextRepeatAt <= now) nextRepeatAt = now + repeat.intervalMillis * NanosPerMilli
        }

        if (longPressed && repeat == null) stop()
    }

    /** The press came up. True when the release is still a click, rather than the end of a hold. */
    fun finish(): Boolean {
        stop()
        return !spent
    }

    /** The press was taken away. Nothing more happens. */
    fun cancel() = stop()

    private fun stop() {
        tree?.stopWaiting(this)
    }
}

/**
 * Which click was the last one, so the next can tell whether it is the second of a double.
 *
 * One per source of presses — a pointer router, a focus manager — because a mouse click followed by
 * Enter is two people's worth of intent rather than one double click.
 */
internal class ClickMemory {

    private var node: UiNode? = null
    private var at = 0L
    private var element: ClickableElement? = null

    /**
     * A click has happened on [node]: fires the double click if this is the second of two, the
     * click otherwise.
     */
    fun click(node: UiNode, click: ClickableElement, gesture: PressGesture) {
        val onDoubleClick = click.onDoubleClick
        val previous = this.node
        val isSecond = onDoubleClick != null && previous === node && element?.clock == click.clock &&
            gesture.pressedAt - at <= click.doubleClickMillis * NanosPerMilli
        if (isSecond) {
            // Forgotten, so a third click is a click and a fourth is the next double.
            this.node = null
            element = null
            onDoubleClick()
            return
        }
        this.node = node
        element = click
        at = node.tree?.clocks?.time(click.clock) ?: 0L
        click.onClick()
    }

    /** The next click starts afresh, whatever came before. */
    fun forget() {
        node = null
        element = null
    }
}

private const val NanosPerMilli = 1_000_000L
