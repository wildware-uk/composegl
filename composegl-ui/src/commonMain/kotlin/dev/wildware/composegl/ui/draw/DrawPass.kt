package dev.wildware.composegl.ui.draw

import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Shape
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.graphics.box
import dev.wildware.composegl.ui.graphics.boxBorder
import dev.wildware.composegl.ui.graphics.boxShadow
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.modifier.BackgroundElement
import dev.wildware.composegl.ui.modifier.BorderElement
import dev.wildware.composegl.ui.modifier.DrawBehindElement
import dev.wildware.composegl.ui.modifier.DrawInFrontElement
import dev.wildware.composegl.ui.modifier.NinePatchElement
import dev.wildware.composegl.ui.modifier.SkinBackgroundElement
import dev.wildware.composegl.ui.modifier.PaintOp
import dev.wildware.composegl.ui.modifier.ResolvedModifier
import dev.wildware.composegl.ui.modifier.ShadowElement
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.graphics.BlendMode

/**
 * The other half of a frame: a laid-out tree turned into drawing.
 *
 * Layout said where everything is; this walks the tree in order and says what to draw. Each node
 * paints what its chain put behind it, then its own content, then its children, then anything the
 * chain put in front — which is exactly the order a person reading the modifier chain expects.
 *
 * The pass holds no state of its own beyond the canvas, so drawing the same tree twice draws the
 * same thing, and a test can draw a tree without a GPU anywhere near it. That also means a game
 * whose canvas does not change can make one of these once and keep it, rather than one a frame —
 * see the demos. [canvas] is public so that a caller holding one can check it is still the right
 * one.
 */
class DrawPass(val canvas: UiCanvas) {

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

        // Nor can anything scaled to nothing, and a picture of it would be a picture of no pixels.
        // Said true rather than refused, so that a node shrinking to zero stops being clickable on
        // the way down rather than going back to full size.
        if (resolved.scale <= 0f) {
            node.scaleApplied = true
            return
        }

        // Kept on the node and handed back when it has not moved; see RectCache.
        val bounds = node.drawnBounds.of(
            originX + node.x,
            originY + node.y,
            originX + node.x + node.width,
            originY + node.y + node.height,
        )
        val faded = resolved.alpha < 1f
        if (faded) canvas.pushAlpha(resolved.alpha)

        // Inside the fade rather than outside it: the opacity says how much of this node reaches
        // the picture, and the blend function says how what reaches it is combined with what is
        // already there. Asked of the canvas first, because a backend is allowed not to have one
        // and drawing the ordinary way is the right answer when it does not.
        val blended = resolved.blend != BlendMode.SourceOver && canvas.supports(resolved.blend)
        if (blended) canvas.pushBlend(resolved.blend)

        // Where a scale grows or shrinks from, in the coordinates the node is drawn in. Alignment
        // with a child of no width is the anchor itself: the left edge, the middle, or the right.
        val scale = resolved.scale
        val anchorX = if (scale == 1f) 0f else bounds.left + resolved.scaleOrigin.xIn(node.width, 0f)
        val anchorY = if (scale == 1f) 0f else bounds.top + resolved.scaleOrigin.yIn(node.height, 0f)

        // A turn is the outermost thing a node does, and the only one that is not a rectangle, so
        // it is the one operation that takes a picture of its own rather than sharing one.
        if (resolved.rotation == 0f) {
            upright(node, resolved, bounds, scale, anchorX, anchorY)
        } else {
            turned(node, resolved, bounds, scale, anchorX, anchorY)
        }

        if (blended) canvas.popBlend()
        if (faded) canvas.popAlpha()
    }

    /**
     * Everything this node draws, the right way up: its mirror, its scale and its effects, sharing
     * one picture where they can.
     */
    private fun upright(
        node: UiNode,
        resolved: ResolvedModifier,
        bounds: Rect,
        scale: Float,
        anchorX: Float,
        anchorY: Float,
    ) {
        // Asked of the canvas before any picture is taken for it: one that cannot flip a picture
        // would be handed a capture it then puts down the right way round, which is a picture for
        // nothing. Said on the node either way, so hit testing mirrors exactly when drawing does.
        val wantsMirror = resolved.mirrorX || resolved.mirrorY
        val mirrored = wantsMirror && canvas.mirrorsLayers
        if (wantsMirror && !mirrored) node.mirrorApplied = false

        if (resolved.effects.isEmpty()) {
            if (scale == 1f && !mirrored) {
                contents(node, resolved, bounds)
            } else {
                // A mirror and a scale are one composite: the same picture, put down somewhere else
                // and read from the other side.
                val applied = scaled(node, bounds, bounds.scaledAbout(anchorX, anchorY, scale), mirrored)
                if (scale != 1f) node.scaleApplied = applied
                if (mirrored) node.mirrorApplied = applied
            }
        } else if (mirrored) {
            // An effect's composite takes a shader rather than a mirror, so the mirror is the
            // innermost picture and the shaders work on what it made — a glow round a flipped
            // sprite, which is the answer a chain written either way round would want.
            val applied = through(resolved.effects.asReversed(), 0, bounds, scale, anchorX, anchorY) {
                node.mirrorApplied = scaled(node, bounds, bounds, mirrored = true)
            }
            if (scale != 1f) node.scaleApplied = applied
        } else {
            // Reversed, so the first effect in the chain is the innermost picture: written twice,
            // the second one works on the first one's answer, which is how a chain reads.
            //
            // A scale on the same node rides the outermost of those pictures rather than taking
            // one of its own: it is a different destination for a composite that was happening
            // anyway, so an effect and a scale together cost one capture, not two.
            val applied =
                through(resolved.effects.asReversed(), 0, bounds, scale, anchorX, anchorY) {
                    contents(node, resolved, bounds)
                }
            if (scale != 1f) node.scaleApplied = applied
        }
    }

    /**
     * The same, put down turned.
     *
     * Whatever the node would have drawn the right way up is drawn into one picture and that
     * picture is composited at an angle, which is why a rotation composes with a scale and with a
     * chain of effects without knowing anything about either: it turns their answer.
     *
     * It is the one operation here that does not share a picture. A scale is a different
     * destination rectangle for a composite that was happening anyway; a turn is not a rectangle at
     * all, so there is nothing to fold it into. A node that scales *and* turns therefore costs two
     * pictures, and one that only turns costs one.
     *
     * The area captured is the node's drawn rectangle grown by the widest bleed in its chain, so a
     * glow reaching past the node turns with it instead of being cut off square at the edge.
     *
     * Hit testing does not follow this, deliberately and permanently: see
     * [dev.wildware.composegl.ui.modifier.rotate]. Nothing here writes `scaleApplied`, so a turned
     * node reports the rectangle it would have had, which is what the pointer router will hit.
     */
    private fun turned(
        node: UiNode,
        resolved: ResolvedModifier,
        bounds: Rect,
        scale: Float,
        anchorX: Float,
        anchorY: Float,
    ) {
        val drawn = bounds.scaledAbout(anchorX, anchorY, scale)
        var bleed = 0f
        val effects = resolved.effects
        for (index in effects.indices) bleed = maxOf(bleed, effects[index].bleed)
        val area =
            if (bleed > 0f) bounds.inset(-bleed).scaledAbout(anchorX, anchorY, scale) else drawn

        val picture = canvas.layer(area) { upright(node, resolved, bounds, scale, anchorX, anchorY) }
        if (picture == null) {
            // The same bargain a scale and an effect make: drawn plainly, the right size and the
            // right way up, rather than not drawn at all.
            upright(node, resolved, bounds, scale, anchorX, anchorY)
            return
        }

        // The pivot is where the node says it turns, expressed against the captured area - which is
        // a different rectangle whenever the chain has a bleed in it, so the fraction cannot simply
        // be the alignment's own.
        val origin = resolved.rotationOrigin
        canvas.drawLayer(
            picture,
            area,
            resolved.rotation,
            pivotIn(area.left, area.width, drawn.left + drawn.width * origin.xIn(1f, 0f)),
            pivotIn(area.top, area.height, drawn.top + drawn.height * origin.yIn(1f, 0f)),
        )
    }

    /** Where [point] falls across a span, as the fraction a canvas takes a pivot as. */
    private fun pivotIn(start: Float, span: Float, point: Float): Float =
        if (span <= 0f) 0.5f else (point - start) / span

    /**
     * Draws [node]'s contents into a picture and puts that picture down somewhere else.
     *
     * The whole of how a scale works. The subtree is captured at the size it was laid out, so
     * nothing inside it knows the scale is happening — no arithmetic to thread through the walk,
     * no text asked for a font size nobody registered, and every rectangle underneath stays where
     * it was, so the caches keep hitting while the factor animates.
     *
     * The picture is the node's own rectangle and the composite is [destination], which is why a
     * capture is a clip in one direction and no clip at all in the other: a child overflowing the
     * node is chopped, and the node itself draws right across [destination] however big that is,
     * whatever `Modifier.clip` says. The clip this node's chain asks for is pushed inside the
     * capture, by [contents], because that is the picture it is a clip on.
     *
     * Costs two objects a frame for as long as the factor is not one: [destination], which is a
     * fresh [Rect] rather than one of this file's cached ones, and the closure below. That is the
     * same pair the effect path has always made, and a node whose factor is not one is a node
     * being animated, so it is not the standing-still case the caches exist for.
     *
     * Returns whether the scale actually happened. A canvas with no offscreen drawing — or a
     * subtree too big for one picture — hands back nothing and has drawn nothing, so the subtree
     * is drawn straight, at its ordinary size. That is the bargain a layer already makes, and
     * saying so out loud is what lets hit testing degrade along with it.
     */
    private fun scaled(node: UiNode, bounds: Rect, destination: Rect, mirrored: Boolean): Boolean {
        val resolved = node.resolved
        val picture = canvas.layer(bounds) { contents(node, resolved, bounds) }
        if (picture == null) {
            contents(node, resolved, bounds)
            return false
        }
        if (mirrored) {
            canvas.drawLayer(picture, destination, resolved.mirrorX, resolved.mirrorY)
        } else {
            canvas.drawLayer(picture, destination)
        }
        return true
    }

    /** Everything a node draws: what its chain put behind it, itself, its children, what is in front. */
    private fun contents(node: UiNode, resolved: ResolvedModifier, bounds: Rect) {
        // A shape a scissor cannot be takes the other road. Asked of the canvas first: one that
        // cannot cut a picture clips to the node's rectangle below, which is everything still
        // there, square, rather than nothing. A node with no area takes no picture: the rectangle
        // clip below is empty and already hides everything, for nothing.
        val shape = resolved.clip?.shape
        if (shape != null && shape !== Shapes.Rectangle && !bounds.isEmpty && canvas.cutsLayers &&
            cut(node, resolved, bounds, shape)
        ) {
            return
        }

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

        // By zIndex, not by source order, and the same list the pointer walks backwards — so what
        // is drawn on top is what gets the click. Nearly always `children` itself.
        val children = node.drawOrder
        for (index in children.indices) draw(children[index], bounds.left, bounds.top)

        if (clipped) canvas.popClip()

        val inFront = resolved.inFront
        for (index in inFront.indices) paint(inFront[index], bounds)
    }

    /**
     * [contents], with everything the clip covers drawn into a picture and put down through
     * [shape].
     *
     * Chain order is kept: what the chain painted before the clip is painted plainly first, what
     * it painted after goes into the picture with the content and the children, and a
     * `drawInFront` from before the clip lands on top of the cut picture. So a ring round a round
     * portrait can be square or round, depending on which side of the clip it was written.
     *
     * Returns false, having drawn nothing, when the canvas would not make the picture — a node
     * bigger than a layer can be — so the caller clips to the rectangle instead. The outline is a
     * fresh array a frame, the same kind of cost a scale or an effect pays for the frames it is on.
     */
    private fun cut(node: UiNode, resolved: ResolvedModifier, bounds: Rect, shape: Shape): Boolean {
        val picture = canvas.layer(bounds) { insideClip(node, resolved, bounds) } ?: return false

        val behind = resolved.behind
        for (index in 0 until resolved.clipBehind) paint(behind[index], bounds)

        val outline = shape.outline(bounds.width, bounds.height)
        for (at in outline.indices) outline[at] += if (at % 2 == 0) bounds.left else bounds.top
        canvas.cutLayer(picture, bounds, outline)

        val inFront = resolved.inFront
        for (index in 0 until resolved.clipInFront) paint(inFront[index], bounds)
        return true
    }

    /** What a shaped clip covers: the later half of the chain's painting, the content, the children. */
    private fun insideClip(node: UiNode, resolved: ResolvedModifier, bounds: Rect) {
        val behind = resolved.behind
        for (index in resolved.clipBehind until behind.size) paint(behind[index], bounds)

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

        val inFront = resolved.inFront
        for (index in resolved.clipInFront until inFront.size) paint(inFront[index], bounds)
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
    private fun through(
        effects: List<ShaderEffect>,
        index: Int,
        bounds: Rect,
        scale: Float,
        anchorX: Float,
        anchorY: Float,
        body: () -> Unit,
    ): Boolean {
        if (index == effects.size) {
            body()
            return true
        }

        val effect = effects[index]
        // The area the shader gets to write to. A blur or a glow reaches past the widget, and
        // without the bleed the spread would be cut off square at its edge.
        val area = if (effect.bleed > 0f) bounds.inset(-effect.bleed) else bounds
        // Only this outermost picture is scaled, and the whole bled area with it, so a glow grows
        // with the thing that is glowing instead of staying its own size around it.
        val picture = canvas.layer(area) { through(effects, index + 1, bounds, 1f, 0f, 0f, body) }
        if (picture == null) {
            through(effects, index + 1, bounds, 1f, 0f, 0f, body)
            return false
        }
        canvas.drawLayer(picture, area.scaledAbout(anchorX, anchorY, scale), effect)
        return true
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
            // Through the helpers rather than the canvas's own calls: four equal corners go to
            // the single-radius call, so a canvas that wraps another still sees every box.
            is BackgroundElement -> canvas.box(rect, element.colour, element.corners)
            is BorderElement -> canvas.boxBorder(rect, element.colour, element.width, element.corners)
            is ShadowElement -> canvas.boxShadow(rect, element.colour, element.spread, element.corners)
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
