package dev.wildware.composegl.webgl

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.BackStack
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
import dev.wildware.composegl.ui.widget.ProvideHaptics
import dev.wildware.composegl.ui.widget.ProvideInputSource
import dev.wildware.composegl.ui.widget.ProvideSoftKeyboard
import dev.wildware.composegl.ui.widget.ProvideTextInput
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.WebGLRenderingContext as GL
import kotlin.math.roundToInt

/**
 * An interface running in a browser tab: the loop, the input and the wiring, for one canvas.
 *
 * Everything a desktop launcher writes by hand — a window, a frame loop, a pointer translator, a key
 * translator, a pad poll — a page already has in some form, so this is the one class a web game needs
 * beyond its own screens. It wires the toolkit the way every launcher in this repository does: a
 * [PointerRouter] for the pointer, a [KeyRouter] asked before a [KeyNavigator] so a field keeps the
 * keys it needs, a [GamepadNavigator] for the pad, all behind one [SourceAware] sink so prompts follow
 * the device, and the backend's fonts, clipboard, keyboard, input method and haptics provided to the
 * content.
 *
 * [start] runs it on `requestAnimationFrame`, which is the browser's own game loop: it stops when the
 * tab is hidden and matches the display's refresh. [frame] is one turn of it, for a page that runs its
 * own loop and for the tests.
 *
 * The canvas's backing store follows its size on the page times the device pixel ratio, so the
 * interface is sharp on a phone and the viewport letterboxes [design] into whatever shape the page
 * gives it.
 *
 * @param backend the canvas, fonts and the rest.
 * @param design the size the interface is laid out at.
 * @param policy how [design] is fitted to the canvas.
 * @param background what the letterbox is painted.
 * @param onBack what Escape, a pad's East and the browser's back key do once nothing on the
 *   [BackStack] took it.
 * @param input wraps the sink every event goes into, the way a game wraps its own.
 * @param pads where the pads are read from each frame: the browser's, unless a test hands in its own.
 * @param content the screen.
 */
class BrowserUi(
    val backend: WebGlBackend,
    design: Size,
    private val policy: ScalePolicy = ScalePolicy.Fit,
    private val background: Colour = Colour.rgb(0x0B0E13),
    onBack: () -> Unit = {},
    input: (InputSink) -> InputSink = { it },
    pads: () -> JsArray<JsGamepad?> = ::browserGamepads,
    content: @Composable () -> Unit,
) : AutoCloseable {

    /**
     * The size the interface is laid out at. Settable, for a page that reshapes its interface to the
     * window — a phone held upright gets a narrow layout rather than a wide one shrunk to fit — and
     * read again on every [frame].
     */
    var design: Size = design

    val host = UiHost()
    val focus = FocusManager(host.root)

    /** What the player last used. */
    val source = InputSourceTracker()

    /** Where `OnBack` handlers in the content put themselves. */
    val backs = BackStack()

    private val back = { if (!backs.back()) onBack() }
    private val pointerRouter = PointerRouter(host.root, focus, backend.cursor)
    private val keyRouter = KeyRouter(focus, host.root)
    private val keyNavigator = KeyNavigator(focus, onBack = back)
    private val padNavigator = GamepadNavigator(focus, onBack = back)

    /** Everything the page reports goes in here. */
    val sink: InputSink = input(SourceAware(
        source,
        object : InputSink {
            override fun onPointer(event: PointerEvent) = pointerRouter.onPointer(event)
            override fun onKey(event: KeyEvent) = keyRouter.onKey(event) || keyNavigator.onKey(event)
            override fun onText(event: TextEvent) = keyRouter.onText(event)
            override fun onGamepad(event: GamepadEvent) = padNavigator.onGamepad(event)
        },
    ))

    /** Where the interface sits in the canvas now. Follows the canvas's size, frame by frame. */
    var viewport: Viewport = viewportFor()
        private set

    val renderer = UiRenderer(host, backend.canvas).also {
        it.focus = focus
        // The pad's held-direction repeat, after layout so a step lands on something still there.
        it.onLaidOut = { millis -> padNavigator.frame(millis) }
    }

    val pointer = DomPointerInput(sink, backend.element, backend.textBox) { viewport }
    val keyboard = DomKeyboardInput(sink, backend.clipboard)
    val textInput = DomTextInput(sink, backend.textBox, backend.element)
    val gamepads = DomGamepadInput(sink, pads = pads)

    private val listeners = Listeners()
    private var running = false
    private var frameRequest = 0

    init {
        pointer.attach()
        keyboard.attachTo(backend.element)
        keyboard.attachTo(backend.textBox)
        keyboard.listenForPaste(document)
        textInput.attach()
        // A tab switched away from will not report the release of whatever was held when it went.
        listeners.add(window, "blur") {
            pointer.cancelAll()
            gamepads.releaseAll()
        }

        host.setContent {
            ProvideFonts(backend.fonts) {
                ProvideClipboard(backend.clipboard) {
                    ProvideSoftKeyboard(backend.softKeyboard) {
                        ProvideTextInput(textInput) {
                            ProvideHaptics(backend.haptics) {
                                ProvideInputSource(source) {
                                    ProvideBackStack(backs, content)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * One frame: fit the canvas to the page, read the pads, clear, and draw the interface.
     *
     * @param nanos the frame's time, from the page's clock — a `requestAnimationFrame` timestamp.
     * @return whether anything changed, as [UiRenderer.render] says.
     */
    fun frame(nanos: Long): Boolean {
        fitToPage()
        viewport = viewportFor()
        gamepads.poll()

        val gl = backend.gl
        gl.bindFramebuffer(GL.FRAMEBUFFER, null)
        gl.disable(GL.SCISSOR_TEST)
        gl.viewport(0, 0, backend.element.width, backend.element.height)
        gl.clearColor(background.red / 255f, background.green / 255f, background.blue / 255f, 1f)
        gl.clear(GL.COLOR_BUFFER_BIT)
        return renderer.render(viewport, nanos)
    }

    /** Runs [frame] on every animation frame until [stop] or [close]. */
    fun start() {
        if (running) return
        running = true
        schedule()
    }

    fun stop() {
        running = false
        window.cancelAnimationFrame(frameRequest)
    }

    private fun schedule() {
        frameRequest = window.requestAnimationFrame { millis ->
            if (!running) return@requestAnimationFrame
            frame((millis * 1_000_000.0).toLong())
            schedule()
        }
    }

    /** Stops the loop, takes every listener off, and lets go of the composition. The backend is the page's to close. */
    override fun close() {
        stop()
        listeners.clear()
        pointer.detach()
        keyboard.detach()
        textInput.detach()
        host.dispose()
    }

    /**
     * The backing store sized to the canvas's box on the page, in real pixels. A canvas with no box —
     * not in the page yet, or hidden — keeps the size it was given.
     */
    private fun fitToPage() {
        val element = backend.element
        val cssWidth = element.clientWidth
        val cssHeight = element.clientHeight
        if (cssWidth <= 0 || cssHeight <= 0) return
        val ratio = window.devicePixelRatio
        val width = (cssWidth * ratio).roundToInt()
        val height = (cssHeight * ratio).roundToInt()
        if (element.width != width) element.width = width
        if (element.height != height) element.height = height
    }

    private fun viewportFor() = Viewport(
        design = design,
        physical = Size(backend.element.width.toFloat(), backend.element.height.toFloat()),
        policy = policy,
    )
}
