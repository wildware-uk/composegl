package dev.wildware.composegl.snake

import androidx.compose.runtime.mutableStateOf
import dev.wildware.composegl.demo.web.CoinFrames
import dev.wildware.composegl.demo.web.CoinSize
import dev.wildware.composegl.demo.web.Grain
import dev.wildware.composegl.demo.web.GrainSize
import dev.wildware.composegl.demo.web.Section
import dev.wildware.composegl.demo.web.Showcase
import dev.wildware.composegl.demo.web.ShowcaseMaterials
import dev.wildware.composegl.demo.web.ShowcaseSkins
import dev.wildware.composegl.demo.web.ShowcaseState
import dev.wildware.composegl.demo.web.coinSheet
import dev.wildware.composegl.demo.web.designFor
import dev.wildware.composegl.demo.web.grainSheet
import dev.wildware.composegl.demo.web.showcaseAtlas
import dev.wildware.composegl.lwjgl3.GlCanvas
import dev.wildware.composegl.lwjgl3.GlTexture
import dev.wildware.composegl.lwjgl3.GlfwContext
import dev.wildware.composegl.lwjgl3.GlfwWindow
import dev.wildware.composegl.lwjgl3.StbFonts
import dev.wildware.composegl.render.gl.ProbeGl
import dev.wildware.composegl.render.gl.report
import dev.wildware.composegl.snake.game.Cell
import dev.wildware.composegl.snake.game.Direction
import dev.wildware.composegl.snake.game.HighScore
import dev.wildware.composegl.snake.game.HighScoreStore
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.input.PointerType
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Screen
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.widget.ProvideFonts
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.lwjgl.BufferUtils
import java.lang.management.ManagementFactory
import org.lwjgl.opengl.GL11
import org.lwjgl.opengles.GLES20

/**
 * PROFILING ONLY (#242, #267): the showcase and snake at a phone's size, on OpenGL ES 3.
 * `COMPOSEGL_PROBE_OUT` is the folder the report and one picture per scene go to.
 *
 * `COMPOSEGL_PROBE_MODE=count` (the default) draws through [ProbeGl] and counts every GL call.
 * `COMPOSEGL_PROBE_MODE=time` draws straight to GL and reads the thread's CPU time and allocated
 * bytes around each frame's work (input and render, not the clear or the swap), after a warm-up.
 */
fun main() {
    val out = File(System.getenv("COMPOSEGL_PROBE_OUT") ?: "build/probe").also { it.mkdirs() }
    val only = System.getenv("COMPOSEGL_PROBE_ONLY")
    val timing = System.getenv("COMPOSEGL_PROBE_MODE") == "time"
    val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    val width = 1080
    val height = 2400
    // Square, so the showcase can be drawn upright (1080x2400) and snake sideways (2400x1080), each
    // in the bottom-left corner of the same window.
    // Counting runs on OpenGL ES 3, as #242 did. Timing may run on desktop GL instead
    // (`COMPOSEGL_PROBE_CONTEXT=desktop`): under Xvfb an ES context comes through EGL and so from
    // Mesa's software renderer, while desktop GL can reach the real GPU.
    Raw.desktop = System.getenv("COMPOSEGL_PROBE_CONTEXT") == "desktop"
    val context = if (Raw.desktop) GlfwContext.Desktop else GlfwContext.Es3
    val window = GlfwWindow("probe", 2400, 2400, visible = false, vsync = false, context = context)
    val renderer = Raw.string(GLES20.GL_RENDERER)
    val version = Raw.string(GLES20.GL_VERSION)
    val report = StringBuilder("renderer $renderer, $version, window ${width}x$height\n")

    val fonts = StbFonts(pageSize = 2048)
    val typeface = resource("fonts/DejaVuSans.ttf")
    fonts.register("default", typeface, (8..72).toList())
    fonts.register("body", typeface, listOf(13, 16, 20))
    fonts.register("display", typeface, listOf(34))

    val gl = context.binding
    val sheet = GlTexture.decode(resource("ui/ui.png"), gl = gl)
    val coins = GlTexture.rgba(CoinFrames * CoinSize, CoinSize, coinSheet(), smooth = false, gl = gl)
    val atlas = showcaseAtlas(sheet.region(80, 0, 24, 24), coins) { texture, left, top, w, h -> texture.region(left, top, w, h) }
    fun grain(kind: Grain) = GlTexture.rgba(GrainSize, GrainSize, grainSheet(kind), smooth = true, gl = gl)
    val materials = ShowcaseMaterials(grain(Grain.Wood), grain(Grain.Paper), grain(Grain.Metal))
    val skins = ShowcaseSkins(atlas)

    val shaders = LinkedHashSet<String>()

    // Counting reads the same frames timing does (#267): 300 to settle, then 600.
    fun scene(name: String, frames: Int = 900, skip: Int = 300, wide: Boolean = false, run: (ProbeGl?, GlCanvas, Int) -> Unit) {
        val shotWidth = if (wide) height else width
        val shotHeight = if (wide) width else height
        if (only != null && only !in name) return
        if (timing) {
            // Long enough for the JIT to settle: 300 frames of warm-up, then 600 measured.
            val total = 900
            val warm = 300
            val canvas = GlCanvas(fonts, gl)
            val cpu = ArrayList<Long>()
            val bytes = ArrayList<Long>()
            val wall = ArrayList<Long>()
            try {
                for (frame in 0 until total) {
                    Raw.viewport(0, 0, 2400, 2400)
                    Raw.clearColor(0.043f, 0.055f, 0.075f, 1f)
                    Raw.clear(GLES20.GL_COLOR_BUFFER_BIT)
                    // The GPU is idle when a frame starts, so no measured call waits for an earlier frame.
                    Raw.finish()
                    val c0 = threads.currentThreadCpuTime
                    val b0 = threads.currentThreadAllocatedBytes
                    val w0 = System.nanoTime()
                    run(null, canvas, frame)
                    val w1 = System.nanoTime()
                    val c1 = threads.currentThreadCpuTime
                    val b1 = threads.currentThreadAllocatedBytes
                    if (frame >= warm) {
                        cpu += c1 - c0
                        bytes += b1 - b0
                        wall += w1 - w0
                    }
                    if (frame == total - 1) save(File(out, "$name.png"), shotWidth, shotHeight)
                    window.present()
                }
                val text = timingReport(name, cpu, bytes, wall)
                report.append(text).append('\n')
                println(text)
                File(out, "$name.csv").writeText("cpu_ns,alloc_bytes,wall_ns\n" + cpu.indices.joinToString("") { "${cpu[it]},${bytes[it]},${wall[it]}\n" })
            } finally {
                canvas.close()
            }
            return
        }
        val probe = ProbeGl(gl)
        val canvas = GlCanvas(fonts, probe)
        try {
            for (frame in 0 until frames) {
                Raw.viewport(0, 0, 2400, 2400)
                Raw.clearColor(0.043f, 0.055f, 0.075f, 1f)
                Raw.clear(GLES20.GL_COLOR_BUFFER_BIT)
                run(probe, canvas, frame)
                if (frame == frames - 1) save(File(out, "$name.png"), shotWidth, shotHeight)
                // A scroll scene at the top of its drag, to show the page itself moved.
                if (frame == 74 && "scroll" in name) save(File(out, "$name-mid.png"), shotWidth, shotHeight)
                window.present()
                probe.endFrame(width.toLong() * height)
            }
            report.append(probe.report(name, skip)).append('\n')
            println(probe.report(name, skip))
            shaders += probe.shaderTexts
        } finally {
            canvas.close()
        }
    }

    // The showcase as a phone shows it: 393 CSS pixels across at a ratio of 2.75, laid out at 400.
    val design = designFor(width / 2.75, height / 2.75)
    val viewport = Viewport(design = design, physical = Size(width.toFloat(), height.toFloat()), policy = ScalePolicy.Fit)

    fun showcase(section: Section, still: Boolean, scroll: Boolean = false, tag: String) =
        scene("showcase-${section.tag}-$tag") { _, canvas, frame ->
            showcaseFrame(section, still, scroll, canvas, viewport, design, skins, materials, fonts, frame)
        }

    for (section in listOf(Section.Home, Section.Widgets, Section.Game, Section.Hud, Section.Gear, Section.Effects, Section.Surfaces, Section.Text)) {
        showcase(section, still = true, tag = "still")
    }
    showcase(Section.Home, still = false, tag = "animated")
    showcase(Section.Game, still = false, tag = "animated")
    showcase(Section.Animation, still = false, tag = "animated")
    showcase(Section.Effects, still = false, tag = "animated")
    showcase(Section.Surfaces, still = false, tag = "animated")
    showcase(Section.Widgets, still = true, scroll = true, tag = "scroll")
    showcase(Section.Gear, still = true, scroll = true, tag = "scroll")

    // Snake on a phone held sideways: 2400x1080.
    fun snake(name: String, script: List<String>) = scene(name, wide = true) { _, canvas, frame ->
        snakeFrame(canvas, fonts, script, frame)
    }
    snake("snake-menu", emptyList())
    snake("snake-playing", listOf("play"))

    File(out, if (timing) "report-time.txt" else "report.txt").writeText(report.toString())
    if (!timing) {
        val dir = File(out, "shaders").also { it.mkdirs() }
        shaders.forEachIndexed { i, text -> File(dir, "$i.glsl").writeText(text) }
    }
    showcaseApps.values.forEach { it.close() }
    snakeApp?.close()
    window.close()
}

private class ShowcaseApp(val host: UiHost, val renderer: UiRenderer, val router: PointerRouter, val state: ShowcaseState) : AutoCloseable {
    override fun close() = host.dispose()
}

private val showcaseApps = HashMap<String, ShowcaseApp>()

@Suppress("LongParameterList")
private fun showcaseFrame(
    section: Section,
    still: Boolean,
    scroll: Boolean,
    canvas: GlCanvas,
    viewport: Viewport,
    design: Size,
    skins: ShowcaseSkins,
    materials: ShowcaseMaterials,
    fonts: StbFonts,
    frame: Int,
) {
    val key = "${section.tag}-$still-$scroll"
    if (frame == 0) showcaseApps.remove(key)?.close()
    val app = showcaseApps.getOrPut(key) {
        val state = ShowcaseState()
        state.width = design.width
        state.reduceMotion = still
        state.goTo(section)
        val host = UiHost()
        val focus = FocusManager(host.root)
        val router = PointerRouter(host.root, focus)
        host.screen = Screen.of(viewport)
        host.setContent { ProvideFonts(fonts) { Showcase(state, skins, null, {}, materials) } }
        ShowcaseApp(host, UiRenderer(host, canvas).also { it.focus = focus }, router, state)
    }
    val nanos = frame * 16_666_667L
    if (scroll) {
        // A finger held on the page's left margin, clear of every widget, dragged up 6 units a
        // frame and back down, and never let go (#267): the page itself scrolls on every frame
        // from 31 on, with no fling and no tap on whatever comes under the finger.
        val x = 10f
        val span = design.height * 0.3f
        val travelled = (frame - 30).coerceAtLeast(0) * 6f % (2 * span)
        val y = design.height * 0.75f - (if (travelled < span) travelled else 2 * span - travelled)
        val id = PointerId(1)
        val millis = nanos / 1_000_000
        when {
            frame == 30 -> app.router.onPointer(PointerEvent.Press(id, Offset(x, y), type = PointerType.Touch, timeMillis = millis))
            frame > 30 -> app.router.onPointer(
                PointerEvent.Move(id, Offset(x, y), setOf(dev.wildware.composegl.ui.input.PointerButton.Primary), type = PointerType.Touch, timeMillis = millis),
            )
        }
    }
    app.renderer.render(viewport, nanos)
}

private var snakeApp: SnakeApp? = null

private fun snakeFrame(canvas: GlCanvas, fonts: StbFonts, script: List<String>, frame: Int) {
    if (frame == 0) {
        snakeApp?.close()
        snakeApp = SnakeApp(fonts, object : HighScoreStore {
            override fun load(): List<HighScore> = emptyList()
            override fun save(scores: List<HighScore>) = Unit
        })
    }
    val app = checkNotNull(snakeApp)
    if (frame < script.size && script[frame] == "play") app.session.startGame()
    if ("play" in script) steer(app)
    app.update(1f / 60f)
    // A phone held sideways: 2400x1080.
    val viewport = Viewport(design = SnakeApp.Design, physical = Size(2400f, 1080f), policy = ScalePolicy.Fit)
    app.frame(canvas, viewport, frame * 16_666_667L)
}

/** Keeps a playing snake alive: before it would run into a wall or itself, it turns. */
private fun steer(app: SnakeApp) {
    val game = app.session.game
    val head = game.snake.firstOrNull() ?: return
    fun clear(d: Direction): Boolean {
        val next = Cell(head.x + d.dx, head.y + d.dy)
        return next.x in 0 until game.width && next.y in 0 until game.height && next !in game.snake
    }
    if (clear(game.direction)) return
    Direction.entries.firstOrNull { it != game.direction && !it.isOpposite(game.direction) && clear(it) }?.let(app.session::turn)
}

/** PROFILING ONLY (#267): per-frame CPU, allocation and wall time summed up. */
private fun timingReport(label: String, cpu: List<Long>, bytes: List<Long>, wall: List<Long>): String {
    fun stats(values: List<Long>, scale: Double, unit: String): String {
        val sorted = values.sorted()
        val mean = values.average() / scale
        fun q(p: Double) = sorted[((sorted.size - 1) * p).toInt()] / scale
        return "mean %.3f median %.3f p90 %.3f max %.3f %s".format(mean, q(0.5), q(0.9), q(1.0), unit)
    }
    return buildString {
        appendLine("== $label: ${cpu.size} frames")
        appendLine("thread cpu: ${stats(cpu, 1e6, "ms")}")
        appendLine("allocated: ${stats(bytes, 1024.0, "KB")}")
        appendLine("wall: ${stats(wall, 1e6, "ms")}")
    }
}

/** The few raw GL calls the probe makes itself, on whichever API the window has. */
private object Raw {
    var desktop = false
    fun string(name: Int): String? = if (desktop) GL11.glGetString(name) else GLES20.glGetString(name)
    fun viewport(x: Int, y: Int, w: Int, h: Int) = if (desktop) GL11.glViewport(x, y, w, h) else GLES20.glViewport(x, y, w, h)
    fun clearColor(r: Float, g: Float, b: Float, a: Float) = if (desktop) GL11.glClearColor(r, g, b, a) else GLES20.glClearColor(r, g, b, a)
    fun clear(mask: Int) = if (desktop) GL11.glClear(mask) else GLES20.glClear(mask)
    fun finish() = if (desktop) GL11.glFinish() else GLES20.glFinish()
    fun readPixels(x: Int, y: Int, w: Int, h: Int, format: Int, type: Int, into: java.nio.ByteBuffer) =
        if (desktop) GL11.glReadPixels(x, y, w, h, format, type, into) else GLES20.glReadPixels(x, y, w, h, format, type, into)
}

private fun save(file: File, width: Int, height: Int) {
    val pixels = BufferUtils.createByteBuffer(width * height * 4)
    Raw.readPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) for (x in 0 until width) {
        val at = ((height - 1 - y) * width + x) * 4
        val r = pixels.get(at).toInt() and 0xff
        val g = pixels.get(at + 1).toInt() and 0xff
        val b = pixels.get(at + 2).toInt() and 0xff
        image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
    }
    ImageIO.write(image, "png", file)
}

private fun resource(path: String): ByteArray =
    checkNotNull(object {}.javaClass.classLoader.getResourceAsStream(path)) { "no $path on the classpath" }.use { it.readBytes() }

@Suppress("unused")
private val keepState = mutableStateOf(0)
