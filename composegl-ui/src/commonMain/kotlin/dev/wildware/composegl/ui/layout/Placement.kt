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

    /**
     * Whether a layout may read, while it measures, something this handler keeps: a size held in a
     * plain field that a measure policy of your own works from. True unless the handler says
     * otherwise, and then the layout pass after this handler is told anything measures every node,
     * as every pass once did, so such a layout catches up. A handler that only writes state read
     * while composing, or keeps what it hears for input or drawing, can answer false, and the passes
     * after it measure only what changed:
     *
     * ```kotlin
     * val handler = remember {
     *     object : SizeChangedHandler {
     *         override val readByLayout get() = false
     *         override fun onSizeChanged(size: Size) { emitter.resize(size) }
     *     }
     * }
     * ```
     */
    val readByLayout: Boolean get() = true
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
 * What the node says during the call is this frame's. Keep what you asked it, or keep the node and
 * ask it again later: a measure policy that reads the node's rectangle is noticed reading it, and
 * is measured on every pass from then on.
 */
fun interface PlacedHandler {
    fun onPlaced(node: UiNode)

    /**
     * Whether a layout may read, while it measures, something this handler keeps: a rectangle held
     * in a plain field that a measure policy of your own lines something up with. True unless the
     * handler says otherwise, and then the layout pass after this handler is told anything measures
     * every node, as every pass once did, so such a layout catches up. A handler that only writes
     * state read while composing, or keeps what it hears for input or drawing, can answer false,
     * and the passes after it measure only what changed. See [SizeChangedHandler.readByLayout].
     */
    val readByLayout: Boolean get() = true
}

/**
 * A [PlacedHandler] whose [readByLayout][PlacedHandler.readByLayout] is false: for the library's own
 * handlers that keep what they hear for focus, input or drawing, and that sit on rows of lists, so
 * that a list scrolling does not make every pass measure the whole screen.
 */
internal inline fun quietPlaced(crossinline block: (UiNode) -> Unit): PlacedHandler = object : PlacedHandler {
    override val readByLayout: Boolean get() = false
    override fun onPlaced(node: UiNode) = block(node)
}

/** The same for a [SizeChangedHandler]. */
internal inline fun quietSized(crossinline block: (Size) -> Unit): SizeChangedHandler = object : SizeChangedHandler {
    override val readByLayout: Boolean get() = false
    override fun onSizeChanged(size: Size) = block(size)
}
