package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.node.UiNode

/**
 * Told when layout gives a node a different size from the one it had.
 *
 * Called after the layout pass has finished, never in the middle of it, and only when the size
 * actually changed — so a screen standing still calls nothing. The first pass that reaches the
 * node counts as a change, which is how a handler hears the starting size at all. So does a
 * different handler taking this one's place: it has not been told anything yet.
 *
 * What a thing sized to its surroundings wants: a particle emitter filling a panel, a render
 * target the size of a viewport. A state written here is seen by the next recomposition, so it
 * lands one frame later, the same as in Compose.
 *
 * A handler written inline is a new object every recomposition and so never compares equal —
 * `remember` it, exactly as with a pointer handler.
 */
fun interface SizeChangedHandler {
    fun onSizeChanged(size: Size)
}

/**
 * Told when a node ends up somewhere different on screen.
 *
 * "Somewhere" is [UiNode.boundsInRoot]: where the node is *drawn*, scale folded in. So a node
 * whose own size and place in its parent never change is still reported when a parent moves it,
 * or scales it. Handed the node rather than a rectangle because the thing that wants to know
 * usually wants a different question answered — `boundsInRoot` for a popup under a button,
 * `layoutBoundsInRoot` for the slot, `paintedInRoot` for an arrow that points at the ink.
 *
 * The same rules as [SizeChangedHandler]: after layout, only on a change, the first pass counts.
 * The node is only good for reading during the call — hold on to what you asked it, not to it.
 */
fun interface PlacedHandler {
    fun onPlaced(node: UiNode)
}
