package uk.wildware.composegl.ui.input

import uk.wildware.composegl.ui.focus.FocusDirection
import uk.wildware.composegl.ui.focus.FocusManager
import kotlin.math.abs

/**
 * A pad, driving the interface on its own.
 *
 * The three things that make stick navigation feel right, and that every first attempt gets wrong:
 *
 * 1. **A dead zone.** A stick at rest is never quite at zero. Without one, a menu drifts.
 * 2. **One direction at a time.** A stick pushed up and slightly right is *up*. Taking both axes
 *    sends focus diagonally, which no player ever means.
 * 3. **A repeat that starts slow.** Holding a direction should move once, pause, and then step —
 *    the same shape as a held key. Repeating every frame flies across the screen and lands
 *    somewhere nobody chose.
 *
 * Buttons are the pointer's press and release: South holds the focused node down and clicks it on
 * the way up, so a widget's pressed state means the same thing whatever is driving it. East is
 * back, which is the screen's business rather than the toolkit's, so it is a callback.
 *
 * Nothing here polls. A backend pushes events in, and [frame] is called once a frame with the
 * game's own clock, which is what makes the repeat testable without waiting for real time.
 *
 * @param deadZone how far a stick must travel before it counts. Backends normalise, but a pad with
 *   a worn stick still reports a little, and this is the interface's own floor.
 * @param firstRepeatMillis how long a held direction waits before it starts stepping.
 * @param repeatMillis how long between steps after that.
 * @param onBack what East and the pad's Back button do. Usually "close this screen".
 */
class GamepadNavigator(
    private val focus: FocusManager,
    private val deadZone: Float = 0.5f,
    private val firstRepeatMillis: Long = 400L,
    private val repeatMillis: Long = 110L,
    private val onBack: () -> Unit = {},
) {

    private var stickX = 0f
    private var stickY = 0f
    private val dpad = mutableSetOf<GamepadButton>()

    private var held: FocusDirection? = null
    private var repeatAt = 0L
    private var now = 0L

    /** The direction being held, if any. For tests, and for a game that wants to show it. */
    val direction: FocusDirection? get() = held

    fun onGamepad(event: GamepadEvent): Boolean = when (event) {
        is GamepadEvent.Axis -> axis(event)
        is GamepadEvent.ButtonDown -> down(event.button)
        is GamepadEvent.ButtonUp -> up(event.button)
        is GamepadEvent.Disconnected -> {
            // Somebody pulled the cable mid-press. Let go of everything rather than leaving a
            // button stuck down and a direction repeating forever.
            stickX = 0f
            stickY = 0f
            dpad.clear()
            held = null
            focus.cancelPress()
            false
        }
        is GamepadEvent.Connected -> false
    }

    /**
     * One frame of held-direction repeat.
     *
     * @param timeMillis the game's own monotonic clock. The only time source here, so a test can
     *   run a four-second hold in four lines.
     */
    fun frame(timeMillis: Long) {
        now = timeMillis
        val direction = held ?: return
        if (timeMillis < repeatAt) return
        focus.moveFocus(direction)
        repeatAt = timeMillis + repeatMillis
    }

    // --- what the pad said ---------------------------------------------------------------------

    private fun axis(event: GamepadEvent.Axis): Boolean {
        when (event.axis) {
            GamepadAxis.LeftX -> stickX = event.value
            GamepadAxis.LeftY -> stickY = event.value
            // The right stick scrolls and the triggers page, both of which are a widget's business
            // rather than focus. They land with the scrolling widgets.
            else -> return false
        }
        return aim()
    }

    private fun down(button: GamepadButton): Boolean = when (button) {
        GamepadButton.DpadUp, GamepadButton.DpadDown,
        GamepadButton.DpadLeft, GamepadButton.DpadRight -> {
            dpad += button
            aim()
        }
        GamepadButton.South -> focus.pressFocused()
        GamepadButton.East, GamepadButton.Back -> {
            onBack()
            true
        }
        else -> false
    }

    private fun up(button: GamepadButton): Boolean = when (button) {
        GamepadButton.DpadUp, GamepadButton.DpadDown,
        GamepadButton.DpadLeft, GamepadButton.DpadRight -> {
            dpad -= button
            aim()
        }
        GamepadButton.South -> focus.releaseFocused()
        else -> false
    }

    /**
     * Works out which single direction the pad is asking for, and acts if it changed.
     *
     * The d-pad wins over the stick when both are pushed, because a player using the d-pad is
     * being deliberate and a stick they are resting a thumb on is not.
     */
    private fun aim(): Boolean {
        val wanted = fromDpad() ?: fromStick()
        if (wanted == held) return wanted != null

        held = wanted
        if (wanted == null) return false

        // A fresh direction moves at once and then waits. Anything else feels like lag.
        focus.moveFocus(wanted)
        repeatAt = now + firstRepeatMillis
        return true
    }

    private fun fromDpad(): FocusDirection? = when {
        GamepadButton.DpadUp in dpad -> FocusDirection.Up
        GamepadButton.DpadDown in dpad -> FocusDirection.Down
        GamepadButton.DpadLeft in dpad -> FocusDirection.Left
        GamepadButton.DpadRight in dpad -> FocusDirection.Right
        else -> null
    }

    private fun fromStick(): FocusDirection? {
        val x = if (abs(stickX) < deadZone) 0f else stickX
        val y = if (abs(stickY) < deadZone) 0f else stickY
        return when {
            x == 0f && y == 0f -> null
            // The bigger push wins outright. Up-and-slightly-right is up.
            abs(x) >= abs(y) -> if (x > 0f) FocusDirection.Right else FocusDirection.Left
            else -> if (y > 0f) FocusDirection.Down else FocusDirection.Up
        }
    }
}
