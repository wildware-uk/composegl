package dev.wildware.composegl.ui.node

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.modifier.Modifier

/**
 * A camera over a node's children: they are laid out once, in world units, and this says where on
 * the node they are seen and how big.
 *
 * What a `PanZoomCanvas` puts on its node. Four readers ask it the same questions — the draw pass,
 * the pointer router walking down, [UiNode.boundsInRoot] walking up, and a focus reveal through
 * [UiNode.toLocal] — and all four go through the functions below rather than doing the arithmetic
 * themselves, so a child is clicked exactly where it is drawn.
 *
 * World (0, 0) is the corner of the node's content box, inside its padding. A point of the world is
 * seen at `world × zoom + pan` from there.
 */
internal interface ContentCamera {

    /** How much the world is grown by. Above zero. */
    val zoom: Float

    /**
     * Where world (0, 0) is seen when the world is grown by [atZoom], from the corner of the content.
     *
     * A zoom rather than a plain number because a canvas that cannot transform draws the world at
     * its own size: asked for the pan at a zoom of one, the camera answers with the pan that keeps
     * the same world point in the middle of the view, so such a canvas shows the middle of what it
     * was looking at rather than somewhere off the edge.
     */
    fun panX(atZoom: Float): Float

    fun panY(atZoom: Float): Float

    /** The zoom glyphs are made for, which may lag behind [zoom] while a gesture is under way. */
    val textZoom: Float

    /** Told which node it is on each time that node is laid out or drawn, so a move can redraw it. */
    fun attach(node: UiNode)

    /** Draws under the children, in world coordinates, with [visible] the part of the world seen. */
    fun drawBackground(canvas: UiCanvas, visible: Rect)
}

/** Puts [camera] on a node. See [ContentCamera]. */
internal class CameraElement(val camera: ContentCamera) : Modifier.Element

/** How much this node's camera grows the world as it is actually drawn: one when the canvas could not. */
internal fun UiNode.appliedZoom(camera: ContentCamera): Float = if (cameraApplied) camera.zoom else 1f

/** How much [child] of this node is grown by the camera: the zoom, or one for a child that keeps its size. */
internal fun UiNode.childScale(child: UiNode): Float {
    val camera = resolved.camera ?: return 1f
    return if (child.resolved.worldPosition?.scaleWithZoom == false) 1f else appliedZoom(camera)
}

/**
 * Where [child]'s own (0, 0) lands on this node, less the child's own position times [childScale]:
 * a point `p` of this node's layout coordinates is seen at `p × childScale + childOffsetX`.
 *
 * For a child grown by the zoom that is the plain camera, with the padding kept where it is. For one
 * that keeps its size, the world point it is pinned to follows the camera and the child hangs off it
 * at its own size.
 */
internal fun UiNode.childOffsetX(child: UiNode): Float {
    val camera = resolved.camera ?: return 0f
    val zoom = appliedZoom(camera)
    val scale = childScale(child)
    val pin = child.resolved.worldPosition?.x ?: 0f
    return resolved.padding.left * (1f - scale) + camera.panX(zoom) + pin * (zoom - scale)
}

internal fun UiNode.childOffsetY(child: UiNode): Float {
    val camera = resolved.camera ?: return 0f
    val zoom = appliedZoom(camera)
    val scale = childScale(child)
    val pin = child.resolved.worldPosition?.y ?: 0f
    return resolved.padding.top * (1f - scale) + camera.panY(zoom) + pin * (zoom - scale)
}

/** The part of the world this node shows, in world units. */
internal fun UiNode.visibleWorld(camera: ContentCamera): Rect {
    val zoom = appliedZoom(camera)
    val padding = resolved.padding
    val width = (this.width - padding.horizontal).coerceAtLeast(0f)
    val height = (this.height - padding.vertical).coerceAtLeast(0f)
    val panX = camera.panX(zoom)
    val panY = camera.panY(zoom)
    return Rect(-panX / zoom, -panY / zoom, (width - panX) / zoom, (height - panY) / zoom)
}

/**
 * Whether any of [child] is inside this node's view. A child that is not is neither drawn nor
 * hit-tested: a board of a thousand tiles draws the few dozen on the screen.
 *
 * In bare floats, because the pointer asks it of every child on every mouse move.
 */
internal fun UiNode.showsChild(child: UiNode): Boolean {
    if (resolved.camera == null) return true
    val scale = childScale(child)
    val offsetX = childOffsetX(child)
    val offsetY = childOffsetY(child)
    val left = child.x * scale + offsetX
    val top = child.y * scale + offsetY
    val right = left + child.width * scale
    val bottom = top + child.height * scale
    val padding = resolved.padding
    return right > padding.left && left < width - padding.right && bottom > padding.top && top < height - padding.bottom
}
