package composegl.ui.focus

import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.node.UiNode
import kotlin.math.abs

/** Which way the player asked focus to go. [Next] and [Previous] are Tab and Shift-Tab. */
enum class FocusDirection { Up, Down, Left, Right, Next, Previous }

/**
 * Asked to bring a rectangle into view.
 *
 * Every ancestor of a newly focused node is asked, innermost first, which is how focus moving to a
 * button halfway down a list scrolls the list instead of leaving the player looking at nothing. It
 * is deliberately about a rectangle rather than about scrolling: a panel that slides, a camera that
 * pans and a list that scrolls all answer the same question.
 *
 * The rectangle arrives in the handling node's own coordinates, so a scrolling area can compare it
 * with its own size without knowing where on the screen it is.
 *
 * Return true if something was moved. Nothing is done with the answer yet; it is there so a caller
 * can tell "nobody could" from "somebody did".
 *
 * A handler written inline is a new object every recomposition and so never compares equal —
 * `remember` it, exactly as with a pointer handler.
 */
fun interface RevealHandler {
    fun onReveal(area: Rect): Boolean
}

/**
 * A handle on one focusable node, for the times geometry is not the whole story.
 *
 * A screen makes one, attaches it with [composegl.ui.modifier.focusRequester], and can then focus
 * that node directly or name it as where a direction should go. It holds no state of its own: it
 * is an identity, and the node is found by looking for it in the tree.
 */
class FocusRequester {
    override fun toString(): String = "FocusRequester@${hashCode()}"
}

/**
 * Who has focus, and where a direction takes it.
 *
 * Directional focus is the thing console interfaces live or die by, and the reason it is worth
 * doing carefully is that the obvious implementation — nearest centre — is wrong in ways players
 * feel immediately: pressing right in a grid jumps diagonally, and a wide button beside two narrow
 * ones swallows both of them.
 *
 * So the scoring is Android's, which has had a decade of console-shaped abuse. Two ideas:
 *
 * - **The beam.** A candidate that overlaps the source on the perpendicular axis — directly to the
 *   right of it, rather than up and to the right — always beats one that does not, however close
 *   the other one looks.
 * - **Weighted distance.** Within the beam, distance along the direction counts thirteen times
 *   more than distance across it, so focus travels in the direction that was pressed instead of
 *   drifting sideways.
 *
 * When geometry lies — a wrapped grid, a gap, an L-shaped menu — `focusOrder` names the answer
 * directly and the scoring is not consulted.
 *
 * @param autoFocus whether to put focus somewhere when it has nowhere to be. True is right for a
 *   menu, which must never open with nothing selected. False suits an interface driven by a mouse,
 *   where a focus ring appearing unasked is noise.
 */
class FocusManager(private val root: UiNode, private val autoFocus: Boolean = true) {

    private var current: UiNode? = null

    /** The node with focus, or null when nothing has it. */
    val focused: UiNode? get() = current

    /**
     * Makes sure focus still points at something real.
     *
     * Called once a frame, after layout. A screen change removes the node that had focus, and
     * without this the next direction press would have nothing to move from — which is exactly how
     * a menu ends up open with nothing selected.
     */
    fun refresh() {
        settleScope()
        val focusable = focusables()
        if (pressing != null && pressing !in focusable) cancelPress()
        if (current != null && current !in focusable) release(current)
        if (current == null && autoFocus) take(preferred(focusable))
    }

    /** Gives focus to whatever [requester] is attached to. False when nothing is. */
    fun focusOn(requester: FocusRequester): Boolean {
        val node = nodeFor(requester) ?: return false
        take(node)
        return true
    }

    fun focusOn(node: UiNode): Boolean {
        if (!node.isFocusable) return false
        take(node)
        return true
    }

    /** Nothing has focus. A pointer-driven screen does this when the player clicks the background. */
    fun clearFocus() = release(current)

    // --- activating ----------------------------------------------------------------------------
    //
    // A pad's South button and a keyboard's Enter are a press and a release on the focused node,
    // and they behave like a pointer press on it for exactly the same reasons: the button looks
    // pressed while it is held, the click happens on the way up, and a gesture that gets taken
    // away fires nothing.

    private var pressing: UiNode? = null

    /** The focused node goes down. False when nothing is focused or it cannot be clicked. */
    fun pressFocused(): Boolean {
        if (pressing != null) return true
        val node = current ?: return false
        if (node.resolved.click?.enabled != true) return false
        pressing = node
        node.resolved.interactions.forEach { it.press() }
        return true
    }

    /** The focused node comes up, and that is a click — unless focus moved out from under it. */
    fun releaseFocused(): Boolean {
        val node = pressing ?: return false
        pressing = null
        node.resolved.interactions.forEach { it.release() }
        if (node !== current) return false
        val click = node.resolved.click ?: return false
        if (!click.enabled) return false
        click.onClick()
        return true
    }

    /** The press is abandoned. The pad was unplugged, or the screen went away. No click. */
    fun cancelPress() {
        val node = pressing ?: return
        pressing = null
        node.resolved.interactions.forEach { it.release() }
    }

    /**
     * Moves focus one step in [direction]. False when there is nowhere to go, which is what a
     * caller needs in order to decide whether to scroll, wrap, or leave the menu.
     */
    fun moveFocus(direction: FocusDirection): Boolean {
        val focusable = focusables()
        if (focusable.isEmpty()) return false

        val from = current
        // Nothing is focused, so the first press does not move focus — it starts it. Pressing down
        // in a menu nobody has touched yet has to select something rather than do nothing.
        if (from == null) {
            take(preferred(focusable))
            return current != null
        }

        val named = override(from, direction)
        if (named != null) {
            take(named)
            return true
        }

        // The focused node gets first refusal: Left on a slider is a smaller number rather than
        // the control to its left. It is asked after an explicit focus order, because a screen
        // that wired a direction by hand meant it, and it may say no — a slider already at its
        // maximum lets the next press to the right take focus away, which is how a player leaves.
        if (from.resolved.focusDirections.any { it.onDirection(direction) }) return true

        val next = when (direction) {
            FocusDirection.Next -> step(focusable, from, 1)
            FocusDirection.Previous -> step(focusable, from, -1)
            else -> nearest(from, focusable, direction)
        } ?: return false

        take(next)
        return true
    }

    // --- scopes --------------------------------------------------------------------------------
    //
    // A dialogue is a promise that the pad cannot wander behind it. That promise is one rule: the
    // innermost trap in the tree is the only part of it focus can see. Everything else — the pad,
    // Tab, the geometry, the reveal — is written against "the focusable nodes", and so all of it
    // obeys the trap without knowing what one is.

    /** Where focus was, outside each trap that has been entered, so it can be given back. */
    private class Outside(val scope: UiNode?, val focused: UiNode?)

    private val leftBehind = ArrayDeque<Outside>()

    private var scope: UiNode? = null

    /**
     * Follows the innermost trap, and puts focus back where it was when one goes away.
     *
     * Opening a dialogue over a button and closing it again should leave the player on that button.
     * Nothing else can do this: by the time the dialogue has gone, the node that had focus before
     * it opened is the only record of where the player was.
     */
    private fun settleScope() {
        val now = trap()
        if (now === scope) return

        val returning = leftBehind.lastOrNull()?.scope === now
        if (returning) {
            val outside = leftBehind.removeLast()
            scope = now
            val back = outside.focused
            if (back != null && back.isFocusable && back.isInside(now)) take(back)
            return
        }

        leftBehind.addLast(Outside(scope, current))
        scope = now
    }

    /** The innermost trap: the last one in tree order, which is the one drawn on top. */
    private fun trap(): UiNode? {
        var found: UiNode? = null
        root.forEach { if (it.resolved.focusTrap && it.resolved.alpha > 0f) found = it }
        return found
    }

    private fun UiNode.isInside(scope: UiNode?): Boolean {
        if (scope == null) return true
        var walk: UiNode? = this
        while (walk != null) {
            if (walk === scope) return true
            walk = walk.parent
        }
        return false
    }

    // --- choosing ------------------------------------------------------------------------------

    /** Every focusable node inside the innermost trap, in tree order — the order Tab walks. */
    private fun focusables(): List<UiNode> {
        val found = mutableListOf<UiNode>()
        (scope ?: root).forEach { if (it.isFocusable) found += it }
        return found
    }

    /** Where focus goes when it has to go somewhere: whatever the screen declared, else the first. */
    private fun preferred(focusable: List<UiNode>): UiNode? =
        focusable.firstOrNull { it.resolved.focusable?.initial == true } ?: focusable.firstOrNull()

    private fun step(focusable: List<UiNode>, from: UiNode, by: Int): UiNode? {
        val at = focusable.indexOf(from)
        if (at < 0) return focusable.firstOrNull()
        // Tab wraps. A menu you can tab off the end of is a menu you can get stuck outside.
        val to = (at + by + focusable.size) % focusable.size
        return focusable[to]
    }

    private fun override(from: UiNode, direction: FocusDirection): UiNode? {
        val order = from.resolved.focusOrder ?: return null
        val requester = when (direction) {
            FocusDirection.Up -> order.up
            FocusDirection.Down -> order.down
            FocusDirection.Left -> order.left
            FocusDirection.Right -> order.right
            FocusDirection.Next -> order.next
            FocusDirection.Previous -> order.previous
        } ?: return null
        return nodeFor(requester)
    }

    private fun nodeFor(requester: FocusRequester): UiNode? =
        root.firstOrNull { it.resolved.focusRequester === requester && it.isFocusable }

    private fun nearest(from: UiNode, focusable: List<UiNode>, direction: FocusDirection): UiNode? {
        val source = from.boundsInRoot
        var best: UiNode? = null
        var bestBounds = Rect.Zero
        focusable.forEach { candidate ->
            if (candidate === from) return@forEach
            val bounds = candidate.boundsInRoot
            if (best == null) {
                if (isCandidate(source, bounds, direction)) {
                    best = candidate
                    bestBounds = bounds
                }
            } else if (isBetter(direction, source, bounds, bestBounds)) {
                best = candidate
                bestBounds = bounds
            }
        }
        return best
    }

    // --- holding -------------------------------------------------------------------------------

    private fun take(node: UiNode?) {
        if (node === current) return
        release(current)
        current = node
        node?.resolved?.focusable?.state?.focus()
        if (node != null) reveal(node)
    }

    /**
     * Asks every ancestor to bring the newly focused node into view.
     *
     * Innermost first, using the bounds the last layout produced — focus moves before the frame
     * that will lay the tree out again, and a rectangle from one frame ago is the right answer for
     * everything that has not moved since. Two scrolling lists nested inside each other are the
     * one case that can take a second press to settle, because scrolling the inner one has not
     * moved the outer one's idea of where it is yet.
     */
    private fun reveal(node: UiNode) {
        val area = node.boundsInRoot
        var walk = node.parent
        while (walk != null) {
            val handlers = walk.resolved.reveals
            if (handlers.isNotEmpty()) {
                val corner = walk.boundsInRoot.topLeft
                val local = area.translate(Offset(-corner.x, -corner.y))
                handlers.forEach { it.onReveal(local) }
            }
            walk = walk.parent
        }
    }

    private fun release(node: UiNode?) {
        node?.resolved?.focusable?.state?.unfocus()
        if (node === current) current = null
    }
}

private val UiNode.isFocusable: Boolean
    get() = resolved.focusable?.enabled == true && resolved.alpha > 0f

// --- the scoring -------------------------------------------------------------------------------
//
// Android's `FocusFinder`, in our own words and our own geometry. The shape of it is deliberately
// unchanged: it is the one implementation that a decade of D-pads has already found the holes in.

/** Whether [dest] is far enough in [direction] to be worth considering at all. */
private fun isCandidate(source: Rect, dest: Rect, direction: FocusDirection): Boolean = when (direction) {
    FocusDirection.Left ->
        (source.right > dest.right || source.left >= dest.right) && source.left > dest.left
    FocusDirection.Right ->
        (source.left < dest.left || source.right <= dest.left) && source.right < dest.right
    FocusDirection.Up ->
        (source.bottom > dest.bottom || source.top >= dest.bottom) && source.top > dest.top
    FocusDirection.Down ->
        (source.top < dest.top || source.bottom <= dest.top) && source.bottom < dest.bottom
    else -> false
}

private fun isBetter(direction: FocusDirection, source: Rect, rect: Rect, against: Rect): Boolean {
    if (!isCandidate(source, rect, direction)) return false
    if (!isCandidate(source, against, direction)) return true
    if (beamBeats(direction, source, rect, against)) return true
    if (beamBeats(direction, source, against, rect)) return false
    return weighted(direction, source, rect) < weighted(direction, source, against)
}

/**
 * Whether [rect] wins purely by being in the beam — the strip directly along [direction] from the
 * source — when [against] is not.
 *
 * This is the rule that stops a diagonal neighbour stealing a press that was meant for the thing
 * straight ahead, however much closer the diagonal one is by any honest measure of distance.
 */
private fun beamBeats(direction: FocusDirection, source: Rect, rect: Rect, against: Rect): Boolean {
    val inBeam = beamsOverlap(direction, source, rect)
    val otherInBeam = beamsOverlap(direction, source, against)
    if (otherInBeam || !inBeam) return false
    if (!isCandidate(source, against, direction)) return true
    // Sideways, being in the beam is enough. Up and down, a candidate in the beam still has to be
    // closer than the far edge of the other one, or a tall neighbour beside a short one wins from
    // implausibly far away.
    if (direction == FocusDirection.Left || direction == FocusDirection.Right) return true
    return majorDistance(direction, source, rect) < majorDistanceToFarEdge(direction, source, against)
}

private fun beamsOverlap(direction: FocusDirection, source: Rect, dest: Rect): Boolean =
    when (direction) {
        FocusDirection.Left, FocusDirection.Right ->
            dest.bottom >= source.top && dest.top <= source.bottom
        else -> dest.right >= source.left && dest.left <= source.right
    }

/** Distance along the direction counts thirteen times as much as distance across it. */
private fun weighted(direction: FocusDirection, source: Rect, dest: Rect): Float {
    val major = majorDistance(direction, source, dest)
    val minor = minorDistance(direction, source, dest)
    return 13f * major * major + minor * minor
}

private fun majorDistance(direction: FocusDirection, source: Rect, dest: Rect): Float =
    maxOf(0f, majorDistanceRaw(direction, source, dest))

private fun majorDistanceRaw(direction: FocusDirection, source: Rect, dest: Rect): Float =
    when (direction) {
        FocusDirection.Left -> source.left - dest.right
        FocusDirection.Right -> dest.left - source.right
        FocusDirection.Up -> source.top - dest.bottom
        else -> dest.top - source.bottom
    }

private fun majorDistanceToFarEdge(direction: FocusDirection, source: Rect, dest: Rect): Float =
    maxOf(
        1f,
        when (direction) {
            FocusDirection.Left -> source.left - dest.left
            FocusDirection.Right -> dest.right - source.right
            FocusDirection.Up -> source.top - dest.top
            else -> dest.bottom - source.bottom
        },
    )

private fun minorDistance(direction: FocusDirection, source: Rect, dest: Rect): Float =
    when (direction) {
        FocusDirection.Left, FocusDirection.Right -> abs(source.centre.y - dest.centre.y)
        else -> abs(source.centre.x - dest.centre.x)
    }
