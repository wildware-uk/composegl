package dev.wildware.composegl.ui.modifier

import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.node.UiNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** What `Modifier.perspective` accepts, and what a chain of them folds down to. */
class PerspectiveTest {

    private fun resolved(modifier: Modifier) = UiNode("row").also { it.modifier = modifier }.resolved

    @Test
    fun `a node with no perspective hands down no camera`() {
        val plain = resolved(Modifier.size(10f))

        assertEquals(0f, plain.perspective)
        assertEquals(Alignment.Centre, plain.perspectiveOrigin)
    }

    @Test
    fun `one perspective resolves to its distance and origin`() {
        val row = resolved(Modifier.perspective(distance = 800f, origin = Alignment.BottomEnd))

        assertEquals(800f, row.perspective)
        assertEquals(Alignment.BottomEnd, row.perspectiveOrigin)
    }

    @Test
    fun `the later perspective is the camera`() {
        val row = resolved(Modifier.perspective(300f, Alignment.TopStart).perspective(900f))

        assertEquals(900f, row.perspective)
        assertEquals(Alignment.Centre, row.perspectiveOrigin, "the later origin, even when it is the default")
    }

    @Test
    fun `a perspective leaves the node's own 3D rotation alone`() {
        val card = resolved(Modifier.rotate3d(y = 30f, cameraDistance = 4f).perspective(500f))

        assertEquals(30f, card.rotation3dY)
        assertEquals(4f, card.cameraDistance)
        assertEquals(500f, card.perspective)
    }

    @Test
    fun `a distance that is not in front of the screen is refused where it was written`() {
        assertThrows(IllegalArgumentException::class.java) { Modifier.perspective(0f) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.perspective(-100f) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.perspective(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.perspective(Float.POSITIVE_INFINITY) }
    }

    @Test
    fun `two perspectives written the same way compare equal so nothing redraws`() {
        assertEquals(Modifier.perspective(800f), Modifier.perspective(800f))
    }
}
