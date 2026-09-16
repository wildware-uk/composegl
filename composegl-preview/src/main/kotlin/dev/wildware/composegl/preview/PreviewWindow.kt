package dev.wildware.composegl.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.lwjgl3.GlRenderTarget
import dev.wildware.composegl.lwjgl3.GlTexture
import dev.wildware.composegl.lwjgl3.GlfwKeyboardInput
import dev.wildware.composegl.lwjgl3.GlfwPointerInput
import dev.wildware.composegl.lwjgl3.GlfwWindow
import dev.wildware.composegl.lwjgl3.Lwjgl3Backend
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.widget.ProvideFonts
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The live preview window: a [PreviewSession] drawn on the raw OpenGL backend.
 *
 * Each visible preview is drawn into a picture of its own size whenever it changes, and the window's
 * own interface ([PreviewChrome]) shows those pictures. The fonts, the GL context, composegl and the
 * window are made once; only the previews come and go with each build.
 *
 * Everything here runs on the thread that owns the window's GL context. Other threads hand it
 * [LiveEvent]s through [post], and [frame] acts on them.
 *
 * @param watchdog told what each frame is drawing and when it finishes, so a hang names a preview.
 */
class PreviewWindow(
    val window: GlfwWindow,
    val backend: Lwjgl3Backend,
    val session: PreviewSession,
    private val watchdog: HangWatchdog = HangWatchdog(),
) : AutoCloseable {

    private val events = ConcurrentLinkedQueue<LiveEvent>()
    private val draw = DrawPass(backend.canvas)
    private val host = UiHost()
    private val focus = FocusManager(host.root)
    private val renderer = UiRenderer(host, backend.canvas).also { it.focus = focus }

    /** One picture per preview, by name, for the stages in [pictured]. */
    private var pictures: Map<String, GlRenderTarget> by mutableStateOf(emptyMap())
    private val textures = HashMap<String, GlTexture>()
    private var pictured: List<PreviewStage>? = null

    /** What to say while the output folder, rather than Gradle, is what keeps the window up to date. */
    private var watching: String? = null

    /** The window's own interface, for a test to look through. */
    val root: UiNode get() = host.root

    var viewport: Viewport = Viewport.oneToOne(window.framebuffer)
        private set

    init {
        host.setContent {
            ProvideFonts(backend.fonts) {
                PreviewChrome(session) { name -> pictures[name]?.let { textures[name] } }
            }
        }

        val pointer = PointerRouter(host.root, focus, backend.cursor)
        val keys = KeyRouter(focus, host.root)
        val sink = object : InputSink {
            override fun onPointer(event: PointerEvent) = pointer.onPointer(event)
            override fun onKey(event: KeyEvent) = shortcut(event) || keys.onKey(event)
            override fun onText(event: TextEvent) = keys.onText(event)
            override fun onGamepad(event: GamepadEvent) = false
        }
        GlfwPointerInput(sink, viewport = { viewport }, pixelScale = { window.pixelScale }).attachTo(window)
        GlfwKeyboardInput(sink).attachTo(window)
    }

    /** Hands an event to the drawing thread. Safe from any thread. */
    fun post(event: LiveEvent) {
        events.add(event)
    }

    /** The picture [name] was last drawn into, or null when it is not showing. */
    fun picture(name: String): GlRenderTarget? = pictures[name]

    /**
     * One frame: act on what other threads posted, draw every visible preview that changed into its
     * picture, then the window's interface into the window. The caller presents it.
     *
     * @return whether anything happened. A caller can wait for input when nothing did.
     */
    fun frame(nanos: Long): Boolean {
        var busy = drainEvents()
        if (pictured !== session.stages) {
            repicture()
            busy = true
        }

        session.frame(nanos).forEach { stage ->
            val target = pictures[stage.name] ?: return@forEach
            watchdog.working(session.describe(stage))
            target.draw(backend.canvas, Tile) { stage.draw(draw) }
            busy = true
        }

        watchdog.working("the preview window's own interface")
        viewport = Viewport.oneToOne(window.framebuffer)
        val gl = window.context.binding
        gl.clearColor(0f, 0f, 0f, 1f)
        gl.clear(ColourBufferBit)
        if (renderer.render(viewport, nanos)) busy = true

        watchdog.frameDone()?.let(session::hangEnded)
        return busy
    }

    private fun drainEvents(): Boolean {
        var any = false
        while (true) {
            val event = events.poll() ?: return any
            any = true
            when (event) {
                LiveEvent.Compiling -> session.status = "Compiling…"
                LiveEvent.Reload -> {
                    session.status = watching
                    session.reload()
                }
                is LiveEvent.CompileFailed -> {
                    session.compileFailed(event.result)
                    session.status = watching
                }
                is LiveEvent.RestartNeeded -> session.restartNeeded(event.reason)
                is LiveEvent.WatchingOutput -> {
                    watching = "Not compiling (${event.reason}). Watching the compiled classes instead: " +
                        "run ${event.advice} to keep them up to date."
                    session.status = watching
                }
            }
        }
    }

    /** A picture of each preview's own size, for the stages now on screen. The old pictures go. */
    private fun repicture() {
        val old = pictures
        textures.clear()
        pictures = session.stages.associate { stage ->
            val target = GlRenderTarget(stage.width, stage.height, window.context.binding)
            // A render target keeps its bottom row first, so the picture is read upside down.
            textures[stage.name] = GlTexture(target.textureName, target.width, target.height, 0f, 1f, 1f, 0f, gl = window.context.binding)
            stage.name to target
        }
        pictured = session.stages
        old.values.forEach { it.close() }
    }

    /** G switches between one preview and all of them; Up and Down pick. */
    private fun shortcut(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.Down) return false
        when (event.key) {
            Key.G -> session.gallery = !session.gallery
            Key.Down -> session.selectNext(1)
            Key.Up -> session.selectNext(-1)
            else -> return false
        }
        return true
    }

    /** Disposes the window's interface and its pictures. The session, backend and window are the caller's. */
    override fun close() {
        host.dispose()
        pictures.values.forEach { it.close() }
        pictures = emptyMap()
        textures.clear()
    }

    private companion object {
        const val ColourBufferBit = 0x4000
    }
}
