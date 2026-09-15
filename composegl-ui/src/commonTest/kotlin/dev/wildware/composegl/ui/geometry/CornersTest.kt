package dev.wildware.composegl.ui.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Four radii, and the handful of questions a box asks about them. */
class CornersTest {

    @Test
    fun `the named shorthands round the corners they name and no others`() {
        assertEquals(Corners(topLeft = 8f, topRight = 8f), Corners.top(8f))
        assertEquals(Corners(bottomRight = 8f, bottomLeft = 8f), Corners.bottom(8f))
        assertEquals(Corners(topLeft = 8f, bottomLeft = 8f), Corners.left(8f))
        assertEquals(Corners(topRight = 8f, bottomRight = 8f), Corners.right(8f))
        assertEquals(Corners(8f, 8f, 8f, 8f), Corners.all(8f))
        assertEquals(Corners(0f, 0f, 0f, 0f), Corners.None)
    }

    @Test
    fun `the order is clockwise from the top-left`() {
        val corners = Corners(1f, 2f, 3f, 4f)

        assertEquals(1f, corners.topLeft)
        assertEquals(2f, corners.topRight)
        assertEquals(3f, corners.bottomRight)
        assertEquals(4f, corners.bottomLeft)
    }

    @Test
    fun `uniform means all four agree`() {
        assertTrue(Corners.all(6f).isUniform)
        assertTrue(Corners.None.isUniform)
        assertFalse(Corners.top(6f).isUniform)
        assertFalse(Corners(6f, 6f, 6f, 5.9f).isUniform)
    }

    @Test
    fun `smallest and largest are the extremes of the four`() {
        val bubble = Corners(topLeft = 12f, topRight = 12f, bottomRight = 12f, bottomLeft = 2f)

        assertEquals(2f, bubble.smallest)
        assertEquals(12f, bubble.largest)
    }

    @Test
    fun `the smallest box fits the wider pair across and the taller pair down`() {
        // Across the top 10 + 2, across the bottom 0 + 4; down the left 10 + 4, down the right 2 + 0.
        val corners = Corners(topLeft = 10f, topRight = 2f, bottomRight = 0f, bottomLeft = 4f)

        assertEquals(Size(12f, 14f), corners.minimumSize)
        assertEquals(Size(12f, 12f), Corners.all(6f).minimumSize, "one radius is twice itself each way")
    }

    @Test
    fun `a negative radius is refused where it was written`() {
        assertFailsWith<IllegalArgumentException> { Corners(topLeft = -1f) }
        assertFailsWith<IllegalArgumentException> { Corners.all(Float.NaN) }
    }
}
