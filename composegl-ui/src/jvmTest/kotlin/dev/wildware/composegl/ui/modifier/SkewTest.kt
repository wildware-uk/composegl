package dev.wildware.composegl.ui.modifier

import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.node.UiNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** What `Modifier.skew` accepts, and what a chain of them folds down to. */
class SkewTest {

    private fun resolved(modifier: Modifier) = UiNode("banner").also { it.modifier = modifier }.resolved

    @Test
    fun `a node with no skew leans nowhere`() {
        val plain = resolved(Modifier.size(10f))

        assertEquals(0f, plain.skewX)
        assertEquals(0f, plain.skewY)
        assertEquals(Alignment.Centre, plain.skewOrigin)
    }

    @Test
    fun `one skew resolves to its own angles`() {
        val leaning = resolved(Modifier.skew(x = -12f, y = 7f, origin = Alignment.BottomStart))

        assertEquals(-12f, leaning.skewX, 0.001f)
        assertEquals(7f, leaning.skewY, 0.001f)
        assertEquals(Alignment.BottomStart, leaning.skewOrigin)
    }

    @Test
    fun `two skews on one axis add their slopes rather than their angles`() {
        // Thirty and thirty is not sixty: two shears of slope 0.577 are one of slope 1.155.
        val twice = resolved(Modifier.skew(x = 30f).skew(x = 30f))

        val slope = 2.0 * kotlin.math.tan(Math.toRadians(30.0))
        assertEquals(Math.toDegrees(kotlin.math.atan(slope)).toFloat(), twice.skewX, 0.001f)
    }

    @Test
    fun `a skew on each axis is one shear with both slopes`() {
        val both = resolved(Modifier.skew(x = 10f).skew(y = -5f))

        assertEquals(10f, both.skewX, 0.001f, "the second one said nothing about across")
        assertEquals(-5f, both.skewY, 0.001f)
    }

    @Test
    fun `opposite skews cancel out to nothing`() {
        val cancelled = resolved(Modifier.skew(x = 20f).skew(x = -20f))

        assertEquals(0f, cancelled.skewX, 0.001f)
    }

    @Test
    fun `an angle that is not a slant is refused where it was written`() {
        assertThrows(IllegalArgumentException::class.java) { Modifier.skew(x = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.skew(y = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.skew(x = 90f) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.skew(x = -90f) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.skew(y = 135f) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.skew(x = Float.POSITIVE_INFINITY) }
    }

    @Test
    fun `two skews written the same way compare equal so nothing redraws`() {
        assertEquals(Modifier.skew(x = -12f), Modifier.skew(x = -12f))
    }
}
