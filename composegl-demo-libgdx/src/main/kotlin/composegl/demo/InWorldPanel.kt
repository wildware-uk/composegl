package composegl.demo

import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.Ray
import com.badlogic.gdx.utils.Disposable
import composegl.gdx.ComposeTexture
import kotlin.math.abs

/**
 * A Compose panel living inside the 3D scene, on a slowly turning quad.
 *
 * The interesting part is not the drawing — it is that the game, not ComposeGL, decides what a
 * click means. ComposeGL hands over a texture and takes pixel coordinates; working out which
 * pixel the player pointed at is the game's job, because only the game knows where the panel is.
 * That is [pick].
 */
class InWorldPanel(
    private val width: Int = 512,
    private val height: Int = 384,
) : Disposable {

    val ui = ComposeTexture(width, height)

    /** Corners in the quad's own space: bottom-left, bottom-right, top-right, top-left. */
    private val local = arrayOf(
        Vector3(-1.2f, -0.9f, 0f),
        Vector3(1.2f, -0.9f, 0f),
        Vector3(1.2f, 0.9f, 0f),
        Vector3(-1.2f, 0.9f, 0f),
    )

    private val model: Model = ModelBuilder().createRect(
        local[0].x, local[0].y, local[0].z,
        local[1].x, local[1].y, local[1].z,
        local[2].x, local[2].y, local[2].z,
        local[3].x, local[3].y, local[3].z,
        0f, 0f, 1f,
        Material(
            TextureAttribute.createDiffuse(ui.texture),
            // The panel is premultiplied, like everything Skia writes.
            BlendingAttribute(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA),
        ),
        (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal or
            VertexAttributes.Usage.TextureCoordinates).toLong(),
    )

    val instance = ModelInstance(model)

    private var angle = 0f

    // Scratch, so picking allocates nothing per frame.
    private val corner0 = Vector3()
    private val axisU = Vector3()
    private val axisV = Vector3()
    private val normal = Vector3()
    private val hit = Vector3()
    private val toPlane = Vector3()

    init {
        placeAndFaceCamera(0f)
    }

    fun update(deltaSeconds: Float) {
        angle += deltaSeconds * 25f
        placeAndFaceCamera(angle)
        ui.update()
        ui.render()
    }

    /** Roughly facing the camera, with a slow sway so you can see it really is in the scene. */
    private fun placeAndFaceCamera(angle: Float) {
        val sway = 18f * kotlin.math.sin(Math.toRadians(angle.toDouble())).toFloat()
        instance.transform.setToTranslation(2.6f, 0.7f, -2.4f).rotate(Vector3.Y, 12f + sway)
    }

    fun render(batch: ModelBatch, camera: Camera) {
        batch.render(instance)
    }

    /**
     * Where on the panel a ray lands, in the panel's own pixels, or null when it misses.
     *
     * Plain geometry: intersect the ray with the quad's plane, then measure how far along the
     * quad's two edges the hit is.
     */
    fun pick(ray: Ray): Pair<Float, Float>? {
        corner0.set(local[0]).mul(instance.transform)
        axisU.set(local[1]).mul(instance.transform).sub(corner0)
        axisV.set(local[3]).mul(instance.transform).sub(corner0)
        normal.set(axisU).crs(axisV)

        val facing = normal.dot(ray.direction)
        if (abs(facing) < 1e-6f) return null
        val distance = toPlane.set(corner0).sub(ray.origin).dot(normal) / facing
        if (distance < 0f) return null

        hit.set(ray.direction).scl(distance).add(ray.origin).sub(corner0)
        val u = hit.dot(axisU) / axisU.len2()
        val v = hit.dot(axisV) / axisV.len2()
        if (u !in 0f..1f || v !in 0f..1f) return null

        // v runs up the quad; texture rows run down.
        return u * width to (1f - v) * height
    }

    fun sendPointer(type: PointerEventType, x: Float, y: Float, button: PointerButton? = null): Boolean =
        ui.sendPointer(type, x, y, button)

    /** The world point a panel pixel sits at. The inverse of [pick], used to check it. */
    fun worldPointAt(x: Float, y: Float, out: Vector3): Vector3 {
        corner0.set(local[0]).mul(instance.transform)
        axisU.set(local[1]).mul(instance.transform).sub(corner0)
        axisV.set(local[3]).mul(instance.transform).sub(corner0)
        return out.set(corner0)
            .mulAdd(axisU, x / width)
            .mulAdd(axisV, 1f - y / height)
    }

    override fun dispose() {
        ui.dispose()
        model.dispose()
    }
}
