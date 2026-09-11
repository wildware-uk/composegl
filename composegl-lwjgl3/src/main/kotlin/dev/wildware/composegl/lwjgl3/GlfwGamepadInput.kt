package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.InputSink
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWGamepadState
import kotlin.math.abs
import kotlin.math.sign

/**
 * GLFW's pads, translated into the toolkit's.
 *
 * The counterpart to [GlfwPointerInput], and the raw-OpenGL twin of `GdxGamepadInput`. The two
 * share no code on purpose: the toolkit's input contract is small enough that two independent
 * translations of it are a cheap way to find out whether the contract is really engine-agnostic,
 * and a disagreement between them is a bug in the contract rather than in one of the backends.
 *
 * GLFW has no event for a pad, so this is polled: call [poll] once a frame, after the window's
 * `present`. It compares what each pad reads now against what it read last frame and reports the
 * differences. That includes plugging in and unplugging, so hot-plugging needs no callback and
 * nothing to free.
 *
 * Only pads GLFW recognises are reported. `glfwJoystickIsGamepad` is true when SDL's database has
 * a layout for the device, which is what makes "the south face button" a question with an answer;
 * a flight stick with no such mapping is left for the game, which knows what its axes mean.
 *
 * Y is not flipped: GLFW reports positive as down, the same direction the toolkit's y runs.
 * Triggers are: GLFW rests them at -1 and pulls them to 1, and the toolkit's contract says 0 to 1.
 *
 * Not verified against real hardware: there is no pad on the machine this was written on, so the
 * mapping, the hot-plug paths and the axis directions are read from GLFW's contract rather than
 * measured.
 *
 * @param sink where the translated events go.
 * @param deadZone how far a stick must move before it counts as moved, 0 to 1. Past it, values are
 *   rescaled over the whole range so the first movement reported is a small one.
 */
class GlfwGamepadInput(
    private val sink: InputSink,
    private val deadZone: Float = 0.2f,
) {

    /** What each pad read last frame, by GLFW's joystick number. Absent means not plugged in. */
    private val pads = mutableMapOf<Int, Pad>()

    private class Pad(val player: GamepadId) {
        val buttons = BooleanArray(GLFW.GLFW_GAMEPAD_BUTTON_LAST + 1)
        val axes = FloatArray(GLFW.GLFW_GAMEPAD_AXIS_LAST + 1)
    }

    /**
     * Reads every pad and reports what changed since the last call.
     *
     * Cheap enough to call every frame with no pad attached: GLFW answers "no" for an empty slot
     * without touching the driver.
     */
    fun poll() {
        GLFWGamepadState.malloc().use { state ->
            for (jid in GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST) {
                val readable = GLFW.glfwJoystickIsGamepad(jid) && GLFW.glfwGetGamepadState(jid, state)
                val pad = pads[jid]
                when {
                    readable && pad == null -> report(plugIn(jid), state)
                    readable && pad != null -> report(pad, state)
                    !readable && pad != null -> unplug(jid, pad)
                }
            }
        }
    }

    /**
     * Lets go of every pad, as though they had all been unplugged.
     *
     * For when the game is put away — a window losing focus, a level unloading — so nothing is
     * left holding a direction down.
     */
    fun releaseAll() {
        pads.keys.toList().forEach { jid -> pads[jid]?.let { unplug(jid, it) } }
    }

    private fun plugIn(jid: Int): Pad {
        val pad = Pad(GamepadId(lowestFreeSlot()))
        pads[jid] = pad
        sink.onGamepad(GamepadEvent.Connected(pad.player))
        return pad
    }

    private fun unplug(jid: Int, pad: Pad) {
        pads.remove(jid)
        // No per-button release first: the toolkit reads a disconnection as "let go of
        // everything", which is one event instead of fifteen and cannot half-arrive.
        sink.onGamepad(GamepadEvent.Disconnected(pad.player))
    }

    private fun report(pad: Pad, state: GLFWGamepadState) {
        val buttons = state.buttons()
        for (code in pad.buttons.indices) {
            val down = buttons[code].toInt() == GLFW.GLFW_PRESS
            if (down == pad.buttons[code]) continue
            pad.buttons[code] = down
            val button = named(code) ?: continue
            sink.onGamepad(
                if (down) GamepadEvent.ButtonDown(pad.player, button)
                else GamepadEvent.ButtonUp(pad.player, button),
            )
        }

        val axes = state.axes()
        for (code in pad.axes.indices) {
            val axis = axisNamed(code) ?: continue
            val settled = settle(axis, axes[code])
            // A stick at rest still wobbles. Reporting every wobble would wake the interface every
            // frame for nothing, so only a real change is passed on.
            if (settled == pad.axes[code]) continue
            pad.axes[code] = settled
            sink.onGamepad(GamepadEvent.Axis(pad.player, axis, settled))
        }
    }

    /** A raw GLFW reading, in the toolkit's units. */
    private fun settle(axis: GamepadAxis, value: Float): Float = when (axis) {
        // GLFW rests a trigger at -1 and pulls it to 1; the toolkit's range is 0 to 1. No dead
        // zone: a trigger that rests slightly off zero is a fact the game may want.
        GamepadAxis.LeftTrigger, GamepadAxis.RightTrigger -> ((value + 1f) / 2f).coerceIn(0f, 1f)
        else -> deadZoned(value)
    }

    /**
     * A stick reading, with the slack taken out.
     *
     * Cutting at the dead zone alone makes a stick jump from nothing to a fifth of its travel.
     * Rescaling what is left over the whole range means the first movement the game sees is small,
     * which is what makes a stick feel like a stick.
     */
    private fun deadZoned(value: Float): Float {
        if (abs(value) <= deadZone) return 0f
        if (deadZone >= 1f) return sign(value)
        return ((abs(value) - deadZone) / (1f - deadZone)) * sign(value)
    }

    /** Player numbers are handed back out, so unplugging one pad does not renumber the others. */
    private fun lowestFreeSlot(): Int {
        val taken = pads.values.map { it.player.value }.toSet()
        var slot = 0
        while (slot in taken) slot++
        return slot
    }

    private fun named(code: Int): GamepadButton? = when (code) {
        GLFW.GLFW_GAMEPAD_BUTTON_A -> GamepadButton.South
        GLFW.GLFW_GAMEPAD_BUTTON_B -> GamepadButton.East
        GLFW.GLFW_GAMEPAD_BUTTON_X -> GamepadButton.West
        GLFW.GLFW_GAMEPAD_BUTTON_Y -> GamepadButton.North
        GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER -> GamepadButton.LeftBumper
        GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER -> GamepadButton.RightBumper
        GLFW.GLFW_GAMEPAD_BUTTON_BACK -> GamepadButton.Back
        GLFW.GLFW_GAMEPAD_BUTTON_START -> GamepadButton.Start
        GLFW.GLFW_GAMEPAD_BUTTON_GUIDE -> GamepadButton.Guide
        GLFW.GLFW_GAMEPAD_BUTTON_LEFT_THUMB -> GamepadButton.LeftStick
        GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_THUMB -> GamepadButton.RightStick
        GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP -> GamepadButton.DpadUp
        GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN -> GamepadButton.DpadDown
        GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT -> GamepadButton.DpadLeft
        GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT -> GamepadButton.DpadRight
        else -> null
    }

    private fun axisNamed(code: Int): GamepadAxis? = when (code) {
        GLFW.GLFW_GAMEPAD_AXIS_LEFT_X -> GamepadAxis.LeftX
        GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y -> GamepadAxis.LeftY
        GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X -> GamepadAxis.RightX
        GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y -> GamepadAxis.RightY
        GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER -> GamepadAxis.LeftTrigger
        GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER -> GamepadAxis.RightTrigger
        else -> null
    }
}
