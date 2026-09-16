package dev.wildware.composegl.showcase

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Vector3
import dev.wildware.composegl.game.HitKind
import dev.wildware.composegl.game.SubtitleSize
import dev.wildware.composegl.game.WorldAnchor
import dev.wildware.composegl.game.WorldProjection
import dev.wildware.composegl.gdx.GdxCanvas
import dev.wildware.composegl.gdx.GdxFonts
import dev.wildware.composegl.gdx.GdxKeyboardInput
import dev.wildware.composegl.gdx.GdxPointerInput
import dev.wildware.composegl.gdx.GdxSystemCursor
import dev.wildware.composegl.showcase.ui.HoloScreen
import dev.wildware.composegl.showcase.ui.ShowcaseUi
import dev.wildware.composegl.showcase.world.HoloStand
import dev.wildware.composegl.showcase.world.Particles
import dev.wildware.composegl.showcase.world.Scene3D
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.layout.run
import dev.wildware.composegl.ui.skin.ReloadingSkin
import dev.wildware.composegl.ui.text.scaledTextSizes
import kotlin.random.Random

/**
 * The showcase: a 3D scene, the game-widget tier over it, and an interface standing inside it.
 *
 * The order of a frame is the whole integration, and it is six lines: the scene, the embers, the
 * panel in the world, then the HUD over the top. Nothing in `ui/` knows any of this exists.
 *
 * Run it with `./gradlew :composegl-demo-showcase:run`. `COMPOSEGL_SHOWCASE_SHOT=<path>` draws one
 * frame, saves it and exits, and `COMPOSEGL_SHOWCASE_SHOT_AT` is how long to let it run first.
 * `COMPOSEGL_SHOWCASE_POINTER=x,y[,press]` puts the pointer somewhere, which is the only way to
 * photograph the ray hitting the terminal. `COMPOSEGL_SHOWCASE_SECTION=ui|debug|game` opens that
 * module's section at startup, which is how the three section screenshots are taken.
 */
class Showcase : ApplicationAdapter() {

    private lateinit var fonts: GdxFonts
    private lateinit var skin: ReloadingSkin
    private lateinit var sprites: SpriteBatch
    private lateinit var canvas: GdxCanvas

    /** The whole interface in one call, made once; see [UiRenderer]. */
    private lateinit var ui: UiRenderer
    private lateinit var host: UiHost
    private lateinit var input: ShowcaseInput
    private lateinit var pointerInput: GdxPointerInput
    private lateinit var keyboardInput: GdxKeyboardInput

    private val state = ShowcaseState()
    private val scene = Scene3D()
    private val particles = Particles()
    private val holo = HoloStand()
    private val random = Random(7)

    /** What the interface costs a frame, split three ways. F3 switches it off. */
    val budget = FrameBudget()

    private var viewport = Viewport.oneToOne(Size(1280f, 720f))
    private var elapsed = 0f
    private var sinceHit = 0f
    private var frames = 0

    private val shot: String? = System.getenv("COMPOSEGL_SHOWCASE_SHOT")
    private val shotAt: Float = System.getenv("COMPOSEGL_SHOWCASE_SHOT_AT")?.toFloatOrNull() ?: 0f
    private val scriptedPointer: String? = System.getenv("COMPOSEGL_SHOWCASE_POINTER")

    /** Which module's section to open on the first frame, by its short name. Null is the fight. */
    private val openSection: Module? = System.getenv("COMPOSEGL_SHOWCASE_SECTION")
        ?.let { name -> Module.entries.firstOrNull { it.tab.equals(name, ignoreCase = true) } }

    /**
     * The game's camera, as the one function the toolkit asks for: where on screen is this point,
     * and is it on screen at all.
     */
    private val projection = WorldProjection { point, _, onto ->
        val projected = Vector3(point.x, point.y, point.z)
        scene.camera.project(projected)
        // LibGDX projects with y up from the bottom; an interface measures down from the top. The
        // third number is how far away it is, in the game's own units — metres here — which is
        // what the marker layer fades tags by and stacks them in.
        onto.set(
            projected.x,
            Gdx.graphics.height - projected.y,
            scene.camera.position.dst(point.x, point.y, point.z),
        )
        projected.z <= 1f
    }

    override fun create() {
        fonts = GdxFonts()
        val file = Gdx.files.internal("fonts/DejaVuSans.ttf")
        // Every size the skin file asks for, and every size the subtitle presets can turn 16 into.
        // A size nobody registered is not a smaller word, it is a showcase that will not start, so
        // the list has to follow the skin and the settings both.
        val sizes = (ShowcaseTextSizes + scaledTextSizes(listOf(16), SubtitleSize.scales))
            .distinct()
            .sorted()
        fonts.registerTrueType("body", file, sizes)
        fonts.registerTrueType("display", file, listOf(34))

        skin = showcaseSkin(fonts)
        sprites = SpriteBatch()
        canvas = GdxCanvas(sprites, fonts.atlas)

        scene.create()
        particles.create()
        holo.create()

        scene.drones.forEach { state.targets.add(TargetReadout(it.callsign)) }

        state.section = openSection
        // A photograph of a section is a picture of that page, so the two floating windows and the
        // target panel, which a player would drag out of the way or close, are put away for it.
        // Only while a shot is being taken: running the showcase by hand leaves them where they are.
        if (openSection != null && shot != null) {
            state.tuningOpen = false
            if (state.isOn(Exhibit.Nodes)) state.toggle(Exhibit.Nodes)
        }

        host = UiHost()
        host.setContent { ShowcaseUi(state, fonts, skin.skin, projection, budget) }
        holo.panel.setContent { HoloScreen(state, fonts, skin.skin) }

        input = ShowcaseInput(
            host.root,
            holo,
            budget,
            ray = { x, y ->
                val ray = scene.camera.getPickRay(x, y)
                Vector3(ray.origin) to Vector3(ray.direction)
            },
            cursor = GdxSystemCursor(),
        )
        pointerInput = GdxPointerInput(input, { viewport })
        keyboardInput = GdxKeyboardInput(input)
        Gdx.input.inputProcessor = InputMultiplexer(pointerInput, keyboardInput)

        // Last, because it needs the host and the canvas. Set once rather than passed per frame:
        // hit testing needs positions, so input cannot be told about a frame until the tree has
        // been laid out.
        ui = UiRenderer(host, canvas, budget)
        ui.onLaidOut = { millis ->
            scriptedPointer?.let { pretendPointerIsAt(it) }
            input.frame(millis)
        }
    }

    override fun resize(width: Int, height: Int) {
        scene.resize(width, height)
    }

    override fun render() {
        val delta = Gdx.graphics.deltaTime
        elapsed += delta

        scene.update(delta)
        if (state.isOn(Exhibit.Particles)) particles.update(delta)
        readScene()
        fireAtSomething(delta)

        skin.reloadIfChanged()
        viewport = Viewport.oneToOne(
            Size(Gdx.graphics.backBufferWidth.toFloat(), Gdx.graphics.backBufferHeight.toFloat()),
        )

        Gdx.gl.glClearColor(0.03f, 0.04f, 0.06f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        // The scene, then the embers in it, then the panel standing in it, then the HUD over it.
        scene.render()
        if (state.isOn(Exhibit.Particles)) particles.render(scene.camera)

        if (state.isOn(Exhibit.Holo)) {
            if (holo.update(canvas, scene.time, System.nanoTime())) state.holoDraws++
            holo.render(scene.camera)
        }

        // The whole interface: recompose, lay out, hand input the positions, draw, and time all
        // three. See UiRenderer. The scene above is drawn straight to GL rather than through the
        // canvas, so it does not need to be inside the canvas's own frame.
        ui.render(viewport, System.nanoTime())

        frames++
        if (shot != null && frames >= 2 && elapsed >= shotAt) {
            save(shot)
            Gdx.app.exit()
        }
    }

    /** What the interface is told about the world: positions, bearings and how hurt things are. */
    private fun readScene() {
        val camera = scene.camera
        state.heading = Math.toDegrees(kotlin.math.atan2(camera.direction.x.toDouble(), -camera.direction.z.toDouble())).toFloat()

        scene.drones.forEachIndexed { index, drone ->
            val readout = state.targets[index]
            readout.shield = drone.shield
            readout.integrity = drone.integrity
            readout.screenX = drone.screen.x
            readout.screenY = drone.screen.y
            readout.distance = camera.position.dst(drone.position)
            readout.onScreen = drone.screen.x in 0f..Gdx.graphics.width.toFloat() &&
                drone.screen.y in 0f..Gdx.graphics.height.toFloat()
            // The radar is the world from above, with north up; the frame turns it.
            readout.mapX = drone.position.x
            readout.mapY = drone.position.z
            // And where it really is, for the tag pinned to it: the layer does its own projecting.
            readout.worldX = drone.position.x
            readout.worldY = drone.position.y
            readout.worldZ = drone.position.z
        }

        // Whatever is nearest the middle of the screen is what the reticle is on.
        val middleX = Gdx.graphics.width / 2f
        val middleY = Gdx.graphics.height / 2f
        state.locked = state.targets.indices.minByOrNull { index ->
            val readout = state.targets[index]
            if (!readout.onScreen) Float.MAX_VALUE
            else kotlin.math.hypot(readout.screenX - middleX, readout.screenY - middleY)
        } ?: -1

        state.hull = (0.55f + 0.3f * kotlin.math.sin(elapsed * 0.21f))
        state.heat = (state.heat - 0.12f * Gdx.graphics.deltaTime).coerceAtLeast(0.05f)
        // Out and back over six seconds, so the dissolve is always mid-way through when somebody
        // looks at it — and so a screenshot taken at a fixed moment always shows it happening.
        state.dissolve = kotlin.math.abs(((elapsed * 0.33f) % 2f) - 1f)
    }

    /** Something is always being shot at, because a demo with nothing happening proves nothing. */
    private fun fireAtSomething(delta: Float) {
        if (!state.isOn(Exhibit.Damage)) return
        sinceHit += delta
        // Everything about the rate is the tuning window's, and "now" is its button.
        val now = state.fireNow
        if (now) state.fireNow = false
        if (!now && (state.holdFire || sinceHit < state.pace.seconds)) return
        sinceHit = 0f

        val index = random.nextInt(scene.drones.size)
        val drone = scene.drones[index]
        val critical = random.nextInt(5) == 0
        val rolled = if (critical) random.nextInt(180, 340) else random.nextInt(20, 90)
        val amount = (rolled * state.damageScale).toInt()
        // An anchor rather than a position: the drone is moving, and the number should follow it.
        state.damage.show(
            if (critical) "$amount!" else "$amount",
            WorldAnchor { it.set(drone.position.x, drone.position.y + 0.35f, drone.position.z) },
            critical,
        )
        // The sparks are in the interface's own coordinates, so the game hands over where the
        // drone is on screen — which it already worked out for the readouts.
        if (state.isOn(Exhibit.Sparks)) {
            val readout = state.targets[index]
            if (readout.onScreen) {
                val burst = if (critical) state.sparkBurst * 2 else state.sparkBurst
                state.sparks.burst(burst, readout.screenX, readout.screenY, HitSparks.copy(colour = state.sparkTint))
            }
        }

        // The player's own end of it: the shot landed, so the marker flashes, and one drone in
        // three shoots back from wherever it happens to be.
        state.hitMarker.hit(if (critical) HitKind.Critical else HitKind.Normal)
        if (random.nextInt(3) == 0) shootBack(state.targets[index])

        state.ammo = (state.ammo - 1).coerceAtLeast(0)
    }

    /**
     * Return fire, as the arcs read it.
     *
     * The bearing is worked out the way a game with a camera works one out — where the shooter is
     * against where the player is looking — except that this camera is already pointing down the
     * middle of the screen, so the drone's own place on screen is that answer.
     */
    private fun shootBack(shooter: TargetReadout) {
        if (!shooter.onScreen) return
        val acrossScreen = shooter.screenX - Gdx.graphics.width / 2f
        val upScreen = Gdx.graphics.height / 2f - shooter.screenY
        val bearing = kotlin.math.atan2(acrossScreen, upScreen) * 180f / kotlin.math.PI.toFloat()
        state.incoming.hit(fromAngle = bearing, strength = 0.35f + random.nextFloat() * 0.65f)
    }

    /** Puts the pointer somewhere without a mouse, so a screenshot can show the ray landing. */
    private fun pretendPointerIsAt(where: String) {
        val parts = where.split(',')
        val x = parts[0].trim().toFloat()
        val y = parts[1].trim().toFloat()
        input.onPointer(dev.wildware.composegl.ui.input.PointerEvent.Move(dev.wildware.composegl.ui.input.PointerId.Mouse, dev.wildware.composegl.ui.geometry.Offset(x, y)))
        if (parts.size > 2 && parts[2].trim() == "press") {
            input.onPointer(dev.wildware.composegl.ui.input.PointerEvent.Press(dev.wildware.composegl.ui.input.PointerId.Mouse, dev.wildware.composegl.ui.geometry.Offset(x, y)))
        }
    }

    private fun save(path: String) {
        val frame = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        val upright = Pixmap(frame.width, frame.height, Pixmap.Format.RGBA8888)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                upright.drawPixel(x, y, frame.getPixel(x, frame.height - 1 - y))
            }
        }
        PixmapIO.writePNG(Gdx.files.absolute(path), upright)
        frame.dispose()
        upright.dispose()
        println("wrote $path (${canvas.drawCalls} draw calls, ${state.holoDraws} panel redraws)")
    }

    fun windowLostFocus() {
        if (!::pointerInput.isInitialized) return
        pointerInput.cancelAll()
        keyboardInput.releaseAll()
        input.windowLostFocus()
    }

    override fun dispose() {
        host.dispose()
        holo.dispose()
        particles.dispose()
        scene.dispose()
        canvas.dispose()
        sprites.dispose()
        fonts.dispose()
    }
}

fun main() {
    val showcase = Showcase()
    val configuration = Lwjgl3ApplicationConfiguration().apply {
        setTitle("ComposeGL - showcase")
        setWindowedMode(1280, 720)
        useVsync(true)
        setBackBufferConfig(8, 8, 8, 8, 16, 0, 0)
        setWindowListener(object : Lwjgl3WindowAdapter() {
            override fun focusLost() = showcase.windowLostFocus()
        })
    }
    Lwjgl3Application(showcase, configuration)
}
