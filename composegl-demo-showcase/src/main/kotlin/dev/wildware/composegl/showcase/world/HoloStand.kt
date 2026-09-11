package dev.wildware.composegl.showcase.world

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
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import dev.wildware.composegl.gdx.GdxCanvas
import dev.wildware.composegl.gdx.GdxRenderTarget
import dev.wildware.composegl.ui.world.WorldPanel

/**
 * An interface standing in the world: a panel drawn into a texture, on a quad, turning slowly.
 *
 * Three toolkit pieces and nothing else. [WorldPanel] runs the composition and says whether
 * anything changed; [GdxRenderTarget] is the texture it draws into; the quad and the maths below
 * are the game's, because where a panel hangs in a scene is a game's business and nothing the
 * toolkit should have an opinion about.
 *
 * It is drawn with `GL_ONE, GL_ONE` — added to what is behind it rather than covering it, which is
 * what makes it a hologram. That works because what the render target hands back is premultiplied;
 * with straight alpha every transparent pixel would show as a grey haze.
 *
 * @param pixels how big the panel is in its own units, which is also the texture's size.
 */
class HoloStand(
    private val pixels: Int = 420,
    private val tall: Int = 340,
) : Disposable {

    /** The panel itself: an ordinary composition that has no idea where it is being drawn. */
    val panel = WorldPanel(pixels.toFloat(), tall.toFloat())

    private lateinit var target: GdxRenderTarget
    private lateinit var model: Model
    private lateinit var instance: ModelInstance
    private lateinit var batch: ModelBatch

    /** Where the quad is, and the arithmetic that turns a ray into a point on the panel. */
    private val plane = PanelPlane(pixels, tall)
    private val transform = Matrix4()

    fun create() {
        target = GdxRenderTarget(pixels, tall)
        batch = ModelBatch()

        val material = Material(
            TextureAttribute.createDiffuse(target.texture.region),
            BlendingAttribute(GL20.GL_ONE, GL20.GL_ONE),
        )
        model = ModelBuilder().createRect(
            -plane.halfWidth, -plane.halfHeight, 0f,
            plane.halfWidth, -plane.halfHeight, 0f,
            plane.halfWidth, plane.halfHeight, 0f,
            -plane.halfWidth, plane.halfHeight, 0f,
            0f, 0f, 1f,
            material,
            (
                VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal or
                    VertexAttributes.Usage.TextureCoordinates
                ).toLong(),
        )
        instance = ModelInstance(model)
    }

    /**
     * Moves it, and redraws it only if the composition changed.
     *
     * The last part is the reason to do any of this: a terminal nobody is touching costs one
     * comparison a frame, not a render pass.
     *
     * @return whether it was redrawn this frame.
     */
    fun update(canvas: GdxCanvas, time: Float, nanos: Long): Boolean {
        transform
            .setToTranslation(0f, 3.05f + MathUtils.sin(time * 0.9f) * 0.06f, 0f)
            .rotate(Vector3.Y, time * 16f)
        instance.transform.set(transform)
        plane.place(transform)

        if (!panel.needsRedraw(nanos)) return false
        target.draw(canvas) { panel.draw(canvas) }
        return true
    }

    fun render(camera: PerspectiveCamera) {
        // A hologram does not hide what is behind it, so it does not write depth either.
        Gdx.gl.glDepthMask(false)
        batch.begin(camera)
        batch.render(instance)
        batch.end()
        Gdx.gl.glDepthMask(true)
    }

    /**
     * Where a ray meets the panel, in the panel's own units, or null if it misses.
     *
     * The whole of the game's half of pointing at an interface in the world: put the ray into the
     * quad's space, find where it crosses z = 0, and turn that into a coordinate on the panel. What
     * happens next — hit testing, hover, capture, the click — is the toolkit's and is the same code
     * a mouse goes through.
     */
    fun hit(origin: Vector3, direction: Vector3): Pair<Float, Float>? = plane.hit(origin, direction)

    override fun dispose() {
        panel.close()
        target.dispose()
        model.dispose()
        batch.dispose()
    }
}
