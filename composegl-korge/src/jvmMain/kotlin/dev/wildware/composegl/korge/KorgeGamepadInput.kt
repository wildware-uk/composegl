package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.InputSink
import korlibs.event.GameButton
import korlibs.event.GamePadConnectionEvent
import korlibs.event.GamePadUpdateEvent
import kotlin.math.abs
import kotlin.math.sign

/**
 * KorGE's pads, translated into the toolkit's.
 *
 * KorGE does not send "South went down". Every frame it sends a snapshot of every pad — each button
 * and stick as a number — and a connection event when one comes or goes. This turns snapshots into
 * [GamepadEvent]s: what changed since the last one, and nothing else. It decides nothing about what
 * an event means; that is `GamepadNavigator`'s job.
 *
 * Four conversions:
 *
 * - **Pad to player number.** The lowest free number is given out when a pad is first seen, and
 *   handed back when it goes, so unplugging player two does not renumber player three. KorGE's own
 *   index is the only identity it offers for a pad, and on some platforms it closes the gaps when one
 *   is unplugged; a pad that moves index is then read as one pad leaving and another arriving.
 * - **Names.** KorGE's layout already names buttons by position (`BUTTON_SOUTH` is A on one pad and
 *   ✕ on another), so this is a table. `SYSTEM` is Guide and `SELECT` is Back.
 * - **Up.** KorGE reports a stick pushed up as +1. The toolkit's y runs down, so the y axes are
 *   flipped here and nowhere else.
 * - **Dead zone.** A stick or trigger inside [deadZone] has not moved, and the travel past it is
 *   rescaled so the first reported movement is small rather than a jump.
 *
 * Triggers are axes (how far pulled), never buttons.
 *
 * KorGE has no rumble API, so pads do not vibrate: see [KorgeHaptics].
 *
 * @param sink where the translated events go.
 * @param deadZone how far a stick must move before it is considered moved, 0 to 1.
 */
class KorgeGamepadInput(
    private val sink: InputSink,
    private val deadZone: Float = 0.2f,
) {

    /** Which player number each pad was given, by KorGE's index. */
    private val players = mutableMapOf<Int, GamepadId>()

    /** The buttons held on each pad at the last snapshot. */
    private val buttons = mutableMapOf<Int, MutableSet<GamepadButton>>()

    /** The last value reported per pad and axis, so an unmoved stick says nothing. */
    private val axes = mutableMapOf<Pair<Int, GamepadAxis>, Float>()

    /** A pad came or went. */
    fun onConnection(event: GamePadConnectionEvent): Boolean = when (event.type) {
        GamePadConnectionEvent.Type.CONNECTED -> connect(event.gamepad)
        GamePadConnectionEvent.Type.DISCONNECTED -> disconnect(event.gamepad)
    }

    /** One frame's snapshot of every pad. */
    fun onUpdate(event: GamePadUpdateEvent): Boolean {
        var used = false
        val seen = mutableSetOf<Int>()
        for (slot in 0 until event.gamepadsLength) {
            val pad = event.gamepads[slot]
            if (!pad.connected) continue
            val index = pad.index
            seen += index
            used = connect(index) or used
            val player = players.getValue(index)

            val before = buttons.getOrPut(index) { mutableSetOf() }
            GameButton.entries.forEach { korge ->
                val button = buttonOf(korge) ?: return@forEach
                val down = pad[korge] != 0f
                if (down && before.add(button)) used = sink.onGamepad(GamepadEvent.ButtonDown(player, button)) or used
                if (!down && before.remove(button)) used = sink.onGamepad(GamepadEvent.ButtonUp(player, button)) or used
            }

            AxisSources.forEach { (korge, axis, flip) ->
                val settled = deadZoned(pad[korge]) * (if (flip) -1f else 1f) + 0f
                // An axis nobody has heard from yet counts as resting, so a pad plugged in reports
                // nothing until somebody touches it.
                val previous = axes.put(index to axis, settled) ?: 0f
                if (previous != settled) used = sink.onGamepad(GamepadEvent.Axis(player, axis, settled)) or used
            }
        }
        // A pad missing from the snapshot has gone, whether or not anyone said so.
        players.keys.filter { it !in seen }.forEach { used = disconnect(it) or used }
        return used
    }

    /** Lets go of every pad. Call it when the view goes away, so nothing stays held. */
    fun stop() {
        players.keys.toList().forEach { disconnect(it) }
    }

    private fun connect(index: Int): Boolean {
        if (index in players) return false
        val player = GamepadId(lowestFreeSlot())
        players[index] = player
        return sink.onGamepad(GamepadEvent.Connected(player))
    }

    private fun disconnect(index: Int): Boolean {
        val player = players.remove(index) ?: return false
        buttons.remove(index)
        axes.keys.removeAll { it.first == index }
        // Whatever was held is now unreachable: "let go of everything".
        return sink.onGamepad(GamepadEvent.Disconnected(player))
    }

    private fun deadZoned(value: Float): Float {
        if (abs(value) <= deadZone) return 0f
        if (deadZone >= 1f) return sign(value)
        return ((abs(value) - deadZone) / (1f - deadZone)).coerceAtMost(1f) * sign(value)
    }

    private fun lowestFreeSlot(): Int {
        val taken = players.values.map { it.value }.toSet()
        var slot = 0
        while (slot in taken) slot++
        return slot
    }

    companion object {

        /** A KorGE button, as the toolkit names it. Sticks, triggers and generic buttons are not buttons. */
        fun buttonOf(button: GameButton): GamepadButton? = when (button) {
            GameButton.BUTTON_SOUTH -> GamepadButton.South
            GameButton.BUTTON_EAST -> GamepadButton.East
            GameButton.BUTTON_WEST -> GamepadButton.West
            GameButton.BUTTON_NORTH -> GamepadButton.North
            GameButton.L1 -> GamepadButton.LeftBumper
            GameButton.R1 -> GamepadButton.RightBumper
            GameButton.SELECT -> GamepadButton.Back
            GameButton.START -> GamepadButton.Start
            GameButton.SYSTEM -> GamepadButton.Guide
            GameButton.L3 -> GamepadButton.LeftStick
            GameButton.R3 -> GamepadButton.RightStick
            GameButton.UP -> GamepadButton.DpadUp
            GameButton.DOWN -> GamepadButton.DpadDown
            GameButton.LEFT -> GamepadButton.DpadLeft
            GameButton.RIGHT -> GamepadButton.DpadRight
            else -> null
        }

        /** Each axis, where KorGE keeps it, and whether its direction is the other way up. */
        private val AxisSources = listOf(
            Triple(GameButton.LX, GamepadAxis.LeftX, false),
            Triple(GameButton.LY, GamepadAxis.LeftY, true),
            Triple(GameButton.RX, GamepadAxis.RightX, false),
            Triple(GameButton.RY, GamepadAxis.RightY, true),
            Triple(GameButton.L2, GamepadAxis.LeftTrigger, false),
            Triple(GameButton.R2, GamepadAxis.RightTrigger, false),
        )
    }
}
