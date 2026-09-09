package composegl.showcase.world

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import composegl.gdx.ComposeTexture

/**
 * A Compose surface standing in the world as a hologram.
 *
 * The interesting part is the blend mode. Skia gives us premultiplied pixels, so drawing them with
 * `GL_ONE, GL_ONE` adds them to whatever is behind — the panel emits light instead of covering the
 * scene, and its black background disappears. That is a hologram, and it costs one attribute.
 */
class HoloPanel(
    private val widthPixels: Int = 420,
    private val heightPixels: Int = 300,
) : Disposable {

    lateinit var surface: ComposeTexture
        private set

    private lateinit var model: Model
    private lateinit var instance: ModelInstance
    private lateinit var batch: ModelBatch

    /** Half-extents in world units, keeping the pixel aspect. */
    private val halfWidth = 2.1f
    private val halfHeight = 1.5f * heightPixels / widthPixels

    fun create() {
        surface = ComposeTexture(widthPixels, heightPixels)
        batch = ModelBatch()

        val material = Material(
            TextureAttribute.createDiffuse(surface.texture),
            BlendingAttribute(GL20.GL_ONE, GL20.GL_ONE),
        )
        model = ModelBuilder().createRect(
            -halfWidth, -halfHeight, 0f,
            halfWidth, -halfHeight, 0f,
            halfWidth, halfHeight, 0f,
            -halfWidth, halfHeight, 0f,
            0f, 0f, 1f,
            material,
            (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal or
                VertexAttributes.Usage.TextureCoordinates).toLong(),
        )
        instance = ModelInstance(model)
    }

    fun update(time: Float) {
        surface.update()
        surface.render()
        // Standing on the pedestal, turning slowly, bobbing a little.
        instance.transform
            // High enough to clear the reticle in the middle of the screen.
            .setToTranslation(0f, 3.05f + MathUtils.sin(time * 0.9f) * 0.06f, 0f)
            .rotate(Vector3.Y, time * 16f)
    }

    fun render(camera: PerspectiveCamera) {
        // Holograms do not hide what is behind them, so do not write depth.
        Gdx.gl.glDepthMask(false)
        batch.begin(camera)
        batch.render(instance)
        batch.end()
        Gdx.gl.glDepthMask(true)
    }

    override fun dispose() {
        surface.dispose()
        model.dispose()
        batch.dispose()
    }
}
