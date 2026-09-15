package dev.wildware.composegl.webgl

import dev.wildware.composegl.ui.backend.MapTextureSource
import dev.wildware.composegl.ui.backend.TextureSource
import dev.wildware.composegl.ui.backend.UiBackend
import org.khronos.webgl.WebGLRenderingContext as GL
import org.w3c.dom.HTMLCanvasElement

/**
 * Everything the toolkit needs from a browser page: a WebGL canvas, the page's own fonts, a
 * clipboard as far as a page may have one, the cursor, the phone's keyboard and a buzz.
 *
 * A thin wrapper round the library's own renderer: WebGL through [WebGl], glyphs from the page's 2D
 * canvas, and the DOM's input. Held to the same test scenes as every other backend — so the whole
 * toolkit, every widget and every screen a game has already written, runs in a browser tab unchanged.
 *
 * Input is not in here, for the reason it is not in any backend: translating it is per platform and
 * belongs with whatever runs the loop. [BrowserUi] is that, for a page.
 *
 * ```kotlin
 * val fonts = WebFonts().apply { load("body", "fonts/DejaVuSans.ttf", listOf(13, 16, 20)) }
 * val backend = WebGlBackend(document.getElementById("game") as HTMLCanvasElement, fonts)
 * BrowserUi(backend, Size(1280f, 720f)) { MainMenu() }.start()
 * ```
 *
 * @param element the canvas to draw on. It is made focusable, so it can take the keyboard.
 * @param fonts where text is measured and drawn from.
 * @param textures where pictures come from by name. Load them with [loadTexture] and hand them over.
 * @param preserveDrawingBuffer keep each frame after it is shown, so it can be read back — for a
 *   screenshot or a test. Off by default, because a browser composites faster without it.
 */
class WebGlBackend(
    val element: HTMLCanvasElement,
    override val fonts: WebFonts,
    override val textures: TextureSource = MapTextureSource(),
    preserveDrawingBuffer: Boolean = false,
) : UiBackend, AutoCloseable {

    /** The context: WebGL 2 where the browser has it, WebGL 1 where it does not. */
    val gl: GL = webGlContext(element, preserveDrawingBuffer)
        ?: error("this browser would not give the page a WebGL context; it may be switched off")

    /** Which of the two [gl] is. The renderer compiles its shaders for whichever it is given. */
    val isWebGl2: Boolean = isWebGl2(gl)

    override val canvas = WebGlCanvas(gl, fonts)

    /** The invisible text box an input method composes into, and a phone raises its keyboard for. */
    val textBox = DomTextInput.hiddenTextBox(element)

    override val clipboard = DomClipboard()

    override val softKeyboard = DomSoftKeyboard(textBox, element)

    override val cursor = DomSystemCursor(element)

    override val haptics = DomHaptics()

    private val listeners = Listeners()

    init {
        if (!element.hasAttribute("tabindex")) element.tabIndex = 0
        // Focus is drawn by the interface itself; the browser's ring round the whole canvas is noise.
        element.style.outline = "none"
        // The browser can take the GPU away — a driver reset, too many tabs — and give it back. Every
        // WebGL object dies with it, so the renderer forgets them and builds again on the next frame.
        // Preventing the default is what asks for the context back.
        listeners.add(element, "webglcontextlost") { event ->
            event.preventDefault()
            canvas.contextLost()
        }
        listeners.add(element, "webglcontextrestored") {
            canvas.binding.restored()
            canvas.contextLost()
        }
    }

    /** Fetches a picture the browser can decode and uploads it to this backend's context. */
    suspend fun loadTexture(url: String, smooth: Boolean = true): WebGlTexture = WebGlTexture.load(gl, url, smooth)

    /** Lets go of the canvas's GPU resources, the glyph atlas and the text box. The element is the page's. */
    override fun close() {
        listeners.clear()
        canvas.close()
        fonts.close()
        textBox.remove()
    }
}

private fun webGlContext(canvas: HTMLCanvasElement, preserve: Boolean): GL? = js(
    """(() => { const o = { alpha: false, antialias: false, premultipliedAlpha: true, preserveDrawingBuffer: preserve }; return canvas.getContext('webgl2', o) || canvas.getContext('webgl', o); })()""",
)
