package dev.wildware.composegl.ui.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode

/**
 * The node every debug overlay is: no size, no input, lifted over its siblings, painting [painter].
 *
 * [painter] has to be remembered by the caller on everything it was built from, so a screen
 * recomposing with the same arguments hands the node the same object and the tree hears nothing.
 */
@Composable
internal fun OverlayNode(name: String, modifier: Modifier, painter: OverlayPainter) {
    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode(name) },
        update = {
            set(modifier) { this.modifier = Modifier.zIndex(OnTop).then(it) }
            set(MeasurePolicy.Empty) { this.measurePolicy = it }
            // "Painted nothing", so a parent's painted rectangle is not stretched to reach this node.
            set(NoInk) { this.ink = it }
            set(painter) {
                it.node = this
                this.content = it
            }
        },
    )
}

private const val OnTop = Float.MAX_VALUE

private val NoInk: (Rect) -> Rect? = { null }

/**
 * The drawing of an overlay, handed to its node as content: finds the tree, and walks it.
 *
 * Every overlay walks the tree the draw pass draws, and every overlay leaves out every other
 * overlay — two on one screen have no size, and would otherwise mark each other as dots.
 */
internal abstract class OverlayPainter : DebugOverlayPainter {

    /** The node this draws for. Set when it is handed over, and how it finds the tree. */
    var node: UiNode? = null

    override fun invoke(canvas: UiCanvas, content: Rect) {
        // Drawn into OverdrawOverlay's count: its marks are not what the screen paints.
        if (canvas is OverdrawCanvas) return
        val self = node ?: return
        var root = self
        while (true) root = root.parent ?: break

        // The canvas is in the root's coordinates everywhere except a picture a caller drew a subtree
        // into at some other origin. Where this node's own content box landed says which.
        val box = self.layoutBoundsInRoot
        val dx = content.left - box.left - self.resolved.padding.left
        val dy = content.top - box.top - self.resolved.padding.top - self.baselineTop
        paint(canvas, root, self, dx, dy)
    }

    /** Draws over the tree under [root], moving every root rectangle by [dx], [dy] onto [canvas]. */
    protected abstract fun paint(canvas: UiCanvas, root: UiNode, self: UiNode, dx: Float, dy: Float)

    /** Every node the draw pass would draw, but overlays, parents first. */
    protected fun walk(node: UiNode, self: UiNode, visit: (UiNode) -> Unit) {
        if (node === self || node.content is DebugOverlayPainter || !node.everMeasured) return
        val resolved = node.resolved
        if (resolved.alpha <= 0f || resolved.scale <= 0f) return
        visit(node)
        val children = node.drawOrder
        for (index in children.indices) walk(children[index], self, visit)
    }
}
