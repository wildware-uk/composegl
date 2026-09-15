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
 * if (panel.needsRedraw(now)) target.draw(canvas) { panel.draw(canvas) }
 * scene.drawQuad(target.texture)
 * ```
 *
 * @param gl the binding for the context it draws on, the same one as the canvas's.
 */
class GlRenderTarget(width: Int, height: Int, gl: Gl = GlfwContext.Default.binding) : AutoCloseable {

    private val shared = RenderTarget(GlDevice(gl), width, height)

    val width: Int get() = shared.width

    val height: Int get() = shared.height

    private val gl: GlDeviceTarget? get() = shared.target as GlDeviceTarget?

    /** The framebuffer's GL name, or 0 once closed. */
    val framebufferName: Int get() = gl?.framebuffer ?: 0

    /** The colour texture's GL name, or 0 once closed. */
    val textureName: Int get() = gl?.texture?.name ?: 0

    /** The picture, for the game to map onto whatever it is drawing. Replaced by a [resize]. */
    var texture: GlTexture = GlTexture(textureName, width, height)
        private set

    /** Makes it a different size, giving the old framebuffer and texture back at once. */
    fun resize(width: Int, height: Int) {
        shared.resize(width, height)
        texture = GlTexture(textureName, width, height)
    }

    /**
     * Draws into it: clears it to [clear], runs [block] in a frame of [canvas], and puts back the
     * framebuffer and viewport it found.
     */
    fun <T> draw(canvas: GlCanvas, clear: Colour = Colour.Transparent, block: () -> T): T = shared.draw(canvas, clear, block)

    /** Its pixels: premultiplied RGBA, the bottom row first. */
    fun readPixels(): ByteArray = shared.read()

    override fun close() = shared.close()
}
