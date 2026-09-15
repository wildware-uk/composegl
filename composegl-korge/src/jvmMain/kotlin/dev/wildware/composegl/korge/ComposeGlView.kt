package dev.wildware.composegl.korge

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.input.PointerType
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.widget.ProvideClipboard
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.ProvideSoftKeyboard
import korlibs.event.MouseButton
import korlibs.event.MouseEvent
import korlibs.korge.render.RenderContext
import korlibs.korge.view.Container
import korlibs.korge.view.View
import korlibs.math.geom.Point
import korlibs.math.geom.Rectangle
import kotlin.math.max
import kotlin.math.min

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
 * [policy], exactly as a `Viewport` fits one onto a window on the other backends, so `280f` means 280
 * on a 1280-wide design however big the view ends up.
 *
 * Each frame it does what `UiRenderer` does on every other backend — recompose, lay out, draw — with
 * the frame's time taken once, before KorGE renders anything, so two of these in one stage animate
 * on the same clock. The mouse goes to the toolkit's [PointerRouter]; keys, text and pads are the
 * next step.
 *
 * @param backend where the canvas and fonts come from. Not closed here: it is the game's.
 * @param design the size the screen is designed at.
 * @param policy how the design is fitted into the view.
 * @param clock monotonic nanoseconds, for the frame's time.
 */
class ComposeGlView(
    val backend: KorgeBackend,
    val design: Size,
    val policy: ScalePolicy = ScalePolicy.Fit,
    private val clock: () -> Long = System::nanoTime,
) : View(), AutoCloseable {

    val host = UiHost()

    val focus = FocusManager(host.root)

    val pointer = PointerRouter(host.root, focus, backend.cursor)

    val renderer = UiRenderer(host, backend.canvas).also { it.focus = focus }

    /**
     * Where pointer events go once they are in design coordinates. The router by default; a game that
     * wants first refusal wraps it.
     */
    var input: InputSink = pointer

    /** The viewport the last frame was laid out and drawn in, in the framebuffer's pixels. */
    var viewport: Viewport = Viewport.oneToOne(design)
        private set

    /** The size of the box the design is fitted into, in the parent's units. The stage's by default. */
    var boxSize: Size? = null

    private var frameNanos = -1L
    private var wired = false
    private val closeables = mutableListOf<AutoCloseable>()
    private val held = mutableSetOf<PointerButton>()

    fun setContent(content: @Composable () -> Unit) {
        host.setContent {
            ProvideFonts(backend.fonts) {
                ProvideClipboard(backend.clipboard) {
                    ProvideSoftKeyboard(backend.softKeyboard, content)
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

    /** The frame clock and the mouse, the first time this view is drawn in a stage. */
    private fun wire() {
        if (wired) return
        val stage = stage ?: return
        wired = true
        closeables += stage.views.onBeforeRender { frameNanos = clock() }
        closeables += stage.onEvents(*MouseEvent.Type.ALL) { event -> onMouse(event) }
    }

    /**
     * One KorGE mouse event, in the window's pixels, as the toolkit's. A translation and nothing else:
     * which node it hit and whether it was a click are the router's decisions.
     */
    internal fun onMouse(event: MouseEvent): Boolean {
        val at = viewport.toDesign(Offset(event.x.toFloat(), event.y.toFloat()))
        val now = clock() / 1_000_000
        return when (event.type) {
            MouseEvent.Type.DOWN -> {
                val button = event.button.asPointerButton() ?: return false
                held += button
                input.onPointer(PointerEvent.Press(PointerId.Mouse, at, button, PointerType.Mouse, now))
            }
            MouseEvent.Type.UP -> {
                val button = event.button.asPointerButton() ?: return false
                held -= button
                input.onPointer(PointerEvent.Release(PointerId.Mouse, at, button, PointerType.Mouse, now))
            }
            MouseEvent.Type.MOVE, MouseEvent.Type.DRAG ->
                input.onPointer(PointerEvent.Move(PointerId.Mouse, at, held.toSet(), PointerType.Mouse, now))
            MouseEvent.Type.EXIT ->
                input.onPointer(PointerEvent.Exit(PointerId.Mouse, at, PointerType.Mouse, now))
            // Click is the router's to decide, and enter says nothing a move does not.
            else -> false
        }
    }

    private fun MouseButton.asPointerButton(): PointerButton? = when (this) {
        MouseButton.LEFT -> PointerButton.Primary
        MouseButton.RIGHT -> PointerButton.Secondary
        MouseButton.MIDDLE -> PointerButton.Tertiary
        else -> null
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
