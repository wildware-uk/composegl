package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.CanvasState
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.NineRegions
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.geometry.Matrix4
import dev.wildware.composegl.ui.graphics.featherOutline
import korlibs.graphics.AGScissor
import korlibs.graphics.clear
import korlibs.image.color.RGBA
import korlibs.korge.render.RenderContext
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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

    /** Offscreen pictures, kept between frames. Nothing is made until the first [layer]. */
    private val layers = KorgeLayers()

    private var effects: KorgeEffects? = null

    private fun effects() = effects ?: KorgeEffects().also { effects = it }

    /** The layer being drawn into, or null for the frame's own framebuffer. The scissor asks. */
    private var layer: LayerFrame? = null

    private class LayerFrame(val bounds: Rect, val pixelWidth: Int, val pixelHeight: Int)

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
        layers.trim()
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
    ) = shape(rect, fill, corner, corner, corner, corner, border, borderWidth, shadow, shadowSpread)

    /**
     * The one place a box reaches the batch. Four floats rather than a [Corners], so the single-radius
     * calls, which are most of a frame, make no object on their way through.
     */
    @Suppress("LongParameterList")
    private fun shape(
        rect: Rect,
        fill: Colour,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
        border: Colour,
        borderWidth: Float,
        shadow: Colour,
        shadowSpread: Float,
    ) {
        batch().shape(
            rect.left, rect.top, rect.width, rect.height,
            fill = fill.faded(),
            topLeft = topLeft, topRight = topRight, bottomRight = bottomRight, bottomLeft = bottomLeft,
            border = border.faded(), borderWidth = borderWidth,
            shadow = shadow.faded(), shadowSpread = shadowSpread,
            aa = antialias,
        )
    }

    // --- a radius per corner ---

    // Four radii are four more numbers on the same vertex, so a tab and a plain panel beside it still
    // batch together: the shader picks a corner's radius by which quarter of the box a pixel is in.

    override fun rect(rect: Rect, colour: Colour, corners: Corners) {
        if (state.isHidden || rect.isEmpty) return
        shape(rect, colour, corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft, Colour.Transparent, 0f, Colour.Transparent, 0f)
    }

    override fun border(rect: Rect, colour: Colour, width: Float, corners: Corners) {
        if (state.isHidden || rect.isEmpty || width <= 0f) return
        shape(rect, Colour.Transparent, corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft, colour, width, Colour.Transparent, 0f)
    }

    override fun shadow(rect: Rect, colour: Colour, spread: Float, corners: Corners) {
        if (state.isHidden || spread <= 0f) return
        shape(rect, Colour.Transparent, corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft, Colour.Transparent, 0f, colour, spread)
    }

    /** Each corner by its own radius, in the same shader as everything else. */
    override val roundsCornersSeparately: Boolean get() = true

    // --- gradients ---

    override fun rect(rect: Rect, brush: Brush, corner: Float) =
        gradient(rect, brush, corner, corner, corner, corner)

    override fun rect(rect: Rect, brush: Brush, corners: Corners) =
        gradient(rect, brush, corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)

    @Suppress("LongParameterList")
    private fun gradient(rect: Rect, brush: Brush, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
        if (state.isHidden || rect.isEmpty) return
        // Worked out once, in the toolkit's coordinates, which are the batch's too: no flip. A radial
        // gradient is symmetric and has no axis.
        val axis = (brush as? Brush.Linear)?.axis(rect.width, rect.height)
        batch().gradient(
            rect.left, rect.top, rect.width, rect.height,
            start = brush.first.faded(),
            end = brush.last.faded(),
            radial = brush is Brush.Radial,
            axisX = axis?.x ?: 0f,
            axisY = axis?.y ?: 0f,
            topLeft = topLeft, topRight = topRight, bottomRight = bottomRight, bottomLeft = bottomLeft,
            aa = antialias,
        )
    }

    /** Both kinds, straight and radial, through the same shader as every other box. */
    override val drawsGradients: Boolean get() = true

    // --- text and pictures ---

    override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) =
        drawText(layout, x, y, colour, ring = false)

    /**
     * One copy of an outline's ring, which leaves picture glyphs out.
     *
     * A letter's copies are silhouettes in the ring colour, because a letter is only coverage. An
     * emoji's copies would be eight more emoji in their own colours, smeared round the real one.
     * Leaving them out keeps the ring round the words and the picture clean, as the LibGDX canvas does.
     */
    override fun textRing(layout: TextLayout, x: Float, y: Float, colour: Colour) =
        drawText(layout, x, y, colour, ring = true)

    private fun drawText(layout: TextLayout, x: Float, y: Float, colour: Colour, ring: Boolean) {
        if (state.isHidden) return
        val korge = layout as? KorgeTextLayout
            ?: error("this canvas can only draw text measured by KorgeFonts, not ${layout::class}")
        val packed = colour.faded()
        // A picture keeps its own colours and takes only the text's fade. Worked out once per run,
        // and only for a run that has one.
        var picturePacked = 0
        var pictureReady = false
        val pages = korge.atlas.pages
        // Indexed rather than `forEach`, which asks for an iterator: a HUD draws hundreds of runs, and
        // an outlined one comes through here nine times.
        val glyphs = korge.glyphs
        for (index in glyphs.indices) {
            val glyph = glyphs[index]
            if (glyph.picture && ring) continue
            if (glyph.picture && !pictureReady) {
                picturePacked = Colour.White.scaleAlpha(colour.alphaFraction).faded()
                pictureReady = true
            }
            val region = glyph.region
            batch().textured(
                pages[region.page],
                smooth = false,
                left = x + glyph.left,
                top = y + glyph.top,
                width = glyph.width,
                height = glyph.height,
                u = region.u, v = region.v, u2 = region.u2, v2 = region.v2,
                colour = if (glyph.picture) picturePacked else packed,
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

    // --- turned pictures ---

    /**
     * The same picture, turned: four corners on the processor and the same quad in the same batch, so
     * a sunburst of rays costs no draw call more than one ray.
     */
    @Suppress("LongParameterList")
    override fun image(
        texture: TextureHandle,
        destination: Rect,
        degrees: Float,
        pivotX: Float,
        pivotY: Float,
        tint: Colour,
        source: Rect?,
    ) {
        if (degrees == 0f) {
            image(texture, destination, tint, source)
            return
        }
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
            pivotX = destination.left + destination.width * pivotX,
            pivotY = destination.top + destination.height * pivotY,
            degrees = degrees,
            u = (source?.left ?: 0f) / width,
            v = (source?.top ?: 0f) / height,
            u2 = (source?.right ?: width) / width,
            v2 = (source?.bottom ?: height) / height,
            colour = tint.faded(),
        )
    }

    /** It really turns one, and turning costs no draw call. */
    override val rotatesImages: Boolean get() = true

    // --- blending and tint ---

    /**
     * Both: every quad goes through the batch, and the batch names its blending on every draw. A layer
     * or an effect that draws some other way has to follow [BlendMode] too.
     */
    override fun supports(mode: BlendMode): Boolean = true

    override fun pushBlend(mode: BlendMode) {
        state.pushBlend(mode)
        applyBlend()
    }

    override fun popBlend() {
        state.popBlend()
        applyBlend()
    }

    /**
     * The batch's blending follows the toolkit's blend stack, which has already decided the innermost
     * mode wins. The batch flushes first: what is queued was queued to blend the old way.
     *
     * `batch?`, and nothing outside a frame: pushing a mode should not build a batch. [begin] starts
     * the batch at plain blending, which is where a fresh state starts too.
     */
    private fun applyBlend() {
        if (context == null) return
        batch?.blend(state.blend, premultiplied = false)
    }

    /**
     * No flush: the tint goes into each vertex's colour as it is queued (see [faded]), so a tinted
     * hotbar batches with the untinted panel behind it.
     */
    override fun pushTint(tint: Colour) = state.pushTint(tint)

    override fun popTint() = state.popTint()

    /** Every call that takes a colour. Not raw(). */
    override val tints: Boolean get() = true

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
        layer?.let { into ->
            batch.scissor(scissorInLayer(into, clip))
            return
        }
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

    /**
     * The same, in a layer's pixels: its own origin, and no letterbox — a layer is exactly the picture
     * and nothing around it.
     */
    private fun scissorInLayer(into: LayerFrame, clip: Rect): AGScissor {
        val bounds = into.bounds
        if (clip.left <= bounds.left && clip.top <= bounds.top && clip.right >= bounds.right && clip.bottom >= bounds.bottom) {
            return AGScissor.NIL
        }
        val left = ((clip.left - bounds.left) * viewport.scaleX).roundToInt().coerceIn(0, into.pixelWidth)
        val right = ((clip.right - bounds.left) * viewport.scaleX).roundToInt().coerceIn(0, into.pixelWidth)
        val top = ((clip.top - bounds.top) * viewport.scaleY).roundToInt().coerceIn(0, into.pixelHeight)
        val bottom = ((clip.bottom - bounds.top) * viewport.scaleY).roundToInt().coerceIn(0, into.pixelHeight)
        return AGScissor(left, top, (right - left).coerceAtLeast(0), (bottom - top).coerceAtLeast(0))
    }

    // --- layers ---

    /** Framebuffers, so yes. */
    override val drawsLayers: Boolean get() = true

    /**
     * Draws [block] into an offscreen picture of [bounds], at screen resolution, and hands it back.
     *
     * The framebuffer goes on KorGE's own stack for the block, so anything inside that asks KorGE where
     * it is drawing — a layer inside this one, a `raw` block — is told the truth, and the one it found
     * comes back afterwards whether that was the window or a render target in the world.
     */
    override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? {
        val context = checkNotNull(context) { "layer() outside a frame" }
        if (bounds.isEmpty) return null

        // Screen resolution, not design resolution, so a layer drawn back is as sharp as what is round
        // it. Rounded up, so nothing falls off the right or the bottom of a picture a fraction too big.
        val pixelWidth = ceil(bounds.width * viewport.scaleX).toInt()
        val pixelHeight = ceil(bounds.height * viewport.scaleY).toInt()
        if (pixelWidth <= 0 || pixelHeight <= 0) return null
        if (pixelWidth > MaxLayerPixels || pixelHeight > MaxLayerPixels) return null

        val batch = batch()
        // What is queued was queued for the framebuffer out here.
        batch.flush(BatchBreak.Layer)
        val target = layers.acquire(pixelWidth, pixelHeight)

        val previousTransform = batch.transform()
        val previousState = state
        val previousLayer = layer

        layer = LayerFrame(bounds, pixelWidth, pixelHeight)
        // A fresh clip, full opacity and plain blending: the opacity out here is applied when the
        // picture is drawn back, which is what makes a group fade as one object. The tint comes in.
        state = previousState.forLayer(bounds)

        context.pushFrameBuffer(target.buffer)
        try {
            context.ag.clear(target.buffer, color = RGBA(0, 0, 0, 0))
            // The same arithmetic as [begin], with the layer's top-left at the corner of clip space.
            val downwards = if (context.flipRenderTexture) 1f else -1f
            val scaleX = 2f * viewport.scaleX / pixelWidth
            val scaleY = downwards * 2f * viewport.scaleY / pixelHeight
            batch.transform(scaleX, scaleY, -1f - bounds.left * scaleX, -downwards - bounds.top * scaleY)
            batch.scissor(AGScissor.NIL)
            applyBlend()

            block()
            batch.flush(BatchBreak.Layer)
            check(state.isBalanced) { "a clip, an alpha, a blend or a tint was pushed inside a layer and never popped" }
        } finally {
            context.popFrameBuffer()
            layer = previousLayer
            state = previousState
            batch.transform(previousTransform[0], previousTransform[1], previousTransform[2], previousTransform[3])
            applyBlend()
            // Worked out again rather than remembered: the clip out here is the one in force again.
            applyScissor()
            layers.release(target)
        }
        return target.picture
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, effect: ShaderEffect?) {
        if (state.isHidden || destination.isEmpty) return
        val picture = layerPicture(layer)
        if (effect != null) {
            drawThrough(effect, picture, destination)
            return
        }
        composite(picture, destination, mirrorX = false, mirrorY = false)
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, mirrorX: Boolean, mirrorY: Boolean) {
        if (state.isHidden || destination.isEmpty) return
        composite(layerPicture(layer), destination, mirrorX, mirrorY)
    }

    /** It really mirrors one: the texture coordinates are swapped on the same quad. */
    override val mirrorsLayers: Boolean get() = true

    /** A layer put down upright, the plain way or with either axis of its picture swapped. */
    private fun composite(picture: KorgeLayerPicture, destination: Rect, mirrorX: Boolean, mirrorY: Boolean) {
        premultiplied {
            batch().layer(
                picture.texture,
                destination.left, destination.top, destination.width, destination.height,
                u = if (mirrorX) 1f else 0f,
                v = if (mirrorY) 1f else 0f,
                u2 = if (mirrorX) 0f else 1f,
                v2 = if (mirrorY) 0f else 1f,
                colour = layerFade(),
            )
        }
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, degrees: Float, pivotX: Float, pivotY: Float) {
        if (degrees == 0f) {
            drawLayer(layer, destination)
            return
        }
        if (state.isHidden || destination.isEmpty) return
        val picture = layerPicture(layer)
        val centreX = destination.left + destination.width * pivotX
        val centreY = destination.top + destination.height * pivotY
        val radians = degrees * PI.toFloat() / 180f
        val turnCos = cos(radians)
        val turnSin = sin(radians)
        // y grows downwards, so this is the ordinary rotation and a positive angle turns clockwise.
        fun x(x: Float, y: Float) = centreX + (x - centreX) * turnCos - (y - centreY) * turnSin
        fun y(x: Float, y: Float) = centreY + (x - centreX) * turnSin + (y - centreY) * turnCos
        val (l, t, r, b) = listOf(destination.left, destination.top, destination.right, destination.bottom)
        val fade = layerFade()
        premultiplied {
            batch().corners(
                picture.texture,
                x(l, t), y(l, t), 0f, 0f, fade,
                x(r, t), y(r, t), 1f, 0f, fade,
                x(r, b), y(r, b), 1f, 1f, fade,
                x(l, b), y(l, b), 0f, 1f, fade,
            )
        }
    }

    /** It really turns one, on the same quad the upright composite uses. */
    override val turnsLayers: Boolean get() = true

    override fun drawLayerOnto(layer: TextureHandle, destination: Rect, corners: FloatArray) {
        require(corners.size == 8) { "four corners are eight numbers, not ${corners.size}" }
        if (state.isHidden || destination.isEmpty) return
        val picture = layerPicture(layer)
        val fade = layerFade()
        // KorGE counts y down, like the corners, so they go in as they came.
        premultiplied {
            batch().corners(
                picture.texture,
                corners[0], corners[1], 0f, 0f, fade,
                corners[2], corners[3], 1f, 0f, fade,
                corners[4], corners[5], 1f, 1f, fade,
                corners[6], corners[7], 0f, 1f, fade,
            )
        }
    }

    /** It really does, on the same quad the upright composite uses. */
    override val drawsLayersOnto: Boolean get() = true

    override fun drawLayer(layer: TextureHandle, destination: Rect, transform: Matrix4) {
        if (state.isHidden || destination.isEmpty) return
        val picture = layerPicture(layer)
        // Each corner through the transform, left undivided: x, y and w. The GPU divides per pixel.
        val corners = FloatArray(12)
        transform.project(destination.left, destination.top, corners, 0)
        transform.project(destination.right, destination.top, corners, 3)
        transform.project(destination.right, destination.bottom, corners, 6)
        transform.project(destination.left, destination.bottom, corners, 9)
        premultiplied { batch().projected(picture.texture, corners, 0f, 0f, 1f, 1f, layerFade()) }
    }

    /** It really does, dividing by depth for every pixel in the same shader as everything else. */
    override val tiltsLayers: Boolean get() = true

    /**
     * The picture as quads through [outline], with a ring one screen pixel wide round it that fades to
     * nothing — see [featherOutline]. Same texture, same program and same batch as every other picture.
     * The texture coordinates are held inside the picture, because the soft ring reaches past it.
     */
    override fun cutLayer(layer: TextureHandle, destination: Rect, outline: FloatArray) {
        if (state.isHidden || destination.isEmpty || outline.size < 6) return
        val texture = layerPicture(layer).texture
        val solid = layerFade()
        val left = destination.left
        val top = destination.top
        val width = destination.width
        val height = destination.height
        premultiplied {
            val batch = batch()
            featherOutline(outline, antialias) { ax, ay, aCover, bx, by, bCover, cx, cy, cCover, dx, dy, dCover ->
                batch.corners(
                    texture,
                    ax, ay, ((ax - left) / width).coerceIn(0f, 1f), ((ay - top) / height).coerceIn(0f, 1f), if (aCover > 0f) solid else 0,
                    bx, by, ((bx - left) / width).coerceIn(0f, 1f), ((by - top) / height).coerceIn(0f, 1f), if (bCover > 0f) solid else 0,
                    cx, cy, ((cx - left) / width).coerceIn(0f, 1f), ((cy - top) / height).coerceIn(0f, 1f), if (cCover > 0f) solid else 0,
                    dx, dy, ((dx - left) / width).coerceIn(0f, 1f), ((dy - top) / height).coerceIn(0f, 1f), if (dCover > 0f) solid else 0,
                )
            }
        }
    }

    /** It really cuts one, with a soft edge, in the same batch as everything else. */
    override val cutsLayers: Boolean get() = true

    /**
     * The same picture, through somebody's shader. The quad is worked out here, in clip space, from
     * the batch's own transform — so it lands where the plain composite would, in a layer or out — and
     * blends the way the canvas's blend stack says, because it does not go through the batch.
     */
    private fun drawThrough(effect: ShaderEffect, picture: KorgeLayerPicture, destination: Rect) {
        val context = checkNotNull(context) { "drawLayer() outside a frame" }
        val batch = batch()
        // Whatever is queued was queued to land under this, so it goes first.
        batch.flush(BatchBreak.Shader)
        val t = batch.transform()
        effects().draw(
            context = context,
            effect = effect,
            texture = picture.texture,
            left = destination.left * t[0] + t[2],
            top = destination.top * t[1] + t[3],
            right = destination.right * t[0] + t[2],
            bottom = destination.bottom * t[1] + t[3],
            textureWidth = picture.width.toFloat(),
            textureHeight = picture.height.toFloat(),
            designWidth = destination.width,
            designHeight = destination.height,
            alpha = state.alpha.coerceIn(0f, 1f),
            mode = state.blend,
            scissor = batch.currentScissor,
        )
    }

    /**
     * Blending and a fade for a layer: premultiplied, because that is what the layer's own drawing made,
     * in the mode the blend stack says, so pushing Additive round a drawLayer makes a group glow.
     */
    private inline fun premultiplied(draw: () -> Unit) {
        batch().blend(state.blend, premultiplied = true, reason = BatchBreak.Layer)
        draw()
        batch().blend(state.blend, premultiplied = false, reason = BatchBreak.Layer)
    }

    /**
     * The opacity in all four channels, because a premultiplied colour that faded only its alpha would
     * get brighter as it disappeared. No tint: the layer's picture already has it.
     */
    private fun layerFade(): Int {
        val f = (state.alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        return (f shl 24) or (f shl 16) or (f shl 8) or f
    }

    private fun layerPicture(layer: TextureHandle): KorgeLayerPicture =
        layer as? KorgeLayerPicture ?: error("this canvas can only draw layers it made, not ${layer::class}")

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
        layers.close()
        effects?.close()
    }

    /**
     * ARGB with the opacity in force multiplied into the alpha and the tint in force into the other
     * three. The toolkit's colours are straight, and so is everything the shader does with them.
     */
    private fun Colour.faded(): Int {
        val alpha = (((argb ushr 24) and 0xFF) * state.alpha).toInt().coerceIn(0, 255)
        val tint = state.tint
        val red = ((argb shr 16) and 0xFF) * tint.red / 255
        val green = ((argb shr 8) and 0xFF) * tint.green / 255
        val blue = (argb and 0xFF) * tint.blue / 255
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /**
     * What to say about a texture this canvas cannot draw. Nine separately cut pieces are a
     * TextureHandle too, and "only textures it made" would send the reader hunting for a backend
     * mismatch that is not the problem.
     */
    private fun notOnePicture(texture: TextureHandle): Nothing =
        if (texture is NineRegions) throw IllegalArgumentException(NineRegions.NotOnePicture)
        else error("this canvas can only draw textures it made, not ${texture::class}")

    private companion object {
        /** Past this a layer is refused rather than asked of a driver that may not have it. */
        const val MaxLayerPixels = 4096
    }
}
