package uk.wildware.composegl.ui.layout

import uk.wildware.composegl.ui.geometry.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConstraintsTest {

    @Test
    fun `a size inside the range is left alone`() {
        val constraints = Constraints(10f, 100f, 10f, 100f)

        assertEquals(Size(50f, 50f), constraints.constrain(Size(50f, 50f)))
    }

    @Test
    fun `a size outside the range is pulled to the nearest edge`() {
        val constraints = Constraints(10f, 100f, 10f, 100f)

        assertEquals(Size(100f, 10f), constraints.constrain(Size(500f, 1f)))
    }

    @Test
    fun `unbounded means no maximum, so nothing is too big`() {
        assertEquals(9_000f, Constraints.Unbounded.constrainWidth(9_000f))
        assertFalse(Constraints.Unbounded.hasBoundedWidth)
    }

    @Test
    fun `fixed leaves nothing to decide`() {
        assertTrue(Constraints.fixed(20f, 30f).isTight)
        assertFalse(Constraints.atMost(20f, 30f).isTight)
    }

    @Test
    fun `shrinking takes room off both ends`() {
        val inner = Constraints(50f, 100f, 50f, 100f).shrink(horizontal = 20f, vertical = 10f)

        assertEquals(Constraints(30f, 80f, 40f, 90f), inner)
    }

    @Test
    fun `shrinking past zero stops at zero`() {
        val inner = Constraints(10f, 20f).shrink(horizontal = 100f)

        assertEquals(0f, inner.minWidth)
        assertEquals(0f, inner.maxWidth)
    }

    @Test
    fun `shrinking an unbounded axis leaves it unbounded`() {
        val inner = Constraints.Unbounded.shrink(horizontal = 20f)

        assertFalse(inner.hasBoundedWidth)
    }

    @Test
    fun `loosening keeps the maximum and drops the minimum`() {
        val loose = Constraints(50f, 100f, 50f, 100f).loosen()

        assertEquals(Constraints(0f, 100f, 0f, 100f), loose)
    }

    @Test
    fun `a minimum above the maximum is a mistake, not a shrug`() {
        assertThrows<IllegalArgumentException> { Constraints(minWidth = 100f, maxWidth = 10f) }
    }

    @Test
    fun `negative room is a mistake`() {
        assertThrows<IllegalArgumentException> { Constraints(minWidth = -1f) }
    }

    @Test
    fun `toString says what it is`() {
        assertEquals("Constraints(w=0.0..100.0, h=0.0..∞)", Constraints(maxWidth = 100f).toString())
    }
}
