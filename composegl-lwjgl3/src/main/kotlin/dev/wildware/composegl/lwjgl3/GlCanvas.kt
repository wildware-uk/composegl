package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.render.RenderCanvas
import dev.wildware.composegl.render.gl.Gl
import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.GlDeviceTarget
import dev.wildware.composegl.ui.layout.Viewport

/**
 * What [dev.wildware.composegl.ui.graphics.UiCanvas.raw] hands a game on this backend.
 *
 * There is no drawing object to pass — the drawing object is OpenGL, and it is global. What a
 * game's own render code needs is where to draw: the frame's projection, already in the interface's
 * coordinates with y measured up, and the viewport it was set up with.
 *
 * The interface's own quads have been flushed by the time this arrives, so whatever the game draws
 * lands on top of them.
 */
class GlFrame(val projection: FloatArray, val viewport: Viewport)

/**
 * The shared renderer, on raw OpenGL through LWJGL.
 *
 * All the drawing is [RenderCanvas]'s. This class only says which GL to call ([LwjglGl] or [LwjglGles]), which
 * textures it can draw ([GlTexture]) and what a game gets from `raw` ([GlFrame]).
 *
 * @param fonts where text is measured, and where solid colour is sampled from. Without it the
 *   canvas keeps a one-pixel white texture of its own and cannot draw text at all.
 * @param gl the binding for the context it draws on: the window's [GlfwWindow.context] binding.
 */
class GlCanvas(fonts: StbFonts? = null, gl: Gl = GlfwContext.Default.binding) : RenderCanvas(GlDevice(gl), fonts, GlTexture.Resolver) {

    /**
     * A frame drawn into the framebuffer called [framebuffer] rather than the window — an interface
     * on a surface in a 3D world. It is put back bound, with its viewport, when the frame ends.
     */
    fun begin(viewport: Viewport, framebuffer: Int) {
        if (framebuffer == 0) {
            begin(viewport)
        } else {
            val size = viewport.physical
            begin(viewport, GlDeviceTarget.adopt(framebuffer, 0, size.width.toInt(), size.height.toInt()))
        }
    }

    override fun handOver(projection: FloatArray, viewport: Viewport): Any = GlFrame(projection, viewport)
}
