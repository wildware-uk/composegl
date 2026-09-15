package dev.wildware.composegl.ui.modifier

import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.node.UiNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** What `Modifier.rotate3d` accepts, and what a chain of them folds down to. */
class Rotate3dTest {

    private fun resolved(modifier: Modifier) = UiNode("card").also { it.modifier = modifier }.resolved

    @Test
    fun `a node with no 3D rotation turns nowhere`() {
        val plain = resolved(Modifier.size(10f))

        assertEquals(0f, plain.rotation3dX)
        assertEquals(0f, plain.rotation3dY)
        assertEquals(0f, plain.rotation3dZ)
        assertEquals(DefaultCameraDistance, plain.cameraDistance)
        assertEquals(Alignment.Centre, plain.rotation3dOrigin)
    }

    @Test
    fun `one 3D rotation resolves to its own angles camera and origin`() {
        val card = resolved(Modifier.rotate3d(x = 10f, y = 180f, z = -5f, cameraDistance = 3f, origin = Alignment.CentreStart))

        assertEquals(10f, card.rotation3dX)
        assertEquals(180f, card.rotation3dY)
        assertEquals(-5f, card.rotation3dZ)
        assertEquals(3f, card.cameraDistance)
        assertEquals(Alignment.CentreStart, card.rotation3dOrigin)
    }

    @Test
    fun `two 3D rotations add per axis and the later camera wins`() {
        val card = resolved(
            Modifier.rotate3d(y = 20f, cameraDistance = 4f, origin = Alignment.TopStart)
                .rotate3d(x = 5f, y = 90f, cameraDistance = 12f),
        )

        assertEquals(5f, card.rotation3dX)
        assertEquals(110f, card.rotation3dY, "a standing tilt plus a flip")
        assertEquals(12f, card.cameraDistance)
        assertEquals(Alignment.Centre, card.rotation3dOrigin, "the later origin, even when it is the default")
    }

    @Test
    fun `a 3D rotation leaves the flat turn and the slant alone`() {
        val card = resolved(Modifier.rotate(15f).skew(x = -10f).rotate3d(y = 40f))

        assertEquals(15f, card.rotation)
        assertEquals(-10f, card.skewX, 0.001f)
        assertEquals(40f, card.rotation3dY)
    }

    @Test
    fun `a value that is not an angle or a camera is refused where it was written`() {
        assertThrows(IllegalArgumentException::class.java) { Modifier.rotate3d(x = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.rotate3d(y = Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.rotate3d(z = Float.NEGATIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.rotate3d(y = 10f, cameraDistance = 0f) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.rotate3d(y = 10f, cameraDistance = -8f) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.rotate3d(cameraDistance = Float.NaN) }
    }

    @Test
    fun `two 3D rotations written the same way compare equal so nothing redraws`() {
        assertEquals(Modifier.rotate3d(y = 45f), Modifier.rotate3d(y = 45f))
    }
}
