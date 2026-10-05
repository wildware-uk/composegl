package dev.wildware.composegl.render

import dev.wildware.composegl.ui.graphics.SceneSurface
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.layout.Viewport

/**
 * How a backend's own texture type reaches the shared canvas: a function from the toolkit's handle
 * to something the device can bind. Null means "not one of ours", and the canvas refuses it by name.
 *
 * It is called while a frame is drawing. It may bind and upload textures and set their parameters,
 * and must leave every other piece of GL state as it found it: the device remembers the program,
 * buffers, blending, scissor and vertex layout it set, and does not set them again.
 */
fun interface TextureResolver {
    fun resolve(handle: TextureHandle): BoundPicture?
}

/**
 * A picture as the device binds it: the texture, where in it the picture is (texture coordinates,
 * `v` the top edge), whether its colours are already multiplied by their opacity, and whether it
 * lies on its side.
 *
 * A backend keeps one per handle rather than making one per draw.
 */
class BoundPicture(
    val texture: DeviceTexture,
    val u: Float = 0f,
    val v: Float = 0f,
    val u2: Float = 1f,
    val v2: Float = 1f,
    val premultiplied: Boolean = false,
    /**
     * True when a packer laid the picture down a quarter turn. Drawing all of it is fine, since the
     * texture coordinates already say so; drawing part of it is refused.
     */
    val rotated: Boolean = false,
)

/**
 * A picture [RenderCanvas.layer] made. Good until the end of the frame. Premultiplied.
 *
 * Its texture coordinates count the way the device's offscreen pictures do: a framebuffer's first
 * row is its bottom one, so an OpenGL layer comes back with `v` and `v2` swapped and nothing
 * downstream has to know.
 *
 * It is the bottom-left [width] by [height] pixels of [target], which the canvas's pool often hands
 * out a little bigger than the layer: so `u2` and `v` are that corner's far edges, and can be less
 * than one. What lies round the corner is clear.
 */
class LayerPicture internal constructor(
    val target: DeviceTarget,
    override val width: Int,
    override val height: Int,
    val u: Float,
    val v: Float,
    val u2: Float,
    val v2: Float,
) : TextureHandle

/**
 * A picture [RenderCanvas.scene] made: colour and depth, owned until [close]. Premultiplied, and
 * counted the way a framebuffer is, bottom row first, which the canvas turns over when it draws it.
 */
class ScenePicture internal constructor(
    internal val target: RenderTarget,
    internal val device: GpuDevice,
) : SceneSurface {

    override val width: Int get() = target.width

    override val height: Int get() = target.height

    /** The colour texture, as the device binds it. */
    val texture: DeviceTexture get() = checkNotNull(target.target) { "this scene picture has been closed" }.texture

    override var closed: Boolean = false
        private set

    override fun close() {
        if (closed) return
        closed = true
        target.close()
    }
}

/**
 * What [RenderCanvas.raw] hands a block by default: the frame's projection, already in the
 * interface's coordinates with y measured up, and the viewport it was set up with. A backend hands
 * its own type instead.
 */
class RenderFrame(val projection: FloatArray, val viewport: Viewport)
