package dev.wildware.composegl.webgl

import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.backend.Haptics
import dev.wildware.composegl.ui.backend.SystemCursor
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.PointerIcon
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.math.sign

/** A pad as the Gamepad API describes it, the parts this reads. */
external interface JsGamepad : JsAny {
    val index: Int
    val mapping: String
    val connected: Boolean
    val buttons: JsArray<JsGamepadButton>
    val axes: JsArray<JsNumber>
}

external interface JsGamepadButton : JsAny {
    val pressed: Boolean
    val value: Double
}

/** Every pad slot the browser has, in order; an empty slot is null. */
fun browserGamepads(): JsArray<JsGamepad?> = js("(navigator.getGamepads ? Array.from(navigator.getGamepads()) : [])")

/**
 * The browser's pads, translated into the toolkit's.
 *
 * The Gamepad API has no event for a button, so this is polled like GLFW is: call [poll] once a
 * frame and it reports what changed since the last one, including a pad arriving and leaving.
 *
 * Only pads the browser maps to its standard layout are reported — `mapping == "standard"` is what
 * makes "the south face button" a question with an answer. A flight stick is left for the game.
 *
 * The standard layout already counts a stick's y as positive down, the toolkit's way. Triggers are
 * buttons with a value from 0 to 1 there, and become the toolkit's trigger axes.
 *
 * A browser only shows a page its pads after a button has been pressed on one while the page has
 * focus, so a pad plugged in before the page opened turns up on its first press, not before.
 *
 * @param sink where the translated events go.
 * @param deadZone how far a stick must move before it counts, 0 to 1; past it the rest is rescaled.
 * @param pads where the pads are read from. The browser's, unless a test hands in its own.
 */
class DomGamepadInput(
    private val sink: InputSink,
    private val deadZone: Float = 0.2f,
    private val pads: () -> JsArray<JsGamepad?> = ::browserGamepads,
) {

    private class Pad(val player: GamepadId) {
        val buttons = BooleanArray(Buttons.size)
        val axes = FloatArray(GamepadAxis.entries.size)
    }

    /** What each browser slot read last frame. Absent means nothing there. */
    private val known = mutableMapOf<Int, Pad>()

    fun poll() {
        val slots = pads()
        val seen = mutableSetOf<Int>()
        for (at in 0 until slots.length) {
            val pad = slots[at] ?: continue
            if (!pad.connected || pad.mapping != "standard") continue
            seen += pad.index
            report(known[pad.index] ?: plugIn(pad.index), pad)
        }
        known.keys.filter { it !in seen }.forEach { slot -> known[slot]?.let { unplug(slot, it) } }
    }

    /** Lets go of every pad, as though unplugged. For when the page is hidden. */
    fun releaseAll() {
        known.keys.toList().forEach { slot -> known[slot]?.let { unplug(slot, it) } }
    }

    private fun plugIn(slot: Int): Pad {
        val pad = Pad(GamepadId(lowestFreeSlot()))
        known[slot] = pad
        sink.onGamepad(GamepadEvent.Connected(pad.player))
        return pad
    }

    private fun unplug(slot: Int, pad: Pad) {
        known.remove(slot)
        sink.onGamepad(GamepadEvent.Disconnected(pad.player))
    }

    private fun report(pad: Pad, reading: JsGamepad) {
        val buttons = reading.buttons
        for (code in Buttons.indices) {
            val button = Buttons[code] ?: continue
            val down = code < buttons.length && buttons[code]?.pressed == true
            if (down == pad.buttons[code]) continue
            pad.buttons[code] = down
            sink.onGamepad(if (down) GamepadEvent.ButtonDown(pad.player, button) else GamepadEvent.ButtonUp(pad.player, button))
        }

        val axes = reading.axes
        for (axis in GamepadAxis.entries) {
            val value = when (axis) {
                GamepadAxis.LeftX -> deadZoned(axisAt(axes, 0))
                GamepadAxis.LeftY -> deadZoned(axisAt(axes, 1))
                GamepadAxis.RightX -> deadZoned(axisAt(axes, 2))
                GamepadAxis.RightY -> deadZoned(axisAt(axes, 3))
                GamepadAxis.LeftTrigger -> triggerAt(buttons, 6)
                GamepadAxis.RightTrigger -> triggerAt(buttons, 7)
            }
            if (value == pad.axes[axis.ordinal]) continue
            pad.axes[axis.ordinal] = value
            sink.onGamepad(GamepadEvent.Axis(pad.player, axis, value))
        }
    }

    private fun axisAt(axes: JsArray<JsNumber>, at: Int): Float = if (at < axes.length) axes[at]?.toDouble()?.toFloat() ?: 0f else 0f

    private fun triggerAt(buttons: JsArray<JsGamepadButton>, at: Int): Float =
        if (at < buttons.length) (buttons[at]?.value ?: 0.0).toFloat().coerceIn(0f, 1f) else 0f

    private fun deadZoned(value: Float): Float {
        if (abs(value) <= deadZone) return 0f
        if (deadZone >= 1f) return sign(value)
        return ((abs(value) - deadZone) / (1f - deadZone)).coerceAtMost(1f) * sign(value)
    }

    private fun lowestFreeSlot(): Int {
        val taken = known.values.map { it.player.value }.toSet()
        var slot = 0
        while (slot in taken) slot++
        return slot
    }

    private companion object {
        /** The standard layout's buttons by index. Six and seven are the triggers, read as axes. */
        val Buttons: List<GamepadButton?> = listOf(
            GamepadButton.South, GamepadButton.East, GamepadButton.West, GamepadButton.North,
            GamepadButton.LeftBumper, GamepadButton.RightBumper,
            null, null,
            GamepadButton.Back, GamepadButton.Start,
            GamepadButton.LeftStick, GamepadButton.RightStick,
            GamepadButton.DpadUp, GamepadButton.DpadDown, GamepadButton.DpadLeft, GamepadButton.DpadRight,
            GamepadButton.Guide,
        )
    }
}

/**
 * A buzz: the phone's motor where the browser has `navigator.vibrate`, and the first pad's rumble where
 * it has a vibration actuator. Either, both or neither — a laptop with no pad feels nothing.
 */
class DomHaptics(private val pads: () -> JsArray<JsGamepad?> = ::browserGamepads) : Haptics {

    override fun perform(haptic: Haptic) {
        vibrate(haptic.durationMillis)
        val slots = pads()
        for (at in 0 until slots.length) {
            val pad = slots[at] ?: continue
            if (!pad.connected) continue
            rumble(pad, haptic.durationMillis, haptic.strength.toDouble())
            return
        }
    }
}

private fun vibrate(millis: Int): Unit = js("{ if (navigator.vibrate) navigator.vibrate(millis); }")

private fun rumble(pad: JsGamepad, millis: Int, strength: Double): Unit = js(
    "{ if (pad.vibrationActuator && pad.vibrationActuator.playEffect) pad.vibrationActuator.playEffect('dual-rumble', { duration: millis, strongMagnitude: strength, weakMagnitude: strength }).catch(() => null); }",
)

/** The mouse cursor, as the canvas's CSS `cursor`. */
class DomSystemCursor(private val element: HTMLElement) : SystemCursor {

    /** The shape asked for last. */
    var current: PointerIcon = PointerIcon.Default
        private set

    override fun set(icon: PointerIcon) {
        current = icon
        element.style.cursor = when (icon) {
            PointerIcon.Default -> "default"
            PointerIcon.Text -> "text"
            PointerIcon.Hand -> "pointer"
            PointerIcon.Crosshair -> "crosshair"
            PointerIcon.ResizeHorizontal -> "ew-resize"
            PointerIcon.ResizeVertical -> "ns-resize"
            PointerIcon.ResizeTopLeftBottomRight -> "nwse-resize"
            PointerIcon.ResizeTopRightBottomLeft -> "nesw-resize"
            PointerIcon.Move -> "move"
            PointerIcon.NotAllowed -> "not-allowed"
        }
    }
}
