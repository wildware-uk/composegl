package composegl.demo

import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
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
import com.badlogic.gdx.math.Vector3
import kotlin.math.abs
import kotlin.system.exitProcess
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

    /** Set by the self-check so a headless run fails the build instead of just printing. */
    var selfCheckFailed = false
        private set

    private lateinit var ui: ComposeOverlay
    private lateinit var state: DemoState
    private lateinit var fontFamily: FontFamily

    private lateinit var modelBatch: ModelBatch
    private lateinit var cubeModel: Model
    private lateinit var cube: ModelInstance
    private lateinit var camera: PerspectiveCamera
    private lateinit var environment: Environment
    private lateinit var panel: InWorldPanel
    private lateinit var panelState: PanelState

    private var cubeAngle = 0f

    private var spinX = 0f
    private var spinY = 0f
    private var lastStatsSampleNanos = 0L
    private var framesDrawn = 0

    /**
     * The game's own input. It only ever sees what the HUD did not consume — and before it spins
     * the cube it offers the event to the in-world panel, because a panel three metres away in
     * the scene is the game's business to hit-test, not ComposeGL's.
     */
    private val gameInput = object : InputAdapter() {
        private var lastX = 0
        private var lastY = 0
        private var draggingPanel = false

        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            lastX = screenX
            lastY = screenY
            val onPanel = panelPixel(screenX, screenY)
            if (onPanel != null) {
                draggingPanel = panel.sendPointer(
                    PointerEventType.Press, onPanel.first, onPanel.second, PointerButton.Primary,
                )
                if (draggingPanel) return true
            }
            return true
        }

        override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
            if (draggingPanel) {
                val onPanel = panelPixel(screenX, screenY) ?: return true
                panel.sendPointer(PointerEventType.Move, onPanel.first, onPanel.second)
                return true
            }
            spinY += (screenX - lastX) * 0.4f
            spinX += (screenY - lastY) * 0.4f
            lastX = screenX
            lastY = screenY
            return true
        }

        override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (draggingPanel) {
                val onPanel = panelPixel(screenX, screenY)
                panel.sendPointer(
                    PointerEventType.Release,
                    onPanel?.first ?: 0f,
                    onPanel?.second ?: 0f,
                    PointerButton.Primary,
                )
                draggingPanel = false
            }
            return true
        }

        override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
            panelPixel(screenX, screenY)?.let { panel.sendPointer(PointerEventType.Move, it.first, it.second) }
            return false
        }
    }

    /** Screen pixel to panel pixel, via a ray into the scene. Null when the ray misses. */
    private fun panelPixel(screenX: Int, screenY: Int): Pair<Float, Float>? =
        panel.pick(camera.getPickRay(screenX.toFloat(), screenY.toFloat()))

    override fun create() {
        state = DemoState()

        // Bundled font: the same HUD then looks the same on every player's machine.
        val bytes = Gdx.files.internal("fonts/DejaVuSans.ttf").readBytes()
        fontFamily = FontFamily(Font(identity = "DejaVuSans", data = bytes))

        ui = ComposeOverlay()
        ui.setContent { Hud(state, fontFamily) }

        // The overlay gets first refusal on every event; the game gets the rest.
        Gdx.input.inputProcessor = InputMultiplexer(ui, gameInput)

        panelState = PanelState()
        panel = InWorldPanel()
        panel.ui.setContent { PanelUi(panelState, fontFamily) }

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
        panel.update(Gdx.graphics.deltaTime)
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
        if (framesDrawn == limit / 2) checkInWorldPicking()
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

        if (!panelState.paused) cubeAngle += panelState.spinSpeed * 4f
        cube.transform.idt()
        cube.transform.rotate(0f, 1f, 0f, spinY + cubeAngle)
        cube.transform.rotate(1f, 0f, 0f, spinX)

        modelBatch.begin(camera)
        modelBatch.render(cube, environment)
        panel.render(modelBatch, camera)
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

    /**
     * Proves the in-world chain end to end without a person clicking: screen point to ray to
     * panel pixel and back again, then a real press on the panel's Pause button.
     */
    private fun checkInWorldPicking() {
        val world = Vector3()
        val screen = Vector3()
        var worstError = 0f
        for (x in listOf(40f, 256f, 470f)) {
            for (y in listOf(30f, 190f, 350f)) {
                panel.worldPointAt(x, y, world)
                camera.project(screen.set(world))
                // LibGDX projects with y up; input arrives with y down.
                val roundTrip = panelPixel(screen.x.toInt(), (Gdx.graphics.height - screen.y).toInt())
                if (roundTrip == null) {
                    println("demo: FAIL picking missed the panel at ($x, $y)")
                    selfCheckFailed = true
                    return
                }
                worstError = maxOf(worstError, abs(roundTrip.first - x), abs(roundTrip.second - y))
            }
        }
        // Screen coordinates are integers and the trip crosses them twice, so a couple of
        // pixels of slack is the arithmetic, not a bug. Anything more is a bug.
        println("demo: picking round-trips within %.1f px".format(worstError))
        if (worstError > 3f) {
            println("demo: FAIL picking is off by more than 3 px")
            selfCheckFailed = true
        }

        // Now click the Pause button for real, through the same path a mouse would take.
        val before = panelState.paused
        val hits = sweepForPauseButton()
        if (panelState.paused != before) {
            println("demo: PASS clicked the in-world Pause button ($hits presses landed on Compose)")
        } else {
            println("demo: FAIL never hit the in-world Pause button ($hits presses landed on Compose)")
            selfCheckFailed = true
        }
    }

    /** Presses across the panel's lower left, where the Pause button lives, until one lands. */
    private fun sweepForPauseButton(): Int {
        val world = Vector3()
        val screen = Vector3()
        var consumed = 0
        val before = panelState.paused
        var x = 30f
        while (x <= 140f) {
            var y = 200f
            while (y <= 300f) {
                panel.worldPointAt(x, y, world)
                camera.project(screen.set(world))
                val screenX = screen.x.toInt()
                val screenY = (Gdx.graphics.height - screen.y).toInt()
                if (gameInput.touchDown(screenX, screenY, 0, 0)) consumed++
                gameInput.touchUp(screenX, screenY, 0, 0)
                if (panelState.paused != before) return consumed
                y += 12f
            }
            x += 12f
        }
        return consumed
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
        panel.dispose()
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
    val demo = Demo()
    Lwjgl3Application(demo, config)
    if (demo.selfCheckFailed) exitProcess(1)
}
