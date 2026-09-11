package uk.wildware.composegl.snake

import uk.wildware.composegl.snake.game.Direction
import uk.wildware.composegl.snake.game.Screen
import uk.wildware.composegl.snake.game.SnakeSession
import uk.wildware.composegl.ui.debug.FrameBudget
import uk.wildware.composegl.ui.focus.FocusManager
import uk.wildware.composegl.ui.input.GamepadButton
import uk.wildware.composegl.ui.input.GamepadEvent
import uk.wildware.composegl.ui.input.GamepadNavigator
import uk.wildware.composegl.ui.input.InputSink
import uk.wildware.composegl.ui.input.InputSource
import uk.wildware.composegl.ui.input.InputSourceTracker
import uk.wildware.composegl.ui.input.Key
import uk.wildware.composegl.ui.input.KeyEvent
import uk.wildware.composegl.ui.input.KeyEventType
import uk.wildware.composegl.ui.input.KeyNavigator
import uk.wildware.composegl.ui.input.KeyRouter
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.PointerRouter
import uk.wildware.composegl.ui.input.TextEvent
import uk.wildware.composegl.ui.node.UiNode

/**
 * Who gets a keystroke: the snake, or the interface.
 *
 * The interesting half of putting an interface on a game. While a run is on, the arrow keys steer
 * and must not walk the focus around the HUD's buttons, so the game is asked first and the toolkit
 * only sees what is left. Everywhere else — menu, pause, game over — it is the other way round,
 * because then the arrows are how a player without a mouse gets around a menu.
 *
 * Pause is the seam itself, and it is deliberately answered here rather than by either half: Space
 * and the pad's Start button work on both screens.
 */
class SnakeInput(
    private val session: SnakeSession,
    root: UiNode,
    private val budget: FrameBudget,
    initialSource: InputSource = InputSource.Mouse,
) : InputSink {

    val focus = FocusManager(root)

    /**
     * What the player last used, so the HUD can name the right control.
     *
     * Starts on touch or mouse depending on the launcher, and is corrected by the first event
     * either way — a phone with a keyboard plugged in is a real thing.
     */
    val source = InputSourceTracker(initialSource)

    private val pointer = PointerRouter(root, focus)
    private val keys = KeyRouter(focus, root)
    private val navigator = KeyNavigator(focus, onBack = ::back)
    private val swipe = SwipeSteering()
    private val pad = GamepadNavigator(focus, onBack = ::back)

    /**
     * The interface first, then the snake.
     *
     * The opposite way round from keys, and for the same reason: a finger on a button is aiming at
     * the button, whereas an arrow key during a run is aiming at the snake.
     */
    override fun onPointer(event: PointerEvent): Boolean {
        source.saw(event)
        val used = pointer.onPointer(event)
        if (session.screen == Screen.Playing) {
            swipe.onPointer(event, consumed = used)?.let {
                session.turn(it)
                return true
            }
        }
        return used
    }

    override fun onKey(event: KeyEvent): Boolean {
        source.saw(event)
        // Only while there is a run to pause: in the menu the space bar belongs to the player
        // typing their name in.
        val pausable = session.screen == Screen.Playing || session.screen == Screen.Paused
        if (pausable && event.type == KeyEventType.Down && event.key == Key.Space) {
            session.togglePause()
            return true
        }
        // Steering first while the snake is moving: focus is somewhere on the HUD, and an arrow key
        // that reached it would move the highlight instead of the snake.
        if (session.screen == Screen.Playing) {
            steer(event.key)?.let {
                if (event.type == KeyEventType.Down) session.turn(it)
                return true
            }
        }
        return keys.onKey(event) || navigator.onKey(event)
    }

    override fun onText(event: TextEvent): Boolean {
        source.saw(event)
        return keys.onText(event)
    }

    override fun onGamepad(event: GamepadEvent): Boolean {
        source.saw(event)
        if (event is GamepadEvent.ButtonDown && event.button == GamepadButton.Start) {
            session.togglePause()
            return true
        }
        if (session.screen == Screen.Playing) {
            padSteer(event)?.let {
                session.turn(it)
                return true
            }
        }
        return pad.onGamepad(event)
    }

    /**
     * Called once a frame, after layout.
     *
     * Refreshing first is what keeps focus off a node that has gone — and every screen change here
     * takes its whole panel with it.
     */
    fun frame(timeMillis: Long) {
        focus.refresh()
        pad.frame(timeMillis)
    }

    /** The window is no longer in front, so nothing is left holding a capture or a highlight. */
    fun windowLostFocus() {
        pointer.cancelAll()
        swipe.forget()
    }

    /** Escape and the pad's Back button: out of a run, then out of the game's own screens. */
    private fun back() {
        when (session.screen) {
            Screen.Playing -> session.pause()
            Screen.Paused, Screen.GameOver -> session.toMenu()
            Screen.Menu -> Unit
        }
    }

    private fun steer(key: Key): Direction? = when (key) {
        Key.Up, Key.W -> Direction.Up
        Key.Down, Key.S -> Direction.Down
        Key.Left, Key.A -> Direction.Left
        Key.Right, Key.D -> Direction.Right
        else -> null
    }

    private fun padSteer(event: GamepadEvent): Direction? {
        if (event !is GamepadEvent.ButtonDown) return null
        return when (event.button) {
            GamepadButton.DpadUp -> Direction.Up
            GamepadButton.DpadDown -> Direction.Down
            GamepadButton.DpadLeft -> Direction.Left
            GamepadButton.DpadRight -> Direction.Right
            else -> null
        }
    }
}
