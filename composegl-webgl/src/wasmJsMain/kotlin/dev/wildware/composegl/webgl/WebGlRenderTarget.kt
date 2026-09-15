package dev.wildware.composegl.webgl

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Viewport
import org.khronos.webgl.WebGLFramebuffer
import org.khronos.webgl.WebGLRenderingContext as GL
import org.khronos.webgl.WebGLTexture

/**
 * A picture the toolkit draws into instead of the page's canvas: a screen on a wall in a WebGL
 * scene, a minimap, a card being flipped.
 *
 * The same tree, the same layout, the same canvas — only where the pixels land differs. What comes
 * out is **premultiplied**, as on the desktop, so a game puts it on a quad with
 * `gl.blendFunc(ONE, ONE_MINUS_SRC_ALPHA)` and needs no shader of its own.
 */
class WebGlRenderTarget(private val gl: GL, width: Int, height: Int) : AutoCloseable {

    var width: Int = width
        private set

    var height: Int = height
        private set

    private var framebuffer: WebGLFramebuffer? = null
    private var colour: WebGLTexture? = null
    private var closed = false

    /** The framebuffer, for a game with its own renderer, and for a layer that has to put it back. */
    val framebufferName: WebGLFramebuffer get() = checkNotNull(framebuffer) { "this render target has been closed" }

    /** The picture, for the game to map onto whatever it draws. Replaced by a [resize]. */
    lateinit var texture: WebGlTexture
        private set

    init {
        require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
        create()
    }

    /** Makes it a different size, deleting the old framebuffer and texture now rather than leaking them. */
    fun resize(width: Int, height: Int) {
        check(!closed) { "this render target has been closed" }
        require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
        if (width == this.width && height == this.height) return
        destroy()
        this.width = width
        this.height = height
        create()
    }

    /**
     * Draws into it: binds it, clears it, runs [block] between the canvas's begin and end, and puts
     * back the framebuffer and the viewport that were there before.
     */
    fun <T> draw(canvas: WebGlCanvas, clear: Colour = Colour.Transparent, block: () -> T): T {
        check(!closed) { "this render target has been closed" }
        val previous = boundFramebuffer(gl)
        val previousViewport = currentViewport(gl)

        gl.bindFramebuffer(GL.FRAMEBUFFER, framebuffer)
        gl.viewport(0, 0, width, height)
        gl.clearColor(
            clear.red / 255f * clear.alphaFraction,
            clear.green / 255f * clear.alphaFraction,
            clear.blue / 255f * clear.alphaFraction,
            clear.alphaFraction,
        )
        gl.clear(GL.COLOR_BUFFER_BIT)

        return try {
            canvas.begin(Viewport.oneToOne(Size(width.toFloat(), height.toFloat())), framebuffer)
            try {
                block()
            } finally {
                canvas.end()
            }
        } finally {
            gl.bindFramebuffer(GL.FRAMEBUFFER, previous)
            gl.viewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        destroy()
    }

    private fun create() {
        val name = WebGlTexture.create(gl, smooth = true)
        gl.texImage2D(GL.TEXTURE_2D, 0, GL.RGBA, width, height, 0, GL.RGBA, GL.UNSIGNED_BYTE, null)
        gl.bindTexture(GL.TEXTURE_2D, null)
        colour = name

        val made = checkNotNull(gl.createFramebuffer()) { LostContext }
        val previous = boundFramebuffer(gl)
        gl.bindFramebuffer(GL.FRAMEBUFFER, made)
        gl.framebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_2D, name, 0)
        val status = gl.checkFramebufferStatus(GL.FRAMEBUFFER)
        gl.bindFramebuffer(GL.FRAMEBUFFER, previous)
        check(status == GL.FRAMEBUFFER_COMPLETE) {
            "this browser would not give us a ${width}x$height render target (status $status)"
        }
        framebuffer = made
        texture = WebGlTexture(gl, name, width, height)
    }

    private fun destroy() {
        framebuffer?.let { gl.deleteFramebuffer(it) }
        colour?.let { gl.deleteTexture(it) }
        framebuffer = null
        colour = null
    }
}

/**
 * The offscreen pictures a canvas draws effects into, kept and handed out again, so a blurred panel
 * on screen for a minute costs one texture rather than one a frame. Belongs to one canvas.
 */
internal class WebGlLayers(private val gl: GL, private val spare: Int = 60) : AutoCloseable {

    private class Entry(val target: WebGlRenderTarget) {
        var busy = false
        var idle = 0
    }

    private val entries = ArrayList<Entry>()

    /** How many layers exist right now. For the tests, and for anybody chasing memory. */
    val size: Int get() = entries.size

    /** A target exactly [width] by [height], free or new. Exactly, so it samples back one to one. */
    fun acquire(width: Int, height: Int): WebGlRenderTarget {
        val free = entries.firstOrNull { !it.busy && it.target.width == width && it.target.height == height }
        val entry = free ?: Entry(WebGlRenderTarget(gl, width, height)).also { entries += it }
        entry.busy = true
        entry.idle = 0
        return entry.target
    }

    fun release(target: WebGlRenderTarget) {
        entries.firstOrNull { it.target === target }?.busy = false
    }

    /** At the end of a frame: anything unwanted for [spare] frames goes back to the browser. */
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
}

/** Which framebuffer is bound, asked once where the answer is needed to put it back. */
internal fun boundFramebuffer(gl: GL): WebGLFramebuffer? = js("gl.getParameter(gl.FRAMEBUFFER_BINDING)")

private fun viewportPart(gl: GL, at: Int): Int = js("gl.getParameter(gl.VIEWPORT)[at]")

internal fun currentViewport(gl: GL): IntArray = IntArray(4) { viewportPart(gl, it) }
