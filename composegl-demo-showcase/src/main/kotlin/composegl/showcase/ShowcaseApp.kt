package composegl.showcase

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector3
import composegl.gdx.ComposeGdx
import composegl.gdx.ComposeOverlay
import composegl.showcase.ui.HoloTerminal
import composegl.showcase.ui.ShowcaseUi
import composegl.showcase.world.HoloPanel
import composegl.showcase.world.Particles
import composegl.showcase.world.Scene3D

/**
 * A showcase of game-shaped interfaces, all of them Compose, all of them inside one OpenGL frame.
 *
 * The draw order is the whole trick, and it is three lines long:
 *
 * 1. the 3D scene, with depth,
 * 2. the particles, additively, so they glow through it,
 * 3. the hologram, a Compose texture on a panel in the world,
 * 4. the overlay, a Compose surface blitted over the lot.
 *
 * Nothing in the scene knows Compose exists, and nothing in the interface knows about OpenGL.
 */
class ShowcaseApp : ApplicationAdapter() {

    lateinit var state: ShowcaseState
        private set

    private lateinit var ui: ComposeOverlay
    private lateinit var scene: Scene3D
    private lateinit var particles: Particles
    private lateinit var holo: HoloPanel
    private lateinit var fontFamily: FontFamily

    private var framesDrawn = 0
    private var secondsToNextHit = 0.6f
    private val scratch = Vector3()

    var selfCheckFailed = false
        private set

    private val gameInput = object : InputAdapter() {
        override fun keyDown(keycode: Int): Boolean = when (keycode) {
            Input.Keys.NUM_1 -> { state.abilities[0].trigger(); true }
            Input.Keys.NUM_2 -> { state.abilities[1].trigger(); true }
            Input.Keys.NUM_3 -> { state.abilities[2].trigger(); true }
            Input.Keys.NUM_4 -> { state.abilities[3].trigger(); true }
            else -> false
        }

        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            // Shooting at the world spawns a number where the shot landed.
            spawnHit(critical = button == Input.Buttons.RIGHT)
            return true
        }
    }

    override fun create() {
        state = ShowcaseState()

        val bytes = Gdx.files.internal("fonts/DejaVuSans.ttf").readBytes()
        fontFamily = FontFamily(Font(identity = "DejaVuSans", data = bytes))

        scene = Scene3D().also { it.create() }
        particles = Particles().also { it.create() }
        holo = HoloPanel().also { it.create() }
        holo.surface.setContent { HoloTerminal(state, fontFamily) }

        scene.drones.forEach { state.targets.add(TargetReadout(it.callsign)) }

        ui = ComposeOverlay()
        ui.setContent { ShowcaseUi(state, fontFamily) }

        Gdx.input.inputProcessor = InputMultiplexer(ui, gameInput)
    }

    override fun resize(width: Int, height: Int) {
        ui.resize(width, height)
        scene.resize(width, height)
    }

    override fun render() {
        val delta = Gdx.graphics.deltaTime.coerceAtMost(0.1f)

        scene.update(delta)
        particles.update(delta)
        holo.update(scene.time)
        syncState(delta)

        Gdx.gl.glClearColor(0.015f, 0.025f, 0.04f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        scene.render()
        if (state.isOn(Exhibit.Particles)) particles.render(scene.camera)
        if (state.isOn(Exhibit.Holo)) holo.render(scene.camera)

        ui.update()
        ui.draw()

        framesDrawn++
        maybeSelfCheck()
    }

    /**
     * Copies what the scene knows into Compose state, once a frame.
     *
     * The projection is the only fiddly part: LibGDX gives screen positions in *logical* window
     * pixels, and the Compose surface is the size of the framebuffer, so on a high-DPI display
     * they differ by the density. Multiplying here means the interface can position itself in
     * plain pixels and be right on every monitor.
     */
    private fun syncState(delta: Float) {
        val density = Gdx.graphics.backBufferWidth.toFloat() / Gdx.graphics.width
        state.time = scene.time

        scene.drones.forEachIndexed { index, drone ->
            val readout = state.targets[index]
            readout.shield = drone.shield
            readout.integrity = drone.integrity
            readout.screenX = drone.screen.x * density
            readout.screenY = drone.screen.y * density
            readout.distance = scene.camera.position.dst(drone.position)
            readout.onScreen = drone.screen.z < 1f
            // Bearing for the radar, relative to where the camera is looking.
            val toDrone = scratch.set(drone.position).sub(scene.camera.position)
            readout.bearing = MathUtils.atan2(toDrone.z, toDrone.x) -
                MathUtils.atan2(scene.camera.direction.z, scene.camera.direction.x) -
                MathUtils.PI / 2f
        }

        state.abilities.forEach { it.tick(delta) }

        // A little life in the player's own readouts.
        state.hull = 0.6f + 0.25f * MathUtils.sin(scene.time * 0.23f)
        state.heat = 0.4f + 0.45f * MathUtils.sin(scene.time * 0.61f + 1f)

        advanceDamageNumbers(delta, density)

        secondsToNextHit -= delta
        if (secondsToNextHit <= 0f) {
            spawnHit(critical = MathUtils.randomBoolean(0.25f))
            secondsToNextHit = MathUtils.random(0.35f, 1.1f)
        }

        val stats = ui.stats
        state.composeRenders = stats.composeRenders
        state.gameFrames = stats.frames
        state.lastRenderMicros = stats.lastRenderNanos / 1000
    }

    private fun advanceDamageNumbers(delta: Float, density: Float) {
        val iterator = state.damageNumbers.listIterator()
        while (iterator.hasNext()) {
            val number = iterator.next()
            number.age += delta
            if (number.done) {
                iterator.remove()
                continue
            }
            scratch.set(number.worldX, number.worldY, number.worldZ)
            // Numbers drift upward in the world as well as on screen.
            scratch.y += number.age * 0.35f
            scene.camera.project(scratch)
            number.visible = scratch.z < 1f
            number.screenX = scratch.x * density
            number.screenY = (Gdx.graphics.height - scratch.y) * density
        }
    }

    private fun spawnHit(critical: Boolean) {
        val drone = scene.drones.random()
        state.damageNumbers.add(
            DamageNumber(
                worldX = drone.position.x + MathUtils.random(-0.25f, 0.25f),
                worldY = drone.position.y + MathUtils.random(-0.1f, 0.3f),
                worldZ = drone.position.z + MathUtils.random(-0.25f, 0.25f),
                amount = if (critical) MathUtils.random(180, 340) else MathUtils.random(20, 90),
                critical = critical,
            ),
        )
        state.ammo = (state.ammo - 1).coerceAtLeast(0)
        if (state.ammo == 0) state.ammo = 148
    }

    override fun dispose() {
        ui.dispose()
        holo.dispose()
        ComposeGdx.dispose()
        particles.dispose()
        scene.dispose()
    }

    /** Lets CI run the showcase without anybody watching it. */
    private fun maybeSelfCheck() {
        val limit = System.getenv("COMPOSEGL_SHOWCASE_FRAMES")?.toIntOrNull() ?: return

        System.getenv("COMPOSEGL_SHOWCASE_SHOT_AT")?.toIntOrNull()?.let {
            if (framesDrawn == it) saveScreenshot()
        }

        if (framesDrawn == 30) {
            state.abilities.forEach { it.trigger() }
        }

        if (framesDrawn >= limit) {
            println(
                "showcase: $framesDrawn frames, ${state.damageNumbers.size} numbers alive, " +
                    "${ui.stats.composeRenders} compose renders, " +
                    "last render ${ui.stats.lastRenderNanos / 1000} us",
            )
            if (state.targets.none { it.onScreen }) {
                println("showcase: FAIL nothing was on screen")
                selfCheckFailed = true
            }
            if (ui.stats.composeRenders < 10) {
                println("showcase: FAIL the interface barely drew (${ui.stats.composeRenders})")
                selfCheckFailed = true
            }
            Gdx.app.exit()
        }
    }

    private fun saveScreenshot() {
        val path = System.getenv("COMPOSEGL_SHOWCASE_SCREENSHOT") ?: "showcase.png"
        val source = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        val flipped = Pixmap(source.width, source.height, source.format)
        for (y in 0 until source.height) {
            flipped.drawPixmap(source, 0, y, 0, source.height - 1 - y, source.width, 1)
        }
        source.dispose()
        val file = if (path.startsWith("/")) Gdx.files.absolute(path) else Gdx.files.local(path)
        PixmapIO.writePNG(file, flipped)
        flipped.dispose()
        println("showcase: screenshot -> $path")
    }
}
