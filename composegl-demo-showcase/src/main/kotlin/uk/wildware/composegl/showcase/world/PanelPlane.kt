package uk.wildware.composegl.showcase.world

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3

/**
 * A rectangle hanging in the world, and where a ray meets it.
 *
 * The game's half of pointing at an interface that is in the scene, on its own so that it can be
 * tested without a window: put the ray into the quad's space, find where it crosses z = 0, and turn
 * that into a coordinate on the panel. What happens after that is the toolkit's, and is the same
 * code a mouse goes through.
 *
 * @param pixels how wide the panel is in its own units.
 * @param tall how tall it is in its own units.
 * @param halfWidth half its width in world units; the height follows from the panel's aspect.
 */
class PanelPlane(
    private val pixels: Int,
    private val tall: Int,
    val halfWidth: Float = 2.1f,
) {

    val halfHeight: Float = halfWidth * tall / pixels

    private val inverse = Matrix4()
    private val local = Vector3()
    private val towards = Vector3()

    /** Tells it where the quad is now. Called every frame, because the panel is moving. */
    fun place(transform: Matrix4) {
        inverse.set(transform).inv()
    }

    /**
     * Where [origin] pointing along [direction] meets the panel, in the panel's own units.
     *
     * @return null when the ray misses it, points away from it, or meets the plane outside the
     *   rectangle — all of which are "the player is not pointing at the terminal".
     */
    fun hit(origin: Vector3, direction: Vector3): Pair<Float, Float>? {
        local.set(origin).mul(inverse)
        towards.set(direction).rot(inverse).nor()
        if (MathUtils.isZero(towards.z)) return null

        val distance = -local.z / towards.z
        if (distance <= 0f) return null

        val x = local.x + towards.x * distance
        val y = local.y + towards.y * distance
        if (x < -halfWidth || x > halfWidth || y < -halfHeight || y > halfHeight) return null

        // The quad's y grows upwards and a panel's grows downwards.
        return (x + halfWidth) / (halfWidth * 2f) * pixels to
            (halfHeight - y) / (halfHeight * 2f) * tall
    }
}
