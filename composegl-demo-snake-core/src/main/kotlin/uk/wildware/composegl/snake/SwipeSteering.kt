package uk.wildware.composegl.snake

import uk.wildware.composegl.snake.game.Direction
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.PointerId
import uk.wildware.composegl.ui.input.PointerType
import kotlin.math.abs

/**
 * A flick of a finger, as a turn.
 *
 * A phone has no arrow keys, so without this the game is watchable and not playable. The rule is
 * the one every phone game uses: whichever way the finger has travelled furthest since the last
 * turn, once it has gone far enough to be a swipe rather than a tap.
 *
 * Two deliberate details. A press the interface already took — the pause button in the corner —
 * never starts a swipe, so a mis-hit button does not also turn the snake. And a turn moves the
 * origin to where the finger is now rather than ending the gesture, so one long drag round a
 * corner steers twice, which is what a player doing it expects.
 *
 * @param minimumTravel how far, in the interface's own units, counts as a swipe rather than a tap.
 */
class SwipeSteering(private val minimumTravel: Float = 40f) {

    private var pointerId: PointerId? = null
    private var origin: Offset = Offset.Zero

    /**
     * @param consumed whether the interface used this event first.
     * @return the way to turn, or null, which is most events.
     */
    fun onPointer(event: PointerEvent, consumed: Boolean): Direction? {
        when (event) {
            is PointerEvent.Press -> {
                pointerId = if (consumed || event.type != PointerType.Touch) null else event.pointerId
                origin = event.position
            }

            is PointerEvent.Move -> {
                if (event.pointerId != pointerId) return null
                val direction = direction(event.position - origin) ?: return null
                origin = event.position
                return direction
            }

            is PointerEvent.Release, is PointerEvent.Cancel ->
                if (event.pointerId == pointerId) pointerId = null

            else -> Unit
        }
        return null
    }

    /** The finger is no longer on the screen as far as anybody knows. */
    fun forget() {
        pointerId = null
    }

    private fun direction(travel: Offset): Direction? {
        // The larger axis wins outright rather than both being tested: a diagonal flick has to mean
        // one thing, and "mostly right" is the thing a player meant.
        val horizontal = abs(travel.x) >= abs(travel.y)
        val distance = if (horizontal) travel.x else travel.y
        if (abs(distance) < minimumTravel) return null
        return when {
            horizontal && distance > 0f -> Direction.Right
            horizontal -> Direction.Left
            distance > 0f -> Direction.Down
            else -> Direction.Up
        }
    }
}
