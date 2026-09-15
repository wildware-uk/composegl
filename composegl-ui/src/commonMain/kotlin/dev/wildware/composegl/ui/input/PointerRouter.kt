package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.backend.SystemCursor
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.modifier.DraggableElement
import dev.wildware.composegl.ui.modifier.ResolvedModifier
import dev.wildware.composegl.ui.node.UiNode

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
 * The router holds the gesture state and nothing else: no timers of its own (a long press or a
 * repeat waits on the tree's clocks), no thread, no allocation per frame. It is created once with the tree's root and lives as long as the interface does.
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
    /**
     * The mouse cursor, whose shape follows whatever the mouse is over.
     *
     * Asked only when the shape changes, never per move, and only by a mouse or a stylus: a finger
     * has no cursor and a ray in the world is not the one on the desktop. Leave it out and
     * [pointerIcon] still says what the shape would be, which is what a game drawing its own
     * cursor reads.
     */
    private val cursor: SystemCursor = SystemCursor.None,
) : InputSink {

    /** What a captured pointer is doing, and to whom. */
    private class Capture(
        /** Who has the gesture. Only changes once, when a draggable ancestor takes it over. */
        var node: UiNode,
        val buttons: MutableSet<PointerButton>,
        /** The button that started it, which is what decides whether it can become a drag. */
        val first: PointerButton,
        /** Where it started, in the root's coordinates, so slop is measured from here. */
        val pressedAt: Offset,
    ) {
        /** True while the pointer is inside the captured node, which is what "pressed" means. */
        var inside = true
            set(value) {
                field = value
                gesture.inside = value
            }

        /**
         * The long press, the repeat and the start time a double click is measured from. Always
         * the node that took the press: a drag stops it, and a drag is never a click anyway.
         */
        val gesture = PressGesture(node)

        /**
         * The draggable this gesture turned into, or null while it is still a press that might be
         * a click. Kept, rather than read off the node each time, so a drag whose modifier goes
         * away half way through still has someone to tell it was cancelled.
         */
        var drag: DraggableElement? = null

        /**
         * True once this gesture has been a drag, even one cancelled half way through. A spent
         * gesture never starts another drag, never hands itself to a parent, and is never a click:
         * the player was dragging, and turning the draggable off under them does not change that.
         */
        var dragged = false

        /** Where the pointer was last reported to [drag], in the root's coordinates. */
        var lastAt: Offset = pressedAt
    }

    private val captures = mutableMapOf<PointerId, Capture>()

    /** The last click, for telling whether the next one is a double. */
    private val clicks = ClickMemory()

    /** What each pointer is hovering, deepest first. Kept so a change can be a diff. */
    private val hovering = mutableMapOf<PointerId, List<UiNode>>()

    /**
     * The cursor shape the mouse should be right now: the icon of the topmost node under it, or of
     * that node's nearest ancestor that asked for one, or [PointerIcon.Default].
     *
     * Held still for the length of a press, because the drag it starts belongs to what was under
     * the pointer when it began. See [dev.wildware.composegl.ui.modifier.pointerHoverIcon].
     */
    var pointerIcon: PointerIcon = PointerIcon.Default
        private set

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
            capture.gesture.cancel()
            capture.node.resolved.interactions.forEach { it.clear() }
            cancelDrag(capture)
        }
        hovering.keys.toList().forEach { id -> hover(id, emptyList()) }
        show(PointerIcon.Default)
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
        captures[event.pointerId] = Capture(taker, mutableSetOf(event.button), event.button, event.position)
        taker.resolved.interactions.forEach { it.press() }
        focus?.focusOn(taker)
        return true
    }

    private fun move(event: PointerEvent.Move): Boolean {
        val capture = captures[event.pointerId]
        if (capture != null) {
            val inside = capture.node.claims(event.position)
            if (inside != capture.inside) {
                capture.inside = inside
                // Dragging off a button un-presses it, and coming back presses it again. Nothing
                // has been decided yet — that happens on release.
                capture.node.resolved.interactions.forEach { if (inside) it.press() else it.release() }
            }
            val used = deliver(capture.node, event)
            drag(capture, event, used)
            // Captured means captured: the event belongs to this gesture whether or not a handler
            // had anything to say about it.
            return true
        }

        val candidates = candidatesUnder(event.position)
        val path = hoverPathFrom(candidates.firstOrNull(), event.position)
        hover(event.pointerId, path)
        if (event.type.hasCursor) show(iconOf(path))
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
        if (capture.drag != null) {
            // Wherever the release is, the drag gets there before it ends: a platform that reports
            // the last few pixels only on the release must not leave the item short of the pointer.
            val current = currentDrag(capture)
            if (current == null) {
                cancelDrag(capture)
            } else {
                follow(capture, current, event.position)
                current.onDragEnd()
            }
        }
        val click = capture.node.resolved.click
        // Asked first and always, because it is also what stops the gesture waiting on the clock.
        // False when a long press or a repeat already spent the press.
        val stillAClick = capture.gesture.finish()
        // A release inside the node that took the press is a click. Anywhere else is a change of
        // mind, which is a thing players do on purpose and must not be a click. "Inside" is the
        // node's own answer, so sliding off a round button onto the corner of its rectangle is a
        // change of mind like any other — the press could not have started there either. A drag
        // is never a click either, even one let go exactly where it started or cancelled on the way.
        if (!capture.dragged && click != null && click.enabled && capture.node.claims(event.position)) {
            if (stillAClick) clicks.click(capture.node, click, capture.gesture) else clicks.forget()
        } else {
            // A change of mind in between means the next click is a first one, not a second.
            clicks.forget()
        }

        // The gesture is over, so whatever the pointer is now over is hovered again, and the cursor
        // it held still through the drag is free to be that thing's.
        val path = hoverPathFrom(candidatesUnder(event.position).firstOrNull(), event.position)
        hover(event.pointerId, path)
        if (event.type.hasCursor) show(iconOf(path))
        return true
    }

    private fun cancel(event: PointerEvent.Cancel): Boolean {
        val capture = captures.remove(event.pointerId)
        hover(event.pointerId, emptyList())
        // Nothing is hovered after a cancel, so nothing is asking for a shape.
        if (event.type.hasCursor) show(PointerIcon.Default)
        if (capture == null) return false

        capture.gesture.cancel()
        if (capture.inside) capture.node.resolved.interactions.forEach { it.release() }
        // Delivered, so a handler mid-drag can put back whatever it was moving. No click: that is
        // the entire difference between a cancel and a release.
        deliver(capture.node, event)
        cancelDrag(capture)
        return true
    }

    private fun scroll(event: PointerEvent.Scroll): Boolean =
        candidatesUnder(event.position).any { deliver(it, event) }

    private fun exit(event: PointerEvent.Exit): Boolean {
        // Hover ends; a drag does not. A mouse leaving the window while a slider is being dragged
        // must keep dragging, and every toolkit that conflates the two has sliders that let go.
        val wasHovering = hovering[event.pointerId]?.isNotEmpty() == true
        hover(event.pointerId, emptyList())
        // Unless a drag is still going, in which case the shape it started with stays with it.
        if (event.type.hasCursor && event.pointerId !in captures) show(PointerIcon.Default)
        return wasHovering
    }

    // --- the machinery ------------------------------------------------------------------------

    /**
     * Every interactive node containing [point], in the order they should be offered it.
     *
     * The reverse of the order they are drawn in: children before their parent, last sibling
     * drawn — the highest `zIndex`, then the last written — before the ones under it. So the node
     * on top and furthest down the tree is asked first, and a node that declines an event lets it
     * through to whatever is underneath — an overlay that watches without blocking, a transparent
     * gutter beside a list.
     */
    private fun candidatesUnder(point: Offset): List<UiNode> {
        val found = mutableListOf<UiNode>()
        collect(root, 0f, 0f, 1f, point.x, point.y, found)
        return found
    }

    /**
     * The walk itself, in bare floats.
     *
     * The one reader of root coordinates that does not go through
     * [dev.wildware.composegl.ui.node.UiNode.boundsInRoot]: it is already coming down the tree, so
     * it works the rectangles out as it goes rather than walking back up for each one. That means
     * the scale has to be applied here too, the same way and with the same arithmetic, or a scaled
     * button would be drawn in one place and found in another.
     *
     * [scale] is what every ancestor together does to this node — so a child's position and size
     * are multiplied by it, measured from the corner its parent is actually drawn at.
     *
     * Floats rather than an [Offset] and a [Rect]: this runs over every node in the tree on every
     * mouse move, and the pair of objects it used to make per node was two allocations a node for
     * a pointer that had moved one pixel.
     */
    private fun collect(
        node: UiNode,
        originX: Float,
        originY: Float,
        scale: Float,
        pointX: Float,
        pointY: Float,
        into: MutableList<UiNode>,
    ) {
        val resolved = node.resolved
        // Invisible is untouchable, and it is the same test the draw pass makes, so what you
        // cannot see you cannot click.
        if (resolved.alpha <= 0f) return

        var left = originX + node.x * scale
        var top = originY + node.y * scale
        var right = left + node.width * scale
        var bottom = top + node.height * scale

        // This node's own scale, about its own anchor. Deliberately the same arithmetic as
        // Rect.scaledAbout rather than a call to it: the helper builds a Rect, and this runs over
        // every node in the tree on every mouse move. DrawPassTest's `where a scaled node is drawn
        // is where it says it is` pins the arithmetic against what the canvas is asked to
        // composite, because nothing else would notice the three parting.
        val own = node.drawnScale
        if (own != 1f) {
            val anchorX = left + resolved.scaleOrigin.xIn(node.width, 0f) * scale
            val anchorY = top + resolved.scaleOrigin.yIn(node.height, 0f) * scale
            left = anchorX + (left - anchorX) * own
            right = anchorX + (right - anchorX) * own
            top = anchorY + (top - anchorY) * own
            bottom = anchorY + (bottom - anchorY) * own
        }

        val inside = pointX >= left && pointX < right && pointY >= top && pointY < bottom
        // Two things stop the search early, and both are the same rule: nothing outside them is
        // drawn, so nothing outside them can be hit, children included. A clip says so. A scale
        // says so too, because the subtree is captured at exactly this node's own rectangle and
        // a child that overflows it is cut off on screen — so without this line a child hanging
        // out of a shrunk panel keeps taking clicks in the empty space where it used to be.
        if ((resolved.clip != null || own != 1f) && !inside) return
        // And a clip that is a shape says so for the corners it cut away, in the node's own units
        // for the same reason a hit shape is asked in them.
        if (inside && !insideClipShape(node, left, top, scale * own, pointX, pointY)) return

        // The draw pass's own list, walked the other way, so a lifted card takes the press.
        val children = node.drawOrder
        for (index in children.indices.reversed()) {
            collect(children[index], left, top, scale * own, pointX, pointY, into)
        }
        // The rectangle said yes; a node with a shape of its own now gets to say no. Turning it
        // down here rather than at the top leaves the children alone and lets the event carry on
        // to whatever is underneath this node.
        if (inside && resolved.isInteractive && ownsPoint(node, left, top, scale * own, pointX, pointY)) {
            into += node
        }
    }

    /**
     * Whether a node's own hit shape claims a point its rectangle already contains. True when it
     * has no shape, which is nearly always.
     *
     * [total] is everything scaling the node, its own scale included, so dividing by it gives the
     * node's own unscaled coordinates — the ones [dev.wildware.composegl.ui.node.UiNode.toLocal]
     * and [deliver] use, so a shape is written once and never in screen pixels. It cannot be zero
     * here: a scale of zero makes an empty rectangle, and an empty rectangle contains no point.
     */
    private fun ownsPoint(
        node: UiNode,
        left: Float,
        top: Float,
        total: Float,
        pointX: Float,
        pointY: Float,
    ): Boolean {
        val shape = node.resolved.hitShape ?: return true
        return shape(Offset((pointX - left) / total, (pointY - top) / total), Size(node.width, node.height))
    }

    /**
     * Whether a point the node's rectangle contains is also inside the shape its clip cuts to.
     * True for no clip and for a rectangular one, which is every clip there was before shapes.
     *
     * Same arguments and the same division as [ownsPoint], and for the same reason.
     */
    private fun insideClipShape(
        node: UiNode,
        left: Float,
        top: Float,
        total: Float,
        pointX: Float,
        pointY: Float,
    ): Boolean {
        val shape = node.resolved.clip?.shape ?: return true
        if (shape === Shapes.Rectangle) return true
        return shape.contains(Offset((pointX - left) / total, (pointY - top) / total), Size(node.width, node.height))
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
            // The same two questions the search asked on the way down, because an ancestor is
            // only hovered where it would have been clicked.
            if (walk.resolved.isInteractive && walk.claims(point)) path += walk
            walk = walk.parent
        }
        return path
    }

    /**
     * Whether a node claims a point of the root's: inside its rectangle, and inside its own hit
     * shape if it has one.
     *
     * The one place the question is answered for a node the search has already found — hover, the
     * pressed state under a capture, and whether a release is a click all ask this, so a shape
     * cannot narrow one of them and leave the others on the rectangle. The search on the way down
     * answers it itself, out of the numbers it is already carrying, because it runs over every
     * node in the tree on every mouse move and this walks the parent chain twice.
     *
     * The safe call is doing real work: [toLocal] allocates, and a node without a shape — which is
     * nearly every node — never reaches it.
     */
    private fun UiNode.claims(point: Offset): Boolean {
        if (point !in boundsInRoot) return false
        // A shaped clip anywhere above cut this point away on screen, so it is not claimed here
        // either — otherwise a press dragged onto a corner the portrait cut off still clicks.
        var above: UiNode? = this
        while (above != null) {
            val clip = above.resolved.clip?.shape
            if (clip != null && clip !== Shapes.Rectangle &&
                !clip.contains(above.toLocal(point), Size(above.width, above.height))
            ) {
                return false
            }
            above = above.parent
        }
        val shape = resolved.hitShape ?: return true
        return shape(toLocal(point), Size(width, height))
    }

    /** Asks one node's handlers, in chain order, in its own coordinates. */
    private fun deliver(node: UiNode, event: PointerEvent): Boolean {
        val handlers = node.resolved.handlers
        if (handlers.isEmpty()) return false
        // In the node's own units, not in screen pixels: a handler on a node drawn at half size
        // still thinks it is its full size, so a drag across it is the distance it looks like.
        val local = event.movedTo(node.toLocal(event.position))
        return handlers.any { it.onPointer(local) }
    }

    /** Whether [node] takes this press: a handler that says so, a `clickable`, or a `draggable`. */
    private fun consumes(node: UiNode, event: PointerEvent.Press): Boolean =
        deliver(node, event) || node.resolved.click != null ||
            (node.resolved.drag != null && event.button == PointerButton.Primary)

    // --- dragging -----------------------------------------------------------------------------

    /**
     * What a captured move means for a drag: carry one on, start one, or hand the gesture to a
     * draggable ancestor.
     *
     * [used] is whether the captured node's own handlers did anything with the move. A node that
     * is using the pointer — a slider, a text selection — keeps it; one that is not, a button with
     * nothing but a click, lets a draggable panel around it have the gesture once it is plainly a
     * drag rather than a click.
     */
    private fun drag(capture: Capture, event: PointerEvent.Move, used: Boolean) {
        val at = event.position
        if (capture.drag != null) {
            val current = currentDrag(capture)
            if (current == null) cancelDrag(capture) else follow(capture, current, at)
            return
        }
        if (capture.dragged || capture.first != PointerButton.Primary) return
        // Pressed and then taken off the screen before it moved: it keeps its last modifier, but
        // nobody can see it, so there is nothing to drag and no ancestor to hand it to.
        if (!capture.node.isUnder(root)) return

        val own = capture.node.resolved.drag
        val taker = when {
            own != null -> capture.node
            used -> return
            else -> draggableAncestorOf(capture.node) ?: return
        }
        val element = checkNotNull(taker.resolved.drag)

        // Slop in the taker's own units, so a panel drawn at half size wants the same distance
        // across it as it would at full size.
        val from = taker.toLocal(capture.pressedAt)
        if (taker.toLocal(at).distanceTo(from) <= element.slop) return

        // A drag is not a hold: the long press and the repeat the press was waiting on are off.
        capture.gesture.cancel()
        if (taker !== capture.node) handOff(capture, taker, event)
        capture.drag = element
        capture.dragged = true
        capture.lastAt = capture.pressedAt
        element.onDragStart(from)
        follow(capture, element, at)
    }

    /**
     * Tells [element] how far the pointer went since it was last told, in the node's own units.
     *
     * Both ends are turned into the node's coordinates *now*, so wherever the node has moved to in
     * the meantime cancels out: a window being dragged by its title bar moves exactly as far as
     * the pointer does, instead of seeing the pointer stay still relative to itself.
     */
    private fun follow(capture: Capture, element: DraggableElement, at: Offset) {
        val node = capture.node
        val delta = node.toLocal(at) - node.toLocal(capture.lastAt)
        capture.lastAt = at
        if (delta.x != 0f || delta.y != 0f) element.onDrag(delta)
    }

    /**
     * The node's draggable as it is now, or null if it was turned off mid-drag or the node itself
     * is no longer on the screen — an item dropped into a slot that the screen then rebuilt
     * elsewhere, say. A removed node keeps its last modifier, so without the second check it would
     * go on being told about a drag nobody can see.
     */
    private fun currentDrag(capture: Capture): DraggableElement? =
        capture.node.takeIf { it.isUnder(root) }?.resolved?.drag

    /** Whether [ancestor] is this node or somewhere above it. Removal cuts the walk short. */
    private fun UiNode.isUnder(ancestor: UiNode): Boolean {
        var walk: UiNode? = this
        while (walk != null) {
            if (walk === ancestor) return true
            walk = walk.parent
        }
        return false
    }

    /** Ends a drag that did not finish, if there is one. The newest callback hears it. */
    private fun cancelDrag(capture: Capture) {
        val started = capture.drag ?: return
        capture.drag = null
        (currentDrag(capture) ?: started).onDragCancel()
    }

    /** The nearest ancestor with a draggable, which is who gets a gesture its child did not want. */
    private fun draggableAncestorOf(node: UiNode): UiNode? {
        var walk = node.parent
        while (walk != null) {
            if (walk.resolved.drag != null) return walk
            walk = walk.parent
        }
        return null
    }

    /**
     * Moves a gesture from the child that took the press to the ancestor that is going to drag.
     *
     * The child is let go of exactly as a cancel would let go of it — un-pressed, told, and never
     * clicked — because from its point of view that is what happened: the player's press turned
     * out not to be for it.
     */
    private fun handOff(capture: Capture, to: UiNode, event: PointerEvent.Move) {
        val from = capture.node
        if (capture.inside) from.resolved.interactions.forEach { it.release() }
        deliver(from, PointerEvent.Cancel(event.pointerId, event.position, event.type, event.timeMillis))
        capture.node = to
        capture.inside = to.claims(event.position)
        if (capture.inside) to.resolved.interactions.forEach { it.press() }
    }

    /**
     * The icon a hover path asks for: the deepest node's own, or the nearest ancestor's that has
     * one. The path is already only the nodes that claim the point, so an ancestor's icon never
     * shows over a corner its hit shape turned down.
     */
    private fun iconOf(path: List<UiNode>): PointerIcon {
        for (node in path) node.resolved.hoverIcon?.let { return it }
        return PointerIcon.Default
    }

    /** Tells the cursor about [icon], if it is not already showing it. */
    private fun show(icon: PointerIcon) {
        if (icon == pointerIcon) return
        pointerIcon = icon
        cursor.set(icon)
    }

    /** Whether this kind of pointer has a cursor on a screen for [cursor] to change. */
    private val PointerType.hasCursor: Boolean
        get() = this == PointerType.Mouse || this == PointerType.Stylus

    /** Moves a pointer's hover from whatever it was on to [now], touching only the difference. */
    private fun hover(id: PointerId, now: List<UiNode>) {
        val before = hovering[id].orEmpty()
        if (before == now) return
        before.forEach { if (it !in now) it.resolved.interactions.forEach { state -> state.leave() } }
        now.forEach { if (it !in before) it.resolved.interactions.forEach { state -> state.enter() } }
        if (now.isEmpty()) hovering.remove(id) else hovering[id] = now
    }
}
