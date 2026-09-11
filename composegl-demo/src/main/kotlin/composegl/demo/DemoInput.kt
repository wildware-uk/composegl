package composegl.demo

import composegl.ui.focus.FocusManager
import composegl.ui.geometry.Offset
import composegl.ui.input.GamepadButton
import composegl.ui.input.GamepadEvent
import composegl.ui.input.GamepadId
import composegl.ui.input.GamepadNavigator
import composegl.ui.input.InputSink
import composegl.ui.input.KeyEvent
import composegl.ui.input.PointerEvent
import composegl.ui.input.PointerId
import composegl.ui.input.PointerRouter
import composegl.ui.input.SourceAware
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

    /**
     * Focus, which on a console is the cursor.
     *
     * A pad drives it, and so does clicking. The keyboard is the one thing still missing, and it
     * is the next milestone.
     */
    val focus = FocusManager(root)

    private val router = PointerRouter(root, focus)

    /** The pad's end of the same thing: a direction moves focus, South presses what it is on. */
    private val pad = GamepadNavigator(focus, onBack = { state.back() })

    /** The toolkit's two halves behind one contract, so the backend sees a single sink. */
    private val toolkit = object : InputSink {
        override fun onPointer(event: PointerEvent) = router.onPointer(event)
        override fun onKey(event: KeyEvent) = false
        override fun onText(event: TextEvent) = false
        override fun onGamepad(event: GamepadEvent) = pad.onGamepad(event)
    }

    /**
     * Everything goes through here first, so the interface always knows what the player is using.
     *
     * It wraps rather than intercepts: the answers below are the toolkit's, unchanged.
     */
    private val tracked = SourceAware(state.source, toolkit)

    override fun onPointer(event: PointerEvent): Boolean {
        state.pointer = when (event) {
            is PointerEvent.Exit -> null
            else -> event.position
        }
        return tracked.onPointer(event)
    }

    override fun onKey(event: KeyEvent) = tracked.onKey(event)

    override fun onText(event: TextEvent) = tracked.onText(event)

    override fun onGamepad(event: GamepadEvent) = tracked.onGamepad(event)

    /**
     * Called once a frame, after layout.
     *
     * Refreshing first is what keeps focus off a node that has gone; the pad's repeat runs after,
     * so a held direction steps onto something that is still there.
     *
     * @param timeMillis the game's own monotonic clock, which is the pad repeat's only time source.
     */
    fun frame(timeMillis: Long) {
        focus.refresh()
        pad.frame(timeMillis)
    }

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

    /**
     * Plays a pad script, so a screenshot can show what a pad does without a pad being plugged in.
     *
     * [script] is a comma-separated list of `up`, `down`, `left`, `right`, `press` and `back`. Each
     * direction is a d-pad tap, which is one step of focus. Only used when the demo is taking a
     * picture of itself; a real run never calls it.
     */
    fun pretendPadDid(script: String) {
        script.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { step ->
            when (step) {
                "up" -> tap(GamepadButton.DpadUp)
                "down" -> tap(GamepadButton.DpadDown)
                "left" -> tap(GamepadButton.DpadLeft)
                "right" -> tap(GamepadButton.DpadRight)
                "press" -> tap(GamepadButton.South)
                "hold" -> onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))
                "back" -> tap(GamepadButton.Back)
                else -> error("a pad script step is up, down, left, right, press, hold or back, not '$step'")
            }
        }
    }

    private fun tap(button: GamepadButton) {
        onGamepad(GamepadEvent.ButtonDown(GamepadId.First, button))
        onGamepad(GamepadEvent.ButtonUp(GamepadId.First, button))
    }

    /** The window is no longer in front, so nothing is left holding a capture or a highlight. */
    fun windowLostFocus() {
        state.pointer = null
        router.cancelAll()
    }
}
