package composegl.demo

import composegl.ui.focus.FocusManager
import composegl.ui.geometry.Offset
import composegl.ui.input.GamepadButton
import composegl.ui.input.GamepadEvent
import composegl.ui.input.GamepadId
import composegl.ui.input.GamepadNavigator
import composegl.ui.input.InputSink
import composegl.ui.input.Modifiers
import composegl.ui.input.Key
import composegl.ui.input.KeyEvent
import composegl.ui.input.KeyEventType
import composegl.ui.input.KeyNavigator
import composegl.ui.input.KeyRouter
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

    /**
     * The pad's end of the same thing: a direction moves focus, South presses what it is on.
     *
     * Back asks the back stack first, because a pad button does not bubble up the tree the way a
     * key does: anything open — the confirmation dialogue here — put itself on that stack, and only
     * if nothing wanted it does the screen itself deal with it.
     */
    private val pad = GamepadNavigator(focus, onBack = { if (!state.backs.back()) state.back() })

    /** Keys, to whatever has focus and then outwards. */
    private val keyRouter = KeyRouter(focus, root)

    /** And the keyboard's own navigation, for the keys nothing wanted. */
    private val keys = KeyNavigator(focus, onBack = { if (!state.backs.back()) state.back() })

    /** The toolkit's three halves behind one contract, so a backend sees a single sink. */
    private val toolkit = object : InputSink {
        override fun onPointer(event: PointerEvent) = router.onPointer(event)

        // The router first, always. A widget that wanted a key has consumed it by the time the
        // navigator is asked, which is the whole of "a field takes the keys it needs".
        override fun onKey(event: KeyEvent) = keyRouter.onKey(event) || keys.onKey(event)

        override fun onText(event: TextEvent) = keyRouter.onText(event)
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

    /**
     * Splits a keyboard script into its steps, for the same reason [pretendPadDid] exists.
     *
     * A step is `tab`, `shift-tab`, `up`, `down`, `left`, `right`, `enter`, `escape`, one of the
     * digits `1` to `9` and `0`, or `text:HELLO` — the other input stream, what the platform says
     * was typed, which is how a scripted run gets something into a text field.
     *
     * They come back as a list rather than being played here because a backend must put a frame
     * between them: pressing a tab heading changes what is on screen, and the keystroke that walks
     * into the new page cannot be sent before the page exists.
     */
    fun keyScript(script: String): List<String> =
        script.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Plays one step of a [keyScript]. */
    fun pretendKeyWas(step: String) {
        when (step) {
            "tab" -> type(Key.Tab)
            "shift-tab" -> type(Key.Tab, Modifiers.Shift)
            "up" -> type(Key.Up)
            "down" -> type(Key.Down)
            "left" -> type(Key.Left)
            "right" -> type(Key.Right)
            "enter" -> type(Key.Enter)
            "escape" -> type(Key.Escape)
            in numbers -> type(numbers.getValue(step))
            else -> {
                val typed = step.removePrefix("text:")
                if (typed == step) {
                    error("a key script step is tab, shift-tab, a direction, enter, escape, a digit or text:…, not '$step'")
                }
                typed.forEach { onText(TextEvent(it.toString())) }
            }
        }
    }

    private fun type(key: Key, modifiers: Modifiers = Modifiers.None) {
        onKey(KeyEvent(key, KeyEventType.Down, modifiers))
        onKey(KeyEvent(key, KeyEventType.Up, modifiers))
    }

    private fun tap(button: GamepadButton) {
        onGamepad(GamepadEvent.ButtonDown(GamepadId.First, button))
        onGamepad(GamepadEvent.ButtonUp(GamepadId.First, button))
    }

    private companion object {
        /** What each digit key is called in a script, as it is printed on the keyboard. */
        val numbers = mapOf(
            "1" to Key.Digit1, "2" to Key.Digit2, "3" to Key.Digit3, "4" to Key.Digit4,
            "5" to Key.Digit5, "6" to Key.Digit6, "7" to Key.Digit7, "8" to Key.Digit8,
            "9" to Key.Digit9, "0" to Key.Digit0,
        )
    }

    /** The window is no longer in front, so nothing is left holding a capture or a highlight. */
    fun windowLostFocus() {
        state.pointer = null
        router.cancelAll()
    }
}
