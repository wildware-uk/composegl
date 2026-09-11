package composegl.showcase

import com.badlogic.gdx.math.Vector3
import composegl.showcase.world.HoloStand
import composegl.ui.debug.FrameBudget
import composegl.ui.focus.FocusManager
import composegl.ui.input.GamepadEvent
import composegl.ui.input.GamepadNavigator
import composegl.ui.input.InputSink
import composegl.ui.input.Key
import composegl.ui.input.KeyEvent
import composegl.ui.input.KeyEventType
import composegl.ui.input.KeyNavigator
import composegl.ui.input.KeyRouter
import composegl.ui.input.PointerEvent
import composegl.ui.input.PointerRouter
import composegl.ui.input.TextEvent
import composegl.ui.node.UiNode
import composegl.ui.world.WorldPointer

/**
 * Two interfaces, one mouse.
 *
 * The HUD is on the screen and the terminal is in the world, and the rule between them is the one a
 * game always wants: **the interface on the screen gets first refusal**. Anything it did not want
 * becomes a ray into the scene, and if that ray meets the panel standing on the pedestal, it turns
 * into the same pointer entry point the mouse uses — the same router, the same hit testing, the
 * same click.
 *
 * The release is the part worth watching. It is delivered whenever the ray pressed on the panel,
 * never because the toolkit said it did something with the press, which is why a click on the
 * terminal's background does not leave it dead.
 */
internal class ShowcaseInput(
    root: UiNode,
    private val holo: HoloStand,
    private val budget: FrameBudget,
    /** Where the camera is and where a screen point is pointing, from the game. */
    private val ray: (Float, Float) -> Pair<Vector3, Vector3>,
) : InputSink {

    val focus = FocusManager(root)

    /** The panel in the world has focus of its own: a pad could walk its buttons too. */
    val holoFocus = FocusManager(holo.panel.root)

    private val screen = PointerRouter(root, focus)
    private val inWorld = PointerRouter(holo.panel.root, holoFocus)
    private val pointer = WorldPointer(inWorld)

    private val keys = KeyRouter(focus, root)
    private val navigator = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)

    override fun onPointer(event: PointerEvent): Boolean {
        val onScreen = screen.onPointer(event)

        when (event) {
            is PointerEvent.Move -> {
                val hit = if (onScreen) null else pick(event.position.x, event.position.y)
                if (hit == null) pointer.away() else pointer.aim(hit.first, hit.second)
            }
            is PointerEvent.Press -> if (!onScreen) pointer.press(event.button)
            // Never conditional on where the ray is now, or on what the press did: if this pointer
            // pressed on the panel, the panel hears about the release.
            is PointerEvent.Release -> pointer.release(event.button)
            is PointerEvent.Exit -> pointer.away()
            is PointerEvent.Cancel -> pointer.cancel()
            else -> Unit
        }

        return onScreen
    }

    override fun onKey(event: KeyEvent): Boolean {
        // The frame budget is the game's switch, not the toolkit's: F3 here, F3 in every game that
        // has ever had one.
        if (event.type == KeyEventType.Down && event.key == Key.F3) {
            budget.toggle()
            return true
        }
        return keys.onKey(event) || navigator.onKey(event)
    }

    override fun onText(event: TextEvent) = keys.onText(event)

    override fun onGamepad(event: GamepadEvent) = pad.onGamepad(event)

    /** Called once a frame, after both layouts. */
    fun frame(timeMillis: Long) {
        focus.refresh()
        holoFocus.refresh()
        pad.frame(timeMillis)
    }

    fun windowLostFocus() {
        screen.cancelAll()
        pointer.cancel()
    }

    private fun pick(x: Float, y: Float): Pair<Float, Float>? {
        val (origin, direction) = ray(x, y)
        return holo.hit(origin, direction)
    }
}
