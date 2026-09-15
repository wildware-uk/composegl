package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.graphics.TextureHandle
import korlibs.graphics.AGFrameBuffer
import korlibs.graphics.AGTexture

/**
 * The offscreen pictures a canvas draws layers into, kept and handed out again.
 *
 * A layer is one KorGE framebuffer, and a frame that blurs a panel wants the same one every frame
 * for as long as the panel is on screen. Making them per frame instead would be a texture allocation
 * and a driver stall sixty times a second. The same pool, with the same rules, as the LibGDX
 * backend's `GdxLayers`.
 *
 * Not shared between canvases, and not thread safe: it belongs to the canvas that made it, on the
 * thread that does the drawing.
 */
internal class KorgeLayers(private val spare: Int = SpareFrames) : AutoCloseable {

    class Layer(val buffer: AGFrameBuffer) {
        /** The handle the canvas gives out, one per layer, so a picture drawn back finds its buffer. */
        val picture = KorgeLayerPicture(buffer)

        var busy = false
        var idle = 0
    }

    private val layers = ArrayList<Layer>()

    /** How many layers exist right now. For the tests, and for anybody chasing memory. */
    val size: Int get() = layers.size

    /**
     * A layer exactly [width] by [height] pixels, either one that was free or a new one.
     *
     * Exactly, rather than the nearest bigger one: a layer is sampled back at one texel per pixel, and
     * a size that is nearly right is a picture that is slightly soft.
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
     * Called when a frame ends: anything that has gone [spare] frames without being wanted goes back
     * to the driver. Not immediately, because a panel blurred every other frame would otherwise pay
     * for a new texture every other frame.
     */
    fun trim() {
        layers.removeAll { layer ->
            if (layer.busy) return@removeAll false
            layer.idle++
            val stale = layer.idle > spare
            if (stale) layer.buffer.close()
            stale
        }
    }

    override fun close() {
        layers.forEach { it.buffer.close() }
        layers.clear()
    }

    private companion object {
        /** About a second of not being used, at sixty frames a second. */
        const val SpareFrames = 60

        /**
         * KorGE makes the texture and the framebuffer the first time one is drawn into, so a new
         * layer is only its size until then.
         */
        fun make(width: Int, height: Int): Layer = Layer(AGFrameBuffer().also { it.setSize(width, height) })
    }
}

/**
 * A layer's picture, wearing the toolkit's handle: a KorGE framebuffer's colour texture.
 *
 * Premultiplied, because that is what drawing into transparent black with the canvas's blending makes,
 * and y down from its first row, because the canvas draws into it the same way KorGE draws into any
 * render texture.
 */
internal class KorgeLayerPicture(val buffer: AGFrameBuffer) : TextureHandle {
    val texture: AGTexture get() = buffer.tex
    override val width: Int get() = buffer.width
    override val height: Int get() = buffer.height
}
