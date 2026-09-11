package composegl.ui.draw

import composegl.ui.effect.ShaderEffect
import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.graphics.UiCanvas
import composegl.ui.layout.Padding
import composegl.ui.modifier.BackgroundElement
import composegl.ui.modifier.BorderElement
import composegl.ui.modifier.DrawBehindElement
import composegl.ui.modifier.DrawInFrontElement
import composegl.ui.modifier.NinePatchElement
import composegl.ui.modifier.SkinBackgroundElement
import composegl.ui.modifier.PaintOp
import composegl.ui.modifier.ResolvedModifier
import composegl.ui.modifier.ShadowElement
import composegl.ui.node.UiNode

/**
 * The other half of a frame: a laid-out tree turned into drawing.
 *
 * Layout said where everything is; this walks the tree in order and says what to draw. Each node
 * paints what its chain put behind it, then its own content, then its children, then anything the
 * chain put in front — which is exactly the order a person reading the modifier chain expects.
 *
 * The pass holds no state of its own beyond the canvas, so drawing the same tree twice draws the
 * same thing, and a test can draw a tree without a GPU anywhere near it.
 */
class DrawPass(private val canvas: UiCanvas) {

    /** Draws [node] and everything under it. [origin] is where its parent's content box starts. */
    fun draw(node: UiNode, origin: Offset = Offset.Zero) = draw(node, origin.x, origin.y)

    /**
     * The same, as two floats.
     *
     * What the walk itself uses. A tree is drawn from its parent's corner, and building an [Offset]
     * to carry two numbers one level down is an object per node per frame for a screen that is
     * standing still.
     */
    fun draw(node: UiNode, originX: Float, originY: Float) {
        val resolved = node.resolved

        // Nothing under a fully transparent node can be seen, so nothing under it is drawn. A
        // fading panel costs a comparison instead of a subtree.
        if (resolved.alpha <= 0f) return

        // Kept on the node and handed back when it has not moved; see RectCache.
        val bounds = node.drawnBounds.of(
            originX + node.x,
            originY + node.y,
            originX + node.x + node.width,
            originY + node.y + node.height,
        )
        val faded = resolved.alpha < 1f
        if (faded) canvas.pushAlpha(resolved.alpha)

        if (resolved.effects.isEmpty()) {
            contents(node, resolved, bounds)
        } else {
            // Reversed, so the first effect in the chain is the innermost picture: written twice,
            // the second one works on the first one's answer, which is how a chain reads.
            through(resolved.effects.asReversed(), 0, bounds) { contents(node, resolved, bounds) }
        }

        if (faded) canvas.popAlpha()
    }

    /** Everything a node draws: what its chain put behind it, itself, its children, what is in front. */
    private fun contents(node: UiNode, resolved: ResolvedModifier, bounds: Rect) {
        // Index loops rather than `forEach`, here and below: the lambda would capture `bounds`,
        // which makes a fresh object for it, per list, per node, every frame.
        val behind = resolved.behind
        for (index in behind.indices) paint(behind[index], bounds)

        // The clip covers this node's content and its children, not its own background — which is
        // the node's own bounds anyway, so clipping it would change nothing.
        val clipped = resolved.clip != null
        if (clipped) canvas.pushClip(bounds)

        // Only when something is actually drawn into it. Most nodes are a box round other boxes
        // and have no content of their own, and the inset rectangle would be made and dropped.
        val content = node.content
        if (content != null) {
            val padding = resolved.padding
            content(
                canvas,
                node.drawnContent.of(
                    bounds.left + padding.left,
                    bounds.top + padding.top,
                    bounds.right - padding.right,
                    bounds.bottom - padding.bottom,
                ),
            )
        }

        val children = node.children
        for (index in children.indices) draw(children[index], bounds.left, bounds.top)

        if (clipped) canvas.popClip()

        val inFront = resolved.inFront
        for (index in inFront.indices) paint(inFront[index], bounds)
    }

    /**
     * Draws [body] through the shaders in [effects], from [index] on.
     *
     * One picture each, from the inside out, so that two effects in a chain are the second one
     * working on the first one's answer rather than both arguing over the same pixels.
     *
     * A canvas with no offscreen drawing hands back nothing and has drawn nothing, so the subtree
     * is drawn again, straight, and the effect is simply not there. That is the bargain: an effect
     * degrades to no effect, never to a missing widget.
     */
    private fun through(effects: List<ShaderEffect>, index: Int, bounds: Rect, body: () -> Unit) {
        if (index == effects.size) {
            body()
            return
        }

        val effect = effects[index]
        // The area the shader gets to write to. A blur or a glow reaches past the widget, and
        // without the bleed the spread would be cut off square at its edge.
        val area = if (effect.bleed > 0f) bounds.inset(-effect.bleed) else bounds
        val picture = canvas.layer(area) { through(effects, index + 1, bounds, body) }
        if (picture == null) {
            through(effects, index + 1, bounds, body)
            return
        }
        canvas.drawLayer(picture, area, effect)
    }

    /**
     * One painting element, inset by the padding that came before it in the chain.
     *
     * That inset is the whole reason a `PaintOp` exists rather than a bare element:
     * `padding(8f).background(blue)` paints inside the padding, `background(blue).padding(8f)`
     * paints across the node, and both are things people write on purpose.
     */
    private fun paint(op: PaintOp, bounds: Rect) {
        val inset = op.inset
        // Kept on the op and handed back when it has not moved; see RectCache.
        val rect = if (inset == Padding.None) {
            bounds
        } else {
            op.painted.of(
                bounds.left + inset.left,
                bounds.top + inset.top,
                bounds.right - inset.right,
                bounds.bottom - inset.bottom,
            )
        }
        when (val element = op.element) {
            is BackgroundElement -> canvas.rect(rect, element.colour, element.corner)
            is BorderElement -> canvas.border(rect, element.colour, element.width, element.corner)
            is ShadowElement -> canvas.shadow(rect, element.colour, element.spread, element.corner)
            is NinePatchElement -> element.patch.drawInto(canvas, rect, element.tint)
            is SkinBackgroundElement -> element.drawable.drawInto(canvas, rect, element.tint)
            is DrawBehindElement -> element.draw(canvas, rect)
            is DrawInFrontElement -> element.draw(canvas, rect)
            else -> Unit
        }
    }
}

/**
 * The last rectangle worked out for a node, handed back when it has not moved.
 *
 * A draw pass runs every frame, and for a screen that is standing still every rectangle in it is
 * the same one as last time. Four float comparisons instead of an allocation, per node, per frame.
 *
 * [Rect] is immutable, so handing the same one back twice is safe however far it travels — a
 * recording canvas that keeps it is keeping a value, not a view onto something that will change.
 */
internal class RectCache {

    private var held: Rect? = null

    fun of(left: Float, top: Float, right: Float, bottom: Float): Rect {
        val held = held
        if (held != null &&
            held.left == left && held.top == top && held.right == right && held.bottom == bottom
        ) {
            return held
        }
        return Rect(left, top, right, bottom).also { this.held = it }
    }
}
