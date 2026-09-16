package dev.wildware.composegl.showcase.world

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
    /** What it is painted, so a view of one drone on its own says which drone it is. */
    val colour: String,
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

    /** Which way it is flying, one unit long: the way a camera behind it looks. */
    val heading = Vector3(1f, 0f, 0f)

    private val was = Vector3()

    fun update(time: Float) {
        was.set(position)
        val angle = time * speed + phase
        position.set(
            MathUtils.cos(angle) * radius,
            1.4f + MathUtils.sin(time * 0.8f + phase) * 0.35f + heightOffset,
            MathUtils.sin(angle) * radius,
        )
        if (!was.isZero && !was.epsilonEquals(position, 0.0001f)) heading.set(position).sub(was).nor()
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
        Drone("VESPER", "6fd3ff", radius = 4.2f, speed = 0.35f, phase = 0f, heightOffset = 0.3f),
        Drone("KESTREL", "ffb454", radius = 5.6f, speed = -0.24f, phase = 2.1f, heightOffset = 0.9f),
        Drone("MERLIN", "7ee08a", radius = 3.1f, speed = 0.47f, phase = 4.0f, heightOffset = -0.2f),
    )

    private lateinit var modelBatch: ModelBatch
    private lateinit var environment: Environment
    private lateinit var droneModels: List<Model>
    private lateinit var pedestalModel: Model
    private lateinit var droneInstances: List<ModelInstance>
    private lateinit var pedestal: ModelInstance
    private lateinit var shapes: ShapeRenderer

    /**
     * The camera the interface's scene views are filmed through: a second one, so a view never
     * moves the camera the screen itself is drawn with. See [renderOrbit], [renderModel] and
     * [renderChase].
     */
    private lateinit var viewCamera: PerspectiveCamera

    /** One drone on its own, for a preview; its own instance, so turning it moves nothing in the world. */
    private lateinit var modelInstances: List<ModelInstance>

    var time = 0f
        private set

    fun create() {
        camera = PerspectiveCamera(60f, 1280f, 800f)
        viewCamera = PerspectiveCamera(50f, 1f, 1f).apply {
            near = 0.1f
            far = 120f
        }
        modelBatch = ModelBatch()
        shapes = ShapeRenderer()

        val builder = ModelBuilder()
        val attributes = (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong()
        droneModels = drones.map { drone ->
            builder.createBox(
                0.5f, 0.5f, 0.5f,
                Material(ColorAttribute.createDiffuse(Color.valueOf(drone.colour))),
                attributes,
            )
        }
        pedestalModel = builder.createCylinder(
            2.6f, 0.25f, 2.6f, 32,
            Material(ColorAttribute.createDiffuse(Color.valueOf("1b2534"))),
            attributes,
        )

        droneInstances = droneModels.map { ModelInstance(it) }
        modelInstances = droneModels.map { ModelInstance(it) }
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

    fun render() = render(camera)

    /**
     * The whole world through [camera]: the floor, the pedestal and the drones, depth tested. Draws
     * into whatever framebuffer is bound, over the viewport already set.
     */
    private fun render(camera: PerspectiveCamera) {
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        drawFloor(camera)
        modelBatch.begin(camera)
        modelBatch.render(pedestal, environment)
        droneInstances.forEach { modelBatch.render(it, environment) }
        modelBatch.end()
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
    }

    /**
     * The world from a camera orbiting its middle, into a picture [width] by [height] pixels: the
     * level editor's view. [yaw] and [pitch] are degrees, [distance] metres.
     */
    fun renderOrbit(width: Int, height: Int, yaw: Float, pitch: Float, distance: Float) {
        val flat = distance * MathUtils.cosDeg(pitch)
        viewCamera.position.set(
            MathUtils.cosDeg(yaw) * flat,
            Middle.y + distance * MathUtils.sinDeg(pitch),
            MathUtils.sinDeg(yaw) * flat,
        )
        aim(width, height, Middle)
        render(viewCamera)
    }

    /**
     * Drone [index] on its own, close up, turned [turn] degrees: a preview. Nothing else in the
     * world is drawn, so the picture's clear colour is its background.
     */
    fun renderModel(width: Int, height: Int, index: Int, turn: Float) {
        val instance = modelInstances.getOrNull(index) ?: return
        // A corner towards the camera and tipped forward, so three faces show even standing still.
        instance.transform.setToRotation(Vector3.Y, turn + 35f).rotate(Vector3.X, 25f)
        viewCamera.position.set(0f, 0.55f, 1.1f)
        aim(width, height, Vector3.Zero)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        modelBatch.begin(viewCamera)
        modelBatch.render(instance, environment)
        modelBatch.end()
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
    }

    /** The world from just behind drone [index] and a little above, looking where it flies. */
    fun renderChase(width: Int, height: Int, index: Int) {
        val drone = drones.getOrNull(index) ?: return
        viewCamera.position.set(drone.heading).scl(-2.6f).add(drone.position).add(0f, 0.7f, 0f)
        aim(width, height, ahead.set(drone.heading).scl(3f).add(drone.position))
        render(viewCamera)
    }

    private val ahead = Vector3()

    private fun aim(width: Int, height: Int, at: Vector3) {
        viewCamera.viewportWidth = width.toFloat()
        viewCamera.viewportHeight = height.toFloat()
        viewCamera.up.set(Vector3.Y)
        viewCamera.lookAt(at)
        viewCamera.up.set(Vector3.Y)
        viewCamera.update()
    }

    private fun drawFloor(camera: PerspectiveCamera) {
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
        droneModels.forEach { it.dispose() }
        pedestalModel.dispose()
        shapes.dispose()
    }

    private companion object {
        const val GRID_EXTENT = 28
        const val GRID_STEP = 1f
        val GRID_COLOUR: Color = Color.valueOf("4fb0d8")

        /** What the orbiting camera looks at: the middle of the pedestal, at drone height. */
        val Middle = Vector3(0f, 1.4f, 0f)
    }
}
