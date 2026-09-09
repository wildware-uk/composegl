package spike.rt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.math.MathUtils
import kotlin.math.roundToInt

/** State the interface reads. Ordinary Compose state — the runtime does not know it is in a game. */
class HudState {
    var health by mutableStateOf(1f)
    var score by mutableStateOf(0)
    var animating by mutableStateOf(false)
    val log = mutableStateListOf<String>()
}

@Composable
fun Hud(state: HudState, frames: () -> Long) {
    if (state.animating) {
        LaunchedEffect(Unit) {
            // A frame-driven animation, exactly as it would be written in Compose UI.
            while (true) {
                withFrameNanos { nanos ->
                    state.health = 0.5f + 0.5f * MathUtils.sin(nanos / 1_000_000_000f * 1.5f)
                }
            }
        }
    }

    Column(offsetX = 24f, offsetY = 24f, padding = 14f, gap = 8f, background = 0xCC0B141Bu.toLong(), border = 0xFF39C0ED) {
        Text("COMPOSE RUNTIME - NO COMPOSE UI", size = 20f, colour = 0xFF39C0ED)
        Text("SCORE  ${state.score}", size = 28f)
        Bar(state.health, width = 260f, height = 12f)
        Text("HULL  ${(state.health * 100).roundToInt()}%", size = 16f, colour = 0xFFB6D8E8)
    }

    // Never changes after the first composition. If this cost anything per frame, the spike failed.
    Column(offsetX = 24f, offsetY = 210f, padding = 12f, gap = 4f, background = 0x880B141Bu.toLong()) {
        Text("STATIC PANEL", size = 16f, colour = 0xFF8AA6B8)
        repeat(6) { Text("· system nominal", size = 14f, colour = 0xFF6E8798) }
    }

    Column(offsetX = 520f, offsetY = 24f, padding = 10f, gap = 3f, background = 0x880B141Bu.toLong()) {
        Text("EVENT LOG", size = 16f, colour = 0xFF8AA6B8)
        state.log.forEach { Text(it, size = 14f, colour = 0xFFDFF6FF) }
    }

    Row(offsetX = 24f, offsetY = 420f, gap = 10f) {
        Button("HIT") {
            state.health = (state.health - 0.1f).coerceAtLeast(0f)
            state.score += 10
            state.log.add(0, "hit  frame ${frames()}")
            if (state.log.size > 8) state.log.removeAt(state.log.lastIndex)
        }
        Button("HEAL") { state.health = (state.health + 0.15f).coerceAtMost(1f) }
        Button(if (state.animating) "STOP ANIM" else "START ANIM") { state.animating = !state.animating }
        Button("CLEAR") { state.log.clear() }
    }
}

class SpikeApp : ApplicationAdapter() {

    private lateinit var compose: GlCompose
    private lateinit var renderer: Renderer
    private lateinit var shapes: ShapeRenderer
    private val state = HudState()

    private var frames = 0L
    private var dirtyFrames = 0L
    private var framesWhileStatic = 0L
    private var redrawsWhileStatic = 0L
    private var framesWhileAnimating = 0L
    private var redrawsWhileAnimating = 0L
    private var time = 0f
    private var clockNanos = 0L
    private var laidOut = false
    private var lastWidth = 0f
    private var lastHeight = 0f

    override fun create() {
        renderer = Renderer()
        shapes = ShapeRenderer()
        compose = GlCompose()
        compose.setContent { Hud(state) { frames } }

        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                val hit = compose.root.hitTest(screenX.toFloat(), screenY.toFloat()) ?: return false
                hit.style.onClick?.invoke()
                return true
            }

            override fun keyDown(keycode: Int): Boolean {
                if (keycode == Input.Keys.SPACE) {
                    state.animating = !state.animating
                    return true
                }
                return false
            }
        }
    }

    override fun render() {
        time += Gdx.graphics.deltaTime
        frames++

        Gdx.gl.glClearColor(0.04f, 0.06f, 0.09f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        drawScenery()

        script()
        // A clock that starts at zero. `System.nanoTime()` is a huge arbitrary number, and a float
        // that big cannot resolve a sixtieth of a second — the animation would move in visible steps.
        clockNanos += (Gdx.graphics.deltaTime * 1_000_000_000f).toLong()
        val changed = compose.frame(clockNanos)
        if (changed) {
            dirtyFrames++
            if (state.animating) redrawsWhileAnimating++ else redrawsWhileStatic++
        }
        if (state.animating) framesWhileAnimating++ else framesWhileStatic++

        renderer.screenWidth = Gdx.graphics.width.toFloat()
        renderer.screenHeight = Gdx.graphics.height.toFloat()
        val resized = renderer.screenWidth != lastWidth || renderer.screenHeight != lastHeight
        lastWidth = renderer.screenWidth
        lastHeight = renderer.screenHeight
        if (changed || resized || !laidOut) {
            Layout.run(compose.root, renderer.screenWidth, renderer.screenHeight, renderer)
            laidOut = true
        }
        renderer.draw(compose.root)

        drawStats()
        capture()
    }

    /**
     * Drives the interface on a headless run: some clicks, then the animation switched on.
     *
     * Without this the automated run would only ever measure a still interface, which is the easy
     * half of the question.
     */
    private fun script() {
        if (System.getenv("SPIKE_S6_SCRIPT") == null) return
        when (frames) {
            40L, 45L, 50L -> compose.root.forEach { if (it.style.text == "HIT") it.style.onClick?.invoke() }
            120L -> state.animating = true
        }
    }

    /**
     * Runs the window for a fixed number of frames, saves a screenshot and reports the idle share.
     *
     * This is how the spike is checked on a machine with no screen: `SPIKE_S6_FRAMES=240` under
     * Xvfb draws a real interface with real OpenGL and prints the one number the spike is about.
     */
    private fun capture() {
        val target = System.getenv("SPIKE_S6_FRAMES")?.toIntOrNull() ?: return
        if (frames < target) return
        // glReadPixels hands back rows bottom-up; flip so the PNG is the right way up.
        val source = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        val flipped = Pixmap(source.width, source.height, source.format)
        for (y in 0 until source.height) {
            flipped.drawPixmap(source, 0, y, 0, source.height - 1 - y, source.width, 1)
        }
        source.dispose()
        val path = System.getenv("SPIKE_S6_SHOT") ?: "s6.png"
        PixmapIO.writePNG(if (path.startsWith("/")) Gdx.files.absolute(path) else Gdx.files.local(path), flipped)
        flipped.dispose()
        println("frames=$frames redraws=$dirtyFrames idle=${frames - dirtyFrames} (${(frames - dirtyFrames) * 100 / frames}%)")
        println("still:     $framesWhileStatic frames, $redrawsWhileStatic redraws")
        println("animating: $framesWhileAnimating frames, $redrawsWhileAnimating redraws")
        Gdx.app.exit()
    }

    /** Something moving underneath, so it is obvious the interface is not a full-screen blit. */
    private fun drawScenery() {
        shapes.projectionMatrix.setToOrtho2D(0f, 0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.setColor(0.1f, 0.3f, 0.45f, 1f)
        val centreX = Gdx.graphics.width / 2f
        val centreY = Gdx.graphics.height / 2f
        repeat(24) { index ->
            val angle = time * 20f + index * 15f
            val radius = 120f + index * 9f
            shapes.circle(
                centreX + MathUtils.cosDeg(angle) * 40f,
                centreY + MathUtils.sinDeg(angle) * 40f,
                radius,
                48,
            )
        }
        shapes.end()
    }

    /**
     * Counters drawn outside the tree.
     *
     * Deliberately not a `GlNode`: writing to a node marks the tree dirty, and a stats readout that
     * dirtied the tree every frame would make the number it reports come out as 100%.
     */
    private fun drawStats() {
        val idle = frames - dirtyFrames
        val share = if (frames == 0L) 0 else (idle * 100 / frames)
        renderer.text(
            "frames $frames   redraws $dirtyFrames   idle $idle ($share%)   fps ${Gdx.graphics.framesPerSecond}   [space] animate",
            24f,
            Gdx.graphics.height - 24f,
            16f,
            0xFFFFE08A,
        )
    }

    override fun dispose() {
        compose.dispose()
        renderer.dispose()
        shapes.dispose()
    }
}

fun main() {
    if (System.getenv("SPIKE_S6_HEADLESS") != null) {
        SelfCheck.run()
        return
    }
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("S6 — Compose runtime on OpenGL (no Compose UI)")
        setWindowedMode(960, 640)
        useVsync(true)
    }
    Lwjgl3Application(SpikeApp(), config)
}
