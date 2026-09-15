package dev.wildware.composegl.render

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Viewport

/**
 * A picture the toolkit draws into instead of the window: a screen on a wall, a terminal, the
 * display on a gun. The same tree, the same canvas; only where the pixels land differs.
 *
 * What comes out is **premultiplied**, which is what makes it usable in a 3D scene without a shader
 * of its own: draw it with `ONE, ONE_MINUS_SRC_ALPHA` for a screen, or `ONE, ONE` for a hologram.
 *
 * Made on [device] at once, so it needs the context that device draws on.
 */
class RenderTarget(private val device: GpuDevice, width: Int, height: Int) : AutoCloseable {

    var width: Int = width
        private set

    var height: Int = height
        private set

    private var closed = false

    /** The device's picture, or null once closed. Replaced by a [resize]. */
    var target: DeviceTarget? = null
        private set

    init {
        require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
        target = device.offscreen(width, height)
    }

    /**
     * Makes it a different size. The old picture is given back here rather than left to a
     * collector: a panel that follows a window's size would otherwise leak one per resize.
     */
    fun resize(width: Int, height: Int) {
        check(!closed) { "this render target has been closed" }
        require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
        if (width == this.width && height == this.height) return
        target?.let(device::delete)
        target = null
        this.width = width
        this.height = height
        target = device.offscreen(width, height)
    }

    /**
     * Draws into it: clears it to [clear], runs [block] inside a frame of [canvas], and puts back
     * whatever was bound before — so a game can call this in the middle of its own scene.
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
