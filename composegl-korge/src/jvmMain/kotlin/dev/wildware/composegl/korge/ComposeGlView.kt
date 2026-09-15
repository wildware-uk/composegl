package dev.wildware.composegl.korge

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.BackStack
import dev.wildware.composegl.ui.input.GamepadCursor
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.InputSourceTracker
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.input.SourceAware
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.widget.ProvideBackStack
import dev.wildware.composegl.ui.widget.ProvideClipboard
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.ProvideGamepadCursor
import dev.wildware.composegl.ui.widget.ProvideHaptics
import dev.wildware.composegl.ui.widget.ProvideInputSource
import dev.wildware.composegl.ui.widget.ProvideSoftKeyboard
import dev.wildware.composegl.ui.widget.ProvideTextInput
import korlibs.korge.render.RenderContext
import korlibs.korge.service.vibration.vibration
import korlibs.korge.view.Container
import korlibs.korge.view.View
import korlibs.math.geom.Point
import korlibs.math.geom.Rectangle
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * A ComposeGL screen, as a KorGE view.
 *
 * ```kotlin
 * suspend fun main() = Korge(windowSize = Size(1280, 720)) {
 *     val fonts = KorgeFonts().apply { registerTrueType("default", resourcesVfs["DejaVuSans.ttf"].readAll(), listOf(16)) }
 *     composeGl(KorgeBackend(fonts) { views.gameWindow }, Size(1280f, 720f)) { MainMenu() }
 * }
 * ```
 *
 * Put it anywhere in the stage and it draws there, at its own place in the draw order, sized to the
 * view — the stage's size unless the game says otherwise. The design size is fitted into that box by
 * [policy], exactly as a `Viewport` fits one onto a window on the other backends.
 *
 * Each frame it does what `UiRenderer` does on every other backend — recompose, lay out, draw — with
 * the frame's time taken once, before KorGE renders anything, so two of these in one stage animate on
 * the same clock.
 *
 * **Input** is wired the way the LibGDX demo wires it, so a KorGE screen behaves like one: the mouse
 * and touches go to the [pointer] router, keys to whatever has [focus] and then to the keyboard's
 * own navigation (Tab, arrows, Enter, Escape), typed text to the focused field, and pads to the
 * [cursor] when a `VirtualCursor` is on screen and otherwise to pad navigation. Everything passes
 * [source] on the way, so focus rings, prompts and haptics follow what the player last touched.
 * Escape, B and Back ask [backs] first and then [onBack].
 *
 * Split-screen: build each player's view with `listens = false`, and feed one [KorgeInput] into an
 * `InputRouter` that hands each player's events to their view's [player].
 *
 * @param backend where the canvas and fonts come from. Not closed here: it is the game's.
 * @param design the size the screen is designed at.
 * @param policy how the design is fitted into the view.
 * @param clock monotonic nanoseconds, for the frame's time.
 * @param listens whether this view listens to the stage's input itself. False when a game routes it.
 */
class ComposeGlView(
    val backend: KorgeBackend,
    val design: Size,
    val policy: ScalePolicy = ScalePolicy.Fit,
    private val clock: () -> Long = System::nanoTime,
    val listens: Boolean = true,
) : View(), AutoCloseable {

    val host = UiHost()

    val focus = FocusManager(host.root)

    val pointer = PointerRouter(host.root, focus, backend.cursor)

    /** What the player is using now: a mouse, a finger, keys or a pad. */
    val source = InputSourceTracker()

    /** What Escape, B and Back close first: anything open that put itself here. */
    val backs = BackStack()

    /** What Escape, B and Back do when nothing on [backs] wanted them. Usually "leave this screen". */
    var onBack: () -> Unit = {}

    private val back = { if (!backs.back()) onBack() }

    private val keys = KeyRouter(focus, host.root)

    private val keyNavigator = KeyNavigator(focus, onBack = back)

    private val padNavigator = GamepadNavigator(focus, onBack = back)

    /** The arrow a pad pushes around while a `VirtualCursor` is on screen. */
    val cursor = GamepadCursor(host.root, pointer)

    /**
     * This screen's end of the input contract, in design units: the routers and navigators behind
     * one [SourceAware]. What an `InputRouter` hands a player's events to.
     */
    val player: InputSink = SourceAware(
        source,
        object : InputSink {
            override fun onPointer(event: PointerEvent) = pointer.onPointer(event)

            // The router first, always: a field that wanted a key has taken it before navigation is asked.
            override fun onKey(event: KeyEvent) = keys.onKey(event) || keyNavigator.onKey(event)

            override fun onText(event: TextEvent) = keys.onText(event)

            override fun onGamepad(event: GamepadEvent) = cursor.onGamepad(event) || padNavigator.onGamepad(event)
        },
    )

    /**
     * Where this view's translated events go. [player] by default; a game that wants first refusal
     * wraps it.
     */
    var input: InputSink = player

    /** Always the current [input], so it can be swapped after the translators are built. */
    private val forward = object : InputSink {
        override fun onPointer(event: PointerEvent) = input.onPointer(event)
        override fun onKey(event: KeyEvent) = input.onKey(event)
        override fun onText(event: TextEvent) = input.onText(event)
        override fun onGamepad(event: GamepadEvent) = input.onGamepad(event)
    }

    /** The KorGE side: what turns the stage's events into the toolkit's, in this view's viewport. */
    val translators = KorgeInput(
        forward,
        viewport = { viewport },
        stageToWindow = { at -> stage?.views?.globalToWindowCoords(at) ?: at },
        clock = { clock() / 1_000_000 },
    )

    val renderer = UiRenderer(host, backend.canvas).also {
        it.focus = focus
        it.onLaidOut = { millis -> frame(millis) }
    }

    /** The viewport the last frame was laid out and drawn in, in the framebuffer's pixels. */
    var viewport: Viewport = Viewport.oneToOne(design)
        private set

    /** The size of the box the design is fitted into, in the parent's units. The stage's by default. */
    var boxSize: Size? = null

    private var frameNanos = -1L
    private var wired = false
    private val closeables = mutableListOf<AutoCloseable>()

    fun setContent(content: @Composable () -> Unit) {
        host.setContent {
            ProvideFonts(backend.fonts) {
                ProvideClipboard(backend.clipboard) {
                    ProvideSoftKeyboard(backend.softKeyboard) {
                        ProvideHaptics(backend.haptics) {
                            ProvideTextInput(translators.text) {
                                ProvideInputSource(source) {
                                    ProvideGamepadCursor(cursor) {
                                        ProvideBackStack(backs, content)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun size(): Size = boxSize ?: stage?.let { Size(it.views.virtualWidth.toFloat(), it.views.virtualHeight.toFloat()) } ?: design

    override fun getLocalBoundsInternal(): Rectangle = size().let { Rectangle(0.0, 0.0, it.width.toDouble(), it.height.toDouble()) }

    override fun renderInternal(ctx: RenderContext) {
        wire()
        viewport = viewportFor(ctx)
        backend.canvas.renderContext = ctx
        try {
            renderer.render(viewport, if (frameNanos >= 0) frameNanos else clock())
        } finally {
            backend.canvas.renderContext = null
        }
        // A held direction repeats and a pad-driven cursor glides, both on the frame clock: ask for
        // the next frame even when nothing on screen has changed yet.
        if (padNavigator.direction != null || cursor.enabled) invalidateRender()
    }

    /** Once a frame, after layout: focus off anything that went, then the pad's repeat and the cursor. */
    private fun frame(millis: Long) {
        focus.refresh()
        cursor.frame(millis)
        padNavigator.frame(millis)
    }

    /**
     * The design fitted into this view's box, as the framebuffer's pixels see it.
     *
     * The box goes through the view's own matrix and, for the window, the stage's scale onto it. A
     * turned view is drawn upright in the box its corners cover — turning an interface is a layer's
     * job, and that comes later.
     */
    internal fun viewportFor(ctx: RenderContext): Viewport {
        val framebuffer = ctx.currentFrameBuffer
        val matrix = if (framebuffer.isTexture) globalMatrix else globalMatrix * ctx.projectionMatrixTransform
        val box = size()
        val topLeft = matrix.transform(Point(0.0, 0.0))
        val bottomRight = matrix.transform(Point(box.width.toDouble(), box.height.toDouble()))
        val area = Rect(
            min(topLeft.x, bottomRight.x).toFloat(),
            min(topLeft.y, bottomRight.y).toFloat(),
            max(topLeft.x, bottomRight.x).toFloat(),
            max(topLeft.y, bottomRight.y).toFloat(),
        )
        return Viewport(
            design = design,
            physical = Size(framebuffer.width.toFloat(), framebuffer.height.toFloat()),
            policy = policy,
            area = area,
        )
    }

    /** The frame clock, the phone's motor and the stage's input, the first time this view is drawn. */
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun wire() {
        if (wired) return
        val stage = stage ?: return
        wired = true
        closeables += stage.views.onBeforeRender { frameNanos = clock() }
        backend.haptics.let { haptics ->
            if (haptics.source == null) haptics.source = source
            if (haptics.vibrator == null) {
                haptics.vibrator = { millis, strength -> stage.views.vibration.vibrate(millis.milliseconds, strength.toDouble()) }
            }
        }
        if (listens) closeables += translators.listen(stage)
    }

    /** Stops listening, and lets go of the composition. The backend stays the game's. */
    override fun close() {
        closeables.forEach { it.close() }
        closeables.clear()
        host.dispose()
    }
}

/**
 * Adds a ComposeGL screen to this container, sized to the stage, and shows [content] in it.
 *
 * The one line a KorGE game writes; [ComposeGlView] is there for everything else.
 */
fun Container.composeGl(
    backend: KorgeBackend,
    design: Size,
    policy: ScalePolicy = ScalePolicy.Fit,
    content: @Composable () -> Unit,
): ComposeGlView = ComposeGlView(backend, design, policy).also { view ->
    view.setContent(content)
    addChild(view)
}
