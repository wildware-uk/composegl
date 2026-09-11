package uk.wildware.composegl.lwjgl3

import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.layout.Viewport
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/**
 * A picture the toolkit draws into instead of the window: a screen on a wall, a terminal, the
 * display on a gun.
 *
 * The same tree, the same layout, the same canvas — the only difference is where the pixels land.
 * A game draws its interface into one of these and maps [texture] onto whatever quad it likes.
 *
 * What comes out is **premultiplied**: the colour of each pixel is already multiplied by its own
 * opacity. That is what makes it usable in a 3D scene without a shader of its own. Draw the quad
 * with `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` for an ordinary screen, or with
 * `glBlendFunc(GL_ONE, GL_ONE)` for a hologram — the second is free, and with straight alpha it
 * would show every transparent pixel as a grey haze.
 *
 * ```kotlin
 * val target = GlRenderTarget(512, 256)
 * // in the game loop:
 * if (panel.needsRedraw(now)) target.draw(canvas) { panel.draw(canvas) }
 * scene.drawQuad(target.texture)
 * ```
 */
class GlRenderTarget(width: Int, height: Int) : AutoCloseable {

    var width: Int = width
        private set

    var height: Int = height
        private set

    private var framebuffer = 0
    private var colour = 0
    private var closed = false

    /** The framebuffer's GL name. For a game with its own renderer, and for the tests. */
    val framebufferName: Int get() = framebuffer

    /** The colour texture's GL name. */
    val textureName: Int get() = colour

    /** The picture, for the game to map onto whatever it is drawing. Replaced by a [resize]. */
    var texture: GlTexture = GlTexture(0, width, height)
        private set

    init {
        require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
        create()
    }

    /**
     * Makes it a different size.
     *
     * The old framebuffer and the old texture are deleted here rather than left to a collector:
     * a panel that follows a window's size would otherwise leak one of each per resize, which is
     * the classic version of this bug and is invisible until a machine runs out of memory.
     */
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
     * Draws into it: binds the framebuffer, clears it, runs [block] and puts everything back.
     *
     * Everything is put back — the framebuffer that was bound, the viewport that was set — so a
     * game can call this in the middle of rendering its own scene and find nothing disturbed.
     *
     * @param clear what to fill it with first. Transparent by default, which is what a panel that
     *   is a shape rather than a rectangle wants.
     */
    fun <T> draw(canvas: GlCanvas, clear: Colour = Transparent, block: () -> T): T {
        check(!closed) { "this render target has been closed" }

        val previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
        val previousViewport = IntArray(4)
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport)

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
        GL11.glViewport(0, 0, width, height)
        GL11.glClearColor(
            clear.red / 255f * clear.alphaFraction,
            clear.green / 255f * clear.alphaFraction,
            clear.blue / 255f * clear.alphaFraction,
            clear.alphaFraction,
        )
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)

        return try {
            canvas.begin(
                Viewport.oneToOne(Size(width.toFloat(), height.toFloat())),
                // Ours is bound, so anything inside that binds one of its own — a layer, an
                // effect — knows what to put back when it is done.
                framebuffer = framebuffer,
            )
            try {
                block()
            } finally {
                canvas.end()
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer)
            GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        destroy()
    }

    private fun create() {
        colour = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colour)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA8,
            width,
            height,
            0,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            null as java.nio.ByteBuffer?,
        )
        // Clamped and filtered, because what this is for is being stretched across a quad that is
        // never exactly the size of the texture.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, ClampToEdge)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, ClampToEdge)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)

        framebuffer = GL30.glGenFramebuffers()
        val previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D,
            colour,
            0,
        )
        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous)
        check(status == GL30.GL_FRAMEBUFFER_COMPLETE) {
            "this driver would not give us a ${width}x$height render target (status $status)"
        }

        texture = GlTexture(colour, width, height)
    }

    private fun destroy() {
        GL30.glDeleteFramebuffers(framebuffer)
        GL11.glDeleteTextures(colour)
        framebuffer = 0
        colour = 0
    }

    private companion object {
        val Transparent = Colour(0)

        const val ClampToEdge = 0x812F
    }
}
