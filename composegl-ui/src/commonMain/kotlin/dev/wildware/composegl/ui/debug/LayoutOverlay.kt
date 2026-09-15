package dev.wildware.composegl.ui.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.LinearPolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.node.UiNode
import kotlin.math.max
import kotlin.math.min

/** What a [LayoutOverlay] draws. Any mix of them; [All] is every one. */
enum class Show {
    /** Every node's box, where layout put it: `layoutBoundsInRoot`. Blue. */
    Bounds,

    /**
     * Where a node is really drawn, after a `scale`: `boundsInRoot`. Yellow. Left out where it is the
     * same rectangle as a blue edge already drawn.
     */
    Drawn,

    /**
     * What a node and everything under it painted: `paintedInRoot`. Pink. Left out where it is the
     * same rectangle as a blue or yellow edge already drawn, and for a node that painted nothing.
     */
    Painted,

    /** Each node's padding, shaded inside its box. Green. */
    Padding,

    /** The space a `Row` or `Column` leaves between its children, from its `Arrangement`. Orange. */
    Gaps;

    companion object {
        val All: Set<Show> = entries.toSet()
    }
}

/**
 * The layout of the whole screen, drawn over it.
 *
 * "Why is there a gap here?" and "why is this three pixels off?" are answered by looking, and a
 * node has three rectangles that are otherwise invisible: where layout put it, where a scale draws
 * it, and where its ink really is. This draws all three, in three colours, with each node's padding
 * shaded inside its box and the gaps a row or column leaves between its children shaded between
 * them.
 *
 * Compose it last, at the top level of the screen, behind the game's own switch:
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     Game()
 *     LayoutOverlay(enabled = debug, show = setOf(Show.Bounds, Show.Padding, Show.Gaps))
 * }
 * ```
 *
 * It is a node with no size and no input, lifted over its siblings, that walks the tree it is in
 * while that tree is drawn. So it moves nothing, a click goes straight through it, and it reads
 * every rectangle as it is this frame without being told anything changed. It never tells the tree
 * anything changed either: a still screen with it on stays still, which is the thing a frame budget
 * next to it would otherwise get wrong. Something drawn after the node that holds it — a sibling of
 * an ancestor, later in the source — still goes on top, which is why it belongs at the top.
 *
 * Padding and gaps are laid-out things and are drawn where layout put them. A subtree faded out or
 * scaled to nothing draws nothing and is left out, the same as the draw pass leaves it out.
 *
 * Deliberately unskinnable, like [FrameBudgetOverlay]: its own colours, the same over any game. It
 * costs a few rectangles a node, and a walk down the subtree for each painted rectangle, a frame, while
 * it is on. Take it off before shipping.
 *
 * @param enabled whether it is there at all. Off composes nothing.
 * @param show which of the five things to draw.
 */
@Composable
fun LayoutOverlay(
    enabled: Boolean,
    show: Set<Show> = Show.All,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    // Remembered by value, so a screen recomposing with an equal set hands the node the same painter
    // and the tree hears nothing. A fresh one per recomposition would redraw every frame it recomposed.
    val painter = remember(show) { LayoutOverlayPainter(show) }
    OverlayNode(OverlayName, modifier, painter)
}

/** What the overlay node is called in a dump. */
internal const val OverlayName = "layout overlay"

/** The overlay's colours. Strong, and one colour channel apart, so each reads over the others. */
internal object LayoutOverlayColours {
    val Bounds = Colour.rgb(0x00B4FF)
    val Drawn = Colour.rgb(0xFFE000)
    val Painted = Colour.rgb(0xFF40FF)
    val Padding = Colour.argb(0x5500FF00)
    val Gaps = Colour.argb(0x55FF8800)
}

/**
 * The drawing itself, handed to the overlay node as its content.
 *
 * Shading first, for every node, and outlines after, for every node, so a child's padding never
 * covers its parent's edge. Parents before children in each, in the order the tree is drawn.
 */
internal class LayoutOverlayPainter(private val show: Set<Show>) : OverlayPainter() {

    override fun paint(canvas: UiCanvas, root: UiNode, self: UiNode, dx: Float, dy: Float) {
        val padding = Show.Padding in show
        val gaps = Show.Gaps in show
        if (padding || gaps) {
            walk(root, self) { node ->
                if (padding) shadePadding(canvas, node, dx, dy)
                if (gaps) shadeGaps(canvas, node, dx, dy)
            }
        }
        val bounds = Show.Bounds in show
        val drawn = Show.Drawn in show
        val painted = Show.Painted in show
        if (bounds || drawn || painted) {
            walk(root, self) { node ->
                // Each edge is left out only where the same rectangle already has an edge, so one kind
                // shown on its own still marks every node rather than just the ones that differ.
                val laid = node.layoutBoundsInRoot
                if (bounds) outline(canvas, laid, LayoutOverlayColours.Bounds, dx, dy)
                val where = node.boundsInRoot
                val whereShown = drawn && !(bounds && where == laid)
                if (whereShown) outline(canvas, where, LayoutOverlayColours.Drawn, dx, dy)
                if (painted) {
                    val ink = node.paintedInRoot
                    val alreadyEdged = (bounds && ink == laid) || (whereShown && ink == where)
                    if (ink != null && !alreadyEdged) outline(canvas, ink, LayoutOverlayColours.Painted, dx, dy)
                }
            }
        }
    }

    /** Four bands between the box and its content box: padding, and the room `paddingFrom` added. */
    private fun shadePadding(canvas: UiCanvas, node: UiNode, dx: Float, dy: Float) {
        val padding = node.resolved.padding
        val top = padding.top + node.baselineTop
        val bottom = padding.bottom + node.baselineBottom
        if (padding.left <= 0f && top <= 0f && padding.right <= 0f && bottom <= 0f) return

        val box = node.layoutBoundsInRoot
        // Kept inside the box, so padding bigger than the node shades the node rather than past it.
        val innerTop = min(box.top + top, box.bottom)
        val innerBottom = max(box.bottom - bottom, innerTop)
        val innerLeft = min(box.left + padding.left, box.right)
        val innerRight = max(box.right - padding.right, innerLeft)
        val colour = LayoutOverlayColours.Padding
        fill(canvas, box.left, box.top, box.right, innerTop, colour, dx, dy)
        fill(canvas, box.left, innerBottom, box.right, box.bottom, colour, dx, dy)
        fill(canvas, box.left, innerTop, innerLeft, innerBottom, colour, dx, dy)
        fill(canvas, innerRight, innerTop, box.right, innerBottom, colour, dx, dy)
    }

    /**
     * The space between neighbours in a row or a column, across its content box.
     *
     * Read off where the children ended up rather than off the arrangement, so a `SpaceBetween`, a
     * `spacedBy` and a child pushed along by `offset` all show the gap that is really there.
     */
    private fun shadeGaps(canvas: UiCanvas, node: UiNode, dx: Float, dy: Float) {
        val policy = node.measurePolicy as? LinearPolicy ?: return
        val placed = node.children.filter { it.everMeasured }
        if (placed.size < 2) return

        val horizontal = policy.horizontal
        val ordered = if (horizontal) placed.sortedBy { it.x } else placed.sortedBy { it.y }
        val box = node.layoutBoundsInRoot
        val padding = node.resolved.padding
        val colour = LayoutOverlayColours.Gaps
        for (index in 1 until ordered.size) {
            val before = ordered[index - 1]
            val after = ordered[index]
            if (horizontal) {
                fill(
                    canvas,
                    box.left + before.x + before.width, box.top + padding.top + node.baselineTop,
                    box.left + after.x, box.bottom - padding.bottom - node.baselineBottom,
                    colour, dx, dy,
                )
            } else {
                fill(
                    canvas,
                    box.left + padding.left, box.top + before.y + before.height,
                    box.right - padding.right, box.top + after.y,
                    colour, dx, dy,
                )
            }
        }
    }

    private fun fill(
        canvas: UiCanvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        colour: Colour,
        dx: Float,
        dy: Float,
    ) {
        if (right <= left || bottom <= top) return
        canvas.rect(Rect(left + dx, top + dy, right + dx, bottom + dy), colour)
    }

    /** A one-unit edge, or a one-unit line for a rectangle too thin to have an inside, as debugBounds does. */
    private fun outline(canvas: UiCanvas, rect: Rect, colour: Colour, dx: Float, dy: Float) {
        val left = rect.left + dx
        val top = rect.top + dy
        if (rect.width < 1f || rect.height < 1f) {
            canvas.rect(Rect(left, top, left + max(rect.width, 1f), top + max(rect.height, 1f)), colour)
        } else {
            canvas.border(Rect(left, top, rect.right + dx, rect.bottom + dy), colour, 1f)
        }
    }
}
