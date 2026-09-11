package dev.wildware.composegl.gdx

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.utils.Disposable

/**
 * The offscreen pictures a canvas draws effects into, kept and handed out again.
 *
 * A layer is one texture and one framebuffer, and a frame that blurs a panel wants the same pair
 * every frame for as long as the panel is on screen. Making them per frame instead would be a
 * texture allocation and a driver stall sixty times a second, which is the classic way a pretty
 * effect costs more than the whole interface it is decorating.
 *
 * Not shared between canvases, and not thread safe: it belongs to the canvas that made it, on the
 * thread that does the drawing.
 */
internal class GdxLayers(private val spare: Int = SpareFrames) : Disposable {

    class Layer(val buffer: FrameBuffer) {
        /**
         * The picture, flipped once here.
         *
         * A framebuffer's first row is its bottom one, which is the right way up for OpenGL and
         * upside down for everybody else.
         */
        val texture: GdxTexture = GdxTexture(
            TextureRegion(buffer.colorBufferTexture).also { it.flip(false, true) },
        )

        var busy = false
        var idle = 0
    }

    private val layers = ArrayList<Layer>()

    /** How many layers exist right now. For the tests, and for anybody chasing memory. */
    val size: Int get() = layers.size

    /**
     * A layer exactly [width] by [height] pixels, either one that was free or a new one.
     *
     * Exactly, rather than the nearest bigger one: a layer is sampled back at one texel per pixel,
     * and a size that is nearly right is a picture that is slightly soft.
     */
    fun acquire(width: Int, height: Int): Layer {
        val free = layers.firstOrNull { !it.busy && it.buffer.width == width && it.buffer.height == height }
        val layer = free ?: make(width, height).also { layers += it }
        layer.busy = true
        layer.idle = 0
        return layer
    }

    fun release(layer: Layer) {
        layer.busy = false
    }

    /**
     * Called when a frame ends: anything that has gone [spare] frames without being wanted goes
     * back to the driver.
     *
     * Not immediately, because an interface that shows a blurred panel every other frame would
     * otherwise pay for a new texture every other frame.
     */
    fun trim() {
        layers.removeAll { layer ->
            if (layer.busy) return@removeAll false
            layer.idle++
            val stale = layer.idle > spare
            if (stale) layer.buffer.dispose()
            stale
        }
    }

    override fun dispose() {
        layers.forEach { it.buffer.dispose() }
        layers.clear()
    }

    private companion object {
        /** About a second of not being used, at sixty frames a second. */
        const val SpareFrames = 60

        fun make(width: Int, height: Int): Layer {
            val buffer = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
            // Stretched across a quad that is not always exactly its own size, so filtered.
            buffer.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            return Layer(buffer)
        }
    }
}
