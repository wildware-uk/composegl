package dev.wildware.composegl.render

/**
 * The offscreen pictures a canvas draws layers into, kept and handed out again.
 *
 * A frame that blurs a panel wants the same picture every frame for as long as the panel is on
 * screen. Making one per frame would be a texture allocation and a driver stall sixty times a
 * second. Not shared between canvases, and not thread safe.
 *
 * @param spare how many frames a picture may go unwanted before it goes back to the device.
 */
class LayerPool(private val device: GpuDevice, private val spare: Int = SpareFrames) {

    private class Entry(val target: DeviceTarget) {
        var busy = false
        var idle = 0
    }

    private val entries = ArrayList<Entry>()

    /** How many pictures exist right now. */
    val size: Int get() = entries.size

    /**
     * A picture exactly [width] by [height] pixels, one that was free or a new one. Exactly, because
     * a layer is sampled back at one texel per pixel and a nearly right size is a soft picture.
     */
    fun acquire(width: Int, height: Int): DeviceTarget {
        val free = entries.firstOrNull { !it.busy && it.target.width == width && it.target.height == height }
        val entry = free ?: Entry(device.offscreen(width, height)).also { entries += it }
        entry.busy = true
        entry.idle = 0
        return entry.target
    }

    fun release(target: DeviceTarget) {
        entries.firstOrNull { it.target === target }?.busy = false
    }

    /**
     * At the end of a frame: anything that has gone [spare] frames unwanted goes back to the device.
     * Not at once, or a panel blurred every other frame would pay for a picture every other frame.
     */
    fun trim() {
        entries.removeAll { entry ->
            if (entry.busy) return@removeAll false
            entry.idle++
            val stale = entry.idle > spare
            if (stale) device.delete(entry.target)
            stale
        }
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
    }
}
