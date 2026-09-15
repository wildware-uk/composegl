package dev.wildware.composegl.ui.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How a backend cuts a picture to a shape with a soft edge, as arithmetic. */
class FeatherTest {

    private class Corner(val x: Float, val y: Float, val cover: Float)

    private fun quads(outline: FloatArray, feather: Float): List<List<Corner>> {
        val quads = mutableListOf<List<Corner>>()
        featherOutline(outline, feather) { ax, ay, ac, bx, by, bc, cx, cy, cc, dx, dy, dc ->
            quads += listOf(Corner(ax, ay, ac), Corner(bx, by, bc), Corner(cx, cy, cc), Corner(dx, dy, dc))
        }
        return quads
    }

    private val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)

    @Test
    fun `each edge is a solid wedge and a soft strip`() {
        val quads = quads(square, feather = 1f)
        assertEquals(8, quads.size)
        assertEquals(4, quads.count { quad -> quad.all { it.cover == 1f } }, "the solid middle")
        assertEquals(4, quads.count { quad -> quad.count { it.cover == 0f } == 2 }, "the ring round it")
    }

    @Test
    fun `the solid part is inside the outline and the faded edge is outside it`() {
        val corners = quads(square, feather = 1f).flatten()
        corners.filter { it.cover == 1f }.forEach {
            assertTrue(it.x in 0f..10f && it.y in 0f..10f, "covered corner at ${it.x}, ${it.y} is inside")
        }
        corners.filter { it.cover == 0f }.forEach {
            assertTrue(it.x < 0f || it.y < 0f || it.x > 10f || it.y > 10f, "faded corner at ${it.x}, ${it.y} is outside")
        }
    }

    @Test
    fun `the edge is as wide as the feather`() {
        // A corner of the square, pushed out and pulled in along its diagonal by half a unit each.
        val ring = quads(square, feather = 2f).first { quad -> quad.any { it.cover == 0f } }
        val inner = ring[0]
        val outer = ring[1]
        val spread = kotlin.math.hypot(outer.x - inner.x, outer.y - inner.y)
        assertTrue(kotlin.math.abs(spread - 2f) < 0.001f, "one feather from covered to clear, got $spread")
    }

    @Test
    fun `a shape smaller than its feather is never turned inside out`() {
        val tiny = floatArrayOf(0f, 0f, 0.2f, 0f, 0.1f, 0.2f)
        val hubX = 0.1f
        val hubY = 0.2f / 3f
        quads(tiny, feather = 4f).flatten().filter { it.cover == 1f }.forEach {
            assertTrue(kotlin.math.abs(it.x - hubX) < 0.001f && kotlin.math.abs(it.y - hubY) < 0.001f,
                "a covered corner pulled in stops at the middle, got ${it.x}, ${it.y}")
        }
    }

    @Test
    fun `fewer than three points hands back nothing`() {
        assertTrue(quads(floatArrayOf(0f, 0f, 1f, 1f), feather = 1f).isEmpty())
    }
}
