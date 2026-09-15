package dev.wildware.composegl.ui.debug

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.node.UiNode

/**
 * A node's content that draws debugging marks over the screen rather than being part of it.
 *
 * Handed to a node as its [content][UiNode.content], it tells the toolkit's own measuring to leave
 * it out: [measureOverdraw] does not run it, so an overlay's outlines are never counted as the
 * screen's paint, and a [FrameBudget] does not list the node among the busiest. The overlays in
 * `composegl-debug` are all one, and each leaves the others out of the tree it walks by it. A
 * game's own debug overlay implements it to be treated the same way.
 */
interface DebugOverlay : (UiCanvas, Rect) -> Unit

/**
 * Whether [node] is debug tooling whose own changes and paint are not the screen's: its content is a
 * [DebugOverlay], or it is the box a frame budget overlay puts its numbers in.
 */
fun isDebugOverlay(node: UiNode): Boolean =
    node.content is DebugOverlay || node.name == FrameBudget.OverlayName
