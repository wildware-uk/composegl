package dev.wildware.composegl.gdx

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.glutils.GLVersion
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import dev.wildware.composegl.render.FrameTarget
import dev.wildware.composegl.render.GlyphAtlas
import dev.wildware.composegl.render.RenderCanvas
import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.GlDeviceTarget
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Viewport

/**
 * The shared renderer, on LibGDX.
 *
 * All the drawing is [RenderCanvas]'s. This class only says which GL to call ([GdxGl]), which
 * textures it can draw ([GdxTexture]) and what a game gets from `raw`: its own [Batch], opened with
 * the frame's projection and closed again, with the projection and colour it had put back.
 *
 * Merely having one costs no OpenGL, so a game object holding one can be built in a plain test.
 *
 * @param spriteBatch what [raw] hands a game. Optional: without one, [raw] is refused rather than
 *   silently handing over something a game's existing code cannot use.
 * @param fonts where text is measured and solid colour is sampled from. Sharing its atlas is what
 *   makes a screen of panels and labels one draw call.
 */
class GdxCanvas(
    private val spriteBatch: Batch? = null,
    fonts: GdxFonts? = null,
) : RenderCanvas(GlDevice(GdxGl), fonts, GdxTexture.Resolver), Disposable {

    /** The same, named by the fonts' [GdxFonts.atlas], as a canvas has always been given it. */
    constructor(spriteBatch: Batch? = null, atlas: GlyphAtlas) : this(spriteBatch, GdxFonts.owning(atlas))

    /**
     * The context the last frame was drawn on. Android gives the app a new one after a pause that
     * lost it, and LibGDX makes a new [GLVersion] exactly then: that is how a frame notices.
     */
    private var context: GLVersion? = null

    override fun begin(viewport: Viewport, into: FrameTarget, clear: Colour?) {
        noticeLostContext()
        super.begin(viewport, into, clear)
    }

    /**
     * A frame drawn into the framebuffer called [framebuffer] rather than the window, which the
     * caller has bound — an interface on a surface in a 3D world. It is put back when the frame ends.
     */
    fun begin(viewport: Viewport, framebuffer: Int) {
        if (framebuffer == 0) {
            begin(viewport)
        } else {
            val size = viewport.physical
            begin(viewport, GlDeviceTarget.adopt(framebuffer, 0, size.width.toInt(), size.height.toInt()))
        }
    }

    private fun noticeLostContext() {
        val graphics = Gdx.graphics ?: return
        if (Gdx.app?.type != Application.ApplicationType.Android) return
        val current = graphics.glVersion
        if (context != null && current !== context) contextLost()
        context = current
    }

    /** Whether one was passed to the constructor, which is the whole of the question. */
    override val handsOverRaw: Boolean get() = spriteBatch != null

    override fun handOver(projection: FloatArray, viewport: Viewport): Any =
        spriteBatch ?: error("this canvas was made without a SpriteBatch, so raw() has nothing to hand over")

    private val lentProjection = Matrix4()

    /** The batch, opened on the frame's projection, and handed back as the game lent it. */
    override fun lend(lent: Any, projection: FloatArray, block: (Any) -> Unit) {
        val sprites = lent as Batch
        val savedProjection = Matrix4(sprites.projectionMatrix)
        val savedColour = Color(sprites.color)
        sprites.projectionMatrix = lentProjection.set(projection)
        sprites.begin()
        try {
            block(sprites)
        } finally {
            if (sprites.isDrawing) sprites.end()
            sprites.projectionMatrix = savedProjection
            sprites.color = savedColour
        }
    }

    /** Lets go of everything that was built. A canvas that drew nothing touches no driver. */
    override fun dispose() = close()
}
