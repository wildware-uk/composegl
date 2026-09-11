package composegl.showcase.world

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable

/** Something in the world that the interface has an opinion about. */
class Drone(
    val callsign: String,
    private val radius: Float,
    private val speed: Float,
    private val phase: Float,
    private val heightOffset: Float,
) {
    val position = Vector3()
    var shield: Float = 1f
        private set
    var integrity: Float = 1f
        private set

    /** Screen position, filled in each frame so the UI can follow it. */
    val screen = Vector3()

    fun update(time: Float) {
        val angle = time * speed + phase
        position.set(
            MathUtils.cos(angle) * radius,
            1.4f + MathUtils.sin(time * 0.8f + phase) * 0.35f + heightOffset,
            MathUtils.sin(angle) * radius,
        )
        // Slow, obvious oscillation so the bars visibly move.
        shield = 0.5f + 0.5f * MathUtils.sin(time * 0.7f + phase * 2f)
        integrity = 0.35f + 0.35f * MathUtils.sin(time * 0.31f + phase)
    }
}

/**
 * The scene the interface sits inside: a grid floor, a slowly orbiting camera and three drifting
 * drones. Plain LibGDX, and it imports no Compose — the interface is bolted on from outside.
 */
class Scene3D : Disposable {

    /**
     * Built in [create], not here: a camera touches native code the moment it is made, and nothing
     * native exists until LibGDX has started.
     */
    lateinit var camera: PerspectiveCamera
        private set

    val drones = listOf(
        Drone("VESPER", radius = 4.2f, speed = 0.35f, phase = 0f, heightOffset = 0.3f),
        Drone("KESTREL", radius = 5.6f, speed = -0.24f, phase = 2.1f, heightOffset = 0.9f),
        Drone("MERLIN", radius = 3.1f, speed = 0.47f, phase = 4.0f, heightOffset = -0.2f),
    )

    private lateinit var modelBatch: ModelBatch
    private lateinit var environment: Environment
    private lateinit var droneModel: Model
    private lateinit var pedestalModel: Model
    private lateinit var droneInstances: List<ModelInstance>
    private lateinit var pedestal: ModelInstance
    private lateinit var shapes: ShapeRenderer

    var time = 0f
        private set

    fun create() {
        camera = PerspectiveCamera(60f, 1280f, 800f)
        modelBatch = ModelBatch()
        shapes = ShapeRenderer()

        val builder = ModelBuilder()
        val attributes = (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong()
        droneModel = builder.createBox(
            0.5f, 0.5f, 0.5f,
            Material(ColorAttribute.createDiffuse(Color.valueOf("6fd3ff"))),
            attributes,
        )
        pedestalModel = builder.createCylinder(
            2.6f, 0.25f, 2.6f, 32,
            Material(ColorAttribute.createDiffuse(Color.valueOf("1b2534"))),
            attributes,
        )

        droneInstances = drones.map { ModelInstance(droneModel) }
        pedestal = ModelInstance(pedestalModel).apply { transform.setToTranslation(0f, 0.1f, 0f) }

        environment = Environment().apply {
            set(ColorAttribute(ColorAttribute.AmbientLight, 0.35f, 0.4f, 0.5f, 1f))
            add(DirectionalLight().set(0.7f, 0.85f, 1f, -0.6f, -0.9f, -0.4f))
        }

        camera.near = 0.1f
        camera.far = 120f
    }

    fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
    }

    fun update(delta: Float) {
        time += delta

        // A slow orbit, so everything on screen has to keep up with a moving world.
        val angle = time * 0.12f
        camera.position.set(MathUtils.cos(angle) * 9.5f, 4.6f, MathUtils.sin(angle) * 9.5f)
        camera.lookAt(0f, 1.4f, 0f)
        camera.up.set(Vector3.Y)
        camera.update()

        drones.forEachIndexed { index, drone ->
            drone.update(time)
            droneInstances[index].transform
                .setToTranslation(drone.position)
                .rotate(Vector3.Y, time * 40f + index * 60f)
                .rotate(Vector3.X, time * 25f)
            // Where the interface should draw, in screen pixels with y already flipped.
            drone.screen.set(drone.position)
            camera.project(drone.screen)
            drone.screen.y = Gdx.graphics.height - drone.screen.y
        }
    }

    fun render() {
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        drawFloor()
        modelBatch.begin(camera)
        modelBatch.render(pedestal, environment)
        droneInstances.forEach { modelBatch.render(it, environment) }
        modelBatch.end()
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
    }

    private fun drawFloor() {
        shapes.projectionMatrix = camera.combined
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(ShapeRenderer.ShapeType.Line)
        val half = GRID_EXTENT * GRID_STEP / 2f
        for (i in 0..GRID_EXTENT) {
            val offset = -half + i * GRID_STEP
            val edge = MathUtils.clamp(1f - kotlin.math.abs(offset) / half, 0f, 1f)
            shapes.color = GRID_COLOUR.cpy().apply { a = 0.08f + 0.22f * edge }
            shapes.line(offset, 0f, -half, offset, 0f, half)
            shapes.line(-half, 0f, offset, half, 0f, offset)
        }
        shapes.end()
        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    override fun dispose() {
        modelBatch.dispose()
        droneModel.dispose()
        pedestalModel.dispose()
        shapes.dispose()
    }

    private companion object {
        const val GRID_EXTENT = 28
        const val GRID_STEP = 1f
        val GRID_COLOUR: Color = Color.valueOf("4fb0d8")
    }
}
