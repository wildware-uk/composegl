package dev.wildware.composegl.gdx

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.utils.Disposable
import dev.wildware.composegl.render.gl.GlConst
import dev.wildware.composegl.render.gl.GlDeviceTarget
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Viewport

/**
 * A picture the toolkit draws into instead of the window: a screen on a wall, a terminal, the
 * display on a gun.
 *
 * The same tree, the same layout, the same canvas; the only difference is where the pixels land.
 * A game draws its interface into one of these and maps [texture] onto whatever quad it likes.
 *
 * What comes out is **premultiplied**, so a game can draw the quad with
 * `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` for an ordinary screen or `GL_ONE, GL_ONE` for a hologram. The
 * second is free, and with straight alpha it would show every transparent pixel as a grey haze.
 *
 * ```kotlin
 * val target = GdxRenderTarget(512, 256)
 * if (panel.needsRedraw(now)) target.draw(canvas) { panel.draw(canvas) }
 * scene.drawQuad(target.texture)
 * ```
 *
 * @param depth give it a depth buffer, for a game drawing its own 3D scene into it — a model in an
 *   inventory slot, a level editor's view. LibGDX makes and frees it with the framebuffer, and the
 *   toolkit clears it with the colour. Without one a scene comes out inside-out.
 */
class GdxRenderTarget(width: Int, height: Int, val depth: Boolean = false) : Disposable {

    /** The biggest texture this GPU makes, asked once. */
    private val most: Int by lazy { GdxGl.getInteger(GlConst.MAX_TEXTURE_SIZE).coerceAtLeast(1) }

    /** How wide the picture really is: what was asked for, or the most this GPU makes. */
    var width: Int = 0
        private set

    var height: Int = 0
        private set

    /**
     * Whether the last size asked for was bigger than this GPU's biggest texture, and so was cut
     * down to it rather than failing in the middle of a frame.
     */
    var clamped: Boolean = false
        private set

    private var buffer: FrameBuffer

    init {
        fit(width, height)
        buffer = make(this.width, this.height, depth)
    }

    /** The colour texture's GL name, for a game with a renderer of its own. */
    val textureName: Int get() = buffer.colorBufferTexture.textureObjectHandle

    /** The picture, for the game to map onto whatever it is drawing. Replaced by a [resize]. */
    var texture: GdxTexture = wrap(buffer)
        private set

    /**
     * Makes it a different size.
     *
     * The old framebuffer is disposed here rather than left to a collector: a panel that follows a
     * window's size would otherwise leak one per resize, which is invisible until a machine runs
     * out of memory. A size bigger than this GPU's biggest texture is cut down to it, and [clamped]
     * says so; one that cuts down to the size it already has costs nothing.
     */
    fun resize(width: Int, height: Int) {
        val before = this.width to this.height
        fit(width, height)
        if (this.width to this.height == before) return

        buffer.dispose()
        buffer = make(this.width, this.height, depth)
        texture = wrap(buffer)
    }

    /** Sets [width], [height] and [clamped] for a size asked for. */
    private fun fit(width: Int, height: Int) {
        require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
        this.width = width.coerceAtMost(most)
        this.height = height.coerceAtMost(most)
        clamped = width > this.width || height > this.height
    }

    /**
     * Draws into it: clears it, runs [block] in a frame of [canvas], and puts back whatever
     * framebuffer and viewport were there before — so a game can call this in the middle of its own
     * scene.
     *
     * @param clear what to fill it with first. Transparent by default, which is what a panel that
     *   is a shape rather than a rectangle wants.
     */
    fun <T> draw(canvas: GdxCanvas, clear: Colour = Transparent, block: () -> T): T {
        canvas.begin(
            Viewport.oneToOne(Size(width.toFloat(), height.toFloat())),
            GlDeviceTarget.adopt(buffer.framebufferHandle, textureName, width, height, depth),
            clear,
        )
        return try {
            block()
        } finally {
            canvas.end()
        }
    }

    /**
     * Binds it and runs [block], for reading the pixels back out — a screenshot, a test, a game
     * that wants the picture on the CPU for something.
     */
    fun <T> read(block: () -> T): T {
        buffer.begin()
        return try {
            block()
        } finally {
            buffer.end()
        }
    }

    override fun dispose() = buffer.dispose()

    private companion object {
        val Transparent = Colour(0)

        fun make(width: Int, height: Int, depth: Boolean): FrameBuffer {
            require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
            return FrameBuffer(Pixmap.Format.RGBA8888, width, height, depth)
        }

        /**
         * A framebuffer's texture is the right way up for OpenGL and upside down for everybody
         * else, so the region is flipped once, here, rather than in every game that uses one.
         */
        fun wrap(buffer: FrameBuffer): GdxTexture {
            buffer.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            val region = TextureRegion(buffer.colorBufferTexture)
            region.flip(false, true)
            return GdxTexture(region)
        }
    }
}
