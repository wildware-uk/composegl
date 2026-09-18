package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Viewport
import korlibs.graphics.AGFrameBuffer
import korlibs.graphics.AGTexture
import korlibs.graphics.clear
import korlibs.graphics.readColor
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import korlibs.korge.render.RenderContext
import korlibs.korge.render.Texture
import korlibs.korge.render.TextureBase
import korlibs.korge.view.View
import korlibs.math.geom.Rectangle
import korlibs.math.geom.slice.RectSlice

/**
 * A picture the toolkit draws into instead of the window: a screen on a wall, a terminal, the display
 * on a gun.
 *
 * The same tree, the same layout, the same canvas; the only difference is where the pixels land. A
 * KorGE game draws its interface into one of these during a render and shows [slice] however it likes
 * — through a [KorgeRenderTargetView] on the stage, turned and scaled like any view, or on a quad of
 * its own with `ctx.useBatcher { it.drawQuad(target.slice, …) }`.
 *
 * What comes out is **premultiplied**, as KorGE expects every texture to be, so it can be drawn with an
 * ordinary blend for a screen or an additive one for a hologram. The LibGDX backend's
 * `GdxRenderTarget`, for KorGE.
 *
 * ```kotlin
 * val target = KorgeRenderTarget(512, 256)
 * addUpdater { /* each frame, in a render: */ }
 * stage.addChild(KorgeRenderTargetView(target))
 * // inside a render, with the frame's RenderContext, which a SceneView in the panel renders through:
 * canvas.renderContext = ctx
 * try {
 *     panel.draw(canvas) { tree -> target.draw(canvas, ctx) { tree() } }
 * } finally {
 *     canvas.renderContext = null
 * }
 * ```
 */
class KorgeRenderTarget(width: Int, height: Int) : AutoCloseable {

    init {
        require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
    }

    /**
     * The framebuffer, for a game with a renderer of its own. The same object for the target's whole
     * life: a [resize] changes its size and KorGE reallocates the texture behind it, rather than a new
     * framebuffer being left behind per resize.
     */
    val frameBuffer: AGFrameBuffer = AGFrameBuffer().also { it.setSize(width, height) }

    val width: Int get() = frameBuffer.width

    val height: Int get() = frameBuffer.height

    /** The colour texture, premultiplied. */
    val texture: AGTexture get() = frameBuffer.tex

    /**
     * The picture as KorGE's own texture slice, the way up KorGE expects a render texture to be read,
     * for `BatchBuilder2D.drawQuad` and anything else in KorGE that draws a texture.
     */
    val slice: RectSlice<TextureBase> get() = Texture(frameBuffer)

    /** Makes it a different size. The next [draw] draws at it. */
    fun resize(width: Int, height: Int) {
        require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
        if (width == this.width && height == this.height) return
        frameBuffer.setSize(width, height)
    }

    /**
     * Draws into it, inside a KorGE render: puts it on [context]'s framebuffer stack, clears it, runs
     * [block] between [KorgeCanvas.begin] and [KorgeCanvas.end] at one design unit to the pixel, and
     * takes it off again. A layer or an effect inside the block draws into this and comes back to it.
     *
     * @param clear what to fill it with first. Transparent by default, which is what a panel that is a
     *   shape rather than a rectangle wants.
     */
    fun <T> draw(canvas: KorgeCanvas, context: RenderContext, clear: Colour = Colour.Transparent, block: () -> T): T {
        context.pushFrameBuffer(frameBuffer)
        return try {
            val alpha = clear.alphaFraction
            context.ag.clear(
                frameBuffer,
                color = RGBA((clear.red * alpha).toInt(), (clear.green * alpha).toInt(), (clear.blue * alpha).toInt(), clear.alpha),
            )
            canvas.begin(Viewport.oneToOne(Size(width.toFloat(), height.toFloat())), context)
            try {
                block()
            } finally {
                canvas.end()
            }
        } finally {
            context.popFrameBuffer()
        }
    }

    /**
     * The pixels, read back inside a KorGE render: top row first, as the toolkit counts, and
     * premultiplied, as they are stored. For a screenshot, a test, or a game that wants the picture on
     * the CPU for something.
     */
    fun read(context: RenderContext): Bitmap32 =
        Bitmap32(width, height, premultiplied = true).also { context.ag.readColor(frameBuffer, it) }

    override fun close() = frameBuffer.close()
}

/**
 * A [KorgeRenderTarget] on the stage: a view the size of the target's picture, drawn through KorGE's
 * own batch with the view's position, scale, rotation, skew, colour and blend mode, like any sprite.
 *
 * It shows whatever the target last had drawn into it, so a panel that did not change does not need
 * drawing again for the view to go on showing it.
 */
class KorgeRenderTargetView(val target: KorgeRenderTarget) : View() {

    override fun getLocalBoundsInternal(): Rectangle =
        Rectangle(0.0, 0.0, target.width.toDouble(), target.height.toDouble())

    override fun renderInternal(ctx: RenderContext) {
        if (!visible) return
        ctx.useBatcher { batch ->
            batch.drawQuad(
                target.slice,
                x = 0f,
                y = 0f,
                width = target.width.toFloat(),
                height = target.height.toFloat(),
                m = globalMatrix,
                filtering = true,
                colorMul = renderColorMul,
                blendMode = renderBlendMode,
            )
        }
    }
}
