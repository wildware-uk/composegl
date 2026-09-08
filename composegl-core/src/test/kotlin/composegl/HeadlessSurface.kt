package composegl

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers

/**
 * A [HostServices] that just writes down what it was asked to do.
 */
class RecordingHost(override val density: Float = 1f) : HostServices {
    var frameRequests = 0
    var clipboardText: String? = null
    var softKeyboard: Boolean? = null
    val cursors = mutableListOf<CursorShape>()

    override fun requestFrame() { frameRequests++ }
    override fun setCursor(cursor: CursorShape) { cursors += cursor }
    override fun getClipboard(): String? = clipboardText
    override fun setClipboard(text: String) { clipboardText = text }
    override fun showSoftKeyboard(visible: Boolean) { softKeyboard = visible }
}

/**
 * A whole ComposeGL stack with no GPU behind it: real scene, real clock, real dispatcher, real
 * input path, real Skia — just drawing into a CPU bitmap instead of a framebuffer.
 *
 * This is what makes the core suite runnable in CI on a machine with no display. Compose draws
 * into a raster surface by exactly the same path it draws into GL, so a test here is not a
 * simulation of the real thing; it is the real thing minus the framebuffer.
 *
 * ```kotlin
 * HeadlessSurface(200, 100).use { ui ->
 *     ui.setContent { Button(onClick = { clicks++ }) { Text("go") } }
 *     ui.click(100f, 50f)
 *     assertEquals(1, clicks)
 * }
 * ```
 */
class HeadlessSurface(
    val width: Int = 200,
    val height: Int = 100,
    density: Float = 1f,
    config: SurfaceConfig = SurfaceConfig(),
) : AutoCloseable {

    val host = RecordingHost(density)
    val context = ComposeGlContext.createRaster(ContextConfig(debugChecks = true))
    val surface = ComposeSurface(context, host, config)

    private var nanos = 0L

    val stats: SurfaceStats get() = surface.stats
    val hasKeyboardFocus: Boolean get() = surface.hasKeyboardFocus

    /** Sets the content, sizes the target, and draws the first frame. */
    fun setContent(content: @Composable () -> Unit): HeadlessSurface {
        surface.setContent(content)
        surface.setRenderTarget(RenderTarget.Raster(width, height))
        frame()
        return this
    }

    /** Advances [count] frames at a steady 60 fps, rendering whenever Compose says it must. */
    fun frame(count: Int = 1) {
        repeat(count) {
            nanos += 16_666_667
            surface.update(nanos)
            if (surface.needsRedraw) surface.render(nanos)
        }
    }

    /**
     * Pumps frames until [condition] holds, or fails after [timeoutMillis] of simulated frames.
     * Used for anything that resumes off-thread, like `delay` inside a `LaunchedEffect`.
     */
    fun frameUntil(timeoutMillis: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out after ${timeoutMillis}ms of frames" }
            frame()
            Thread.sleep(1)
        }
    }

    /** Premultiplied ARGB at (x, y), measured from the top-left like Compose. */
    fun pixelAt(x: Int, y: Int): Int {
        val pixels = surface.readPixels() ?: error("no render target; call setContent first")
        require(x in 0 until width && y in 0 until height) { "($x, $y) is outside ${width}x$height" }
        return pixels[y * width + x]
    }

    fun centrePixel(): Int = pixelAt(width / 2, height / 2)

    fun move(x: Float, y: Float, id: Int = 0): Boolean =
        surface.sendPointerEvent(PointerEventType.Move, x, y, pointerId = id)

    fun press(x: Float, y: Float, id: Int = 0, button: PointerButton = PointerButton.Primary): Boolean =
        surface.sendPointerEvent(PointerEventType.Press, x, y, pointerId = id, button = button)

    fun release(x: Float, y: Float, id: Int = 0, button: PointerButton = PointerButton.Primary): Boolean =
        surface.sendPointerEvent(PointerEventType.Release, x, y, pointerId = id, button = button)

    /** A full press-release at one point. Returns whether Compose took the press. */
    fun click(x: Float, y: Float, id: Int = 0): Boolean {
        move(x, y, id)
        val consumed = press(x, y, id)
        release(x, y, id)
        frame()
        return consumed
    }

    fun scroll(x: Float, y: Float, amount: Float): Boolean =
        surface.sendPointerEvent(PointerEventType.Scroll, x, y, scrollY = amount)

    /** A key down followed by a key up. Returns whether Compose took the down. */
    fun tap(key: Key, modifiers: PointerKeyboardModifiers = PointerKeyboardModifiers()): Boolean {
        val consumed = surface.sendKeyEvent(key, down = true, modifiers = modifiers)
        surface.sendKeyEvent(key, down = false, modifiers = modifiers)
        frame()
        return consumed
    }

    /** Types text the way a keyboard would: characters, not key codes. */
    fun type(text: String) {
        text.forEach {
            surface.sendChar(it.code)
            frame()
        }
    }

    override fun close() = context.dispose()
}
