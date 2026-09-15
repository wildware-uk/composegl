package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.modifier.DefaultLongPressMillis
import dev.wildware.composegl.ui.widget.openContextMenu
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
internal class PressGesture(
    private val node: UiNode,
    /**
     * The node whose context menu holding this press opens, when nothing of the node's own does.
     * Only a pointer passes one: a held Enter or South stays a click, since Shift+F10 and the
     * menu's pad button are how a keyboard and a pad open it. See [menuOnHold].
     */
    menu: UiNode? = null,
    /** Called once the hold has opened [menu], so the owner can let go of the press. */
    private val onMenuOpened: () -> Unit = {},
) : FrameWaiter {

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

    /** The menu a hold still may open. Let go of for good once the press turns into something else. */
    private var menu: UiNode? = menu
    private var longPressed = false
    private var repeats = 0
    private var nextRepeatAt = 0L

    /**
     * Where the press is, in the root's coordinates, for a context menu a long press opens. Null for
     * a press from a key or the pad, whose menu opens at the node's edge instead.
     */
    var at: Offset? = null

    init {
        val clocks = tree?.clocks
        val holdsForMenu = menu != null
        val clock = element?.clock ?: Clock.Ui.takeIf { holdsForMenu }
        if (clocks != null && clock != null) {
            // An unregistered clock is never advanced, and a hold on it would never end.
            clocks.register(clock)
            pressedAt = clocks.time(clock)
        } else {
            pressedAt = 0L
        }
        val repeat = element?.repeat
        if (repeat != null) nextRepeatAt = pressedAt + repeat.initialDelayMillis * NanosPerMilli
        if (element?.isTimed == true || holdsForMenu) tree?.wait(this)
    }

    /**
     * The press is being used for something other than holding still — a slider's thumb moving, a
     * list scrolling, a drag about to start — so a hold no longer opens a context menu. A node's
     * own long press is its own business and is left alone.
     */
    fun refuseMenu() {
        if (menu == null) return
        menu = null
        if (element?.isTimed != true) stop()
    }

    /** Whether the press has already done its thing, so the release is not a click as well. */
    private val spent: Boolean get() = longPressed || repeats > 0

    override fun onFrame(clocks: Clocks) {
        // The node's latest element rather than the one it was pressed with: a quantity picker that
        // disables + at its most, or a slot the game locks mid-hold, has to stop where it is.
        val click = node.resolved.click
        // A node taken out of the tree mid-hold is not somewhere a long press can happen any more.
        val menu = menu?.takeIf { it.tree != null && it.resolved.contextMenu?.enabled == true }
        if ((click == null && menu == null) || node.tree == null) return stop()
        // Disabled is a pause, like sliding off: it may be enabled again before the press comes up.
        if (click != null && !click.enabled) return
        val now = clocks.time(click?.clock ?: Clock.Ui)

        // A node's own long press wins; without one, holding it opens the nearest context menu — its
        // own, or the one round it, as a right-click would.
        val onLongPress = click?.onLongPress
        val longPressMillis = click?.longPressMillis ?: DefaultLongPressMillis
        if ((onLongPress != null || menu != null) && !longPressed && !longPressRefused && inside &&
            now - pressedAt >= longPressMillis * NanosPerMilli
        ) {
            longPressed = true
            if (onLongPress != null) {
                onLongPress()
            } else if (menu != null && menu.openContextMenu(at?.let(menu::toLocal))) {
                // The press was the menu's, and it is over: nothing repeats behind an open menu.
                stop()
                onMenuOpened()
                return
            }
        }

        val repeat = click?.repeat
        if (click != null && repeat != null && inside && now >= nextRepeatAt) {
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
 * The node whose context menu holding this one down opens, or null for none.
 *
 * Its own, or the nearest one round it past anything that only clicks — a long press on a button
 * inside an inventory slot is about the slot, exactly as a right-click is, and on a touch screen it
 * is the only way to that menu. A long press of its own on the way stops the walk, since that is
 * what the hold means there, and so does a focus trap: a menu about the screen behind a dialogue
 * does not open from inside it.
 */
internal val UiNode.menuOnHold: UiNode?
    get() {
        var walk: UiNode? = this
        while (walk != null) {
            if (walk.resolved.click?.onLongPress != null) return null
            if (walk.resolved.contextMenu?.enabled == true) return walk
            if (walk.resolved.focusTrap) return null
            walk = walk.parent
        }
        return null
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
