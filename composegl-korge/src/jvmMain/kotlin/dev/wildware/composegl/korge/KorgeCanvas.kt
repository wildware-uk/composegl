package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.CanvasState
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.NineRegions
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextLayout
import korlibs.graphics.AGScissor
import korlibs.korge.render.RenderContext
import kotlin.math.roundToInt

/**
 * The KorGE canvas.
 *
 * Everything goes through one batch and one program: a rounded corner, a border and a soft shadow
 * are the same distance calculation, and text and pictures are the same quad with the shape turned
 * off. A screen of panels and labels costs one draw call while it draws from the glyph atlas, and
 * each change of picture after that costs one more.
 *
 * KorGE counts y downwards from the top, like the toolkit, so the only conversion here is the one
 * every backend has: from the design resolution to the pixels of whatever is being drawn into. That
 * lives in the batch's transform, worked out once in [begin], so a call here passes the toolkit's
 * coordinates straight through.
 *
 * A canvas draws during a KorGE render, into the framebuffer KorGE has bound — the window, or a
 * texture a filter is drawing into — so it needs that frame's [RenderContext]. [ComposeGlView] hands
 * it over; a game drawing through a canvas by hand sets [renderContext] around its frame, or calls
 * the [begin] that takes one.
 *
 * Merely having a canvas needs no OpenGL. The vertex buffer is ordinary memory until the first draw,
 * and KorGE compiles the program the first time it is used, so a game object that owns a canvas can
 * be built in a plain JVM test.
 *
 * @param atlas the glyph atlas, if there is one. Sharing it means solid colour and text come from
 *   the same texture, which is the difference between a screen costing forty draw calls and one.
 */
class KorgeCanvas(private val atlas: KorgeAtlas? = null) : UiCanvas, AutoCloseable {

    /** The frame's render context, for the plain [begin] the toolkit calls. Set it around a frame. */
    var renderContext: RenderContext? = null

    private var batch: KorgeShapeBatch? = null

    private fun batch() = batch ?: run {
        val white = atlas?.white
        if (atlas != null && white != null) {
            val page = atlas.pages[white.page]
            KorgeShapeBatch(page, (white.u + white.u2) / 2f, (white.v + white.v2) / 2f)
        } else {
            KorgeShapeBatch(KorgeShapeBatch.ownWhite(), 0.5f, 0.5f)
        }
    }.also {
        batch = it
        it.trace = trace
    }

    private var state = CanvasState(Rect.Zero)
    private var viewport: Viewport = Viewport.oneToOne(Size(1f, 1f))
    private var context: RenderContext? = null

    /**
     * How wide a softened edge is, in design units: one screen pixel, whatever the screen is doing.
     * A corner that fades over one design unit is a blurry smear at 3x and a hard step at a third.
     */
    private var antialias = 1f

    /** How many times the frame so far has talked to the driver. Nothing drawn yet is none. */
    override val drawCalls: Int get() = batch?.renderCalls ?: 0

    private var trace: DrawCallTrace? = null

    /** Handed to the batch, which is the one place that knows when a call really happened. */
    override fun traceDrawCalls(trace: DrawCallTrace?) {
        this.trace = trace
        batch?.trace = trace
    }

    override val tracesDrawCalls: Boolean get() = true

    /**
     * Builds the vertex buffer now rather than in the first frame. KorGE compiles the program itself
     * the first time it draws with it, which needs the context and so cannot happen here.
     */
    override fun warmUp() {
        batch()
    }

    /** Whether [warmUp], or a first frame, has built the batch. For the tests. */
    internal val warmedUp: Boolean get() = batch != null

    override fun begin(viewport: Viewport) {
        begin(viewport, checkNotNull(renderContext) {
            "this canvas draws inside a KorGE render: set renderContext for the frame, or use ComposeGlView"
        })
    }

    /**
     * Sets up for a frame in [viewport]'s design coordinates, drawn into [context]'s current
     * framebuffer. The viewport's physical size is that framebuffer's pixels.
     */
    fun begin(viewport: Viewport, context: RenderContext) {
        check(this.context == null) { "begin() was called twice without an end()" }
        this.context = context
        this.viewport = viewport
        state = CanvasState(Rect.of(0f, 0f, viewport.design.width, viewport.design.height))
        antialias = 1f / minOf(viewport.scaleX, viewport.scaleY).coerceAtLeast(0.0001f)

        // KorGE's own batch may be holding quads the game queued before this; they go first, so the
        // interface lands on top of them.
        context.flush()

        val framebuffer = context.currentFrameBuffer
        val width = framebuffer.width.toFloat()
        val height = framebuffer.height.toFloat()
        // Which way a design y grows in clip space. The window's top is +1, so going down the screen
        // goes towards -1. KorGE draws into a texture the other way up, and reads it back that way too,
        // so there going down goes towards +1. One sign, on both the scale and the offset.
        val downwards = if (framebuffer.isTexture && context.flipRenderTexture) 1f else -1f
        batch().begin(
            context,
            scaleX = 2f * viewport.scaleX / width,
            scaleY = downwards * 2f * viewport.scaleY / height,
            offsetX = 2f * viewport.origin.x / width - 1f,
            offsetY = downwards * (2f * viewport.origin.y / height - 1f),
        )
        // A picture bigger than its area is cut off at the area's edge rather than drawn over the
        // next player's half.
        applyScissor()
    }

    override fun end() {
        checkNotNull(context) { "end() without a begin()" }
        batch().end()
        context = null
        check(state.isBalanced) { "a clip, an alpha, a blend or a tint was pushed and never popped" }
    }

    // --- shapes ---

    override fun rect(rect: Rect, colour: Colour, corner: Float) {
        if (state.isHidden || rect.isEmpty) return
        shape(rect, fill = colour, corner = corner)
    }

    override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) {
        if (state.isHidden || rect.isEmpty || width <= 0f) return
        shape(rect, corner = corner, border = colour, borderWidth = width)
    }

    override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) {
        if (state.isHidden || spread <= 0f) return
        shape(rect, corner = corner, shadow = colour, shadowSpread = spread)
    }

    override fun fan(points: FloatArray, colour: Colour) {
        if (state.isHidden || points.size < 6) return
        batch().fan(points, colour.faded())
    }

    private fun shape(
        rect: Rect,
        fill: Colour = Colour.Transparent,
        corner: Float = 0f,
        border: Colour = Colour.Transparent,
        borderWidth: Float = 0f,
        shadow: Colour = Colour.Transparent,
        shadowSpread: Float = 0f,
    ) {
        batch().shape(
            rect.left, rect.top, rect.width, rect.height,
            fill = fill.faded(),
            topLeft = corner, topRight = corner, bottomRight = corner, bottomLeft = corner,
            border = border.faded(), borderWidth = borderWidth,
            shadow = shadow.faded(), shadowSpread = shadowSpread,
            aa = antialias,
        )
    }

    // --- text and pictures ---

    override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) {
        if (state.isHidden) return
        val korge = layout as? KorgeTextLayout
            ?: error("this canvas can only draw text measured by KorgeFonts, not ${layout::class}")
        val packed = colour.faded()
        val pages = korge.atlas.pages
        // Indexed rather than `forEach`, which asks for an iterator: a HUD draws hundreds of runs.
        val glyphs = korge.glyphs
        for (index in glyphs.indices) {
            val glyph = glyphs[index]
            val region = glyph.region
            batch().textured(
                pages[region.page],
                smooth = false,
                left = x + glyph.left,
                top = y + glyph.top,
                width = glyph.width,
                height = glyph.height,
                u = region.u, v = region.v, u2 = region.u2, v2 = region.v2,
                colour = packed,
            )
        }
    }

    override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) {
        if (state.isHidden || destination.isEmpty) return
        val korge = texture as? KorgeTexture ?: notOnePicture(texture)
        val bitmap = korge.bitmap
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        batch().textured(
            bitmap,
            smooth = korge.smooth,
            left = destination.left,
            top = destination.top,
            width = destination.width,
            height = destination.height,
            u = (source?.left ?: 0f) / width,
            v = (source?.top ?: 0f) / height,
            u2 = (source?.right ?: width) / width,
            v2 = (source?.bottom ?: height) / height,
            colour = tint.faded(),
        )
    }

    // --- clipping and opacity ---

    override fun pushClip(rect: Rect) {
        state.pushClip(rect)
        applyScissor()
    }

    override fun popClip() {
        state.popClip()
        applyScissor()
    }

    override fun pushAlpha(alpha: Float) = state.pushAlpha(alpha)

    override fun popAlpha() = state.popAlpha()

    /**
     * The scissor follows the toolkit's clip, which has already intersected the nested ones — so the
     * backend cannot get nesting wrong, because it never works it out. KorGE takes the box in the
     * framebuffer's pixels with y down and turns it over for the window itself.
     *
     * Outside a frame there is nothing to cut, and the clip is applied when the frame begins.
     */
    private fun applyScissor() {
        val batch = batch ?: return
        if (context == null) return
        val clip = state.clip
        // Never outside the viewport's area: in split screen that is the edge of this player's part.
        val area = viewport.area
        val topLeft = viewport.toScreen(Offset(clip.left, clip.top))
        val bottomRight = viewport.toScreen(Offset(clip.right, clip.bottom))
        val left = maxOf(topLeft.x, area.left)
        val top = maxOf(topLeft.y, area.top)
        val right = minOf(bottomRight.x, area.right)
        val bottom = minOf(bottomRight.y, area.bottom)
        val whole = clip.left <= 0f && clip.top <= 0f &&
            clip.right >= viewport.design.width && clip.bottom >= viewport.design.height &&
            left - topLeft.x < 0.5f && top - topLeft.y < 0.5f &&
            bottomRight.x - right < 0.5f && bottomRight.y - bottom < 0.5f &&
            area.left <= 0f && area.top <= 0f
        if (whole) {
            batch.scissor(AGScissor.NIL)
            return
        }
        val x = left.roundToInt()
        val y = top.roundToInt()
        batch.scissor(
            AGScissor(
                x,
                y,
                (right.roundToInt() - x).coerceAtLeast(0),
                (bottom.roundToInt() - y).coerceAtLeast(0),
            ),
        )
    }

    // --- the escape hatch ---

    /**
     * The frame's [RenderContext], with this canvas's quads already on the screen.
     *
     * KorGE's batch is flushed again afterwards, so whatever the block queued lands under whatever
     * the interface draws next rather than on top of it. The block draws in KorGE's coordinates —
     * the stage's, through the context's own projection — not the design's.
     */
    override fun raw(block: (Any) -> Unit) {
        val context = checkNotNull(context) { "raw() outside a frame" }
        batch().flush(BatchBreak.Raw)
        block(context)
        context.flush()
    }

    /** There is always a render context inside a frame, so there is always something to hand over. */
    override val handsOverRaw: Boolean get() = true

    /**
     * Lets go of the vertex and index buffers. What was never built is not built here in order to be
     * destroyed. The atlas's pages are the fonts', and stay.
     */
    override fun close() {
        batch?.close()
    }

    /**
     * ARGB with the opacity in force multiplied into the alpha. The toolkit's colours are straight,
     * and so is everything the shader does with them.
     */
    private fun Colour.faded(): Int {
        val alpha = (((argb ushr 24) and 0xFF) * state.alpha).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (argb and 0xFFFFFF)
    }

    /**
     * What to say about a texture this canvas cannot draw. Nine separately cut pieces are a
     * TextureHandle too, and "only textures it made" would send the reader hunting for a backend
     * mismatch that is not the problem.
     */
    private fun notOnePicture(texture: TextureHandle): Nothing =
        if (texture is NineRegions) throw IllegalArgumentException(NineRegions.NotOnePicture)
        else error("this canvas can only draw textures it made, not ${texture::class}")
}
