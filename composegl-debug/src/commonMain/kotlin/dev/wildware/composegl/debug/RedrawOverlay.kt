package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.debug.DebugOverlay
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.debug.isDebugOverlay
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.node.NeverChanged
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode
import kotlin.math.max

/**
 * Which nodes changed, flashed over the screen as they change.
 *
 * A still screen should cost almost nothing, and when one does not there is otherwise no way to see
 * why. With this on, every node that changed — a new chain, a new content lambda, a child added or
 * taken away, a size or place an animation stepped — gets a border that starts bright on the frame it
 * changed and fades out over [holdMillis]. A menu standing still shows nothing. A label being handed a
 * lambda written inline flashes every time its parent recomposes, and a counter ticking once a second
 * blinks once a second.
 *
 * Compose it last, at the top level of the screen, behind the game's own switch, as a
 * [LayoutOverlay] is:
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     Game()
 *     RedrawOverlay(enabled = debug)
 * }
 * ```
 *
 * The same kind of node a [LayoutOverlay] is: no size, no input, lifted over its siblings, walking
 * its tree while that tree is drawn. It turns change counting on for as long as it is composed and
 * reads [UiNode.changes] and [UiNode.changedAtNanos], and never marks anything changed itself — the
 * overlay must not be the reason the screen redraws. So a fade carries on only while the game keeps
 * drawing; a game that skips drawing an unchanged frame holds the last flash until something changes.
 *
 * Only changes after it was turned on flash. A node recomposed with arguments equal to the last is
 * not redrawn and does not flash either, however often it recomposes: what this shows is what costs
 * a frame. For the numbers, a [FrameBudget] made with `busiest = 5` lists the nodes that changed most.
 *
 * Deliberately unskinnable, like [FrameBudgetOverlay]. Take it off before shipping.
 *
 * @param enabled whether it is there at all. Off composes nothing and counts nothing.
 * @param holdMillis how long a flash takes to fade, in the host's frame time.
 */
@Composable
fun RedrawOverlay(
    enabled: Boolean,
    holdMillis: Int = 500,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    val painter = remember(holdMillis) { RedrawOverlayPainter(holdMillis) }
    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode(RedrawOverlayName) },
        update = {
            set(modifier) { this.modifier = Modifier.zIndex(OnTop).then(it) }
            set(MeasurePolicy.Empty) { this.measurePolicy = it }
            set(NoInk) { this.ink = it }
            set(painter) {
                it.node = this
                this.content = it
            }
        },
    )
    // After the node is in the tree, which is when there is a tree to count on.
    DisposableEffect(painter) {
        val tree = painter.node?.tree
        tree?.watchChanges()
        onDispose { tree?.stopWatchingChanges() }
    }
}

/** What the overlay node is called in a dump. */
internal const val RedrawOverlayName = "redraw overlay"

private const val OnTop = Float.MAX_VALUE

private val NoInk: (Rect) -> Rect? = { null }

/** The flash: red with a little blue, which reads over the layout overlay's blue, yellow and green. */
internal val RedrawColour = Colour.rgb(0xFF2050)

/** How wide a flash's border is. Wider than a layout edge, so the two can be told apart when both are on. */
internal const val RedrawWidth = 2f

/** The drawing, handed to the overlay node as its content. */
internal class RedrawOverlayPainter(private val holdMillis: Int) : DebugOverlay {

    /** The node this draws for. Set when it is handed over, and how it finds the tree. */
    var node: UiNode? = null

    override fun invoke(canvas: UiCanvas, content: Rect) {
        val self = node ?: return
        val tree = self.tree ?: return
        val now = tree.clocks.frameNanos
        val hold = holdMillis * 1_000_000L
        if (hold <= 0L) return

        // The same correction the layout overlay makes, for a picture drawn at some other origin.
        val inner = self.contentBoundsInRoot
        val dx = content.left - inner.left
        val dy = content.top - inner.top

        walk(tree.root, self) { node ->
            val at = node.changedAtNanos
            if (at == NeverChanged) return@walk
            val age = now - at
            if (age < 0L || age >= hold) return@walk
            val colour = RedrawColour.scaleAlpha(1f - age.toFloat() / hold)
            flash(canvas, node.boundsInRoot, colour, dx, dy)
        }
    }

    /**
     * Every node the draw pass would draw, parents first, but the debug overlays: a frame budget's
     * numbers refresh four times a second on purpose and would flash for ever.
     */
    private fun walk(node: UiNode, self: UiNode, visit: (UiNode) -> Unit) {
        if (node === self || isDebugOverlay(node)) return
        if (!node.everMeasured) return
        val resolved = node.resolved
        if (resolved.alpha <= 0f || resolved.scale <= 0f) return
        visit(node)
        val children = node.drawOrder
        for (index in children.indices) walk(children[index], self, visit)
    }

    /** A border, or a filled line for a rectangle too thin to have an inside. */
    private fun flash(canvas: UiCanvas, rect: Rect, colour: Colour, dx: Float, dy: Float) {
        val left = rect.left + dx
        val top = rect.top + dy
        if (rect.width < RedrawWidth * 2 || rect.height < RedrawWidth * 2) {
            canvas.rect(Rect(left, top, left + max(rect.width, 1f), top + max(rect.height, 1f)), colour)
        } else {
            canvas.border(Rect(left, top, rect.right + dx, rect.bottom + dy), colour, RedrawWidth)
        }
    }
}
