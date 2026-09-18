package dev.wildware.composegl.kool

import androidx.compose.runtime.Composable
import de.fabmax.kool.KoolContext
import de.fabmax.kool.input.InputStack
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.pipeline.ClearDepthLoad
import de.fabmax.kool.pipeline.backend.gl.RenderBackendGl
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.util.Time
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.InputSourceTracker
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.input.SourceAware
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.widget.ProvideClipboard
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.ProvideInputSource
import dev.wildware.composegl.ui.widget.ProvideSoftKeyboard
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A ComposeGL screen, as a Kool scene.
 *
 * ```kotlin
 * fun main() = KoolApplication(KoolConfigJvm(renderBackend = RenderBackendGl)) {
 *     val fonts = StbFonts().apply { register("default", File("DejaVuSans.ttf").readBytes(), listOf(16)) }
 *     ctx.scenes += gameWorld
 *     ctx.composeGl(KoolBackend(fonts), Size(1280f, 720f)) { MainMenu() }
 * }
 * ```
 *
 * On Android the fonts are an `AndroidFonts` with a `Typeface` registered, and Kool's context is a
 * `KoolContextAndroid`, which always renders with OpenGL ES.
 *
 * Kool draws its scenes in the order they were added, one after another into the same framebuffer. This
 * one clears nothing, so it draws on top of the scenes before it and under the scenes after it. The
 * design size is fitted into the scene's view by [policy], exactly as a `Viewport` fits one onto a
 * window on the other frontends.
 *
 * **Where it draws.** While Kool renders this scene, on Kool's render thread, with Kool's OpenGL context
 * current and Kool's framebuffer bound: recompose, lay out, render any `SceneView`s, draw. The canvas
 * hands Kool its state back afterwards (see [KoolCanvas]), so the scenes Kool draws next draw as they
 * would with no interface at all.
 *
 * **Input.** The toolkit is only ever touched on Kool's render thread. With Kool's default,
 * `asyncSceneUpdate = true`, the desktop updates the game on a thread of its own while it renders the
 * frame before; with `asyncSceneUpdate = false`, and always on Android, both happen on one thread. Either
 * way a pointer listener on Kool's [InputStack] takes each frame's pointers as values, and this scene
 * hands them to the toolkit at the start of the next render. Mouse and touches go to the toolkit's
 * pointer router, and [onPointerUsed] says which of them it used. Kool's keys and gamepads are not
 * translated yet; a game that translates them hands them to [player], on the render thread.
 *
 * @param backend where the canvas and fonts come from. Not closed here: it is the game's.
 * @param design the size the screen is designed at.
 * @param policy how the design is fitted into the view.
 * @param clock monotonic nanoseconds, for the frame's time.
 */
class ComposeGlScene(
    private val backend: KoolBackend,
    private val design: Size,
    private val policy: ScalePolicy = ScalePolicy.Fit,
    private val clock: () -> Long = System::nanoTime,
) : AutoCloseable {

    /** The Kool scene. Add it to the context; [KoolContext.composeGl] does. */
    val scene: Scene = Scene("composegl").apply {
        clearColor = ClearColorLoad
        clearDepth = ClearDepthLoad
    }

    private val host = UiHost()

    private val focus = FocusManager(host.root)

    private val pointer = PointerRouter(host.root, focus, backend.cursor)

    /** What the player is using now: a mouse or a finger. */
    private val source = InputSourceTracker()

    private val keys = KeyRouter(focus, host.root)

    private val keyNavigator = KeyNavigator(focus)

    private val padNavigator = GamepadNavigator(focus)

    /**
     * This screen's end of the input contract, in design units: the routers and navigators behind one
     * [SourceAware]. Kool's pointers arrive here by themselves; a game that translates Kool's keys or
     * pads hands them here too.
     *
     * **Call it on Kool's render thread only**, where the toolkit lays out and draws: in a scene's
     * `onUpdate` with `asyncSceneUpdate = false`, or on Android. Under Kool's default the game's update
     * runs on another thread, and a key handed here from there races the frame being drawn; queue it and
     * hand it over from the render thread instead.
     */
    val player: InputSink = SourceAware(
        source,
        object : InputSink {
            override fun onPointer(event: PointerEvent) = pointer.onPointer(event)

            // The router first, always: a field that wanted a key has taken it before navigation is asked.
            override fun onKey(event: KeyEvent) = keys.onKey(event) || keyNavigator.onKey(event)

            override fun onText(event: TextEvent) = keys.onText(event)

            override fun onGamepad(event: GamepadEvent) = padNavigator.onGamepad(event)
        },
    )

    /** The viewport the last frame was laid out and drawn in, in the framebuffer's pixels. */
    private var viewport: Viewport = Viewport.oneToOne(design)

    /** Kool's pointers, turned into the toolkit's events against the last frame's [viewport]. */
    private val pointerInput = KoolPointerInput(player, viewport = { viewport }, clock = { clock() / 1_000_000 })

    private val renderer = UiRenderer(host, backend.canvas).also {
        it.focus = focus
        it.onLaidOut = { millis ->
            focus.refresh()
            padNavigator.frame(millis)
        }
    }

    /**
     * Told, once for each of Kool's pointers in each of Kool's frames, whether the interface used it:
     * so a game can leave alone a click that landed on a button. A pointer that lifted or left is
     * reported once more, in the frame it went.
     *
     * **Called on Kool's render thread**, at the start of this scene's render, before the interface is
     * drawn. That is where the toolkit handles pointers and where the verdict first exists, so it never
     * arrives in the same step as the pointer: Kool polls the pointer, updates the game, then renders.
     * With `asyncSceneUpdate = false`, and on Android, the verdict for a frame arrives after the game's
     * update for that frame has run. Under Kool's default the game updates on another thread alongside
     * the render, so the verdict can arrive before, during or after that update, and this callback runs
     * on a different thread from the game's update. Either way, match [PointerUse.frame] against the
     * frame the game read the pointer in, not against when the verdict arrives.
     *
     * Kool's own `Pointer.isConsumed()` is not set: by the time the verdict exists, Kool's pointer has
     * moved on.
     */
    @Volatile
    var onPointerUsed: ((PointerUse) -> Unit)? = null

    /** One of Kool's frames of pointers, taken on Kool's update thread. */
    private class PointerFrame(val number: Int, val pointers: List<KoolPointerInput.Sample>)

    /** Pointer frames taken on Kool's update thread, waiting for the render thread. */
    private val pointerFrames = ConcurrentLinkedQueue<PointerFrame>()

    private val listener = InputStack.PointerListener { state, _ ->
        pointerFrames += PointerFrame(Time.frameCount, state.pointers.filter { it.isValid }.map { pointerInput.sample(it) })
    }

    private val input = InputStack.InputHandler("composegl").also { it.pointerListeners += listener }

    init {
        scene.mainRenderPass.defaultView.onSetupView { render() }
        InputStack.pushTop(input)
    }

    fun setContent(content: @Composable () -> Unit) {
        host.setContent {
            ProvideFonts(backend.fonts) {
                ProvideClipboard(backend.clipboard) {
                    ProvideSoftKeyboard(backend.softKeyboard) {
                        ProvideInputSource(source, content)
                    }
                }
            }
        }
    }

    /** One frame: what waited for a context, the pointers since the last frame, then the interface. */
    private fun render() {
        KoolGl.deleteWaiting()
        val pass = scene.mainRenderPass
        val view = pass.defaultView.viewport
        // Kool's views count from the framebuffer's top-left, as the toolkit does.
        viewport = Viewport(
            design = design,
            physical = Size(pass.dimensions.x.toFloat(), pass.dimensions.y.toFloat()),
            policy = policy,
            area = Rect.of(view.x.toFloat(), view.y.toFloat(), view.width.toFloat(), view.height.toFloat()),
        )
        while (true) {
            val frame = pointerFrames.poll() ?: break
            val uses = pointerInput.onFrame(frame.pointers, frame.number)
            onPointerUsed?.let { report -> uses.forEach(report) }
        }
        renderer.render(viewport, clock())
    }

    /** Stops listening, and lets go of the composition. The backend stays the game's. */
    override fun close() {
        InputStack.remove(input)
        host.dispose()
    }
}

/**
 * Adds a ComposeGL screen to this context, on top of the scenes already added, and shows [content] in it.
 *
 * The one line a Kool game writes; [ComposeGlScene] is there for everything else. Kool has to be
 * rendering with OpenGL: on the desktop, `KoolConfigJvm(renderBackend = RenderBackendGl)`. On Android it
 * always is.
 */
fun KoolContext.composeGl(
    backend: KoolBackend,
    design: Size,
    policy: ScalePolicy = ScalePolicy.Fit,
    content: @Composable () -> Unit,
): ComposeGlScene {
    require(this.backend is RenderBackendGl) {
        "the ComposeGL frontend draws with OpenGL, and this Kool context renders with ${this.backend.name}: " +
            "start Kool with KoolConfigJvm(renderBackend = RenderBackendGl)"
    }
    return ComposeGlScene(backend, design, policy).also { ui ->
        ui.setContent(content)
        addScene(ui.scene)
    }
}
