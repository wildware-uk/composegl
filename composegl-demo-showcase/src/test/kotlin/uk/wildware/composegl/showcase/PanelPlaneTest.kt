package uk.wildware.composegl.showcase

import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import uk.wildware.composegl.showcase.world.PanelPlane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Where a ray meets the panel standing in the scene.
 *
 * The game's half of pointing at an interface that is in the world, and the half that has no
 * window in it — so it is tested here rather than looked at in a screenshot.
 */
class PanelPlaneTest {

    private val plane = PanelPlane(pixels = 400, tall = 200, halfWidth = 2f)

    private fun place(x: Float = 0f, y: Float = 0f, z: Float = 0f, turn: Float = 0f) {
        plane.place(Matrix4().setToTranslation(x, y, z).rotate(Vector3.Y, turn))
    }

    @Test
    fun `a ray down the middle lands in the middle`() {
        place()

        val hit = plane.hit(Vector3(0f, 0f, 5f), Vector3(0f, 0f, -1f))

        assertNotNull(hit)
        assertEquals(200f, hit!!.first, 0.01f)
        assertEquals(100f, hit.second, 0.01f)
    }

    @Test
    fun `a panel's y grows downwards even though the world's grows up`() {
        place()

        // Above the middle in the world.
        val hit = plane.hit(Vector3(0f, 0.5f, 5f), Vector3(0f, 0f, -1f))

        assertNotNull(hit)
        assertEquals(50f, hit!!.second, 0.01f, "up in the world is towards the top of the panel")
    }

    @Test
    fun `a ray past the edge misses`() {
        place()

        assertNull(plane.hit(Vector3(2.5f, 0f, 5f), Vector3(0f, 0f, -1f)), "past the side")
        assertNull(plane.hit(Vector3(0f, 1.2f, 5f), Vector3(0f, 0f, -1f)), "over the top")
    }

    @Test
    fun `a ray pointing away from it misses`() {
        place()

        assertNull(
            plane.hit(Vector3(0f, 0f, 5f), Vector3(0f, 0f, 1f)),
            "the panel is behind the player, and behind is not a hit",
        )
    }

    @Test
    fun `a ray along the panel misses rather than dividing by zero`() {
        place()

        assertNull(plane.hit(Vector3(0f, 0f, 0f), Vector3(1f, 0f, 0f)))
    }

    @Test
    fun `a panel that has turned is hit where it now faces`() {
        // Turned a quarter, so it faces down the positive x axis.
        place(turn = 90f)

        val fromTheSide = plane.hit(Vector3(5f, 0f, 0f), Vector3(-1f, 0f, 0f))
        assertNotNull(fromTheSide, "the panel now faces where the player is")
        assertEquals(200f, fromTheSide!!.first, 0.01f)

        assertNull(
            plane.hit(Vector3(0f, 0f, 5f), Vector3(0f, 0f, -1f)),
            "and is edge on to where it used to face",
        )
    }

    @Test
    fun `a panel that has moved takes its hit box with it`() {
        place(x = 10f)

        assertNull(plane.hit(Vector3(0f, 0f, 5f), Vector3(0f, 0f, -1f)), "it is not there any more")

        val hit = plane.hit(Vector3(10f, 0f, 5f), Vector3(0f, 0f, -1f))
        assertNotNull(hit)
        assertEquals(200f, hit!!.first, 0.01f)
    }
}
