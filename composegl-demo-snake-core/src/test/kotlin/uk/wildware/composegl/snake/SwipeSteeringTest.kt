package uk.wildware.composegl.snake

import uk.wildware.composegl.snake.game.Direction
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.PointerId
import uk.wildware.composegl.ui.input.PointerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SwipeSteeringTest {

    private val finger = PointerId(1)
    private val steering = SwipeSteering(minimumTravel = 40f)

    private fun press(x: Float, y: Float, consumed: Boolean = false, type: PointerType = PointerType.Touch) =
        steering.onPointer(PointerEvent.Press(finger, Offset(x, y), type = type), consumed)

    private fun move(x: Float, y: Float, id: PointerId = finger) =
        steering.onPointer(PointerEvent.Move(id, Offset(x, y), type = PointerType.Touch), consumed = false)

    private fun release(x: Float, y: Float) =
        steering.onPointer(PointerEvent.Release(finger, Offset(x, y), type = PointerType.Touch), consumed = false)

    @Test
    fun `a flick right turns right`() {
        press(100f, 100f)
        assertEquals(Direction.Right, move(150f, 100f))
    }

    @Test
    fun `a flick up turns up`() {
        press(100f, 100f)
        assertEquals(Direction.Up, move(100f, 40f))
    }

    @Test
    fun `a tap is not a swipe`() {
        press(100f, 100f)
        assertNull(move(120f, 110f))
        assertNull(release(120f, 110f))
    }

    @Test
    fun `the longer axis wins a diagonal`() {
        press(100f, 100f)
        assertEquals(Direction.Down, move(130f, 200f))
    }

    @Test
    fun `a press the interface took does not steer`() {
        press(100f, 100f, consumed = true)
        assertNull(move(200f, 100f))
    }

    @Test
    fun `a mouse does not steer`() {
        press(100f, 100f, type = PointerType.Mouse)
        assertNull(move(200f, 100f))
    }

    @Test
    fun `one drag round a corner steers twice`() {
        press(100f, 100f)
        assertEquals(Direction.Right, move(160f, 100f))
        assertEquals(Direction.Down, move(160f, 200f))
    }

    @Test
    fun `another finger is ignored`() {
        press(100f, 100f)
        assertNull(move(300f, 100f, id = PointerId(2)))
    }

    @Test
    fun `a lifted finger steers no more`() {
        press(100f, 100f)
        release(100f, 100f)
        assertNull(move(300f, 100f))
    }

    @Test
    fun `a forgotten gesture steers no more`() {
        press(100f, 100f)
        steering.forget()
        assertNull(move(300f, 100f))
    }
}
