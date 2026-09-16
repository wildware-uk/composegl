package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.backend.SystemCursor
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.modifier.DraggableElement
import dev.wildware.composegl.ui.modifier.ResolvedModifier
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.childOffsetX
import dev.wildware.composegl.ui.node.childOffsetY
import dev.wildware.composegl.ui.node.childScale
import dev.wildware.composegl.ui.node.showsChild
import dev.wildware.composegl.ui.modifier.DefaultDragSlop
import dev.wildware.composegl.ui.widget.canOpenContextMenu
import dev.wildware.composegl.ui.widget.openContextMenu

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
 * Capture decides who *handles* an event, and nothing else. A node that only watches the pointer —
 * [dev.wildware.composegl.ui.modifier.watchPointer] — is told everything that happens over it
 * whatever else is going on, a captured gesture included, because it is not competing for the
 * event: see [PointerWatcher].
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
        /** What the pointer was hovering before the press, so letting go over it again is quiet. */
        val hovered: List<UiNode>,
        /** The press itself, for the kind of pointer it was when the gesture has to be cancelled. */
        val press: PointerEvent.Press,
        /** The context menu holding still opens, if any. */
        menu: UiNode?,
        /** What the router does once that hold has opened it. */
        onMenuOpened: (Capture) -> Unit,
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
        val gesture = PressGesture(node, menu) { onMenuOpened(this) }

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
        // Before anything takes it: a menu bar watching for Alt+click has to hear the click either way.
        root.tree?.watched(event)
        val held = captures[event.pointerId]
        if (held != null) {
            // Every watching layer the pointer is over hears it too, whoever is holding the gesture.
            if (watching) watched(candidatesUnder(event.position), event, held.node)
            // A second button on a pointer already holding something belongs to the same gesture.
            // It does not get to move the capture somewhere else half way through.
            held.buttons += event.button
            deliver(held.node, event)
            return true
        }

        val candidates = candidatesUnder(event.position)
        // And every watching layer the pointer is over, whoever ends up taking the press.
        if (watching) watched(candidates, event)

        // The shape is read here as well as on a move, because the press is what the drag will hold
        // it at. A press with no move before it, or after a scroll slid something new under a still
        // mouse, would otherwise drag with whatever shape the last move left.
        if (event.type.hasCursor) show(iconOf(hoverPathFrom(topmost(candidates), event.position)))
        val menu = if (event.button == PointerButton.Secondary) menuUnder(candidates) else -1
        val taker = if (menu < 0) {
            // Nothing else wanting it, a node with a context menu holds the press itself, since a
            // long press may open that menu. Only then: a press that would reach a clickable or a
            // scroll round it is theirs, and the hold finds the menu from there.
            candidates.firstOrNull { consumes(it, event) } ?: holdOnly(candidates, event)
        } else {
            // A right press opens the nearest context menu under it, at the pointer — nearest past
            // anything inside it that only clicks, since a right-click on a button inside an
            // inventory slot is about the slot. A node whose own handler takes the press first, a map
            // that moves a unit on a right-click, keeps it. Something not inside the menu's node — a
            // HUD button drawn over the map, a dialog over the screen — takes it as it always did.
            val opener = candidates[menu]
            val handled = candidates.subList(0, menu).firstOrNull {
                if (it.isUnder(opener)) deliver(it, event) else consumes(it, event)
            }
            if (handled == null && opener.openContextMenu(opener.toLocal(event.position))) {
                hover(event.pointerId, emptyList())
                return true
            }
            handled ?: candidates.drop(menu).firstOrNull { consumes(it, event) }
        } ?: return false

        // A gesture has started, so nothing is merely hovered any more.
        val hovered = hovering[event.pointerId].orEmpty()
        hover(event.pointerId, emptyList())
        captures[event.pointerId] =
            Capture(taker, mutableSetOf(event.button), event.button, event.position, hovered, event, menuForHold(candidates, taker, event), ::menuOpened)
                // A long press that opens a context menu opens it under the finger.
                .also { it.gesture.at = event.position }
        taker.resolved.interactions.forEach { it.press() }
        if (taker.usable) taker.sounds.press()
        focus?.focusOn(taker)
        return true
    }

    private fun move(event: PointerEvent.Move): Boolean {
        val capture = captures[event.pointerId]
        if (capture != null) {
            // The gesture belongs to the captured node, but a watching layer is not competing for
            // it: a card hanging beside the cursor has to keep up while the player drags an item
            // across the bag. So the walk happens under a capture too — but only on a tree that
            // has a watcher on it, since a drag is otherwise the one move that walks nothing.
            if (watching) watched(candidatesUnder(event.position), event, capture.node)
            pressWhereInside(capture, event.position)
            val used = deliver(capture.node, event)
            // A press that is doing something — a slider's thumb following it, a selection growing,
            // a list about to scroll — is not a hold, and a context menu opening under it would pull
            // the gesture out from under the player half way through.
            if (used || capture.node.toLocal(event.position).distanceTo(capture.node.toLocal(capture.pressedAt)) > DefaultDragSlop) {
                capture.gesture.refuseMenu()
            }
            drag(capture, event, used)
            // Asked again, since this move may be the one that started the drag.
            pressWhereInside(capture, event.position)
            // Captured means captured: the event belongs to this gesture whether or not a handler
            // had anything to say about it.
            return true
        }

        val candidates = candidatesUnder(event.position)
        if (watching) watched(candidates, event)
        val path = hoverPathFrom(topmost(candidates), event.position)
        hover(event.pointerId, path)
        if (event.type.hasCursor) show(iconOf(path))
        return candidates.any { deliver(it, event) }
    }

    /**
     * Keeps the captured node pressed while the pointer is inside it, and while it is being dragged.
     *
     * Dragging off a button un-presses it, and coming back presses it again. Nothing has been
     * decided yet — that happens on release. A node being dragged is different: it stays pressed
     * wherever the pointer goes until the button comes up. "Inside" is asked of where the node was
     * last laid out, so a thin handle the pointer outruns between frames — a column divider under
     * a fast flick — would otherwise go dark while it is still following the hand.
     */
    private fun pressWhereInside(capture: Capture, at: Offset) {
        val inside = capture.drag != null || capture.node.claims(at)
        if (inside == capture.inside) return
        capture.inside = inside
        capture.node.resolved.interactions.forEach { if (inside) it.press() else it.release() }
    }

    private fun release(event: PointerEvent.Release): Boolean {
        val capture = captures[event.pointerId]
        if (capture == null) {
            val candidates = candidatesUnder(event.position)
            if (watching) watched(candidates, event)
            return candidates.any { deliver(it, event) }
        }
        // Where the button came up is as much a part of what the pointer did as the move before it,
        // and the gesture holding the event does not stop anything else being told about it.
        if (watching) watched(candidatesUnder(event.position), event, capture.node)

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

        // The gesture is over, so whatever the pointer is now over is hovered again. Quietly when
        // that is the node it pressed or something it was already on, since as far as the player
        // is concerned the pointer never left; a drag let go over another button has arrived there.
        // The cursor it held still through the drag is free to be that thing's.
        val path = hoverPathFrom(topmost(candidatesUnder(event.position)), event.position)
        hover(event.pointerId, path, after = capture)
        if (event.type.hasCursor) show(iconOf(path))
        return true
    }

    private fun cancel(event: PointerEvent.Cancel): Boolean {
        val capture = captures.remove(event.pointerId)
        // Every watcher, not only the ones the point lands on: a cancel is the gesture being taken
        // away, and where the pointer happened to be when that happened is neither here nor there.
        watchedEverywhere(event)
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

    private fun scroll(event: PointerEvent.Scroll): Boolean {
        val candidates = candidatesUnder(event.position)
        if (watching) watched(candidates, event)
        return candidates.any { deliver(it, event) }
    }

    private fun exit(event: PointerEvent.Exit): Boolean {
        // Hover ends; a drag does not. A mouse leaving the window while a slider is being dragged
        // must keep dragging, and every toolkit that conflates the two has sliders that let go.
        val wasHovering = hovering[event.pointerId]?.isNotEmpty() == true
        // The one way a watching layer can learn there is no pointer any more. A card that hangs
        // beside the cursor has nowhere to be once the cursor is off the window, and without this
        // it sits at the last place the mouse was on the way out.
        //
        // Every watcher on the tree, without asking what is under the point — which is the whole
        // difficulty with an exit. Its position is where the pointer went *out*, so it is on the
        // node's edge or past it, and hit testing is half-open: an exit at `x == width` is over
        // nothing at all. A backend that reports the real edge (the browser's `pointerleave`) or a
        // point in the next screen along (a split screen telling the area the pointer just left)
        // would otherwise reach nobody, which is the one case this whole feature exists for.
        watchedEverywhere(event)
        hover(event.pointerId, emptyList())
        // Unless a drag is still going, in which case the shape it started with stays with it.
        if (event.type.hasCursor && event.pointerId !in captures) show(PointerIcon.Default)
        return wasHovering
    }

    // --- the machinery ------------------------------------------------------------------------

    /**
     * Every node the pointer has anything to say to containing [point], in the order they should be
     * offered it — the interactive ones and the ones only watching.
     *
     * The reverse of the order they are drawn in: children before their parent, last sibling
     * drawn — the highest `zIndex`, then the last written — before the ones under it. So the node
     * on top and furthest down the tree is asked first, and a node that declines an event lets it
     * through to whatever is underneath — an overlay that watches without blocking, a transparent
     * gutter beside a list.
     */
    private fun candidatesUnder(point: Offset): List<UiNode> {
        val found = mutableListOf<UiNode>()
        collect(root, 0f, 0f, 1f, 1f, point.x, point.y, found)
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
     * [scaleX] and [scaleY] are what every ancestor together does to this node — so a child's
     * position and size are multiplied by them, measured from where its parent's own zero is
     * actually drawn. They are negative under an odd number of mirrors, which is the whole of how
     * a mirror is carried down: that zero is then the parent's drawn right or bottom edge, and the
     * child's rectangle runs back from it.
     *
     * Floats rather than an [Offset] and a [Rect]: this runs over every node in the tree on every
     * mouse move, and the pair of objects it used to make per node was two allocations a node for
     * a pointer that had moved one pixel.
     */
    private fun collect(
        node: UiNode,
        originX: Float,
        originY: Float,
        scaleX: Float,
        scaleY: Float,
        pointX: Float,
        pointY: Float,
        into: MutableList<UiNode>,
    ) {
        val resolved = node.resolved
        // Invisible is untouchable, and it is the same test the draw pass makes, so what you
        // cannot see you cannot click.
        if (resolved.alpha <= 0f) return

        // Where this node's own zero and its far edge are drawn. Under a mirror the far edge is
        // the smaller number, so the rectangle is whichever way round they came out.
        val startX = originX + node.x * scaleX
        val startY = originY + node.y * scaleY
        val endX = startX + node.width * scaleX
        val endY = startY + node.height * scaleY
        var left = if (scaleX < 0f) endX else startX
        var right = if (scaleX < 0f) startX else endX
        var top = if (scaleY < 0f) endY else startY
        var bottom = if (scaleY < 0f) startY else endY

        // This node's own scale, about its own anchor. Deliberately the same arithmetic as
        // Rect.scaledAbout rather than a call to it: the helper builds a Rect, and this runs over
        // every node in the tree on every mouse move. DrawPassTest's `where a scaled node is drawn
        // is where it says it is` pins the arithmetic against what the canvas is asked to
        // composite, because nothing else would notice the three parting.
        val own = node.drawnScale
        // The anchor is a place in the node's own coordinates, so it is measured from its own zero
        // along its own axes — the drawn right edge, under a mirror above it.
        val anchorX = startX + resolved.scaleOrigin.xIn(node.width, 0f) * scaleX
        val anchorY = startY + resolved.scaleOrigin.yIn(node.height, 0f) * scaleY
        if (own != 1f) {
            left = anchorX + (left - anchorX) * own
            right = anchorX + (right - anchorX) * own
            top = anchorY + (top - anchorY) * own
            bottom = anchorY + (bottom - anchorY) * own
        }

        // This node's own mirror leaves its rectangle where it is and turns the axes its children
        // are measured along: their zero is drawn where this node's far edge is.
        val mirrorX = node.drawnMirrorX
        val mirrorY = node.drawnMirrorY
        val innerX = anchorX + ((if (mirrorX) endX else startX) - anchorX) * own
        val innerY = anchorY + ((if (mirrorY) endY else startY) - anchorY) * own
        val innerScaleX = if (mirrorX) -scaleX * own else scaleX * own
        val innerScaleY = if (mirrorY) -scaleY * own else scaleY * own

        val inside = pointX >= left && pointX < right && pointY >= top && pointY < bottom
        // Two things stop the search early, and both are the same rule: nothing outside them is
        // drawn, so nothing outside them can be hit, children included. A clip says so. A scale or
        // a mirror says so too, because the subtree is captured at exactly this node's own
        // rectangle and a child that overflows it is cut off on screen — so without this line a
        // child hanging out of a shrunk panel keeps taking clicks in the empty space where it used
        // to be. A resize still under way clips as it draws, so it stops the search the same way.
        if ((resolved.clip != null || own != 1f || mirrorX || mirrorY || node.isResizing) && !inside) return
        // And a clip that is a shape says so for the corners it cut away, in the node's own units
        // for the same reason a hit shape is asked in them.
        if (inside && !insideClipShape(node, innerX, innerY, innerScaleX, innerScaleY, pointX, pointY)) return

        // The draw pass's own list, walked the other way, so a lifted card takes the press.
        val children = node.drawOrder
        if (resolved.camera == null) {
            for (index in children.indices.reversed()) {
                collect(children[index], innerX, innerY, innerScaleX, innerScaleY, pointX, pointY, into)
            }
        } else {
            // A camera over the children: each is found where it shows them, and one it does not
            // show — off the edge of the view — is not found at all. See ContentCamera.
            for (index in children.indices.reversed()) {
                val child = children[index]
                if (!node.showsChild(child)) continue
                val grow = node.childScale(child)
                collect(
                    child,
                    innerX + node.childOffsetX(child) * innerScaleX,
                    innerY + node.childOffsetY(child) * innerScaleY,
                    innerScaleX * grow,
                    innerScaleY * grow,
                    pointX,
                    pointY,
                    into,
                )
            }
        }
        // The rectangle said yes; a node with a shape of its own now gets to say no. Turning it
        // down here rather than at the top leaves the children alone and lets the event carry on
        // to whatever is underneath this node.
        if (inside && resolved.hearsPointer &&
            ownsPoint(node, innerX, innerY, innerScaleX, innerScaleY, pointX, pointY)
        ) {
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
        zeroX: Float,
        zeroY: Float,
        totalX: Float,
        totalY: Float,
        pointX: Float,
        pointY: Float,
    ): Boolean {
        val shape = node.resolved.hitShape ?: return true
        return shape(Offset((pointX - zeroX) / totalX, (pointY - zeroY) / totalY), Size(node.width, node.height))
    }

    /**
     * Whether a point the node's rectangle contains is also inside the shape its clip cuts to.
     * True for no clip and for a rectangular one, which is every clip there was before shapes.
     *
     * Same arguments and the same division as [ownsPoint], and for the same reason.
     */
    private fun insideClipShape(
        node: UiNode,
        zeroX: Float,
        zeroY: Float,
        totalX: Float,
        totalY: Float,
        pointX: Float,
        pointY: Float,
    ): Boolean {
        val shape = node.resolved.clip?.shape ?: return true
        if (shape === Shapes.Rectangle) return true
        return shape.contains(
            Offset((pointX - zeroX) / totalX, (pointY - zeroY) / totalY),
            Size(node.width, node.height),
        )
    }

    /**
     * The first of [candidates] that is actually in front of the others.
     *
     * Everything the pointer finds is offered the event, but a node that only *watches* it —
     * [dev.wildware.composegl.ui.modifier.watchPointer] — is not standing in front of anything, so
     * hover, the cursor's shape and a hold that opens a menu all look past it to the real node
     * underneath. Without this a full-screen layer watching the mouse would be the topmost thing
     * the pointer ever found, and nothing under it would light up again.
     */
    private fun topmost(candidates: List<UiNode>): UiNode? = candidates.firstOrNull { it.resolved.isInteractive }

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

    /**
     * Whether anything on this tree is watching the pointer at all. One comparison, and false in
     * nearly every game there will ever be.
     *
     * Everything a watcher needs costs a walk over the tree, and two of those walks — the one under
     * a capture and the one on a release — are walks the router would otherwise not do at all. So
     * they are asked for only when there is somebody to hear them. The register is kept by
     * [dev.wildware.composegl.ui.node.UiNode.pointerWatching] as chains change and nodes come and
     * go, so this is never out of date.
     */
    private val watching: Boolean get() = root.tree?.pointerWatchers?.isNotEmpty() == true

    /**
     * Tells every watcher on the tree, wherever it is and wherever the pointer is.
     *
     * For the two events that are *about* the pointer no longer being anywhere: it left the window,
     * or the platform took the gesture away. Their position is by definition not over the thing
     * being told — an exit's is on the edge or past it — so hit testing for them finds nobody, and
     * a card hanging beside a cursor that is gone would sit there for ever.
     */
    private fun watchedEverywhere(event: PointerEvent) {
        val watchers = root.tree?.pointerWatchers ?: return
        if (watchers.isEmpty()) return
        // Over a copy: a watcher may take its own layer off the tree as it hears the pointer go.
        watchers.toList().forEach { watched(it, event) }
    }

    /**
     * Tells the watchers on everything the pointer is over, before anything decides what to do
     * with the event.
     *
     * A pass of its own rather than a step inside [deliver], because the two questions have
     * different answers. Who *handles* an event is one node — whoever captured the gesture, or the
     * first candidate that wanted it — so a press a button swallows never reaches the layer under
     * it and a move during a drag reaches nothing else at all. Who *watches* it is everything the
     * pointer is over, every time: that is the whole point of
     * [dev.wildware.composegl.ui.modifier.watchPointer], and a card hanging beside the cursor that
     * went quiet the moment a player held the mouse down would be a card frozen mid-drag.
     *
     * [held] is the node holding a captured gesture, if there is one. Told as well when the pointer
     * has been dragged off it, so a watcher on it hears exactly what a handler on it hears.
     */
    private fun watched(candidates: List<UiNode>, event: PointerEvent, held: UiNode? = null) {
        candidates.forEach { watched(it, event) }
        if (held != null && candidates.none { it === held }) watched(held, event)
    }

    /** One node's watchers, in chain order, in its own coordinates, exactly as [deliver] asks. */
    private fun watched(node: UiNode, event: PointerEvent) {
        val watchers = node.resolved.pointerWatchers
        if (watchers.isEmpty()) return
        val local = event.movedTo(node.toLocal(event.position))
        watchers.forEach { it.saw(local) }
    }

    /** Whether [node] takes this press: a handler that says so, a `clickable`, or a `draggable`. */
    private fun consumes(node: UiNode, event: PointerEvent.Press): Boolean =
        deliver(node, event) || node.resolved.click != null ||
            (node.resolved.drag != null && event.button == PointerButton.Primary)

    /**
     * Where in [candidates] the context menu a right press opens is, or -1 for none: the nearest
     * one, but never one past a focus trap, since that is a menu behind a dialog.
     */
    private fun menuUnder(candidates: List<UiNode>): Int {
        candidates.forEachIndexed { index, candidate ->
            if (candidate.resolved.contextMenu?.enabled == true) return index
            if (candidate.resolved.focusTrap) return -1
        }
        return -1
    }

    /**
     * The node that holds a primary press nobody else took, because holding it opens its context
     * menu: the nearest one with a menu that can open. Null when there is none, and the press goes
     * nowhere, exactly as it did before context menus.
     */
    private fun holdOnly(candidates: List<UiNode>, event: PointerEvent.Press): UiNode? {
        if (event.button != PointerButton.Primary) return null
        return topmost(candidates)?.menuOnHold?.takeIf { it in candidates && it.canOpenContextMenu }
    }

    /**
     * The context menu a long press on [taker] opens, or null for none.
     *
     * Found from the deepest node under the pointer that [taker] holds, not from [taker] itself: a
     * box with a menu inside a clickable card leaves the card its click but still opens on a hold.
     * Only the primary button, and only a menu that can actually open, so a hold that could not
     * open anything is still a click when it comes up.
     */
    private fun menuForHold(candidates: List<UiNode>, taker: UiNode, event: PointerEvent.Press): UiNode? {
        if (event.button != PointerButton.Primary) return null
        val deepest = candidates.firstOrNull { it === taker || it.isUnder(taker) } ?: taker
        return deepest.menuOnHold?.takeIf { it.canOpenContextMenu }
    }

    /**
     * A long press opened a context menu: the press is let go of exactly as a cancel lets go, so a
     * scroll or a slider it started stops where it is and the release that follows clicks nothing.
     */
    private fun menuOpened(capture: Capture) {
        val id = capture.press.pointerId
        if (captures[id] !== capture) return
        captures.remove(id)
        if (capture.inside) capture.node.resolved.interactions.forEach { it.release() }
        val cancelled = PointerEvent.Cancel(id, capture.lastAt, capture.press.type, capture.press.timeMillis)
        // Only this node's watchers: this cancel is not something the pointer did, it is one node
        // being let go of, and nothing else is being told it either.
        watched(capture.node, cancelled)
        deliver(capture.node, cancelled)
        cancelDrag(capture)
    }

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
        val cancelled = PointerEvent.Cancel(event.pointerId, event.position, event.type, event.timeMillis)
        // This node's own watchers, for the same reason as in [menuOpened]: the gesture moving to
        // an ancestor is news to the child it left, and to nobody else.
        watched(from, cancelled)
        deliver(from, cancelled)
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

    /**
     * Moves a pointer's hover from whatever it was on to [now], touching only the difference.
     *
     * One hover sound at most, for the deepest usable node the pointer has just arrived on. The
     * path includes ancestors, and a button inside a clickable card ticking twice is two sounds for
     * one movement of the hand. [after] is the gesture that has just ended, if one has: the node it
     * pressed and what was hovered before it stay quiet.
     */
    private fun hover(id: PointerId, now: List<UiNode>, after: Capture? = null) {
        val before = hovering[id].orEmpty()
        if (before == now) return
        before.forEach { if (it !in now) it.resolved.interactions.forEach { state -> state.leave() } }
        now.forEach { if (it !in before) it.resolved.interactions.forEach { state -> state.enter() } }
        val arrived = now.firstOrNull { it !in before && it.usable }
        if (arrived != null && (after == null || arrived !== after.node && arrived !in after.hovered)) {
            arrived.sounds.hover()
        }
        if (now.isEmpty()) hovering.remove(id) else hovering[id] = now
    }
}
