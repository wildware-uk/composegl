package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.render.RenderTarget
import dev.wildware.composegl.render.gl.Gl
import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.GlDeviceTarget
import dev.wildware.composegl.ui.graphics.Colour

/**
 * A picture the toolkit draws into instead of the window: a screen on a wall, a terminal, the
 * display on a gun. The shared [RenderTarget], with its GL names and a [GlTexture] for the game.
 *
 * What comes out is **premultiplied**: draw the quad with `glBlendFunc(GL_ONE,
 * GL_ONE_MINUS_SRC_ALPHA)` for an ordinary screen, or `glBlendFunc(GL_ONE, GL_ONE)` for a hologram.
 *
 * ```kotlin
 * val target = GlRenderTarget(512, 256)
 * // in the game loop:
 * if (panel.needsRedraw(now)) panel.draw(canvas) { tree -> target.draw(canvas) { tree() } }
 * scene.drawQuad(target.texture)
 * ```
 *
 * @param gl the binding for the context it draws on, the same one as the canvas's.
 * @param depth give it a depth buffer, for a game drawing its own 3D scene into it — a model in an
 *   inventory slot, a level editor's view. Cleared with the colour by every [draw]. Without one a
 *   scene comes out inside-out, and the interface itself never needs it.
 */
class GlRenderTarget(
    width: Int,
    height: Int,
    gl: Gl = GlfwContext.Default.binding,
    depth: Boolean = false,
) : AutoCloseable {

    private val shared = RenderTarget(GlDevice(gl), width, height, depth)

    val width: Int get() = shared.width

    val height: Int get() = shared.height

    /** Whether it has a depth buffer to draw a 3D scene against. */
    val depth: Boolean get() = shared.depth

    /** Whether the last size asked for was bigger than this GPU's biggest texture and was cut to it. */
    val clamped: Boolean get() = shared.clamped

    private val gl: GlDeviceTarget? get() = shared.target as GlDeviceTarget?

    /** The framebuffer's GL name, or 0 once closed. */
    val framebufferName: Int get() = gl?.framebuffer ?: 0

    /** The colour texture's GL name, or 0 once closed. */
    val textureName: Int get() = gl?.texture?.name ?: 0

    /** The depth renderbuffer's GL name, or 0 where there is none. */
    val depthBufferName: Int get() = gl?.depthBuffer ?: 0

    /** The picture, for the game to map onto whatever it is drawing. Replaced by a [resize]. */
    var texture: GlTexture = picture()
        private set

    /**
     * Makes it a different size, giving the old framebuffer and texture back at once. A size bigger
     * than this GPU's biggest texture is cut down to it, and [clamped] says so.
     */
    fun resize(width: Int, height: Int) {
        shared.resize(width, height)
        texture = picture()
    }

    private fun picture(): GlTexture = GlTexture(textureName, shared.width, shared.height)

    /**
     * Draws into it: clears it to [clear], runs [block] in a frame of [canvas], and puts back the
     * framebuffer and viewport it found.
     */
    fun <T> draw(canvas: GlCanvas, clear: Colour = Colour.Transparent, block: () -> T): T = shared.draw(canvas, clear, block)

    /** Its pixels: premultiplied RGBA, the bottom row first. */
    fun readPixels(): ByteArray = shared.read()

    override fun close() = shared.close()
}
