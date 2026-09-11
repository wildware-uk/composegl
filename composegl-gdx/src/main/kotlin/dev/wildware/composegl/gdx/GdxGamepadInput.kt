package dev.wildware.composegl.gdx

import com.badlogic.gdx.controllers.Controller
import com.badlogic.gdx.controllers.ControllerListener
import com.badlogic.gdx.controllers.ControllerMapping
import com.badlogic.gdx.controllers.Controllers
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.InputSink
import kotlin.math.abs
import kotlin.math.sign

/**
 * LibGDX's pads, translated into the toolkit's.
 *
 * The same kind of thing [GdxPointerInput] is, for the other half of the input contract: it turns
 * what a pad reports into [GamepadEvent]s and hands them to an [InputSink]. It decides nothing
 * about what an event means — which widget moves, what counts as a press — because that is
 * `GamepadNavigator`'s job and is tested with hand-written events and no pad present.
 *
 * gdx-controllers is worth the dependency for exactly two things the toolkit should not be
 * writing itself:
 *
 * 1. **A name-to-layout database.** A pad reports numbered buttons and axes; which number is the
 *    south face button differs per make, per driver and per platform. `Controller.getMapping()`
 *    answers that, and it is backed by SDL's database of thousands of pads.
 * 2. **Hot-plugging.** A pad plugged in halfway through a game arrives as [connected], and one
 *    unplugged mid-press arrives as [disconnected] so nothing is left held down.
 *
 * Three conversions happen:
 *
 * - **Controller to player number.** The toolkit wants a small stable number per pad. The lowest
 *   free slot is assigned on connection and given back on disconnection, so unplugging player
 *   two's pad does not renumber player three.
 * - **Numbers to names.** Through the mapping, so [GamepadButton.South] is A on one pad and ✕ on
 *   another without anything above here knowing.
 * - **Raw axis to dead-zoned axis.** The toolkit's contract says an [GamepadEvent.Axis] is already
 *   dead-zoned and normalised, so a worn stick is dealt with once, here, rather than being a
 *   drifting menu everywhere.
 *
 * Y is not flipped. SDL, which is what this sits on, already reports positive as down, which is
 * the direction the toolkit's y runs.
 *
 * Not verified against real hardware: there is no pad on the machine this was written on, so the
 * mapping, the hot-plug paths and the axis directions are read from the API's contract rather than
 * measured.
 *
 * @param sink where the translated events go.
 * @param deadZone how far a stick must move before it is considered moved at all, 0 to 1. Values
 *   past it are rescaled so the first reported movement is small rather than a jump.
 */
class GdxGamepadInput(
    private val sink: InputSink,
    private val deadZone: Float = 0.2f,
) : ControllerListener {

    /** Which player number each pad was given, by the pad's own stable id. */
    private val players = mutableMapOf<String, GamepadId>()

    /** The last value reported per pad and axis, so an unmoved stick says nothing. */
    private val lastAxis = mutableMapOf<Pair<String, GamepadAxis>, Float>()

    private var listening = false

    /**
     * Starts listening, and announces the pads that are already plugged in.
     *
     * Call it once the game has a window. Asking for the controller list is also what initialises
     * gdx-controllers' backend, so a pad that was plugged in before the game started is found
     * here rather than never.
     */
    fun start() {
        if (listening) return
        listening = true
        Controllers.getControllers().forEach { connected(it) }
        Controllers.addListener(this)
    }

    /** Stops listening. A pad still held is released first, so nothing stays down. */
    fun stop() {
        if (!listening) return
        listening = false
        Controllers.removeListener(this)
        players.keys.toList().forEach { id ->
            val player = players.remove(id) ?: return@forEach
            sink.onGamepad(GamepadEvent.Disconnected(player))
        }
        lastAxis.clear()
    }

    override fun connected(controller: Controller) {
        val id = controller.uniqueId ?: return
        if (id in players) return
        val player = GamepadId(lowestFreeSlot())
        players[id] = player
        sink.onGamepad(GamepadEvent.Connected(player))
    }

    override fun disconnected(controller: Controller) {
        val id = controller.uniqueId ?: return
        val player = players.remove(id) ?: return
        lastAxis.keys.removeAll { it.first == id }
        // Whatever was held is now unreachable. The toolkit reads this as "let go of everything",
        // which is the difference between a menu that stops and a menu that scrolls for ever.
        sink.onGamepad(GamepadEvent.Disconnected(player))
    }

    override fun buttonDown(controller: Controller, buttonCode: Int): Boolean {
        val player = players[controller.uniqueId] ?: return false
        trigger(controller, buttonCode)?.let { axis ->
            // A pad whose triggers are reported as buttons has no travel to report, so it reports
            // the two ends of the travel. A widget reading "how far is the trigger pulled" then
            // works on both kinds of pad without asking which kind it has.
            return sink.onGamepad(GamepadEvent.Axis(player, axis, 1f))
        }
        val button = controller.mapping?.button(buttonCode) ?: return false
        return sink.onGamepad(GamepadEvent.ButtonDown(player, button))
    }

    override fun buttonUp(controller: Controller, buttonCode: Int): Boolean {
        val player = players[controller.uniqueId] ?: return false
        trigger(controller, buttonCode)?.let { axis ->
            return sink.onGamepad(GamepadEvent.Axis(player, axis, 0f))
        }
        val button = controller.mapping?.button(buttonCode) ?: return false
        return sink.onGamepad(GamepadEvent.ButtonUp(player, button))
    }

    override fun axisMoved(controller: Controller, axisCode: Int, value: Float): Boolean {
        val id = controller.uniqueId ?: return false
        val player = players[id] ?: return false
        val axis = controller.mapping?.axisNamed(axisCode) ?: return false
        val settled = deadZoned(value)
        // A stick at rest still reports, constantly, with tiny changes. Sending those on would
        // wake the interface every frame for no reason. An axis nobody has heard from yet counts
        // as resting, so plugging in a pad reports nothing until somebody touches it.
        val previous = lastAxis.put(id to axis, settled) ?: 0f
        if (previous == settled) return false
        return sink.onGamepad(GamepadEvent.Axis(player, axis, settled))
    }

    /**
     * A stick reading, with the slack taken out.
     *
     * Cutting at the dead zone alone makes the stick jump from nothing to a fifth of its travel.
     * Rescaling what is left over the whole range means the first movement the game sees is a
     * small one, which is what makes a stick feel like a stick.
     */
    private fun deadZoned(value: Float): Float {
        if (abs(value) <= deadZone) return 0f
        if (deadZone >= 1f) return sign(value)
        return ((abs(value) - deadZone) / (1f - deadZone)) * sign(value)
    }

    /** The trigger this button is, if a pad reports its triggers as buttons rather than axes. */
    private fun trigger(controller: Controller, buttonCode: Int): GamepadAxis? {
        val mapping = controller.mapping ?: return null
        return when (buttonCode) {
            defined(mapping.buttonL2) -> GamepadAxis.LeftTrigger
            defined(mapping.buttonR2) -> GamepadAxis.RightTrigger
            else -> null
        }
    }

    /** Player numbers are handed back out, so unplugging one pad does not renumber the others. */
    private fun lowestFreeSlot(): Int {
        val taken = players.values.map { it.value }.toSet()
        var slot = 0
        while (slot in taken) slot++
        return slot
    }

    /**
     * A button number, as the toolkit names it.
     *
     * `UNDEFINED` is filtered out first: a pad with no Guide button reports `-1` for it, and a
     * `when` that did not check would match every unknown button to whichever name happened to be
     * missing.
     */
    private fun ControllerMapping.button(buttonCode: Int): GamepadButton? = when (buttonCode) {
        defined(buttonA) -> GamepadButton.South
        defined(buttonB) -> GamepadButton.East
        defined(buttonX) -> GamepadButton.West
        defined(buttonY) -> GamepadButton.North
        defined(buttonL1) -> GamepadButton.LeftBumper
        defined(buttonR1) -> GamepadButton.RightBumper
        defined(buttonBack) -> GamepadButton.Back
        defined(buttonStart) -> GamepadButton.Start
        defined(buttonLeftStick) -> GamepadButton.LeftStick
        defined(buttonRightStick) -> GamepadButton.RightStick
        defined(buttonDpadUp) -> GamepadButton.DpadUp
        defined(buttonDpadDown) -> GamepadButton.DpadDown
        defined(buttonDpadLeft) -> GamepadButton.DpadLeft
        defined(buttonDpadRight) -> GamepadButton.DpadRight
        else -> null
    }

    private fun ControllerMapping.axisNamed(axisCode: Int): GamepadAxis? = when (axisCode) {
        defined(axisLeftX) -> GamepadAxis.LeftX
        defined(axisLeftY) -> GamepadAxis.LeftY
        defined(axisRightX) -> GamepadAxis.RightX
        defined(axisRightY) -> GamepadAxis.RightY
        else -> null
    }

    /** The code, or a number nothing can ever equal. */
    private fun defined(code: Int): Int =
        if (code == ControllerMapping.UNDEFINED) NeverMatches else code

    private companion object {
        /** Below every real button and axis number, so an undefined mapping matches nothing. */
        const val NeverMatches = Int.MIN_VALUE
    }
}
