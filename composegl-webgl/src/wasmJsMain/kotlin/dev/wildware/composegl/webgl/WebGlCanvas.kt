package dev.wildware.composegl.webgl

import dev.wildware.composegl.render.RenderCanvas
import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.GlDeviceTarget
import dev.wildware.composegl.render.gl.HostState
import dev.wildware.composegl.ui.layout.Viewport
import org.khronos.webgl.WebGLFramebuffer
import org.khronos.webgl.WebGLRenderingContext as GL
import kotlin.math.roundToInt

/**
 * What [dev.wildware.composegl.ui.graphics.UiCanvas.raw] hands a game on this backend: the context
 * itself, the frame's projection in the interface's coordinates, and the viewport it was set up with.
 *
 * The interface's own quads have been flushed by the time this arrives, and the context is in the
 * renderer's documented end state. Leave it as you would want to find it.
 */
class WebGlFrame(val gl: GL, val projection: FloatArray, val viewport: Viewport)

/**
 * The shared renderer, on a page's WebGL.
 *
 * All the drawing is [RenderCanvas]'s. This class only says which WebGL to call ([WebGl]), which
 * textures it can draw ([WebGlTexture]) and what a game gets from `raw` ([WebGlFrame]). WebGL 2
 * where the page's context is one, WebGL 1 where it is not.
 *
 * @param gl the page's context. Nothing is built on it until the first frame, or [warmUp].
 * @param fonts where text is measured, and where solid colour is sampled from, so a panel and its
 *   label are one draw call. Without it the canvas keeps a white pixel of its own and draws no text.
 * @param handOver how the context is left for the page: [HostState.Leave] when this canvas owns it,
 *   [HostState.Restore] when it shares it with a library that caches WebGL state, such as three.js.
 */
class WebGlCanvas private constructor(
    val gl: GL,
    internal val binding: WebGl,
    fonts: WebFonts?,
    handOver: HostState,
) : RenderCanvas(GlDevice(binding, handOver), fonts, WebGlTexture.Resolver) {

    constructor(gl: GL, fonts: WebFonts? = null, handOver: HostState = HostState.Leave) : this(gl, WebGl(gl), fonts, handOver)

    /**
     * A frame drawn into [framebuffer] rather than the page's canvas — a render target's. It is put
     * back bound, with its viewport, when the frame ends. Null is the page's canvas.
     */
    fun begin(viewport: Viewport, framebuffer: WebGLFramebuffer?) {
        if (framebuffer == null) {
            begin(viewport)
        } else {
            val size = viewport.physical
            begin(viewport, GlDeviceTarget.adopt(WebGl.handleOf(framebuffer), 0, size.width.roundToInt(), size.height.roundToInt()))
        }
    }

    override fun handOver(projection: FloatArray, viewport: Viewport): Any = WebGlFrame(gl, projection, viewport)
}
