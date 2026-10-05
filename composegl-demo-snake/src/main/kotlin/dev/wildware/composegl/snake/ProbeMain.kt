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
import org.lwjgl.opengles.GLES20

/**
 * PROFILING ONLY (#242): the showcase and snake at a phone's size, on OpenGL ES 3, with every GL
 * call the renderer makes counted. `COMPOSEGL_PROBE_OUT` is the folder the report and one picture
 * per scene go to.
 */
fun main() {
    val out = File(System.getenv("COMPOSEGL_PROBE_OUT") ?: "build/probe").also { it.mkdirs() }
    val only = System.getenv("COMPOSEGL_PROBE_ONLY")
    val width = 1080
    val height = 2400
    val window = GlfwWindow("probe", width, height, visible = false, vsync = false, context = GlfwContext.Es3)
    val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
    val version = GLES20.glGetString(GLES20.GL_VERSION)
    val report = StringBuilder("renderer $renderer, $version, window ${width}x$height\n")

    val fonts = StbFonts(pageSize = 2048)
    val typeface = resource("fonts/DejaVuSans.ttf")
    fonts.register("default", typeface, (8..72).toList())
    fonts.register("body", typeface, listOf(13, 16, 20))
    fonts.register("display", typeface, listOf(34))

    val gl = GlfwContext.Es3.binding
    val sheet = GlTexture.decode(resource("ui/ui.png"), gl = gl)
    val coins = GlTexture.rgba(CoinFrames * CoinSize, CoinSize, coinSheet(), smooth = false, gl = gl)
    val atlas = showcaseAtlas(sheet.region(80, 0, 24, 24), coins) { texture, left, top, w, h -> texture.region(left, top, w, h) }
    fun grain(kind: Grain) = GlTexture.rgba(GrainSize, GrainSize, grainSheet(kind), smooth = true, gl = gl)
    val materials = ShowcaseMaterials(grain(Grain.Wood), grain(Grain.Paper), grain(Grain.Metal))
    val skins = ShowcaseSkins(atlas)

    fun scene(name: String, frames: Int = 120, skip: Int = 60, run: (ProbeGl, GlCanvas, Int) -> Unit) {
        if (only != null && only !in name) return
        val probe = ProbeGl(gl)
        val canvas = GlCanvas(fonts, probe)
        try {
            for (frame in 0 until frames) {
                GLES20.glViewport(0, 0, width, height)
                GLES20.glClearColor(0.043f, 0.055f, 0.075f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                run(probe, canvas, frame)
                if (frame == frames - 1) save(File(out, "$name.png"), width, height)
                window.present()
                probe.endFrame(width.toLong() * height)
            }
            report.append(probe.report(name, skip)).append('\n')
            println(probe.report(name, skip))
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
    fun snake(name: String, script: List<String>) = scene(name) { _, canvas, frame ->
        snakeFrame(canvas, fonts, script, frame)
    }
    snake("snake-menu", emptyList())
    snake("snake-playing", listOf("play"))

    File(out, "report.txt").writeText(report.toString())
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
        // A finger dragging the page up 6 units a frame, from the middle of the screen, through the
        // measured frames.
        val x = design.width / 2f
        val y = design.height * 0.75f - (frame - 30).coerceAtLeast(0) * 6f % (design.height * 0.5f)
        val id = PointerId(1)
        val millis = nanos / 1_000_000
        when {
            frame == 30 -> app.router.onPointer(PointerEvent.Press(id, Offset(x, y), type = PointerType.Touch, timeMillis = millis))
            frame > 30 && (frame - 30) % 40 == 0 -> {
                app.router.onPointer(PointerEvent.Release(id, Offset(x, y + 6f), type = PointerType.Touch, timeMillis = millis))
                app.router.onPointer(PointerEvent.Press(id, Offset(x, design.height * 0.75f), type = PointerType.Touch, timeMillis = millis))
            }
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
    app.update(1f / 60f)
    // A phone held sideways: the window's 1080x2400 turned round would be 2400x1080, but the window
    // is upright, so the game is fitted into it as it comes.
    val viewport = Viewport(design = SnakeApp.Design, physical = Size(1080f, 2400f), policy = ScalePolicy.Fit)
    app.frame(canvas, viewport, frame * 16_666_667L)
}

private fun save(file: File, width: Int, height: Int) {
    val pixels = BufferUtils.createByteBuffer(width * height * 4)
    GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
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
