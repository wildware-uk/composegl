package dev.wildware.composegl.ui.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode
import kotlin.math.min
import kotlin.math.sqrt

/** What a [FocusOverlay] draws. Any mix of them; [All] is every one. */
enum class FocusShow {
    /**
     * Arrows from the focused node to where Up, Down, Left and Right would take focus. Cyan where the
     * geometry chose, orange where a `focusOrder` named the answer.
     */
    Arrows,

    /**
     * Every focusable node outlined: green where focus can reach it, grey where a focus trap shuts it
     * out. The focused node gets a thicker white edge.
     */
    Focusable,

    /** Each `focusTrap`, shaded violet. */
    Traps,

    /**
     * Where the pointer can press: every node with a click, a drag or a pointer handler, tinted
     * yellow, cut to the clips above it.
     * Where a `hitShape` or a shaped clip on it or above it turns a point of its rectangle down,
     * that hole is shaded red instead.
     */
    HitAreas;

    companion object {
        val All: Set<FocusShow> = entries.toSet()
    }
}

/**
 * How focus and the pointer see the whole screen, drawn over it.
 *
 * Gamepad focus is worked out from geometry, so when Down goes to the wrong button there is
 * otherwise nothing to look at. This draws the answer before the button is pressed: an arrow from
 * the focused node to each of its four neighbours, every focusable node outlined, focus traps
 * shaded, and the areas a click really lands on tinted, with the holes a `hitShape` cuts shown.
 *
 * Compose it last, at the top level of the screen, behind the game's own switch:
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     Game()
 *     FocusOverlay(enabled = debug)
 * }
 * ```
 *
 * It finds the [FocusManager] that was built over its tree — the most recent one built over the
 * nearest root or sub-root above it — so it needs nothing handed to it. A game with two managers over
 * one tree names the one it means with [focus]. With none at all it still outlines focusable nodes
 * and tints hit areas; there is no focus to draw arrows from.
 *
 * An arrow says where focus goes when the focused node lets the press through. A slider takes Left
 * and Right for itself until it reaches an end, and that is not drawn.
 *
 * Like [LayoutOverlay] it is a node with no size and no input, lifted over its siblings, that reads
 * the tree while it is drawn: it moves nothing, a click goes straight through it, and it follows
 * every change. Focus moving is the one change that can leave a screen looking exactly the same, so
 * it tells the tree when that happens, and only while it is on.
 *
 * Deliberately unskinnable. It samples a shaped hit area in small squares, so it costs more on a
 * screen full of round buttons. Take it off before shipping.
 *
 * @param enabled whether it is there at all. Off composes nothing.
 * @param show which of the four things to draw.
 * @param focus the manager whose focus to draw, or null to find the one built over this tree.
 */
@Composable
fun FocusOverlay(
    enabled: Boolean,
    show: Set<FocusShow> = FocusShow.All,
    focus: FocusManager? = null,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    // Remembered by value for the reason LayoutOverlay gives: an equal set must not redraw the tree.
    val painter = remember(show, focus) { FocusOverlayPainter(show, focus) }
    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode(FocusOverlayName) },
        update = {
            set(modifier) { this.modifier = Modifier.zIndex(Float.MAX_VALUE).then(it) }
            set(MeasurePolicy.Empty) { this.measurePolicy = it }
            set(NoInk) { this.ink = it }
            set(painter) {
                it.node = this
                this.content = it
            }
        },
    )
    // After the node is in the tree, which is how the manager is found. A focus move redraws the
    // screen while the overlay is on, so a game that skips unchanged frames still sees the arrows move.
    // Every draw looks again, for a manager built after the overlay went on.
    DisposableEffect(painter) {
        painter.listen()
        onDispose { painter.stopListening() }
    }
}

/** What the overlay node is called in a dump. */
internal const val FocusOverlayName = "focus overlay"

private val NoInk: (Rect) -> Rect? = { null }

/** The overlay's colours. Its own, the same over any skin. */
internal object FocusOverlayColours {
    val Geometry = Colour.rgb(0x00E5FF)
    val Ordered = Colour.rgb(0xFF9900)
    val Focusable = Colour.rgb(0x40FF60)
    val Focused = Colour.rgb(0xFFFFFF)
    val Unreachable = Colour.rgb(0x808080)
    val Trap = Colour.argb(0x40A040FF)
    val HitArea = Colour.argb(0x50FFE000)
    val Hole = Colour.argb(0x60FF2040)
}

/** Every debug overlay's painter, so each one's walk can leave the others out. */
internal interface DebugOverlayPainter : (UiCanvas, Rect) -> Unit

/**
 * The drawing itself, handed to the overlay node as its content.
 *
 * Washes first — traps, then hit areas — and edges and arrows over them, so a tint never hides an
 * outline and an arrow is never under anything.
 */
internal class FocusOverlayPainter(
    private val show: Set<FocusShow>,
    private val focus: FocusManager?,
) : DebugOverlayPainter {

    /** The node this draws for. Set when it is handed over, and how it finds the tree. */
    var node: UiNode? = null

    /** The manager named, else the one built over the nearest root above the overlay. */
    fun manager(): FocusManager? {
        if (focus != null) return focus
        var walk = node
        while (walk != null) {
            walk.focusManager?.let { return it }
            walk = walk.parent
        }
        return null
    }

    /** The manager whose focus moves this is listening to, or null. */
    private var listening: FocusManager? = null

    private val moved: () -> Unit = { node?.invalidate() }

    /** Listens to [manager], leaving the one it listened to before if that has changed. */
    fun listen() {
        val manager = manager()
        if (manager === listening) return
        listening?.movedListeners?.remove(moved)
        manager?.movedListeners?.add(moved)
        listening = manager
    }

    fun stopListening() {
        listening?.movedListeners?.remove(moved)
        listening = null
    }

    override fun invoke(canvas: UiCanvas, content: Rect) {
        // Drawn into OverdrawOverlay's count: its washes and arrows are not what the screen paints.
        if (canvas is OverdrawCanvas) return
        val self = node ?: return
        listen()
        var root = self
        while (true) root = root.parent ?: break
        val manager = manager()

        // The same reading of where the canvas's zero is as LayoutOverlay makes.
        val box = self.layoutBoundsInRoot
        val dx = content.left - box.left - self.resolved.padding.left
        val dy = content.top - box.top - self.resolved.padding.top - self.baselineTop

        if (FocusShow.Traps in show) {
            walk(root, Unbounded, emptyList()) { node, _, _ ->
                if (node.resolved.focusTrap) fill(canvas, node.boundsInRoot, FocusOverlayColours.Trap, dx, dy)
            }
        }
        if (FocusShow.HitAreas in show) {
            walk(root, Unbounded, emptyList()) { node, clip, shaped ->
                if (node.takesPresses) hitArea(canvas, node, clip, shaped, dx, dy)
            }
        }
        val focused = manager?.focused
        if (FocusShow.Focusable in show) {
            // What Tab and the pad can reach, inside the innermost trap. Without a manager, every one.
            val reachable = manager?.reachable()?.toHashSet()
            walk(root, Unbounded, emptyList()) { node, _, _ ->
                if (node === focused) return@walk
                if (node.resolved.focusable?.enabled != true) return@walk
                val colour = if (reachable == null || node in reachable) {
                    FocusOverlayColours.Focusable
                } else {
                    FocusOverlayColours.Unreachable
                }
                outline(canvas, node.boundsInRoot, colour, 1f, dx, dy)
            }
            if (focused != null) outline(canvas, focused.boundsInRoot, FocusOverlayColours.Focused, 2f, dx, dy)
        }
        if (FocusShow.Arrows in show && manager != null && focused != null) {
            val source = focused.boundsInRoot
            for (direction in Directions) {
                val step = manager.peek(direction) ?: continue
                if (step.node === focused) continue
                val colour = if (step.ordered) FocusOverlayColours.Ordered else FocusOverlayColours.Geometry
                arrow(canvas, edgeOf(source, direction), step.node.boundsInRoot.centre, colour, dx, dy)
            }
        }
    }

    /**
     * Every node the draw pass would draw, but overlays, parents first, each with the clip the
     * pointer search would have reached it under, and the nodes at or above it whose clip is a
     * shape. The same things stop that search: a clip, a scale, a mirror or a resize under way
     * cuts off what is outside the node's rectangle, and a shaped clip what is outside its shape.
     */
    private fun walk(node: UiNode, clip: Rect, shaped: List<UiNode>, visit: (UiNode, Rect, List<UiNode>) -> Unit) {
        if (node.content is DebugOverlayPainter || !node.everMeasured) return
        val resolved = node.resolved
        if (resolved.alpha <= 0f || resolved.scale <= 0f) return
        val cuts = resolved.clip != null || node.drawnScale != 1f || node.drawnMirrorX || node.drawnMirrorY ||
            node.isResizing
        val inner = if (cuts) clip.intersect(node.boundsInRoot) else clip
        val within = if (resolved.clip?.shape?.let { it !== Shapes.Rectangle } == true) shaped + node else shaped
        visit(node, clip, within)
        val children = node.drawOrder
        for (index in children.indices) walk(children[index], inner, within, visit)
    }

    /**
     * The part of a node's rectangle a press lands on. A plain one is one tint. A shaped one — its
     * own hit shape, or a shaped clip on it or above it — is asked square by square, each shape in
     * its own node's coordinates as the pointer asks it, and each row's runs of yes and no are drawn
     * as one rectangle each.
     */
    private fun hitArea(canvas: UiCanvas, node: UiNode, clip: Rect, shaped: List<UiNode>, dx: Float, dy: Float) {
        val area = node.boundsInRoot.intersect(clip)
        if (area.isEmpty) return
        val hitShape = node.resolved.hitShape
        if (hitShape == null && shaped.isEmpty()) {
            fill(canvas, area, FocusOverlayColours.HitArea, dx, dy)
            return
        }
        val size = Size(node.width, node.height)
        fun claims(point: Offset): Boolean {
            if (hitShape != null && !hitShape(node.toLocal(point), size)) return false
            return shaped.all { it.resolved.clip!!.shape.contains(it.toLocal(point), Size(it.width, it.height)) }
        }
        var top = area.top
        while (top < area.bottom) {
            val bottom = min(top + HitCell, area.bottom)
            val middle = (top + bottom) / 2f
            var runStart = area.left
            var runClaimed = claims(Offset((area.left + min(area.left + HitCell, area.right)) / 2f, middle))
            var left = area.left
            while (left < area.right) {
                val right = min(left + HitCell, area.right)
                val claimed = claims(Offset((left + right) / 2f, middle))
                if (claimed != runClaimed) {
                    fill(canvas, Rect(runStart, top, left, bottom), runColour(runClaimed), dx, dy)
                    runStart = left
                    runClaimed = claimed
                }
                left = right
            }
            fill(canvas, Rect(runStart, top, area.right, bottom), runColour(runClaimed), dx, dy)
            top = bottom
        }
    }

    private fun runColour(claimed: Boolean) = if (claimed) FocusOverlayColours.HitArea else FocusOverlayColours.Hole

    /** The middle of the source's edge facing [direction], so four arrows to one node do not overlap. */
    private fun edgeOf(rect: Rect, direction: FocusDirection): Offset = when (direction) {
        FocusDirection.Up -> Offset(rect.centre.x, rect.top)
        FocusDirection.Down -> Offset(rect.centre.x, rect.bottom)
        FocusDirection.Left -> Offset(rect.left, rect.centre.y)
        else -> Offset(rect.right, rect.centre.y)
    }

    /** A line and a triangle on the end of it, the tip at [to]. */
    private fun arrow(canvas: UiCanvas, from: Offset, to: Offset, colour: Colour, dx: Float, dy: Float) {
        val x = to.x - from.x
        val y = to.y - from.y
        val length = sqrt(x * x + y * y)
        if (length <= 0f) return
        val ux = x / length
        val uy = y / length
        val head = min(HeadLength, length)
        val baseX = to.x - ux * head + dx
        val baseY = to.y - uy * head + dy
        canvas.line(Offset(from.x + dx, from.y + dy), Offset(baseX, baseY), ArrowWidth, colour)
        canvas.polygon(
            floatArrayOf(
                to.x + dx, to.y + dy,
                baseX - uy * HeadHalfWidth, baseY + ux * HeadHalfWidth,
                baseX + uy * HeadHalfWidth, baseY - ux * HeadHalfWidth,
            ),
            colour,
        )
    }

    private fun fill(canvas: UiCanvas, rect: Rect, colour: Colour, dx: Float, dy: Float) {
        if (rect.isEmpty) return
        canvas.rect(Rect(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy), colour)
    }

    private fun outline(canvas: UiCanvas, rect: Rect, colour: Colour, width: Float, dx: Float, dy: Float) {
        if (rect.isEmpty) return
        canvas.border(Rect(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy), colour, width)
    }

    private companion object {
        val Directions = listOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right)
        val Unbounded = Rect(-Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)

        /** The side of one square a shaped hit area is asked in, in screen units. */
        const val HitCell = 4f
        const val ArrowWidth = 2f
        const val HeadLength = 9f
        const val HeadHalfWidth = 5f
    }
}

/**
 * Whether a press can land here: what the pointer router lets take one — a click, a drag, a pointer
 * handler — or focus taken by a press. A node that is only focusable or only has a hover lets the
 * press through to what is under it, so it is not tinted.
 */
private val UiNode.takesPresses: Boolean
    get() = resolved.click != null || resolved.drag != null || resolved.handlers.isNotEmpty() ||
        resolved.pointerFocus != null
