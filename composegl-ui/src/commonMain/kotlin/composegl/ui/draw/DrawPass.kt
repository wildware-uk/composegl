package composegl.ui.draw

import composegl.ui.effect.ShaderEffect
import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.graphics.UiCanvas
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
    fun draw(node: UiNode, origin: Offset = Offset.Zero) {
        val resolved = node.resolved

        // Nothing under a fully transparent node can be seen, so nothing under it is drawn. A
        // fading panel costs a comparison instead of a subtree.
        if (resolved.alpha <= 0f) return

        val bounds = Rect.of(origin.x + node.x, origin.y + node.y, node.width, node.height)
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
        resolved.behind.forEach { paint(it, bounds) }

        // The clip covers this node's content and its children, not its own background — which is
        // the node's own bounds anyway, so clipping it would change nothing.
        val clipped = resolved.clip != null
        if (clipped) canvas.pushClip(bounds)

        val content = bounds.inset(
            resolved.padding.left,
            resolved.padding.top,
            resolved.padding.right,
            resolved.padding.bottom,
        )
        node.content?.invoke(canvas, content)
        node.children.forEach { draw(it, bounds.topLeft) }

        if (clipped) canvas.popClip()

        resolved.inFront.forEach { paint(it, bounds) }
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
        val rect = bounds.inset(op.inset.left, op.inset.top, op.inset.right, op.inset.bottom)
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
