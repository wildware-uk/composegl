package composegl.gdx

import com.badlogic.gdx.controllers.Controller
import com.badlogic.gdx.controllers.ControllerListener
import com.badlogic.gdx.controllers.ControllerMapping
import com.badlogic.gdx.controllers.ControllerPowerLevel
import composegl.ui.input.GamepadAxis
import composegl.ui.input.GamepadButton
import composegl.ui.input.GamepadEvent
import composegl.ui.input.GamepadId
import composegl.ui.input.InputSink
import composegl.ui.input.KeyEvent
import composegl.ui.input.PointerEvent
import composegl.ui.input.TextEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The translation from a LibGDX pad to the toolkit's, with no pad and no LibGDX application.
 *
 * Every method that does the translating is a listener callback, so a test can call them directly
 * with a pad it made up. What cannot be tested here is the part that talks to the driver —
 * `start` and `stop` — and that part is deliberately two lines long for exactly this reason.
 */
class GdxGamepadInputTest {

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

    /** A layout, in the order `ControllerMapping` takes them. */
    private class Layout(
        axisLeftX: Int, axisLeftY: Int, axisRightX: Int, axisRightY: Int,
        buttonA: Int, buttonB: Int, buttonX: Int, buttonY: Int,
        buttonBack: Int, buttonStart: Int,
        buttonL1: Int, buttonL2: Int, buttonR1: Int, buttonR2: Int,
        buttonLeftStick: Int, buttonRightStick: Int,
        buttonDpadUp: Int, buttonDpadDown: Int, buttonDpadLeft: Int, buttonDpadRight: Int,
    ) : ControllerMapping(
        axisLeftX, axisLeftY, axisRightX, axisRightY,
        buttonA, buttonB, buttonX, buttonY, buttonBack, buttonStart,
        buttonL1, buttonL2, buttonR1, buttonR2,
        buttonLeftStick, buttonRightStick,
        buttonDpadUp, buttonDpadDown, buttonDpadLeft, buttonDpadRight,
    )

    /** A pad that answers questions about itself and does nothing else. */
    private class Pad(
        private val id: String,
        private val layout: ControllerMapping,
    ) : Controller {
        override fun getButton(buttonCode: Int) = false
        override fun getAxis(axisCode: Int) = 0f
        override fun getName() = id
        override fun getUniqueId() = id
        override fun getMinButtonIndex() = 0
        override fun getMaxButtonIndex() = 20
        override fun getAxisCount() = 4
        override fun isConnected() = true
        override fun canVibrate() = false
        override fun isVibrating() = false
        override fun startVibration(duration: Int, strength: Float) = Unit
        override fun cancelVibration() = Unit
        override fun supportsPlayerIndex() = false
        override fun getPlayerIndex() = Controller.PLAYER_IDX_UNSET
        override fun setPlayerIndex(index: Int) = Unit
        override fun getMapping() = layout
        override fun getPowerLevel() = ControllerPowerLevel.POWER_UNKNOWN
        override fun addListener(listener: ControllerListener) = Unit
        override fun removeListener(listener: ControllerListener) = Unit
    }

    /** A pad whose triggers are axes, which is the usual arrangement. */
    private fun xbox(id: String) = Pad(
        id,
        Layout(
            axisLeftX = 0, axisLeftY = 1, axisRightX = 2, axisRightY = 3,
            buttonA = 0, buttonB = 1, buttonX = 2, buttonY = 3,
            buttonBack = 6, buttonStart = 7,
            buttonL1 = 4, buttonL2 = ControllerMapping.UNDEFINED,
            buttonR1 = 5, buttonR2 = ControllerMapping.UNDEFINED,
            buttonLeftStick = 8, buttonRightStick = 9,
            buttonDpadUp = 10, buttonDpadDown = 11, buttonDpadLeft = 12, buttonDpadRight = 13,
        ),
    )

    /** A pad whose triggers are buttons, which some drivers report instead. */
    private fun clicky(id: String) = Pad(
        id,
        Layout(
            axisLeftX = 0, axisLeftY = 1, axisRightX = 2, axisRightY = 3,
            buttonA = 0, buttonB = 1, buttonX = 2, buttonY = 3,
            buttonBack = 8, buttonStart = 9,
            buttonL1 = 4, buttonL2 = 6, buttonR1 = 5, buttonR2 = 7,
            buttonLeftStick = 10, buttonRightStick = 11,
            buttonDpadUp = 12, buttonDpadDown = 13, buttonDpadLeft = 14, buttonDpadRight = 15,
        ),
    )

    @Test
    fun `a button is named by what it means, not by its number`() {
        val recorder = Recorder()
        val input = GdxGamepadInput(recorder)
        val pad = xbox("one")
        input.connected(pad)

        input.buttonDown(pad, 0)
        input.buttonUp(pad, 0)
        input.buttonDown(pad, 12)

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
    fun `a button the layout does not name is left alone`() {
        val recorder = Recorder()
        val input = GdxGamepadInput(recorder)
        val pad = xbox("one")
        input.connected(pad)
        recorder.events.clear()

        // The pad has no L2 button, so its mapping records -1 for it. A translation that did not
        // check would hand every unrecognised button to whichever name happened to be missing.
        assertFalse(input.buttonDown(pad, 99), "an unmapped button is not the toolkit's business")
        assertFalse(input.buttonDown(pad, ControllerMapping.UNDEFINED))
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun `a pad nobody announced is ignored`() {
        val recorder = Recorder()
        val input = GdxGamepadInput(recorder)

        assertFalse(input.buttonDown(xbox("ghost"), 0))
        assertTrue(recorder.events.isEmpty(), "a pad with no player number has nowhere to send")
    }

    @Test
    fun `a stick inside the dead zone has not moved`() {
        val recorder = Recorder()
        val input = GdxGamepadInput(recorder, deadZone = 0.2f)
        val pad = xbox("one")
        input.connected(pad)
        recorder.events.clear()

        input.axisMoved(pad, 0, 0.1f)
        input.axisMoved(pad, 0, -0.15f)

        assertTrue(recorder.events.isEmpty(), "a worn stick at rest is the backend's problem")
    }

    @Test
    fun `past the dead zone a stick starts from nothing`() {
        val recorder = Recorder()
        val input = GdxGamepadInput(recorder, deadZone = 0.2f)
        val pad = xbox("one")
        input.connected(pad)
        recorder.events.clear()

        input.axisMoved(pad, 1, 0.2001f)
        input.axisMoved(pad, 1, 1f)

        val values = recorder.events.filterIsInstance<GamepadEvent.Axis>().map { it.value }
        assertEquals(2, values.size)
        assertTrue(values[0] < 0.01f, "the first movement past the dead zone is a small one")
        assertEquals(1f, values[1], 0.0001f, "the far end of the travel is still the far end")
    }

    @Test
    fun `a stick that has not changed says nothing twice`() {
        val recorder = Recorder()
        val input = GdxGamepadInput(recorder)
        val pad = xbox("one")
        input.connected(pad)
        recorder.events.clear()

        input.axisMoved(pad, 0, 0.8f)
        assertEquals(1, recorder.events.size)
        input.axisMoved(pad, 0, 0.8f)

        assertEquals(1, recorder.events.size, "an unmoved stick must not wake the interface")
    }

    @Test
    fun `a trigger reported as a button still arrives as a trigger`() {
        val recorder = Recorder()
        val input = GdxGamepadInput(recorder)
        val pad = clicky("one")
        input.connected(pad)
        recorder.events.clear()

        input.buttonDown(pad, 6)
        input.buttonUp(pad, 6)

        assertEquals(
            listOf(
                GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftTrigger, 1f),
                GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftTrigger, 0f),
            ),
            recorder.events,
            "a widget reading how far the trigger is pulled works on both kinds of pad",
        )
    }

    @Test
    fun `unplugging player one does not renumber player two`() {
        val recorder = Recorder()
        val input = GdxGamepadInput(recorder)
        val first = xbox("one")
        val second = xbox("two")
        input.connected(first)
        input.connected(second)
        recorder.events.clear()

        input.disconnected(first)
        input.buttonDown(second, 0)

        assertEquals(
            listOf(
                GamepadEvent.Disconnected(GamepadId(0)),
                GamepadEvent.ButtonDown(GamepadId(1), GamepadButton.South),
            ),
            recorder.events,
            "player two keeps their number when player one leaves",
        )
    }

    @Test
    fun `the freed number is given to the next pad plugged in`() {
        val recorder = Recorder()
        val input = GdxGamepadInput(recorder)
        input.connected(xbox("one"))
        val second = xbox("two")
        input.connected(second)
        input.disconnected(second)
        recorder.events.clear()

        input.connected(xbox("three"))

        assertEquals(listOf(GamepadEvent.Connected(GamepadId(1))), recorder.events)
    }

    @Test
    fun `the same pad announced twice is still one pad`() {
        val recorder = Recorder()
        val input = GdxGamepadInput(recorder)
        val pad = xbox("one")
        input.connected(pad)
        input.connected(pad)

        assertEquals(
            listOf(GamepadEvent.Connected(GamepadId.First)),
            recorder.events,
            "asking for the pad list at startup must not double-announce what a callback already said",
        )
    }
}
