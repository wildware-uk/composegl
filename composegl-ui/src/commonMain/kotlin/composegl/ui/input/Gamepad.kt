package composegl.ui.input

import kotlin.jvm.JvmInline

/**
 * A gamepad button, named by what it means rather than by what is printed on it.
 *
 * [South] is A on an Xbox pad and ✕ on a PlayStation pad. Naming it by position is the only way to
 * write an interface once and have the prompts come out right on both, which is what
 * `PromptGlyph` exists to finish.
 */
enum class GamepadButton {
    South, East, West, North,
    LeftBumper, RightBumper,
    Back, Start, Guide,
    LeftStick, RightStick,
    DpadUp, DpadDown, DpadLeft, DpadRight,
}

enum class GamepadAxis { LeftX, LeftY, RightX, RightY, LeftTrigger, RightTrigger }

/** Which pad. Four players, four ids. */
@JvmInline
value class GamepadId(val value: Int) {
    companion object {
        val First = GamepadId(0)
    }
}

sealed interface GamepadEvent {

    val gamepadId: GamepadId

    data class ButtonDown(
        override val gamepadId: GamepadId,
        val button: GamepadButton,
    ) : GamepadEvent

    data class ButtonUp(
        override val gamepadId: GamepadId,
        val button: GamepadButton,
    ) : GamepadEvent

    /**
     * A stick or trigger moved.
     *
     * [value] is already dead-zoned and normalised by the backend: sticks run -1 to 1, triggers 0
     * to 1. The toolkit never sees raw hardware numbers, so a pad with a worn stick is the
     * backend's problem rather than a drifting menu.
     *
     * Y runs the same way the toolkit's y runs: **positive is down**. Most pad APIs already report
     * it this way; the ones that do not are the backend's job to flip, once, where the rest of the
     * platform's quirks live.
     */
    data class Axis(
        override val gamepadId: GamepadId,
        val axis: GamepadAxis,
        val value: Float,
    ) : GamepadEvent

    data class Connected(override val gamepadId: GamepadId) : GamepadEvent

    data class Disconnected(override val gamepadId: GamepadId) : GamepadEvent
}
