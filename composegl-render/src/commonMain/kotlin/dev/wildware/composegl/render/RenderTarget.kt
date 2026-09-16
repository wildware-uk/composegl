package dev.wildware.composegl.render

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Viewport

/**
 * A picture the toolkit draws into instead of the window: a screen on a wall, a terminal, the
 * display on a gun, a 3D scene inside a panel. The same tree, the same canvas; only where the
 * pixels land differs.
 *
 * What comes out is **premultiplied**, which is what makes it usable in a 3D scene without a shader
 * of its own: draw it with `ONE, ONE_MINUS_SRC_ALPHA` for a screen, or `ONE, ONE` for a hologram.
 *
 * Made on [device] at once, so it needs the context that device draws on.
 *
 * @param depth give it a depth buffer as well, for a game drawing its own 3D scene into it. Made
 *   and given back with the picture, always its size, and cleared to the far plane whenever [draw]
 *   clears the colour. Without one a scene comes out inside-out: there is nothing to test against.
 *   The interface never needs it, so it is off by default.
 */
class RenderTarget(
    private val device: GpuDevice,
    width: Int,
    height: Int,
    val depth: Boolean = false,
) : AutoCloseable {

    /** How wide the picture really is: what was asked for, or the most this device makes. */
    var width: Int = 0
        private set

    var height: Int = 0
        private set

    /**
     * Whether the last size asked for was bigger than this device's biggest texture, and so was cut
     * down to it. A panel on a 4K screen can ask for more than a small GPU will make, and a picture
     * a little smaller is better than an allocation that fails in the middle of a frame.
     */
    var clamped: Boolean = false
        private set

    private var closed = false

    /** The device's picture, or null once closed. Replaced by a [resize]. */
    var target: DeviceTarget? = null
        private set

    init {
        require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
        allocate(width, height)
    }

    /**
     * Makes it a different size. The old picture is given back here rather than left to a
     * collector: a panel that follows a window's size would otherwise leak one per resize.
     *
     * A size that clamps to the one it already has costs nothing: dragging a window wider than the
     * GPU's biggest texture does not reallocate a picture a frame.
     */
    fun resize(width: Int, height: Int) {
        check(!closed) { "this render target has been closed" }
        require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
        if (fit(width) == this.width && fit(height) == this.height) {
            clamped = width > this.width || height > this.height
            return
        }
        target?.let(device::delete)
        target = null
        allocate(width, height)
    }

    private fun allocate(width: Int, height: Int) {
        this.width = fit(width)
        this.height = fit(height)
        clamped = width > this.width || height > this.height
        target = device.offscreen(this.width, this.height, depth)
    }

    /** [size] cut down to the biggest texture this device will make. */
    private fun fit(size: Int): Int = size.coerceAtMost(device.limits.maxTextureSize.coerceAtLeast(1))

    /**
     * Draws into it: clears it to [clear], runs [block] inside a frame of [canvas], and puts back
     * whatever was bound before — so a game can call this in the middle of its own scene.
     *
     * A target made with [depth] has its depth buffer cleared to the far plane at the same time.
     */
    fun <T> draw(canvas: RenderCanvas, clear: Colour = Colour.Transparent, block: () -> T): T {
        check(!closed) { "this render target has been closed" }
        canvas.begin(Viewport.oneToOne(Size(width.toFloat(), height.toFloat())), checkNotNull(target), clear)
        return try {
            block()
        } finally {
            canvas.end()
        }
    }

    /** Its pixels: premultiplied RGBA, the bottom row first, as the device reads them. */
    fun read(): ByteArray {
        check(!closed) { "this render target has been closed" }
        val into = ByteArray(width * height * 4)
        val picture = checkNotNull(target)
        device.begin(picture)
        try {
            device.target(picture, 0, 0, width, height)
            device.read(0, 0, width, height, into)
        } finally {
            device.end()
        }
        return into
    }

    override fun close() {
        if (closed) return
        closed = true
        target?.let(device::delete)
        target = null
    }
}
