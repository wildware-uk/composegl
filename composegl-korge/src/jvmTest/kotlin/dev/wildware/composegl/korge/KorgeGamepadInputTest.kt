package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.TextEvent
import korlibs.event.GameButton
import korlibs.event.GamePadConnectionEvent
import korlibs.event.GamePadUpdateEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * KorGE's pads, translated into the toolkit's, with no pad and no KorGE game.
 *
 * KorGE reports a pad as a snapshot of every button and stick, every frame. The translator's job is
 * to turn snapshots into changes, so each test here builds the snapshots by hand.
 */
class KorgeGamepadInputTest {

    /** Remembers everything, consumes nothing. */
    private class Recorder : InputSink {
        val events = mutableListOf<GamepadEvent>()
        override fun onPointer(event: PointerEvent) = false
        override fun onKey(event: KeyEvent) = false
        override fun onText(event: TextEvent) = false
        override fun onGamepad(event: GamepadEvent): Boolean {
            events += event
            return false
        }
    }

    private val recorder = Recorder()
    private val input = KorgeGamepadInput(recorder, deadZone = 0.2f)

    /** One frame's snapshot: [pads] maps KorGE's pad index to the buttons held on it. */
    private fun frame(vararg pads: Pair<Int, Map<GameButton, Float>>) {
        val event = GamePadUpdateEvent()
        event.gamepadsLength = (pads.maxOfOrNull { it.first } ?: -1) + 1
        pads.forEach { (index, buttons) ->
            val pad = event.gamepads[index]
            pad.connected = true
            pad.index = index
            buttons.forEach { (button, value) -> pad.rawButtons[button.index] = value }
        }
        input.onUpdate(event)
    }

    private fun connection(index: Int, connected: Boolean) =
        input.onConnection(GamePadConnectionEvent(GamePadConnectionEvent.Type.fromConnected(connected), index))

    @Test
    fun `a pad seen for the first time is announced, and its buttons are named by what they mean`() {
        frame(0 to emptyMap())
        frame(0 to mapOf(GameButton.BUTTON_SOUTH to 1f))
        frame(0 to emptyMap())
        frame(0 to mapOf(GameButton.LEFT to 1f))

        assertEquals(
            listOf(
                GamepadEvent.Connected(GamepadId.First),
                GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South),
                GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.South),
                GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.DpadLeft),
            ),
            recorder.events,
        )
    }

    @Test
    fun `every button the toolkit names comes from a KorGE button`() {
        val named = GameButton.entries.mapNotNull { KorgeGamepadInput.buttonOf(it) }.toSet()

        assertEquals(GamepadButton.entries.toSet(), named)
    }

    @Test
    fun `a button held across frames is one press`() {
        frame(0 to mapOf(GameButton.START to 1f))
        frame(0 to mapOf(GameButton.START to 1f))
        frame(0 to mapOf(GameButton.START to 1f))

        assertEquals(1, recorder.events.filterIsInstance<GamepadEvent.ButtonDown>().size, "the snapshot is not a new press")
    }

    @Test
    fun `KorGE's up is the toolkit's minus, because the toolkit's y runs down`() {
        frame(0 to mapOf(GameButton.LY to 1f, GameButton.LX to 1f))

        val axes = recorder.events.filterIsInstance<GamepadEvent.Axis>().associate { it.axis to it.value }
        assertEquals(-1f, axes.getValue(GamepadAxis.LeftY), 0.0001f, "KorGE reports +1 for a stick pushed up")
        assertEquals(1f, axes.getValue(GamepadAxis.LeftX), 0.0001f, "and right is right in both")
    }

    @Test
    fun `a stick inside the dead zone has not moved`() {
        frame(0 to mapOf(GameButton.LX to 0.1f))
        frame(0 to mapOf(GameButton.LX to -0.15f))

        assertTrue(recorder.events.none { it is GamepadEvent.Axis }, "a worn stick at rest is the backend's problem")
    }

    @Test
    fun `past the dead zone a stick starts from nothing`() {
        frame(0 to mapOf(GameButton.RX to 0.2001f))
        frame(0 to mapOf(GameButton.RX to 1f))

        val values = recorder.events.filterIsInstance<GamepadEvent.Axis>().map { it.value }
        assertEquals(2, values.size)
        assertTrue(values[0] < 0.01f, "the first movement past the dead zone is a small one")
        assertEquals(1f, values[1], 0.0001f, "the far end of the travel is still the far end")
    }

    @Test
    fun `a trigger arrives as how far it is pulled`() {
        frame(0 to mapOf(GameButton.L2 to 1f))
        frame(0 to emptyMap())

        assertEquals(
            listOf(
                GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftTrigger, 1f),
                GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftTrigger, 0f),
            ),
            recorder.events.filterIsInstance<GamepadEvent.Axis>(),
        )
        assertTrue(recorder.events.none { it is GamepadEvent.ButtonDown }, "a trigger is not a button")
    }

    @Test
    fun `unplugging player one does not renumber player two`() {
        connection(0, true)
        connection(1, true)
        recorder.events.clear()

        connection(0, false)
        frame(1 to mapOf(GameButton.BUTTON_SOUTH to 1f))

        assertEquals(
            listOf(
                GamepadEvent.Disconnected(GamepadId(0)),
                GamepadEvent.ButtonDown(GamepadId(1), GamepadButton.South),
            ),
            recorder.events,
        )
    }

    @Test
    fun `the freed number is given to the next pad plugged in`() {
        connection(0, true)
        connection(1, true)
        connection(1, false)
        recorder.events.clear()

        connection(2, true)

        assertEquals(listOf(GamepadEvent.Connected(GamepadId(1))), recorder.events)
    }

    @Test
    fun `the same pad announced twice is still one pad`() {
        connection(0, true)
        frame(0 to emptyMap())
        connection(0, true)

        assertEquals(listOf(GamepadEvent.Connected(GamepadId.First)), recorder.events)
    }

    @Test
    fun `a pad that vanishes from the snapshot mid-press is let go of`() {
        frame(0 to mapOf(GameButton.DOWN to 1f))
        recorder.events.clear()

        frame()

        assertEquals(listOf(GamepadEvent.Disconnected(GamepadId.First)), recorder.events)

        // And plugged back in with nothing held, it does not remember the old press.
        frame(0 to emptyMap())
        assertFalse(recorder.events.any { it is GamepadEvent.ButtonUp })
    }

    @Test
    fun `stopping lets go of every pad`() {
        frame(0 to emptyMap(), 1 to emptyMap())
        recorder.events.clear()

        input.stop()

        assertEquals(
            setOf(GamepadEvent.Disconnected(GamepadId(0)), GamepadEvent.Disconnected(GamepadId(1))),
            recorder.events.toSet(),
        )
    }
}
