package dev.wildware.composegl.ui.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The arithmetic `Modifier.rotate3d` stands on, checked against answers worked out by hand. */
class Matrix4Test {

    private fun assertPoint(expected: Offset, actual: Offset, because: String) {
        assertEquals(expected.x, actual.x, 0.001f, "$because: x")
        assertEquals(expected.y, actual.y, 0.001f, "$because: y")
    }

    @Test
    fun `the identity leaves every point where it is`() {
        assertPoint(Offset(12f, -7f), Matrix4.Identity.map(12f, -7f), "nothing moved")
        assertEquals(1f, Matrix4.Identity.depthOf(12f, -7f))
        assertTrue(Matrix4.Identity.isIdentity)
    }

    @Test
    fun `a product applies the right hand matrix first`() {
        // Turned a quarter clockwise, (10, 0) is (0, 10); then moved five right it is (5, 10). The
        // other order would move first and give (0, 15).
        val turnThenMove = Matrix4.translation(5f, 0f) * Matrix4.rotationZ(90f)

        assertPoint(Offset(5f, 10f), turnThenMove.map(10f, 0f), "turned then moved")
    }

    @Test
    fun `a positive z turn is clockwise on a y down screen`() {
        assertPoint(Offset(0f, 10f), Matrix4.rotationZ(90f).map(10f, 0f), "right of the pivot goes below it")
    }

    @Test
    fun `a half turn about y mirrors across and leaves depth alone`() {
        val flipped = Matrix4.perspective(100f) * Matrix4.rotationY(180f)

        assertEquals(Offset(-10f, 4f), flipped.map(10f, 4f), "exactly mirrored, no float error from pi")
        assertEquals(1f, flipped.depthOf(10f, 4f), "and exactly on its own plane")
    }

    @Test
    fun `a positive y turn sends the right edge away from the camera`() {
        // A quarter turn puts (10, 0) at depth -10: ten away, so w is 1 + 10 / 100.
        val seen = Matrix4.perspective(100f) * Matrix4.rotationY(90f)

        assertEquals(1.1f, seen.depthOf(10f, 0f), 0.0001f, "the right edge is further")
        assertEquals(0.9f, seen.depthOf(-10f, 0f), 0.0001f, "the left edge is nearer")
    }

    @Test
    fun `a positive x turn sends the top edge away from the camera`() {
        val seen = Matrix4.perspective(100f) * Matrix4.rotationX(90f)

        assertTrue(seen.depthOf(0f, -10f) > 1f, "the top, above the pivot, is further")
        assertTrue(seen.depthOf(0f, 10f) < 1f, "the bottom is nearer")
    }

    @Test
    fun `the camera shows what is further away smaller`() {
        // Thirty degrees about y, camera 200 away. The point (40, 20) goes to depth -20, across
        // 34.64, so it is seen at 200 / 220 of that.
        val seen = Matrix4.perspective(200f) * Matrix4.rotationY(30f)
        val across = 40f * 0.8660254f
        val scale = 200f / 220f

        assertPoint(Offset(across * scale, 20f * scale), seen.map(40f, 20f), "shrunk towards the pivot")
    }

    @Test
    fun `project hands back the numbers before the divide`() {
        val seen = Matrix4.translation(50f, 60f) * Matrix4.perspective(200f) * Matrix4.rotationY(30f)
        val out = FloatArray(4)

        seen.project(40f, 20f, out, at = 1)

        val mapped = seen.map(40f, 20f)
        assertEquals(mapped.x, out[1] / out[3], 0.001f, "x over w is where it lands")
        assertEquals(mapped.y, out[2] / out[3], 0.001f, "y over w too")
        assertEquals(seen.depthOf(40f, 20f), out[3], 0.0001f)
        assertEquals(0f, out[0], "and nothing before where it was asked to write")
    }

    @Test
    fun `a shear slides one axis along the other`() {
        assertPoint(Offset(15f, 10f), Matrix4.shear(0.5f, 0f).map(10f, 10f), "x slides by half of y")
        assertPoint(Offset(10f, 5f), Matrix4.shear(0f, -0.5f).map(10f, 10f), "y slides by minus half of x")
    }

    @Test
    fun `two matrices with the same numbers are equal`() {
        assertEquals(Matrix4.rotationY(30f), Matrix4.rotationY(30f))
        assertEquals(Matrix4.rotationY(30f).hashCode(), Matrix4.rotationY(30f).hashCode())
        assertNotEquals(Matrix4.rotationY(30f), Matrix4.rotationY(31f))
    }

    @Test
    fun `a matrix that is not sixteen numbers or a camera not in front is refused`() {
        assertFailsWith<IllegalArgumentException> { Matrix4.of(1f, 2f, 3f) }
        assertFailsWith<IllegalArgumentException> { Matrix4.perspective(0f) }
        assertFailsWith<IllegalArgumentException> { Matrix4.perspective(-5f) }
        assertFailsWith<IllegalArgumentException> { Matrix4.perspective(Float.POSITIVE_INFINITY) }
    }
}
