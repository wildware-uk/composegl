package dev.wildware.composegl.render

import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.layout.Viewport

/**
 * How a backend's own texture type reaches the shared canvas: a function from the toolkit's handle
 * to something the device can bind. Null means "not one of ours", and the canvas refuses it by name.
 */
fun interface TextureResolver {
    fun resolve(handle: TextureHandle): BoundPicture?
}

/**
 * A picture as the device binds it: the texture, where in it the picture is (texture coordinates,
 * `v` the top edge), and whether its colours are already multiplied by their opacity.
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
)

/**
 * A picture [RenderCanvas.layer] made. Good until the end of the frame. Premultiplied.
 *
 * Its texture coordinates count the way the device's offscreen pictures do: a framebuffer's first
 * row is its bottom one, so an OpenGL layer comes back with `v` and `v2` swapped and nothing
 * downstream has to know.
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
 * What [RenderCanvas.raw] hands a block by default: the frame's projection, already in the
 * interface's coordinates with y measured up, and the viewport it was set up with. A backend hands
 * its own type instead.
 */
class RenderFrame(val projection: FloatArray, val viewport: Viewport)
