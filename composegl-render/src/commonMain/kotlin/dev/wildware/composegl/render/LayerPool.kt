package dev.wildware.composegl.render

/**
 * The offscreen pictures a canvas draws layers into, kept and handed out again.
 *
 * A frame that blurs a panel wants the same picture every frame for as long as the panel is on
 * screen. Making one per frame would be a texture allocation and a driver stall sixty times a
 * second. Not shared between canvases, and not thread safe.
 *
 * A layer whose size moves every frame — a card springing bigger, a panel sliding a pixel either
 * side of a whole one — still wants no new picture each frame. So a new picture is the size asked
 * for rounded up to a [Step] each way, and a layer is handed any free picture at least its size and
 * not more than a step past that rounding. The layer is drawn into the picture's bottom-left corner
 * at one texel per pixel and read back from there; the canvas clears the whole picture first.
 *
 * @param spare how many frames a picture may go unwanted before it goes back to the device.
 */
class LayerPool(private val device: GpuDevice, private val spare: Int = SpareFrames) {

    private class Entry(val target: DeviceTarget) {
        var busy = false
        var idle = 0

        /** Whether a layer was drawn into it this frame, and how big: see [acquire]. */
        var thisFrame = false
        var usedWidth = 0
        var usedHeight = 0
    }

    private val entries = ArrayList<Entry>()

    /** How many pictures exist right now. */
    val size: Int get() = entries.size

    /**
     * A picture at least [width] by [height] pixels, one that was free or a new one: the smallest
     * free one that fits, and not more than a [Step] past [width] and [height] rounded up to steps,
     * since the whole of it is cleared for every layer drawn into it. A new one is the size asked for
     * rounded up to steps, and never past what the device makes.
     *
     * The layer is drawn into the bottom-left [width] by [height] of it.
     *
     * A picture already drawn into this frame goes only to a layer of the same size, as before
     * pictures were shared across sizes: a layer is given back as soon as it is drawn, but may be
     * put down later in the frame, and a layer of the same size has always shared its picture. So
     * the bigger picture a springing card keeps is picked up from one frame to the next.
     */
    fun acquire(width: Int, height: Int): DeviceTarget {
        val widest = stepUp(width) + Step
        val tallest = stepUp(height) + Step
        var best: Entry? = null
        for (index in entries.indices) {
            val entry = entries[index]
            val target = entry.target
            if (entry.busy || target.width < width || target.height < height) continue
            if (target.width > widest || target.height > tallest) continue
            if (entry.thisFrame && (entry.usedWidth != width || entry.usedHeight != height)) continue
            if (best == null || target.width * target.height < best.target.width * best.target.height) best = entry
        }
        val entry = best ?: Entry(device.offscreen(rounded(width), rounded(height))).also { entries += it }
        entry.busy = true
        entry.idle = 0
        entry.thisFrame = true
        entry.usedWidth = width
        entry.usedHeight = height
        return entry.target
    }

    /** [size] rounded up to a step, but no bigger than the device makes, unless [size] is already. */
    private fun rounded(size: Int): Int = maxOf(size, minOf(stepUp(size), MaxLayerPixels, device.limits.maxTextureSize))

    private fun stepUp(size: Int): Int = (size + Step - 1) / Step * Step

    /**
     * Keeps [target] from being handed out again until it is [release]d once more: for a picture
     * still to be read after the layer that made it has given it back.
     */
    fun hold(target: DeviceTarget) {
        entryOf(target)?.busy = true
    }

    fun release(target: DeviceTarget) {
        entryOf(target)?.busy = false
    }

    /** By index, like every walk of [entries]: a layer is handed out and back every frame, and an iterator is garbage on a phone. */
    private fun entryOf(target: DeviceTarget): Entry? {
        for (index in entries.indices) {
            val entry = entries[index]
            if (entry.target === target) return entry
        }
        return null
    }

    /**
     * At the end of a frame: anything that has gone [spare] frames unwanted goes back to the device.
     * Not at once, or a panel blurred every other frame would pay for a picture every other frame.
     * Every picture kept is anybody's that fits again next frame.
     */
    fun trim() {
        // Kept entries are moved down over the stale ones in place, in their order: no lambda and no
        // iterator, since this runs every frame.
        var kept = 0
        for (index in entries.indices) {
            val entry = entries[index]
            entry.thisFrame = false
            if (!entry.busy && ++entry.idle > spare) {
                device.delete(entry.target)
                continue
            }
            entries[kept++] = entry
        }
        while (entries.size > kept) entries.removeAt(entries.size - 1)
    }

    /** The context went away with every picture in it: forget them without deleting anything. */
    fun forget() = entries.clear()

    fun close() {
        entries.forEach { device.delete(it.target) }
        entries.clear()
    }

    companion object {
        /** About a second of not being used, at sixty frames a second. */
        const val SpareFrames = 60

        /** The biggest layer a canvas asks for, each way. Past it a layer is refused, not half made. */
        const val MaxLayerPixels = 4096

        /**
         * What a new picture's size is rounded up to each way, in pixels. Big enough that a card
         * springing a few pixels a frame crosses a step every few frames rather than every frame;
         * small enough that a full-screen picture is only a few percent bigger than the screen.
         */
        const val Step = 64
    }
}
