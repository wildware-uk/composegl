package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.utils.Disposable
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
 */
class GdxRenderTarget(width: Int, height: Int) : Disposable {

    var width: Int = width
        private set

    var height: Int = height
        private set

    private var buffer: FrameBuffer = make(width, height)

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
     * out of memory.
     */
    fun resize(width: Int, height: Int) {
        require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
        if (width == this.width && height == this.height) return

        buffer.dispose()
        this.width = width
        this.height = height
        buffer = make(width, height)
        texture = wrap(buffer)
    }

    /**
     * Draws into it: binds the framebuffer, clears it, runs [block] and puts the viewport back.
     *
     * @param clear what to fill it with first. Transparent by default, which is what a panel that
     *   is a shape rather than a rectangle wants.
     */
    fun <T> draw(canvas: GdxCanvas, clear: Colour = Transparent, block: () -> T): T {
        buffer.begin()
        return try {
            Gdx.gl.glClearColor(
                clear.red / 255f * clear.alphaFraction,
                clear.green / 255f * clear.alphaFraction,
                clear.blue / 255f * clear.alphaFraction,
                clear.alphaFraction,
            )
            Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT)
            canvas.begin(
                Viewport.oneToOne(Size(width.toFloat(), height.toFloat())),
                // Ours is bound, so anything inside that binds one of its own — a layer, an
                // effect — knows what to put back when it is done.
                framebuffer = buffer.framebufferHandle,
            )
            try {
                block()
            } finally {
                canvas.end()
            }
        } finally {
            buffer.end()
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

        fun make(width: Int, height: Int): FrameBuffer {
            require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
            return FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
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
