package uk.wildware.composegl.lwjgl3

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
internal class GlLayers(private val spare: Int = SpareFrames) : AutoCloseable {

    private class Entry(val target: GlRenderTarget) {
        var busy = false
        var idle = 0
    }

    private val entries = ArrayList<Entry>()

    /** How many layers exist right now. For the tests, and for anybody chasing memory. */
    val size: Int get() = entries.size

    /**
     * A target exactly [width] by [height] pixels, either one that was free or a new one.
     *
     * Exactly, rather than the nearest bigger one: a layer is sampled back at one texel per pixel,
     * and a size that is nearly right is a picture that is slightly soft.
     */
    fun acquire(width: Int, height: Int): GlRenderTarget {
        val free = entries.firstOrNull { !it.busy && it.target.width == width && it.target.height == height }
        val entry = free ?: Entry(GlRenderTarget(width, height)).also { entries += it }
        entry.busy = true
        entry.idle = 0
        return entry.target
    }

    fun release(target: GlRenderTarget) {
        entries.firstOrNull { it.target === target }?.busy = false
    }

    /**
     * Called when a frame ends: anything that has gone [spare] frames without being wanted goes
     * back to the driver.
     *
     * Not immediately, because an interface that shows a blurred panel every other frame would
     * otherwise pay for a new texture every other frame.
     */
    fun trim() {
        entries.removeAll { entry ->
            if (entry.busy) return@removeAll false
            entry.idle++
            val stale = entry.idle > spare
            if (stale) entry.target.close()
            stale
        }
    }

    override fun close() {
        entries.forEach { it.target.close() }
        entries.clear()
    }

    private companion object {
        /** About a second of not being used, at sixty frames a second. */
        const val SpareFrames = 60
    }
}
