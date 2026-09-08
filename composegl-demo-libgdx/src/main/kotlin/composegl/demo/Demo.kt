package composegl.demo

import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import composegl.gdx.ComposeGdx
import composegl.gdx.ComposeOverlay

/**
 * A spinning cube with a Material 3 HUD on top.
 *
 * What to try:
 *
 * - Click the button. The counter goes up and the cube does not move.
 * - Drag anywhere the HUD is not. The cube spins and the button is unbothered.
 * - Type in the text field, then press Escape or click the world; WASD goes back to the game.
 * - Watch the counters in the corner. Leave the HUD alone and "compose renders" stops moving
 *   while "game frames" races away. That is the whole point of the library.
 */
class Demo : ApplicationAdapter() {

    private lateinit var ui: ComposeOverlay
    private lateinit var state: DemoState
    private lateinit var fontFamily: FontFamily

    private lateinit var modelBatch: ModelBatch
    private lateinit var cubeModel: Model
    private lateinit var cube: ModelInstance
    private lateinit var camera: PerspectiveCamera
    private lateinit var environment: Environment

    private var spinX = 0f
    private var spinY = 0f
    private var lastStatsSampleNanos = 0L
    private var framesDrawn = 0

    /** The game's own input. It only ever sees what the HUD did not consume. */
    private val gameInput = object : InputAdapter() {
        private var lastX = 0
        private var lastY = 0

        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            lastX = screenX
            lastY = screenY
            return true
        }

        override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
            spinY += (screenX - lastX) * 0.4f
            spinX += (screenY - lastY) * 0.4f
            lastX = screenX
            lastY = screenY
            return true
        }
    }

    override fun create() {
        state = DemoState()

        // Bundled font: the same HUD then looks the same on every player's machine.
        val bytes = Gdx.files.internal("fonts/DejaVuSans.ttf").readBytes()
        fontFamily = FontFamily(Font(identity = "DejaVuSans", data = bytes))

        ui = ComposeOverlay()
        ui.setContent { Hud(state, fontFamily) }

        // The overlay gets first refusal on every event; the game gets the rest.
        Gdx.input.inputProcessor = InputMultiplexer(ui, gameInput)

        modelBatch = ModelBatch()
        cubeModel = ModelBuilder().createBox(
            2f, 2f, 2f,
            Material(ColorAttribute.createDiffuse(Color.valueOf("4CAF50"))),
            (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong(),
        )
        cube = ModelInstance(cubeModel)
        camera = PerspectiveCamera(60f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat()).apply {
            position.set(4f, 3f, 4f)
            lookAt(0f, 0f, 0f)
            near = 0.1f
            far = 100f
            update()
        }
        lastStatsSampleNanos = System.nanoTime()
        environment = Environment().apply {
            set(ColorAttribute(ColorAttribute.AmbientLight, 0.5f, 0.5f, 0.55f, 1f))
            add(DirectionalLight().set(0.9f, 0.9f, 0.85f, -1f, -0.8f, -0.6f))
        }
    }

    override fun resize(width: Int, height: Int) {
        ui.resize(width, height)
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
    }

    override fun render() {
        drawWorld()
        sampleStats()
        ui.update()
        ui.draw()
        maybeCaptureAndExit()
    }

    /**
     * A way to run the demo without a person watching it: set `COMPOSEGL_DEMO_FRAMES` and it
     * draws that many frames, saves a screenshot next to the working directory, prints the stats
     * and quits. Used to smoke-test the demo on CI, where nobody can look at the window.
     */
    private fun maybeCaptureAndExit() {
        val limit = System.getenv("COMPOSEGL_DEMO_FRAMES")?.toIntOrNull() ?: return
        framesDrawn++
        if (framesDrawn < limit) return

        // glReadPixels hands back rows bottom-up, so flip before saving or the PNG is upside down.
        val pixmap = flipVertically(
            Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight),
        )
        val path = System.getenv("COMPOSEGL_DEMO_SCREENSHOT") ?: "demo.png"
        val file = if (path.startsWith("/")) Gdx.files.absolute(path) else Gdx.files.local(path)
        PixmapIO.writePNG(file, pixmap)
        pixmap.dispose()
        println("demo: $framesDrawn frames, ${ui.stats.composeRenders} compose renders, screenshot -> $path")
        Gdx.app.exit()
    }

    private fun drawWorld() {
        Gdx.gl.glClearColor(0.06f, 0.07f, 0.10f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)

        cube.transform.idt()
        cube.transform.rotate(0f, 1f, 0f, spinY + Gdx.graphics.frameId * 0.25f)
        cube.transform.rotate(1f, 0f, 0f, spinX)

        modelBatch.begin(camera)
        modelBatch.render(cube, environment)
        modelBatch.end()

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
    }

    /**
     * Once a second, not every frame. Writing to Compose state invalidates the HUD, so a
     * per-frame counter would make the HUD redraw every frame and quietly disprove the very
     * thing it is there to show.
     */
    private fun sampleStats() {
        val now = System.nanoTime()
        if (now - lastStatsSampleNanos < 500_000_000L) return
        lastStatsSampleNanos = now
        state.stats = ui.stats
    }

    private fun flipVertically(source: Pixmap): Pixmap {
        val flipped = Pixmap(source.width, source.height, source.format)
        for (y in 0 until source.height) {
            flipped.drawPixmap(source, 0, y, 0, source.height - 1 - y, source.width, 1)
        }
        source.dispose()
        return flipped
    }

    override fun dispose() {
        ui.dispose()
        ComposeGdx.dispose()
        modelBatch.dispose()
        cubeModel.dispose()
    }
}

fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("ComposeGL demo")
        setWindowedMode(1280, 720)
        // ComposeGL needs a GL 3.0+ context. This is the line people forget.
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL32, 3, 2)
    }
    Lwjgl3Application(Demo(), config)
}
