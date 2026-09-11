package composegl.demo

import composegl.ui.geometry.Offset
import composegl.ui.input.GamepadEvent
import composegl.ui.input.InputSink
import composegl.ui.input.KeyEvent
import composegl.ui.input.PointerEvent
import composegl.ui.input.PointerId
import composegl.ui.input.PointerRouter
import composegl.ui.input.TextEvent
import composegl.ui.node.UiNode

/**
 * The demo's end of the input contract, shared by both backends.
 *
 * Two jobs. The reticle wants to know where the pointer is whatever happens to it, so that is
 * recorded first and unconditionally. Everything else is the toolkit's: [PointerRouter] finds the
 * node under the pointer, keeps hover and press state up to date, and decides what is a click.
 *
 * A game would put its own handling after the router and act on what came back false — the
 * interface gets first refusal, the world gets the rest.
 */
internal class DemoInput(private val state: DemoState, root: UiNode) : InputSink {

    private val router = PointerRouter(root)

    override fun onPointer(event: PointerEvent): Boolean {
        state.pointer = when (event) {
            is PointerEvent.Exit -> null
            else -> event.position
        }
        return router.onPointer(event)
    }

    override fun onKey(event: KeyEvent) = router.onKey(event)

    override fun onText(event: TextEvent) = router.onText(event)

    override fun onGamepad(event: GamepadEvent) = router.onGamepad(event)

    /**
     * Puts the pointer somewhere without a mouse, so a screenshot can show a hover or a press.
     *
     * [where] is `x,y` in design units, optionally followed by `,press`. Only used when the demo
     * is taking a picture of itself; a real run never calls it.
     */
    fun pretendPointerIsAt(where: String) {
        val parts = where.split(',')
        val at = Offset(parts[0].trim().toFloat(), parts[1].trim().toFloat())
        onPointer(PointerEvent.Move(PointerId.Mouse, at))
        if (parts.size > 2 && parts[2].trim() == "press") {
            onPointer(PointerEvent.Press(PointerId.Mouse, at))
        }
    }

    /** The window is no longer in front, so nothing is left holding a capture or a highlight. */
    fun windowLostFocus() {
        state.pointer = null
        router.cancelAll()
    }
}
