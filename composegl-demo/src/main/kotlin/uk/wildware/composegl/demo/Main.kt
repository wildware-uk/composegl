package uk.wildware.composegl.demo

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
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import uk.wildware.composegl.gdx.GdxClipboard
import uk.wildware.composegl.gdx.GdxSoftKeyboard
import uk.wildware.composegl.gdx.GdxCanvas
import uk.wildware.composegl.gdx.GdxFonts
import uk.wildware.composegl.gdx.GdxGamepadInput
import uk.wildware.composegl.gdx.GdxKeyboardInput
import uk.wildware.composegl.gdx.GdxPointerInput
import uk.wildware.composegl.gdx.GdxTexture
import uk.wildware.composegl.ui.draw.DrawPass
import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.graphics.ArtAtlas
import uk.wildware.composegl.ui.host.UiHost
import uk.wildware.composegl.ui.host.UiRenderer
import uk.wildware.composegl.ui.layout.MeasurePass
import uk.wildware.composegl.ui.layout.ScalePolicy
import uk.wildware.composegl.ui.layout.Viewport
import uk.wildware.composegl.ui.layout.run
import uk.wildware.composegl.ui.skin.ReloadingSkin

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
    private lateinit var skin: ReloadingSkin
    private lateinit var canvas: GdxCanvas

    /** The whole interface in one call, made once; see [UiRenderer]. */
    private lateinit var ui: UiRenderer
    private lateinit var sprites: SpriteBatch
    private lateinit var host: UiHost
    private lateinit var pointerInput: GdxPointerInput
    private lateinit var padInput: GdxGamepadInput
    private lateinit var keyboardInput: GdxKeyboardInput
    private lateinit var input: DemoInput

    /** The viewport the last frame used, which is what a pointer event must be read against. */
    private var viewport = Viewport.oneToOne(Size(1280f, 720f))

    private val state = DemoState()
    private var frames = 0
    private var elapsed = 0f

    private val shot: String? = System.getenv("COMPOSEGL_DEMO_SHOT")

    /** How long to let the demo run before the shot, for what is a movement rather than a state. */
    private val shotAt: Float = System.getenv("COMPOSEGL_DEMO_SHOT_AT")?.toFloatOrNull() ?: 0f

    /** A keyboard script, one step per frame, the same as the other backend plays. */
    private var scriptedKeys: List<String> = emptyList()

    /** A pad script, played once after the first layout: focus moves by geometry, and there is
     *  none before then. Only a screenshot ever sets it. */
    private val scriptedPad: String? = System.getenv("COMPOSEGL_DEMO_PAD")

    /**
     * Where to pretend the mouse is, as `x,y` or `x,y,press`.
     *
     * A screenshot of a hover is otherwise impossible to take: a script cannot move a real mouse.
     * Re-applied every frame, because the real one is still there and still reporting.
     */
    private val scriptedPointer: String? = System.getenv("COMPOSEGL_DEMO_POINTER")

    override fun create() {
        fonts = GdxFonts()
        val file = Gdx.files.internal("fonts/DejaVuSans.ttf")
        fonts.registerTrueType("body", file, listOf(13, 16, 20))
        fonts.registerTrueType("display", file, listOf(34))

        atlas = TextureAtlas(Gdx.files.internal("ui/ui.atlas"))
        // The regions by name, and nothing about what they mean: which one is a panel, where its
        // slices are and how far in its contents sit are all in the skin file, which the other
        // backend reads too.
        skin = demoSkin(
            art = ArtAtlas.of(atlas.regions.associate { it.name to GdxTexture(it) }),
            fonts = fonts,
        )

        sprites = SpriteBatch()
        canvas = GdxCanvas(sprites, fonts.atlas)
        host = UiHost()
        host.setContent { Screen(fonts, skin.skin, state, GdxClipboard(), GdxSoftKeyboard()) }

        // The whole of the engine's involvement in input: a translator, pointed at a sink. What
        // the sink does with an event — which node it hit, whether that is a click — is the
        // toolkit's business and has nothing to do with LibGDX.
        input = DemoInput(state, host.root)
        scriptedKeys = System.getenv("COMPOSEGL_DEMO_KEYS")?.let { input.keyScript(it) } ?: emptyList()
        pointerInput = GdxPointerInput(input, { viewport })
        keyboardInput = GdxKeyboardInput(input)
        // Two translators, one keyboard and one mouse, neither knowing about the other.
        Gdx.input.inputProcessor = InputMultiplexer(pointerInput, keyboardInput)

        // Pads are pushed rather than polled here: gdx-controllers listens to the driver and
        // calls back, so the loop below has nothing to do for them.
        padInput = GdxGamepadInput(input)
        padInput.start()

        ui = UiRenderer(host, canvas, state.budget)
        // Set once rather than passed per frame. Input goes here because hit testing needs
        // positions, and so do the scripted presses, since focus moves by geometry.
        ui.onLaidOut = { millis ->
            input.frame(millis)
            if (frames == 0) scriptedPad?.let { input.pretendPadDid(it) }
            if (frames < scriptedKeys.size) input.pretendKeyWas(scriptedKeys[frames])
        }
    }

    override fun render() {
        elapsed += Gdx.graphics.deltaTime
        state.tick(elapsed)

        scriptedPointer?.let { input.pretendPointerIsAt(it) }

        // One look at a timestamp. An artist saving the skin file is seen on the next frame.
        skin.reloadIfChanged()
        viewport = Viewport(
            design = Size(1280f, 720f),
            physical = Size(Gdx.graphics.backBufferWidth.toFloat(), Gdx.graphics.backBufferHeight.toFloat()),
            policy = ScalePolicy.Fit,
        )

        Gdx.gl.glClearColor(0.03f, 0.04f, 0.05f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        // The whole interface: recompose, lay out, hand input the positions, draw, and time all
        // three. See UiRenderer.
        ui.render(viewport, System.nanoTime())

        frames++
        if (shot != null && frames >= scriptedKeys.size + 2 && elapsed >= shotAt) {
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
        println("wrote $path (${canvas.drawCalls} draw calls)")
    }

    /** Called when the window is no longer in front, so nothing is left holding a capture. */
    fun windowLostFocus() {
        if (!::pointerInput.isInitialized) return
        pointerInput.cancelAll()
        keyboardInput.releaseAll()
        input.windowLostFocus()
    }

    override fun dispose() {
        if (::padInput.isInitialized) padInput.stop()
        host.dispose()
        canvas.dispose()
        sprites.dispose()
        fonts.dispose()
        atlas.dispose()
    }
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
