package dev.wildware.composegl.korge.demo

import korlibs.event.GameButton
import korlibs.event.GamePadUpdateEvent
import korlibs.event.Key
import korlibs.event.KeyEvent
import korlibs.event.MouseButton
import korlibs.event.MouseEvent
import korlibs.korge.view.Views

/**
 * Input a script plays into the stage, so the demo can photograph itself on a machine with nobody at it.
 *
 * Every step is a real KorGE event dispatched into the stage, the same objects KorGE's window sends,
 * so a scripted run goes through the backend's own translators and not round them.
 *
 * - [keys]: `tab`, `shift-tab`, `up`, `down`, `left`, `right`, `enter`, `escape`, `space`, `f3`, a
 *   letter or digit, or `text:HELLO` (typed characters). One step a frame.
 * - [pad]: `up`, `down`, `left`, `right`, `press`, `back`. Each is a press in one snapshot and a
 *   release in the next.
 * - [pointer]: `x,y` or `x,y,press` in window pixels, applied every frame.
 */
class DemoScript(keys: String?, pad: String?, private val pointer: String?) {

    private val frames = ArrayDeque<(Views) -> Unit>()

    init {
        keys?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { step -> frames += key(step) }
        pad?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { step ->
            val button = padButton(step)
            frames += { it.dispatch(snapshot(button)) }
            frames += { it.dispatch(snapshot()) }
        }
    }

    /** Whether every step has been played. */
    val done: Boolean get() = frames.isEmpty()

    private var pressed = false

    /** Plays this frame's step. */
    fun frame(views: Views) {
        pointer?.let { where ->
            val parts = where.split(',').map { it.trim() }
            val x = parts[0].toInt()
            val y = parts[1].toInt()
            views.dispatch(MouseEvent(type = MouseEvent.Type.MOVE, x = x, y = y))
            if (parts.getOrNull(2) == "press" && !pressed) {
                pressed = true
                views.dispatch(MouseEvent(type = MouseEvent.Type.DOWN, x = x, y = y, button = MouseButton.LEFT))
            }
        }
        frames.removeFirstOrNull()?.invoke(views)
    }

    private fun key(step: String): (Views) -> Unit {
        if (step.startsWith("text:")) {
            val text = step.removePrefix("text:")
            return { views -> text.forEach { views.dispatch(KeyEvent(type = KeyEvent.Type.TYPE, character = it)) } }
        }
        val shift = step == "shift-tab"
        val key = when (step) {
            "tab", "shift-tab" -> Key.TAB
            "up" -> Key.UP
            "down" -> Key.DOWN
            "left" -> Key.LEFT
            "right" -> Key.RIGHT
            "enter" -> Key.ENTER
            "escape" -> Key.ESCAPE
            "space" -> Key.SPACE
            "f3" -> Key.F3
            else -> when {
                step.length == 1 && step[0].isLetter() -> Key.valueOf(step.uppercase())
                step.length == 1 && step[0].isDigit() -> Key.valueOf("N$step")
                else -> error("a key step is tab, shift-tab, a direction, enter, escape, space, f3, a letter, a digit or text:…, not '$step'")
            }
        }
        return { views ->
            views.dispatch(KeyEvent(type = KeyEvent.Type.DOWN, key = key, shift = shift))
            views.dispatch(KeyEvent(type = KeyEvent.Type.UP, key = key, shift = shift))
        }
    }

    private fun padButton(step: String): GameButton = when (step) {
        "up" -> GameButton.UP
        "down" -> GameButton.DOWN
        "left" -> GameButton.LEFT
        "right" -> GameButton.RIGHT
        "press" -> GameButton.BUTTON_SOUTH
        "back" -> GameButton.BUTTON_EAST
        else -> error("a pad step is up, down, left, right, press or back, not '$step'")
    }

    companion object {
        /** One frame's pad snapshot: pad 0 connected, with [held] down. */
        fun snapshot(vararg held: GameButton): GamePadUpdateEvent = GamePadUpdateEvent().apply {
            gamepadsLength = 1
            gamepads[0].connected = true
            gamepads[0].index = 0
            held.forEach { gamepads[0].rawButtons[it.index] = 1f }
        }
    }
}
