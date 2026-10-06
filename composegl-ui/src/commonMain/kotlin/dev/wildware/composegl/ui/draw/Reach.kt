package dev.wildware.composegl.ui.draw

import dev.wildware.composegl.ui.debug.DebugBounds
import dev.wildware.composegl.ui.debug.DebugOverlay
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.modifier.DebugBoundsElement
import dev.wildware.composegl.ui.modifier.MouldedElement
import dev.wildware.composegl.ui.modifier.OutsideBorderElement
import dev.wildware.composegl.ui.modifier.PaintOp
import dev.wildware.composegl.ui.modifier.ResolvedModifier
import dev.wildware.composegl.ui.modifier.ShadowElement
import dev.wildware.composegl.ui.node.UiNode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

private const val DegreesToRadians = (PI / 180.0).toFloat()

/** How far past its box a node's own content is taken to reach at the least, in design units. */
private const val ContentSlack = 8f

/**
 * Works out how far a node's drawing reaches, so the draw pass can skip one that cannot be seen.
 *
 * A node's reach is the rectangle, in its own coordinates — its top-left corner at (0, 0) — that
 * everything it and its subtree draw stays inside, once its own scale, turn and slant are applied:
 *
 * - its own box, always;
 * - grown by what its chain paints past the box: a [shadow][dev.wildware.composegl.ui.modifier.shadow]'s
 *   spread, an [outside border][dev.wildware.composegl.ui.modifier.borderOutside]'s width, a
 *   [moulded][dev.wildware.composegl.ui.modifier.moulded] box's shadow and outline, a debug box's
 *   label, an [effect][dev.wildware.composegl.ui.modifier.effect]'s bleed;
 * - grown by its own content, with room to spare for what a widget draws a little past its box;
 * - united with each child's reach where the child sits, unless the node clips, which keeps all of
 *   that inside its box;
 * - then united with the same rectangle scaled, and with the box round it turned and slanted, so
 *   it holds whichever way the canvas ends up drawing the node: through a picture, a transform, or
 *   plainly because the picture was refused.
 *
 * What a `drawBehind` or a `drawInFront` paints is taken to stay inside the rectangle it is
 * handed, and a node's own content within the room [content] leaves it. A node that says otherwise
 * with
 * [drawsOutside][dev.wildware.composegl.ui.modifier.drawsOutside] reaches anywhere, and so does
 * every node above it that does not clip. So does a [DebugOverlay], a node of no size that draws
 * over the whole screen; a node tilted in depth, whose picture a camera
 * can throw anywhere, and a pan-and-zoom canvas that does not clip, whose children are where its
 * camera puts them rather than where layout did.
 *
 * Worked out by layout, at the end of each node's measure, when its children are placed and its
 * size is settled: once per layout, and kept on the still frames that skip one. Gathered straight
 * into the node's own four fields. The one thing it makes is the rectangle a node's `ink` is asked
 * about — text, mostly — and a debug label's words, and only when that node is laid out again.
 */
internal object Reach {

    /** Works out [node]'s reach and writes it on the node. Its children's must be on them already. */
    fun settle(node: UiNode) {
        val resolved = node.resolved
        // A debug overlay is a node of no size that draws over the whole screen.
        if (resolved.drawsOutside || node.content is DebugOverlay || tilts(resolved)) {
            node.reachAnywhere()
            return
        }
        val width = node.rawWidth
        val height = node.rawHeight
        node.reachLeft = 0f
        node.reachTop = 0f
        node.reachRight = width
        node.reachBottom = height

        paints(node, resolved.behind, width, height)
        paints(node, resolved.inFront, width, height)
        val effects = resolved.effects
        for (index in effects.indices) {
            val bleed = effects[index].bleed
            if (bleed > 0f) node.grow(-bleed, -bleed, width + bleed, height + bleed)
        }

        // A clip holds the content and the children inside the box, whatever they reach; see
        // DrawPass.contents. One part-way through `animateContentSize` clips too, but only while it
        // moves, and a reach has to hold on the frames it does not.
        if (resolved.clip == null) {
            content(node, resolved, width, height)
            if (!children(node)) {
                node.reachAnywhere()
                return
            }
        }

        transformed(node, resolved, width, height)
        if (node.reachLeft.isNaN() || node.reachTop.isNaN() || node.reachRight.isNaN() || node.reachBottom.isNaN()) {
            node.reachAnywhere()
        }
    }

    /** Whether this node turns in depth, which takes its picture anywhere a camera puts it. */
    private fun tilts(resolved: ResolvedModifier): Boolean =
        resolved.rotation3dX != 0f || resolved.rotation3dY != 0f || resolved.rotation3dZ != 0f

    /**
     * Each op's rectangle — the box less the op's inset, which a negative padding before it makes
     * bigger than the box — grown by how far that op paints past it.
     */
    private fun paints(node: UiNode, ops: List<PaintOp>, width: Float, height: Float) {
        for (index in ops.indices) {
            val op = ops[index]
            val inset = op.inset
            val left = inset.left
            val top = inset.top
            val right = width - inset.right
            val bottom = height - inset.bottom
            when (val element = op.element) {
                is ShadowElement -> node.grow(left - element.spread, top - element.spread, right + element.spread, bottom + element.spread)
                is OutsideBorderElement -> node.grow(left - element.width, top - element.width, right + element.width, bottom + element.width)
                is MouldedElement -> {
                    // Its shadow and its outline are both a share of the box's shorter side.
                    val across = minOf(right - left, bottom - top).coerceAtLeast(0f)
                    val outline = if (element.outline != null) element.outlineWidth else 0f
                    val past = across * maxOf(element.shadow, outline)
                    node.grow(left - past, top - past, right + past, bottom + past)
                }
                is DebugBoundsElement -> {
                    // A box with no width or height is drawn as a line one unit thick, and the label
                    // is a chip in the top-left corner that can be wider or taller than the box.
                    node.grow(left, top, maxOf(right, left + 1f), maxOf(bottom, top + 1f))
                    if (element.label) {
                        node.grow(left, top, left + DebugBounds.chipWidth(right - left, bottom - top), top + DebugBounds.ChipHeight)
                    }
                }
                else -> node.grow(left, top, right, bottom)
            }
        }
    }

    /**
     * What the node's own content draws: its content box, or where its [UiNode.ink] says the ink
     * really is when that is somewhere else — a line of text wider than the box it was squeezed
     * into, a picture drawn at its own size — grown by [ContentSlack] and a quarter of the box's
     * shorter side. That is room for what a widget draws a little past its box and layout cannot
     * see: a glyph leaning out of its line, an outline round the words, a marker ring on a picker's
     * edge. A content that draws further than that says so with `drawsOutside`.
     */
    private fun content(node: UiNode, resolved: ResolvedModifier, width: Float, height: Float) {
        if (node.content == null) return
        val padding = resolved.padding
        var left = padding.left
        var top = padding.top + node.baselineTop
        var right = width - padding.right
        var bottom = height - padding.bottom - node.baselineBottom
        val ink = node.ink
        if (ink != null) {
            // Null is the widget saying it drew nothing this time; see UiNode.paintedInRoot.
            val inked = ink(Rect(left, top, right, bottom)) ?: return
            left = minOf(left, inked.left)
            top = minOf(top, inked.top)
            right = maxOf(right, inked.right)
            bottom = maxOf(bottom, inked.bottom)
        }
        val slack = ContentSlack + minOf(abs(right - left), abs(bottom - top)) / 4f
        node.grow(left - slack, top - slack, right + slack, bottom + slack)
    }

    /**
     * Grows the reach by every child's, where the child sits. False when one of them reaches
     * anywhere, or the children are where a camera puts them rather than where layout did.
     */
    private fun children(node: UiNode): Boolean {
        val children = node.children
        if (children.isEmpty()) return true
        if (node.resolved.camera != null) return false
        for (index in children.indices) {
            val child = children[index]
            if (!child.hasReach) return false
            val x = child.rawX
            val y = child.rawY
            node.grow(x + child.reachLeft, y + child.reachTop, x + child.reachRight, y + child.reachBottom)
        }
        return true
    }

    /**
     * The reach so far, united with itself scaled, and with that turned and slanted: every way
     * [DrawPass] can put the node down. A slant the canvas cannot draw is drawn as a plain turn, or
     * upright, so both are in it.
     */
    private fun transformed(node: UiNode, resolved: ResolvedModifier, width: Float, height: Float) {
        val scale = resolved.scale
        val left = node.reachLeft
        val top = node.reachTop
        val right = node.reachRight
        val bottom = node.reachBottom
        var scaledLeft = left
        var scaledTop = top
        var scaledRight = right
        var scaledBottom = bottom
        // The node's own box after the scale: what its turn and slant pivot on.
        var drawnLeft = 0f
        var drawnTop = 0f
        var drawnRight = width
        var drawnBottom = height
        // Nothing at all is drawn at a scale of nothing or less, so there is nothing to grow.
        if (scale != 1f && scale > 0f) {
            val anchorX = resolved.scaleOrigin.xIn(width, 0f)
            val anchorY = resolved.scaleOrigin.yIn(height, 0f)
            scaledLeft = anchorX + (left - anchorX) * scale
            scaledTop = anchorY + (top - anchorY) * scale
            scaledRight = anchorX + (right - anchorX) * scale
            scaledBottom = anchorY + (bottom - anchorY) * scale
            drawnLeft = anchorX - anchorX * scale
            drawnTop = anchorY - anchorY * scale
            drawnRight = anchorX + (width - anchorX) * scale
            drawnBottom = anchorY + (height - anchorY) * scale
        }

        val slants = resolved.skewX != 0f || resolved.skewY != 0f
        if (resolved.rotation != 0f || slants) {
            turned(node, resolved, scaledLeft, scaledTop, scaledRight, scaledBottom, drawnLeft, drawnTop, drawnRight, drawnBottom, slant = slants)
            if (slants && resolved.rotation != 0f) {
                turned(node, resolved, scaledLeft, scaledTop, scaledRight, scaledBottom, drawnLeft, drawnTop, drawnRight, drawnBottom, slant = false)
            }
        }
        node.grow(scaledLeft, scaledTop, scaledRight, scaledBottom)
    }

    /**
     * Grows the reach by where the corners of a rectangle land once slanted, then turned, about
     * pivots on the drawn box: the same arithmetic as [DrawPass]'s corners, without the array.
     */
    private fun turned(
        node: UiNode,
        resolved: ResolvedModifier,
        areaLeft: Float,
        areaTop: Float,
        areaRight: Float,
        areaBottom: Float,
        drawnLeft: Float,
        drawnTop: Float,
        drawnRight: Float,
        drawnBottom: Float,
        slant: Boolean,
    ) {
        val drawnWidth = drawnRight - drawnLeft
        val drawnHeight = drawnBottom - drawnTop
        val slopeX = if (slant) tan(resolved.skewX * DegreesToRadians) else 0f
        val slopeY = if (slant) tan(resolved.skewY * DegreesToRadians) else 0f
        val skewPivotX = drawnLeft + drawnWidth * resolved.skewOrigin.xIn(1f, 0f)
        val skewPivotY = drawnTop + drawnHeight * resolved.skewOrigin.yIn(1f, 0f)

        val radians = resolved.rotation * DegreesToRadians
        val turnCos = if (resolved.rotation == 0f) 1f else cos(radians)
        val turnSin = if (resolved.rotation == 0f) 0f else sin(radians)
        val turnPivotX = drawnLeft + drawnWidth * resolved.rotationOrigin.xIn(1f, 0f)
        val turnPivotY = drawnTop + drawnHeight * resolved.rotationOrigin.yIn(1f, 0f)

        for (corner in 0 until 4) {
            val x = if (corner == 1 || corner == 2) areaRight else areaLeft
            val y = if (corner >= 2) areaBottom else areaTop
            val slantX = x + slopeX * (y - skewPivotY)
            val slantY = y + slopeY * (x - skewPivotX)
            val acrossX = slantX - turnPivotX
            val acrossY = slantY - turnPivotY
            val landsX = turnPivotX + acrossX * turnCos - acrossY * turnSin
            val landsY = turnPivotY + acrossX * turnSin + acrossY * turnCos
            node.grow(landsX, landsY, landsX, landsY)
        }
    }

    /** Takes a rectangle into the reach. `minOf` and `maxOf` rather than comparisons, so a NaN is kept and seen. */
    private fun UiNode.grow(left: Float, top: Float, right: Float, bottom: Float) {
        reachLeft = minOf(reachLeft, left)
        reachTop = minOf(reachTop, top)
        reachRight = maxOf(reachRight, right)
        reachBottom = maxOf(reachBottom, bottom)
    }
}
