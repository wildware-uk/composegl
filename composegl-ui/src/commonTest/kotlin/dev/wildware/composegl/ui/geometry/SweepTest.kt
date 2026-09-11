package dev.wildware.composegl.ui.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clock-hand wedge over a rectangle.
 *
 * Every point it produces must be on the rectangle's edge, whatever angle it was asked for. That
 * is the one property worth testing hard: a point slightly inside leaves a hairline of uncovered
 * icon, and a point outside spills over whatever is next to it.
 */
class SweepTest {

    private val box = Rect(0f, 0f, 100f, 100f)

    private fun points(fan: FloatArray): List<Offset> =
        (2 until fan.size step 2).map { Offset(fan[it], fan[it + 1]) }

    private fun hub(fan: FloatArray) = Offset(fan[0], fan[1])

    private fun assertAt(expected: Offset, actual: Offset, message: String = "") {
        assertEquals(expected.x, actual.x, 0.01f, message)
        assertEquals(expected.y, actual.y, 0.01f, message)
    }

    private fun onTheEdge(at: Offset): Boolean {
        val onSide = (at.x == box.left || at.x == box.right) && at.y in box.top..box.bottom
        val onEnd = (at.y == box.top || at.y == box.bottom) && at.x in box.left..box.right
        return onSide || onEnd
    }

    @Test
    fun `it starts at the top and sweeps clockwise`() {
        val quarter = points(boxSweep(box, 0f, 0.25f))

        assertAt(Offset(50f, 0f), quarter.first(), "a sweep starts straight up")
        assertAt(Offset(100f, 50f), quarter.last(), "and a quarter of a turn later it is at three")
    }

    @Test
    fun `it goes round the corner rather than through it`() {
        val quarter = points(boxSweep(box, 0f, 0.25f))

        assertEquals(3, quarter.size, "up, the corner it passed, and three")
        assertAt(Offset(100f, 0f), quarter[1], "the corner itself, or the wedge cuts it off")
    }

    @Test
    fun `every point is on the edge at every angle`() {
        (0..200).forEach { step ->
            val turn = step / 200f
            points(boxSweep(box, turn, 0.1f)).forEach {
                assertTrue(onTheEdge(it), "$it is not on the edge at turn $turn")
            }
        }
    }

    @Test
    fun `a whole turn is the whole box`() {
        val whole = points(boxSweep(box, 0f, 1f))

        assertEquals(6, whole.size, "up, four corners and back to up")
        assertAt(Offset(50f, 0f), whole.first())
        assertAt(Offset(50f, 0f), whole.last())
    }

    @Test
    fun `more than a whole turn is still the whole box`() {
        assertEquals(points(boxSweep(box, 0f, 1f)), points(boxSweep(box, 0f, 4f)))
    }

    @Test
    fun `a sweep that starts late in the turn carries on into the next one`() {
        val across = points(boxSweep(box, 0.9f, 0.3f))

        assertEquals(3, across.size, "the top-right corner is passed on the way")
        assertAt(Offset(100f, 0f), across[1])
    }

    @Test
    fun `the hub is the middle`() {
        assertEquals(Offset(50f, 50f), hub(boxSweep(box, 0f, 0.5f)))
    }

    @Test
    fun `nothing to sweep draws nothing`() {
        assertEquals(0, boxSweep(box, 0f, 0f).size)
        assertEquals(0, boxSweep(box, 0f, -1f).size)
        assertEquals(0, boxSweep(Rect(0f, 0f, 0f, 0f), 0f, 0.5f).size)
    }

    @Test
    fun `a wide box is swept as wide as it is`() {
        val wide = Rect(0f, 0f, 200f, 50f)

        val at = points(boxSweep(wide, 0.25f, 0.01f)).first()

        assertEquals(200f, at.x, 0.01f, "three is the far side rather than a circle's radius")
        assertEquals(25f, at.y, 0.01f)
    }

    @Test
    fun `it is drawn where the box is rather than at the origin`() {
        val moved = Rect(100f, 200f, 200f, 300f)

        val fan = boxSweep(moved, 0f, 0.25f)

        assertAt(Offset(150f, 250f), hub(fan))
        assertAt(Offset(150f, 200f), points(fan).first())
    }
}
