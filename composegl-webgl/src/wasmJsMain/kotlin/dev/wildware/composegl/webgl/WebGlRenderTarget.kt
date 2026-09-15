package dev.wildware.composegl.webgl

import dev.wildware.composegl.render.RenderTarget
import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.GlDeviceTarget
import dev.wildware.composegl.ui.graphics.Colour
import org.khronos.webgl.WebGLFramebuffer
import org.khronos.webgl.WebGLRenderingContext as GL
import org.khronos.webgl.WebGLTexture

/**
 * A picture the toolkit draws into instead of the page's canvas: a screen on a wall in a WebGL
 * scene, a minimap, a card being flipped. The shared [RenderTarget], with WebGL objects for the game.
 *
 * What comes out is **premultiplied**, as on the desktop, so a game puts it on a quad with
 * `gl.blendFunc(ONE, ONE_MINUS_SRC_ALPHA)` and needs no shader of its own.
 */
class WebGlRenderTarget(private val gl: GL, width: Int, height: Int) : AutoCloseable {

    private val shared = RenderTarget(GlDevice(WebGl(gl)), width, height)

    val width: Int get() = shared.width

    val height: Int get() = shared.height

    private val target: GlDeviceTarget? get() = shared.target as GlDeviceTarget?

    /** The framebuffer, for a game with its own renderer. */
    val framebufferName: WebGLFramebuffer
        get() = checkNotNull(target?.let { WebGl.objectOf<WebGLFramebuffer>(it.framebuffer) }) { "this render target has been closed" }

    /** The picture, for the game to map onto whatever it draws. Replaced by a [resize]. */
    var texture: WebGlTexture = picture()
        private set

    /** Makes it a different size, deleting the old framebuffer and texture now rather than leaking them. */
    fun resize(width: Int, height: Int) {
        shared.resize(width, height)
        texture = picture()
    }

    /**
     * Draws into it: clears it to [clear], runs [block] between the canvas's begin and end, and puts
     * back the framebuffer and the viewport that were there before.
     */
    fun <T> draw(canvas: WebGlCanvas, clear: Colour = Colour.Transparent, block: () -> T): T = shared.draw(canvas, clear, block)

    /** Its pixels: premultiplied RGBA, the bottom row first. */
    fun readPixels(): ByteArray = shared.read()

    override fun close() = shared.close()

    private fun picture(): WebGlTexture {
        val name = checkNotNull(target?.let { WebGl.objectOf<WebGLTexture>(it.texture.name) }) { "this render target has been closed" }
        return WebGlTexture(gl, name, shared.width, shared.height)
    }
}
