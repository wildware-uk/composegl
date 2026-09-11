package composegl.ui.input

import composegl.ui.focus.FocusManager
import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.node.UiNode

/**
 * Where a pointer event goes, and what that means.
 *
 * A backend reports what the mouse did; this decides who it happened to. Three rules, and every
 * other behaviour falls out of them:
 *
 * 1. **Deepest first, topmost sibling first.** The node under the pointer is the one furthest down
 *    the tree that contains the point, and where siblings overlap it is the one drawn last.
 * 2. **A press captures.** Whoever consumed the press hears the whole rest of the gesture — every
 *    move, and the release — wherever the pointer ends up, even outside the window. A toolkit that
 *    skips this has buttons that stick down.
 * 3. **A cancel is not a release.** The platform taking a gesture away ends it without firing a
 *    click, which is why [PointerEvent.Cancel] exists as its own thing.
 *
 * Hover is different from press on purpose. Everything interactive under the pointer is hovered,
 * ancestors included, because a panel that lights up while the pointer is anywhere inside it is a
 * thing people want. Only the node that consumed the press is pressed.
 *
 * The router holds the gesture state and nothing else: no timers, no thread, no allocation per
 * frame. It is created once with the tree's root and lives as long as the interface does.
 */
class PointerRouter(
    private val root: UiNode,
    /**
     * Where focus lives, if the screen has any.
     *
     * Clicking a focusable node focuses it, because a player who clicks a button and then reaches
     * for the keyboard expects the keyboard to be talking to the thing they just clicked.
     */
    private val focus: FocusManager? = null,
) : InputSink {

    /** What a captured pointer is doing, and to whom. */
    private class Capture(val node: UiNode, val buttons: MutableSet<PointerButton>) {
        /** True while the pointer is inside the captured node, which is what "pressed" means. */
        var inside = true
    }

    private val captures = mutableMapOf<PointerId, Capture>()

    /** What each pointer is hovering, deepest first. Kept so a change can be a diff. */
    private val hovering = mutableMapOf<PointerId, List<UiNode>>()

    override fun onPointer(event: PointerEvent): Boolean = when (event) {
        is PointerEvent.Press -> press(event)
        is PointerEvent.Move -> move(event)
        is PointerEvent.Release -> release(event)
        is PointerEvent.Cancel -> cancel(event)
        is PointerEvent.Scroll -> scroll(event)
        is PointerEvent.Exit -> exit(event)
    }

    // Pointers only. Keys go to whatever has focus, and focus is its own thing with its own
    // rules — a router that guessed at it would be a router that had to be unpicked later.
    override fun onKey(event: KeyEvent): Boolean = false

    override fun onText(event: TextEvent): Boolean = false

    override fun onGamepad(event: GamepadEvent): Boolean = false

    /** Nothing is being touched and nothing is hovered. For a window that lost focus. */
    fun cancelAll() {
        captures.keys.toList().forEach { id ->
            val capture = captures.remove(id) ?: return@forEach
            capture.node.resolved.interactions.forEach { it.clear() }
        }
        hovering.keys.toList().forEach { id -> hover(id, emptyList()) }
    }

    // --- the events ---------------------------------------------------------------------------

    private fun press(event: PointerEvent.Press): Boolean {
        val held = captures[event.pointerId]
        if (held != null) {
            // A second button on a pointer already holding something belongs to the same gesture.
            // It does not get to move the capture somewhere else half way through.
            held.buttons += event.button
            deliver(held.node, event)
            return true
        }

        val taker = candidatesUnder(event.position).firstOrNull { consumes(it, event) } ?: return false

        // A gesture has started, so nothing is merely hovered any more.
        hover(event.pointerId, emptyList())
        captures[event.pointerId] = Capture(taker, mutableSetOf(event.button))
        taker.resolved.interactions.forEach { it.press() }
        focus?.focusOn(taker)
        return true
    }

    private fun move(event: PointerEvent.Move): Boolean {
        val capture = captures[event.pointerId]
        if (capture != null) {
            val inside = event.position in capture.node.boundsInRoot
            if (inside != capture.inside) {
                capture.inside = inside
                // Dragging off a button un-presses it, and coming back presses it again. Nothing
                // has been decided yet — that happens on release.
                capture.node.resolved.interactions.forEach { if (inside) it.press() else it.release() }
            }
            deliver(capture.node, event)
            // Captured means captured: the event belongs to this gesture whether or not a handler
            // had anything to say about it.
            return true
        }

        val candidates = candidatesUnder(event.position)
        hover(event.pointerId, hoverPathFrom(candidates.firstOrNull(), event.position))
        return candidates.any { deliver(it, event) }
    }

    private fun release(event: PointerEvent.Release): Boolean {
        val capture = captures[event.pointerId]
            ?: return candidatesUnder(event.position).any { deliver(it, event) }

        capture.buttons -= event.button
        if (capture.buttons.isNotEmpty()) {
            deliver(capture.node, event)
            return true
        }

        captures.remove(event.pointerId)
        if (capture.inside) capture.node.resolved.interactions.forEach { it.release() }

        deliver(capture.node, event)
        val click = capture.node.resolved.click
        // A release inside the node that took the press is a click. Anywhere else is a change of
        // mind, which is a thing players do on purpose and must not be a click.
        if (click != null && click.enabled && event.position in capture.node.boundsInRoot) click.onClick()

        // The gesture is over, so whatever the pointer is now over is hovered again.
        hover(event.pointerId, hoverPathFrom(candidatesUnder(event.position).firstOrNull(), event.position))
        return true
    }

    private fun cancel(event: PointerEvent.Cancel): Boolean {
        val capture = captures.remove(event.pointerId)
        hover(event.pointerId, emptyList())
        if (capture == null) return false

        if (capture.inside) capture.node.resolved.interactions.forEach { it.release() }
        // Delivered, so a handler mid-drag can put back whatever it was moving. No click: that is
        // the entire difference between a cancel and a release.
        deliver(capture.node, event)
        return true
    }

    private fun scroll(event: PointerEvent.Scroll): Boolean =
        candidatesUnder(event.position).any { deliver(it, event) }

    private fun exit(event: PointerEvent.Exit): Boolean {
        // Hover ends; a drag does not. A mouse leaving the window while a slider is being dragged
        // must keep dragging, and every toolkit that conflates the two has sliders that let go.
        val wasHovering = hovering[event.pointerId]?.isNotEmpty() == true
        hover(event.pointerId, emptyList())
        return wasHovering
    }

    // --- the machinery ------------------------------------------------------------------------

    /**
     * Every interactive node containing [point], in the order they should be offered it.
     *
     * The reverse of the order they are drawn in: children before their parent, last sibling
     * before its earlier siblings. So the node on top and furthest down the tree is asked first,
     * and a node that declines an event lets it through to whatever is underneath — an overlay
     * that watches without blocking, a transparent gutter beside a list.
     */
    private fun candidatesUnder(point: Offset): List<UiNode> {
        val found = mutableListOf<UiNode>()
        collect(root, Offset.Zero, point, found)
        return found
    }

    private fun collect(node: UiNode, origin: Offset, point: Offset, into: MutableList<UiNode>) {
        val resolved = node.resolved
        // Invisible is untouchable, and it is the same test the draw pass makes, so what you
        // cannot see you cannot click.
        if (resolved.alpha <= 0f) return

        val bounds = Rect.of(origin.x + node.x, origin.y + node.y, node.width, node.height)
        // A clip is the one thing that stops the search early: nothing outside a clipping node is
        // drawn, so nothing outside it can be hit, children included.
        if (resolved.clip != null && point !in bounds) return

        for (index in node.children.indices.reversed()) {
            collect(node.children[index], bounds.topLeft, point, into)
        }
        if (point in bounds && resolved.isInteractive) into += node
    }

    /**
     * What counts as hovered when the pointer is over [node]: the node and its interactive
     * ancestors.
     *
     * Ancestors and not siblings. A panel with the pointer somewhere inside it is hovered, which
     * is a thing people build; the button hidden underneath an overlay is not.
     */
    private fun hoverPathFrom(node: UiNode?, point: Offset): List<UiNode> {
        val path = mutableListOf<UiNode>()
        var walk: UiNode? = node
        while (walk != null) {
            if (walk.resolved.isInteractive && point in walk.boundsInRoot) path += walk
            walk = walk.parent
        }
        return path
    }

    /** Asks one node's handlers, in chain order, in its own coordinates. */
    private fun deliver(node: UiNode, event: PointerEvent): Boolean {
        val handlers = node.resolved.handlers
        if (handlers.isEmpty()) return false
        val local = event.movedTo(event.position - node.boundsInRoot.topLeft)
        return handlers.any { it.onPointer(local) }
    }

    /** Whether [node] takes this press: a handler that says so, or a `clickable`. */
    private fun consumes(node: UiNode, event: PointerEvent.Press): Boolean =
        deliver(node, event) || node.resolved.click != null

    /** Moves a pointer's hover from whatever it was on to [now], touching only the difference. */
    private fun hover(id: PointerId, now: List<UiNode>) {
        val before = hovering[id].orEmpty()
        if (before == now) return
        before.forEach { if (it !in now) it.resolved.interactions.forEach { state -> state.leave() } }
        now.forEach { if (it !in before) it.resolved.interactions.forEach { state -> state.enter() } }
        if (now.isEmpty()) hovering.remove(id) else hovering[id] = now
    }
}
