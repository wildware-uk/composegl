package composegl.demo

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import composegl.gdx.GdxCanvas
import composegl.gdx.GdxFonts
import composegl.gdx.GdxPointerInput
import composegl.gdx.ninePatch
import composegl.ui.draw.DrawPass
import composegl.ui.geometry.Size
import composegl.ui.graphics.EdgeMode
import composegl.ui.host.UiHost
import composegl.ui.input.GamepadEvent
import composegl.ui.input.InputSink
import composegl.ui.input.KeyEvent
import composegl.ui.input.PointerEvent
import composegl.ui.input.TextEvent
import composegl.ui.layout.MeasurePass
import composegl.ui.layout.ScalePolicy
import composegl.ui.layout.Viewport
import composegl.ui.layout.run

/**
 * The example, on the LibGDX backend.
 *
 * Everything below the interface itself is here, in one file, because there is not much of it: a
 * host, a viewport, a measure pass and a draw pass. That is the whole integration.
 *
 * Set `COMPOSEGL_DEMO_SHOT` to a path to draw one frame, save it and exit — which is how the
 * screenshots in the repository are made.
 */
class Demo : ApplicationAdapter() {

    private lateinit var fonts: GdxFonts
    private lateinit var atlas: TextureAtlas
    private lateinit var skin: DemoSkin
    private lateinit var canvas: GdxCanvas
    private lateinit var sprites: SpriteBatch
    private lateinit var host: UiHost
    private lateinit var pointerInput: GdxPointerInput

    /** The viewport the last frame used, which is what a pointer event must be read against. */
    private var viewport = Viewport.oneToOne(Size(1280f, 720f))

    private val state = DemoState()
    private var frames = 0
    private var elapsed = 0f

    private val shot: String? = System.getenv("COMPOSEGL_DEMO_SHOT")

    override fun create() {
        fonts = GdxFonts()
        val file = Gdx.files.internal("fonts/DejaVuSans.ttf")
        fonts.registerTrueType("body", file, listOf(13, 16, 20))
        fonts.registerTrueType("display", file, listOf(34))

        atlas = TextureAtlas(Gdx.files.internal("ui/ui.atlas"))
        skin = DemoSkin(
            panel = atlas.ninePatch("panel"),
            // The hatch keeps its pitch across the ribbon and fills whatever height the text needs.
            ribbon = atlas.ninePatch("ribbon", centreAcross = EdgeMode.Tile),
        )

        sprites = SpriteBatch()
        canvas = GdxCanvas(sprites)
        host = UiHost()
        host.setContent { Screen(fonts, skin, state.health, state.selected, state.pointer) }

        // The whole of the engine's involvement in input: a translator, pointed at a sink. The
        // sink below is the demo's own, because hit testing lands in the next milestone.
        pointerInput = GdxPointerInput(PointerWatcher(state), { viewport })
        Gdx.input.inputProcessor = pointerInput
    }

    override fun render() {
        elapsed += Gdx.graphics.deltaTime
        // Something that moves, so the frame counter below means something.
        state.health = 0.5f + 0.35f * kotlin.math.sin(elapsed.toDouble()).toFloat()
        state.selected = ((elapsed / 0.8f).toInt()) % 10

        host.frame(System.nanoTime())

        viewport = Viewport(
            design = Size(1280f, 720f),
            physical = Size(Gdx.graphics.backBufferWidth.toFloat(), Gdx.graphics.backBufferHeight.toFloat()),
            policy = ScalePolicy.Fit,
        )
        MeasurePass().run(host.root, viewport)

        Gdx.gl.glClearColor(0.03f, 0.04f, 0.05f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        canvas.begin(viewport)
        DrawPass(canvas).draw(host.root)
        canvas.end()

        frames++
        if (shot != null && frames >= 2) {
            save(shot)
            Gdx.app.exit()
        }
    }

    private fun save(path: String) {
        val frame = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        // A frame read back from OpenGL arrives bottom row first.
        val upright = Pixmap(frame.width, frame.height, Pixmap.Format.RGBA8888)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                upright.drawPixel(x, y, frame.getPixel(x, frame.height - 1 - y))
            }
        }
        PixmapIO.writePNG(Gdx.files.absolute(path), upright)
        frame.dispose()
        upright.dispose()
        println("wrote $path (${canvas.renderCalls} draw calls)")
    }

    /** Called when the window is no longer in front, so nothing is left holding a capture. */
    fun windowLostFocus() {
        if (::pointerInput.isInitialized) pointerInput.cancelAll()
    }

    override fun dispose() {
        host.dispose()
        canvas.dispose()
        sprites.dispose()
        fonts.dispose()
        atlas.dispose()
    }
}

/**
 * Where the pointer went.
 *
 * Nothing is consumed — every method answers false — because the interface has nothing to click
 * yet. When hit testing arrives this is the object it replaces.
 */
private class PointerWatcher(private val state: DemoState) : InputSink {

    override fun onPointer(event: PointerEvent): Boolean {
        state.pointer = when (event) {
            is PointerEvent.Exit -> null
            else -> event.position
        }
        return false
    }

    override fun onKey(event: KeyEvent) = false
    override fun onText(event: TextEvent) = false
    override fun onGamepad(event: GamepadEvent) = false
}

fun main() {
    val demo = Demo()
    val configuration = Lwjgl3ApplicationConfiguration().apply {
        setTitle("ComposeGL")
        setWindowedMode(1280, 720)
        useVsync(true)
        setWindowListener(object : Lwjgl3WindowAdapter() {
            override fun focusLost() = demo.windowLostFocus()
        })
    }
    Lwjgl3Application(demo, configuration)
}
