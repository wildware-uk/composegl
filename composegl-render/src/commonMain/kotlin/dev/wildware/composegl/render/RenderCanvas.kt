package dev.wildware.composegl.render

import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Matrix4
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.CanvasState
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.NineRegions
import dev.wildware.composegl.ui.graphics.SceneSurface
import dev.wildware.composegl.ui.graphics.SceneTarget
import dev.wildware.composegl.ui.graphics.TextZoom
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.graphics.featherOutline
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextLayout
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The canvas every backend draws with.
 *
 * Two conventions meet here and are reconciled once: the toolkit measures y downwards from the top
 * and the device's framebuffer upwards from the bottom; the toolkit works in the design resolution
 * and the window is whatever size it is. The scale and the letterbox go into the viewport rather
 * than into arithmetic on every call, so a game's own drawing through [raw] gets a projection
 * already in design coordinates.
 *
 * A backend is a thin wrapper round this: a [GpuDevice] (for OpenGL, `GlDevice` over the backend's
 * `Gl` binding), a [TextureResolver] for its own texture type, and what [raw] hands over.
 *
 * Merely having one costs no GPU: everything is built the first time something is drawn, or when
 * [warmUp] is called.
 *
 * @param atlas where solid colour is sampled from: the fonts' glyph atlas, so a panel and its label
 *   are one draw call. Without one the canvas keeps a one-pixel white texture of its own. Text
 *   still draws either way, from the pages its layout was measured onto.
 * @param prepareFonts called before the atlas is first read, for fonts that make glyphs up front.
 */
open class RenderCanvas protected constructor(
    val device: GpuDevice,
    private val atlas: GlyphAtlas?,
    private val textures: TextureResolver,
    private val prepareFonts: (() -> Unit)?,
) : UiCanvas, AutoCloseable {

    /**
     * @param fonts where text is measured and solid colour is sampled from. Sharing the glyph atlas
     *   is what makes a screen of panels and labels one draw call.
     */
    constructor(
        device: GpuDevice,
        fonts: AtlasFonts? = null,
        textures: TextureResolver = TextureResolver { null },
    ) : this(device, fonts?.atlas, textures, fonts?.let { owner -> { owner.prepare() } })

    private var batch: QuadBatch? = null

    private fun batch() = batch ?: QuadBatch(device).also {
        batch = it
        it.trace = trace
    }

    private var state = CanvasState(Rect.Zero)
    private var viewport: Viewport = Viewport.oneToOne(Size(1f, 1f))
    private var drawing = false
    private var begun = false

    /** How wide a softened edge is, in design units: one screen pixel, whatever the scale. */
    private var antialias = 1f

    /** The frame's scale to the nearest quarter, as a count of quarters: what glyphs are made again at. */
    private var quarter = SharpGlyphs.One

    /** The larger of the frame's two scales, which a pushed transform's text scale multiplies. */
    private var frameScale = 1f

    private val projection = FloatArray(16)

    private var ownWhite: DeviceTexture? = null
    private var whiteSpot: WhiteSpot? = null
    private var whiteSize = 0

    private val layers = LayerPool(device)

    /** The offscreen picture being drawn into, or null when that is the frame's own target. */
    private var layer: LayerFrame? = null

    private class LayerFrame(val bounds: Rect, val pixelWidth: Int, val pixelHeight: Int)

    /** Where the frame is drawn, and where the canvas believes the device is pointed right now. */
    private var frameTarget: FrameTarget = FrameTarget.Host
    private var target: FrameTarget = FrameTarget.Host
    private val viewportBox = IntArray(4)

    private val effectQuad = EffectQuad()

    /** Whether the frame's own target keeps its top row first, rather than OpenGL's bottom row. */
    private var topRowFirst = false

    /** How many times the frame so far has talked to the device. Nothing drawn yet is none. */
    override val drawCalls: Int get() = batch?.renderCalls ?: 0

    private var trace: DrawCallTrace? = null

    override fun traceDrawCalls(trace: DrawCallTrace?) {
        this.trace = trace
        batch?.trace = trace
    }

    override val tracesDrawCalls: Boolean get() = true

    /**
     * Builds the device's programs and buffers and the texture solid colour comes from now, instead
     * of in the first frame the player sees. On the thread that holds the context. Twice does nothing.
     */
    override fun warmUp() {
        batch()
        device.prepare()
        white()
    }

    /** Whether the GPU resources exist yet. */
    open val warmedUp: Boolean get() = batch != null && device.prepared

    override fun begin(viewport: Viewport) = begin(viewport, FrameTarget.Host)

    /**
     * Sets up for a frame in [viewport]'s design coordinates, drawn into [into].
     *
     * @param clear what to fill the target with first, or null to draw over what is there.
     */
    open fun begin(viewport: Viewport, into: FrameTarget, clear: Colour? = null) = begin(viewport, into, clear, topRowFirst = false)

    /**
     * The same, into a target that stores its top row first: a render texture an engine reads back
     * the way up it is drawn, as KorGE does. The viewport, the scissor and the projection are turned
     * over for it here, so everything below still gets pixels counted from the target's first row.
     */
    fun begin(viewport: Viewport, into: FrameTarget, clear: Colour?, topRowFirst: Boolean) {
        check(!drawing) { "begin() was called twice without an end()" }
        this.topRowFirst = topRowFirst
        drawing = true
        begun = true
        device.begin(into)
        frameTarget = into
        target = into
        device.noScissor()
        this.viewport = viewport
        state = CanvasState(Rect.of(0f, 0f, viewport.design.width, viewport.design.height))
        antialias = 1f / minOf(viewport.scaleX, viewport.scaleY).coerceAtLeast(0.0001f)
        frameScale = maxOf(viewport.scaleX, viewport.scaleY)
        quarter = SharpGlyphs.quarterOf(frameScale)
        atlas?.sharp?.beginFrame()

        // The letterbox and the scale live here, so nothing below has to think about them.
        setViewport(
            viewport.origin.x.roundToInt(),
            // The device counts up from the bottom; the viewport counts down from the top.
            if (topRowFirst) {
                viewport.origin.y.roundToInt()
            } else {
                (viewport.physical.height - viewport.origin.y - viewport.design.height * viewport.scaleY).roundToInt()
            },
            (viewport.design.width * viewport.scaleX).roundToInt(),
            (viewport.design.height * viewport.scaleY).roundToInt(),
        )
        if (clear != null) {
            val alpha = clear.alphaFraction
            device.clear(clear.red / 255f * alpha, clear.green / 255f * alpha, clear.blue / 255f * alpha, alpha)
        }
        orthographic(projection, viewport.design.width, viewport.design.height)
        if (topRowFirst) {
            projection[5] = -projection[5]
            projection[13] = -projection[13]
        }
        batch().begin(projection)
        // A picture bigger than its area is cut off at the area's edge rather than drawn over the
        // next player's.
        applyScissor()
    }

    /** Ends the frame and hands the state back the way the device was told to. */
    override fun end() {
        check(drawing) { "end() without a begin()" }

        batch().end()
        device.noScissor()
        setViewport(0, 0, viewport.physical.width.roundToInt(), viewport.physical.height.roundToInt())
        drawing = false
        layers.trim()
        device.end()

        // Complained about last, so an unbalanced frame still leaves things tidy.
        check(state.isBalanced) { "a clip, an alpha, a blend or a tint was pushed and never popped" }
    }

    // --- shapes ---

    override fun rect(rect: Rect, colour: Colour, corner: Float) {
        if (state.isHidden || rect.isEmpty) return
        shape(rect, colour, corner, corner, corner, corner, Colour.Transparent, 0f, Colour.Transparent, 0f)
    }

    override fun rect(rect: Rect, brush: Brush, corner: Float) =
        gradient(rect, brush, corner, corner, corner, corner)

    override fun rect(rect: Rect, brush: Brush, corners: Corners) =
        gradient(rect, brush, corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)

    @Suppress("LongParameterList")
    private fun gradient(rect: Rect, brush: Brush, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
        if (state.isHidden || rect.isEmpty) return
        val box = state.map(rect)
        // Worked out in the toolkit's coordinates, then y flipped. A radial gradient has no axis.
        val axis = (brush as? Brush.Linear)?.axis(box.width, box.height)
        batch().gradient(
            white = white(),
            left = box.left,
            bottom = flip(box.bottom),
            width = box.width,
            height = box.height,
            start = brush.first.inForce(),
            end = brush.last.inForce(),
            radial = brush is Brush.Radial,
            axisX = axis?.x ?: 0f,
            axisY = -(axis?.y ?: 0f),
            topLeft = state.mapLength(topLeft),
            topRight = state.mapLength(topRight),
            bottomRight = state.mapLength(bottomRight),
            bottomLeft = state.mapLength(bottomLeft),
            aa = antialias,
        )
    }

    override val drawsGradients: Boolean get() = true

    override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) {
        if (state.isHidden || rect.isEmpty || width <= 0f) return
        shape(rect, Colour.Transparent, corner, corner, corner, corner, colour, width, Colour.Transparent, 0f)
    }

    override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) {
        if (state.isHidden || spread <= 0f) return
        shape(rect, Colour.Transparent, corner, corner, corner, corner, Colour.Transparent, 0f, colour, spread)
    }

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

    override val roundsCornersSeparately: Boolean get() = true

    override fun fan(points: FloatArray, colour: Colour) {
        if (state.isHidden || points.size < 6) return
        // The batch reads the points before this call returns, so the flipped copy is scratch and
        // one is kept rather than made afresh: a plot hands over the same four corners a segment at
        // a time, hundreds a frame, and every one of them would otherwise be an array left behind.
        // The same size every time, because the batch reads the whole of whatever it is given.
        if (fanScratch.size != points.size) fanScratch = FloatArray(points.size)
        val flipped = fanScratch
        var at = 0
        while (at < points.size) {
            flipped[at] = state.mapX(points[at])
            flipped[at + 1] = flip(state.mapY(points[at + 1]))
            at += 2
        }
        batch().fan(white(), flipped, colour.inForce())
    }

    /** Where [fan] flips its points into. Scratch: nothing holds on to it past the call. */
    private var fanScratch = FloatArray(0)

    /** The one place a box reaches the batch, and where a pushed transform moves it and grows its thicknesses. */
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
        val box = state.map(rect)
        val grow = state.transformScale
        batch().shape(
            white = white(),
            left = box.left,
            bottom = flip(box.bottom),
            width = box.width,
            height = box.height,
            fill = fill.inForce(),
            // Top is still top: the flip moves the box, and the batch's own up is the screen's up.
            topLeft = topLeft * grow,
            topRight = topRight * grow,
            bottomRight = bottomRight * grow,
            bottomLeft = bottomLeft * grow,
            border = border.inForce(),
            borderWidth = borderWidth * grow,
            shadow = shadow.inForce(),
            shadowSpread = shadowSpread * grow,
            aa = antialias,
        )
    }

    // --- text and pictures ---

    override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) =
        drawText(layout, x, y, colour, ring = false)

    /** A ring copy leaves picture glyphs out: an emoji has an edge of its own already. */
    override fun textRing(layout: TextLayout, x: Float, y: Float, colour: Colour) =
        drawText(layout, x, y, colour, ring = true)

    private fun drawText(layout: TextLayout, x: Float, y: Float, colour: Colour, ring: Boolean) {
        if (state.isHidden) return
        val measured = layout as? AtlasTextLayout
            ?: error("this canvas can only draw text measured by its own fonts, not ${layout::class}")

        val tint = colour.inForce()
        // A picture keeps its own colours and takes only the text's fade.
        val pictureTint = Colour.White.scaleAlpha(colour.alphaFraction).inForce()

        var page: AtlasPage? = null
        var texture: DeviceTexture? = null
        val quarter = textQuarter()
        val remade = remadeSharp(quarter)
        val grow = state.transformScale
        val placedGlyphs = measured.placed
        for (index in placedGlyphs.indices) {
            val placed = placedGlyphs[index]
            val glyph = placed.glyph
            if (ring && glyph.colour) continue
            val on = glyph.page ?: continue
            val sharp = if (remade) glyph.sharp(quarter) else null
            if (sharp != null) {
                drawSharp(sharp, placed, x, y, if (sharp.colour) pictureTint else tint)
                page = null
                continue
            }
            if (on !== page) {
                page = on
                texture = on.texture(device)
            }
            val size = on.size.toFloat()
            val top = state.mapY(y + placed.top)
            batch().textured(
                texture = checkNotNull(texture),
                left = state.mapX(x + placed.left),
                bottom = flip(top + placed.height * grow),
                width = placed.width * grow,
                height = placed.height * grow,
                u = glyph.x / size,
                v = glyph.y / size,
                u2 = (glyph.x + glyph.width) / size,
                v2 = (glyph.y + glyph.height) / size,
                tint = if (glyph.colour) pictureTint else tint,
            )
        }
    }

    /**
     * A glyph's copy made for this frame's scale, in the design-unit place its original was measured
     * into. A letter is placed from its own offsets and snapped to the screen's pixels, so each of its
     * pixels lands on one of the screen's; a picture fills its original's box.
     */
    private fun drawSharp(sharp: Glyph, placed: PlacedGlyph, x: Float, y: Float, tint: Colour) {
        val on = checkNotNull(sharp.page)
        // Every time: a copy made just now is on the page but not yet uploaded.
        val texture = on.texture(device)
        val left: Float
        val top: Float
        val width: Float
        val height: Float
        val grow = state.transformScale
        if (sharp.fillsBox) {
            left = state.mapX(x + placed.left)
            top = state.mapY(y + placed.top)
            width = placed.width * grow
            height = placed.height * grow
        } else {
            val scaleX = viewport.scaleX
            val scaleY = viewport.scaleY
            val originX = layer?.bounds?.left ?: 0f
            val originY = layer?.bounds?.top ?: 0f
            val atX = state.mapX(x + placed.pen + sharp.xOffset / sharp.pixelsPerUnit)
            val atY = state.mapY(y + placed.baseline + sharp.yOffset / sharp.pixelsPerUnit)
            left = originX + floor((atX - originX) * scaleX + 0.5f) / scaleX
            top = originY + floor((atY - originY) * scaleY + 0.5f) / scaleY
            // Made for the nearest step of a zoom, so stretched by whatever the step left over.
            width = sharp.width / sharp.pixelsPerUnit * grow
            height = sharp.height / sharp.pixelsPerUnit * grow
        }
        val size = on.size.toFloat()
        batch().textured(
            texture = texture,
            left = left,
            bottom = flip(top + height),
            width = width,
            height = height,
            u = sharp.x / size,
            v = sharp.y / size,
            u2 = (sharp.x + sharp.width) / size,
            v2 = (sharp.y + sharp.height) / size,
            tint = tint,
        )
    }

    /**
     * The quarters glyphs are made at now: the frame's own, or under a pushed transform the frame's
     * scale times the transform's text scale, snapped to a [TextZoom] step first.
     */
    private fun textQuarter(): Int {
        val zoom = state.textScale
        if (zoom == 1f) return quarter
        return SharpGlyphs.quarterOf(frameScale * TextZoom.snap(zoom)).coerceAtLeast(1)
    }

    /**
     * Whether glyphs at [quarter] come off the sharp atlas rather than their ordinary copies.
     *
     * A frame's own scale only ever makes glyphs bigger; a zoom makes them smaller as well, and a
     * zoomed-out plane wants a copy made small just as much as a scaled-up window wants one made big.
     */
    private fun remadeSharp(quarter: Int): Boolean =
        if (state.textScale == 1f) quarter > SharpGlyphs.One else quarter != SharpGlyphs.One

    /** The picture being drawn, resolved once per call into fields rather than a fresh object. */
    private val picture = Resolved()

    private class Resolved {
        lateinit var texture: DeviceTexture
        var width = 0
        var height = 0
        var u = 0f
        var v = 0f
        var u2 = 0f
        var v2 = 0f
        var premultiplied = false
        var rotated = false

        // The part of it a `source` rectangle asks for, texture coordinates with v the top edge.
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f

        fun slice(source: Rect?) {
            if (source == null) {
                left = u
                top = v
                right = u2
                bottom = v2
                return
            }
            // A packer may lay a region down a quarter turn, and then a sub-rectangle's x and y
            // mean the other two axes. Refused rather than drawn wrongly.
            check(!rotated) { "part of a rotated atlas region cannot be drawn; pack this one without rotation" }
            val across = (u2 - u) / width
            val down = (v2 - v) / height
            left = u + source.left * across
            top = v + source.top * down
            right = u + source.right * across
            bottom = v + source.bottom * down
        }
    }

    /** Fills [picture] from [handle]. False when neither this canvas nor its resolver knows it. */
    private fun resolve(handle: TextureHandle): Boolean {
        val into = picture
        if (handle is LayerPicture) {
            into.texture = handle.target.texture
            into.u = handle.u
            into.v = handle.v
            into.u2 = handle.u2
            into.v2 = handle.v2
            into.premultiplied = true
            into.rotated = false
        } else if (handle is ScenePicture) {
            into.texture = handle.texture
            // Bottom row first, like a layer.
            into.u = 0f
            into.v = 1f
            into.u2 = 1f
            into.v2 = 0f
            into.premultiplied = true
            into.rotated = false
        } else {
            val bound = textures.resolve(handle) ?: return false
            into.texture = bound.texture
            into.u = bound.u
            into.v = bound.v
            into.u2 = bound.u2
            into.v2 = bound.v2
            into.premultiplied = bound.premultiplied
            into.rotated = bound.rotated
        }
        into.width = handle.width
        into.height = handle.height
        return true
    }

    override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) {
        if (state.isHidden || destination.isEmpty) return
        if (texture is NineRegions || !resolve(texture)) notOnePicture(texture)
        picture.slice(source)
        val box = state.map(destination)

        batch().textured(
            texture = picture.texture,
            left = box.left,
            bottom = flip(box.bottom),
            width = box.width,
            height = box.height,
            u = picture.left,
            v = picture.top,
            u2 = picture.right,
            v2 = picture.bottom,
            tint = tint.inForce(),
            premultiplied = picture.premultiplied,
        )
    }

    /** The same picture, turned: four corners on the processor, the same quad in the same batch. */
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
        if (texture is NineRegions || !resolve(texture)) notOnePicture(texture)
        picture.slice(source)
        val box = state.map(destination)

        batch().textured(
            texture = picture.texture,
            left = box.left,
            bottom = flip(box.bottom),
            width = box.width,
            height = box.height,
            pivotX = box.left + box.width * pivotX,
            // The pivot is a fraction from the top, and this is where it meets a y that counts up.
            pivotY = flip(box.top + box.height * pivotY),
            degrees = degrees,
            u = picture.left,
            v = picture.top,
            u2 = picture.right,
            v2 = picture.bottom,
            tint = tint.inForce(),
            premultiplied = picture.premultiplied,
        )
    }

    override val rotatesImages: Boolean get() = true

    /** Both, on every path a picture can reach the screen by: the batch and the effect. */
    override fun supports(mode: BlendMode): Boolean = true

    // --- clipping, opacity and blending ---

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

    override fun pushBlend(mode: BlendMode) {
        state.pushBlend(mode)
        applyBlend()
    }

    override fun popBlend() {
        state.popBlend()
        applyBlend()
    }

    /** No flush: the tint goes into each vertex's colour as it is queued. */
    override fun pushTint(tint: Colour) = state.pushTint(tint)

    override fun popTint() = state.popTint()

    override val tints: Boolean get() = true

    /** No flush either: each position is multiplied as it is queued. */
    override fun pushTransform(scale: Float, translateX: Float, translateY: Float) =
        state.pushTransform(scale, translateX, translateY)

    override fun pushTransform(scale: Float, translateX: Float, translateY: Float, textScale: Float) =
        state.pushTransform(scale, translateX, translateY, textScale)

    override fun popTransform() = state.popTransform()

    override val transforms: Boolean get() = true

    /** A colour as it reaches the batch: the tint in force multiplied in, then faded. */
    private fun Colour.inForce(): Colour = modulate(state.tint).scaleAlpha(state.alpha)

    /**
     * The batch follows the blend stack, which has already decided the innermost mode wins.
     * `batch?`: outside a frame there is nothing queued and nothing worth building.
     */
    private fun applyBlend() {
        batch?.blend(state.blend, premultiplied = false)
    }

    /** The scissor follows the clip stack. What is queued was queued under the old clip, so it goes first. */
    private fun applyScissor() {
        batch().flush(BatchBreak.Clip)
        val clip = state.clip
        val into = layer
        if (into != null) {
            scissorLayer(into, clip)
            return
        }
        // Never outside the viewport's area: in split-screen that is the edge of this player's part.
        val area = viewport.area
        val topLeft = viewport.toScreen(Offset(clip.left, clip.top))
        val bottomRight = viewport.toScreen(Offset(clip.right, clip.bottom))
        val left = maxOf(topLeft.x, area.left)
        val top = maxOf(topLeft.y, area.top)
        val right = minOf(bottomRight.x, area.right)
        val bottom = minOf(bottomRight.y, area.bottom)
        if (clip.left <= 0f && clip.top <= 0f &&
            clip.right >= viewport.design.width && clip.bottom >= viewport.design.height &&
            left - topLeft.x < 0.5f && top - topLeft.y < 0.5f &&
            bottomRight.x - right < 0.5f && bottomRight.y - bottom < 0.5f
        ) {
            device.noScissor()
            return
        }

        device.scissor(
            left.roundToInt(),
            (if (topRowFirst) top else viewport.physical.height - bottom).roundToInt(),
            (right - left).roundToInt().coerceAtLeast(0),
            (bottom - top).roundToInt().coerceAtLeast(0),
        )
    }

    /** The same, in a layer's pixels: its own origin, its own height, and no letterbox. */
    private fun scissorLayer(into: LayerFrame, clip: Rect) {
        if (clip.left <= into.bounds.left && clip.top <= into.bounds.top &&
            clip.right >= into.bounds.right && clip.bottom >= into.bounds.bottom
        ) {
            device.noScissor()
            return
        }

        val left = ((clip.left - into.bounds.left) * viewport.scaleX).roundToInt()
        val right = ((clip.right - into.bounds.left) * viewport.scaleX).roundToInt()
        val top = ((clip.top - into.bounds.top) * viewport.scaleY).roundToInt()
        val bottom = ((clip.bottom - into.bounds.top) * viewport.scaleY).roundToInt()
        device.scissor(
            left,
            into.pixelHeight - bottom,
            (right - left).coerceAtLeast(0),
            (bottom - top).coerceAtLeast(0),
        )
    }

    // --- layers ---

    /**
     * Whether the device draws offscreen. Before the first frame the device has not been asked —
     * asking needs a context — so the answer is the optimistic one every OpenGL device gives.
     */
    private val offscreen: Boolean get() = !begun || device.limits.offscreen

    override val drawsLayers: Boolean get() = offscreen

    override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? {
        check(drawing) { "layer() outside a frame" }
        if (bounds.isEmpty || !device.limits.offscreen) return null
        // Where the picture really lands, so it is taken at the screen's resolution of what is there.
        val area = state.map(bounds)

        // Screen resolution, rounded up, so nothing falls off an edge that is not a whole pixel.
        val pixelWidth = ceil(area.width * viewport.scaleX).toInt()
        val pixelHeight = ceil(area.height * viewport.scaleY).toInt()
        if (pixelWidth <= 0 || pixelHeight <= 0) return null
        val most = minOf(LayerPool.MaxLayerPixels, device.limits.maxTextureSize)
        if (pixelWidth > most || pixelHeight > most) return null

        val picture = layers.acquire(pixelWidth, pixelHeight)

        val previousTarget = target
        val previousViewport = viewportBox.copyOf()
        val previousState = state
        val previousLayer = layer
        val previousProjection = projection.copyOf()

        batch().flush(BatchBreak.Layer)
        layer = LayerFrame(area, pixelWidth, pixelHeight)
        // Full opacity and a clip of exactly the layer; the tint and the transform come in with it.
        state = previousState.forLayer(area)

        target = picture
        setViewport(0, 0, pixelWidth, pixelHeight)
        device.noScissor()
        device.clear(0f, 0f, 0f, 0f)
        orthographic(projection, area.width, area.height, area.left)
        batch().projection(projection)
        // Plain blending inside: adding into transparent black then compositing is not adding.
        applyBlend()

        try {
            block()
            batch().flush(BatchBreak.Layer)
            check(state.isBalanced) { "a clip, an alpha, a blend or a tint was pushed inside a layer and never popped" }
        } finally {
            layer = previousLayer
            state = previousState
            previousProjection.copyInto(projection)
            batch().projection(projection)
            applyBlend()
            target = previousTarget
            setViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
            // Worked out again rather than switched back on: the layer set a scissor of its own.
            applyScissor()
            layers.release(picture)
        }

        // A framebuffer's first row is its bottom one, so v and v2 are swapped here, once.
        return LayerPicture(picture, pixelWidth, pixelHeight, u = 0f, v = 1f, u2 = 1f, v2 = 0f)
    }

    private fun layerPicture(layer: TextureHandle) {
        if (layer is NineRegions || !resolve(layer)) error("this canvas can only draw layers it made, not ${layer::class}")
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, effect: ShaderEffect?) {
        if (state.isHidden || destination.isEmpty) return
        layerPicture(layer)
        val box = state.map(destination)
        if (effect != null) {
            drawThrough(effect, box)
            return
        }
        composite(box, mirrorX = false, mirrorY = false)
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, mirrorX: Boolean, mirrorY: Boolean) {
        if (state.isHidden || destination.isEmpty) return
        layerPicture(layer)
        composite(state.map(destination), mirrorX, mirrorY)
    }

    override val mirrorsLayers: Boolean get() = offscreen

    /** The fade in all four channels: a premultiplied colour that faded only its alpha would brighten. */
    private fun fade(): Colour {
        val grey = (state.alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return Colour((grey shl 24) or (grey shl 16) or (grey shl 8) or grey)
    }

    private fun composite(destination: Rect, mirrorX: Boolean, mirrorY: Boolean) {
        // The mode in force applies to the composite; premultiplied, because a layer's drawing is.
        batch().blend(state.blend, premultiplied = true, reason = BatchBreak.Layer)
        val picture = picture
        batch().textured(
            texture = picture.texture,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            u = if (mirrorX) picture.u2 else picture.u,
            v = if (mirrorY) picture.v2 else picture.v,
            u2 = if (mirrorX) picture.u else picture.u2,
            v2 = if (mirrorY) picture.v else picture.v2,
            tint = fade(),
        )
        batch().blend(state.blend, premultiplied = false, reason = BatchBreak.Layer)
    }

    override fun drawLayer(
        layer: TextureHandle,
        destination: Rect,
        degrees: Float,
        pivotX: Float,
        pivotY: Float,
    ) {
        if (degrees == 0f) {
            drawLayer(layer, destination)
            return
        }
        if (state.isHidden || destination.isEmpty) return
        layerPicture(layer)
        val box = state.map(destination)

        batch().blend(state.blend, premultiplied = true, reason = BatchBreak.Layer)
        batch().textured(
            texture = picture.texture,
            left = box.left,
            bottom = flip(box.bottom),
            width = box.width,
            height = box.height,
            pivotX = box.left + box.width * pivotX,
            pivotY = flip(box.top + box.height * pivotY),
            degrees = degrees,
            u = picture.u,
            v = picture.v,
            u2 = picture.u2,
            v2 = picture.v2,
            tint = fade(),
        )
        batch().blend(state.blend, premultiplied = false, reason = BatchBreak.Layer)
    }

    override val turnsLayers: Boolean get() = offscreen

    /**
     * The picture as a fan through [outline], with a ring one screen pixel wide round it that fades
     * to nothing. Texture coordinates are held inside the picture, so a clamped edge never repeats.
     */
    override fun cutLayer(layer: TextureHandle, destination: Rect, outline: FloatArray) {
        if (state.isHidden || destination.isEmpty || outline.size < 6) return
        layerPicture(layer)

        batch().blend(state.blend, premultiplied = true, reason = BatchBreak.Layer)
        val solid = fade()
        val clear = Colour.Transparent
        val box = state.map(destination)
        val left = box.left
        val top = box.top
        val width = box.width
        val height = box.height
        val picture = picture
        val uSpan = picture.u2 - picture.u
        val vSpan = picture.v2 - picture.v
        val texture = picture.texture
        val edge = if (!state.isTransformed) outline
        else FloatArray(outline.size) { if (it % 2 == 0) state.mapX(outline[it]) else state.mapY(outline[it]) }

        featherOutline(edge, antialias) { ax, ay, aCover, bx, by, bCover, cx, cy, cCover, dx, dy, dCover ->
            batch().corners(
                texture,
                ax, flip(ay),
                picture.u + ((ax - left) / width).coerceIn(0f, 1f) * uSpan,
                picture.v + ((ay - top) / height).coerceIn(0f, 1f) * vSpan,
                if (aCover > 0f) solid else clear,
                bx, flip(by),
                picture.u + ((bx - left) / width).coerceIn(0f, 1f) * uSpan,
                picture.v + ((by - top) / height).coerceIn(0f, 1f) * vSpan,
                if (bCover > 0f) solid else clear,
                cx, flip(cy),
                picture.u + ((cx - left) / width).coerceIn(0f, 1f) * uSpan,
                picture.v + ((cy - top) / height).coerceIn(0f, 1f) * vSpan,
                if (cCover > 0f) solid else clear,
                dx, flip(dy),
                picture.u + ((dx - left) / width).coerceIn(0f, 1f) * uSpan,
                picture.v + ((dy - top) / height).coerceIn(0f, 1f) * vSpan,
                if (dCover > 0f) solid else clear,
            )
        }
        batch().blend(state.blend, premultiplied = false, reason = BatchBreak.Layer)
    }

    override val cutsLayers: Boolean get() = offscreen

    override fun drawLayerOnto(layer: TextureHandle, destination: Rect, corners: FloatArray) {
        require(corners.size == 8) { "four corners are eight numbers, not ${corners.size}" }
        if (state.isHidden || destination.isEmpty) return
        layerPicture(layer)

        val flipped = FloatArray(8) { if (it % 2 == 0) state.mapX(corners[it]) else flip(state.mapY(corners[it])) }

        batch().blend(state.blend, premultiplied = true, reason = BatchBreak.Layer)
        batch().textured(
            texture = picture.texture,
            corners = flipped,
            u = picture.u,
            v = picture.v,
            u2 = picture.u2,
            v2 = picture.v2,
            tint = fade(),
        )
        batch().blend(state.blend, premultiplied = false, reason = BatchBreak.Layer)
    }

    override val drawsLayersOnto: Boolean get() = offscreen

    override fun drawLayer(layer: TextureHandle, destination: Rect, transform: Matrix4) {
        if (state.isHidden || destination.isEmpty) return
        layerPicture(layer)

        // Each corner through the transform, left undivided: x, y and w. y is flipped as
        // base * w - y, which after the divide is base - y.
        val base = flipBase()
        val corners = FloatArray(12)
        transform.project(destination.left, destination.top, corners, 0)
        transform.project(destination.right, destination.top, corners, 3)
        transform.project(destination.right, destination.bottom, corners, 6)
        transform.project(destination.left, destination.bottom, corners, 9)
        // A pushed transform after the node's own, on the undivided numbers: scaled, and moved by w.
        val grow = state.transformScale
        for (at in 0 until 12 step 3) {
            corners[at] = corners[at] * grow + state.transformX * corners[at + 2]
            val y = corners[at + 1] * grow + state.transformY * corners[at + 2]
            corners[at + 1] = base * corners[at + 2] - y
        }

        batch().blend(state.blend, premultiplied = true, reason = BatchBreak.Layer)
        batch().projected(
            texture = picture.texture,
            corners = corners,
            u = picture.u,
            v = picture.v,
            u2 = picture.u2,
            v2 = picture.v2,
            tint = fade(),
        )
        batch().blend(state.blend, premultiplied = false, reason = BatchBreak.Layer)
    }

    override val tiltsLayers: Boolean get() = offscreen

    private fun setViewport(x: Int, y: Int, width: Int, height: Int) {
        viewportBox[0] = x
        viewportBox[1] = y
        viewportBox[2] = width
        viewportBox[3] = height
        device.target(target, x, y, width, height)
    }

    /** The resolved picture, through somebody's shader, on a quad worked out here in clip space. */
    private fun drawThrough(effect: ShaderEffect, destination: Rect) {
        // Whatever is queued was queued to land under this, so it goes first.
        batch().flush(BatchBreak.Shader)

        val picture = picture
        val quad = effectQuad
        quad.left = clipX(destination.left)
        quad.top = clipY(flip(destination.top))
        quad.right = clipX(destination.right)
        quad.bottom = clipY(flip(destination.bottom))
        quad.u = picture.u
        quad.v = picture.v
        quad.u2 = picture.u2
        quad.v2 = picture.v2
        quad.textureWidth = picture.width.toFloat()
        quad.textureHeight = picture.height.toFloat()
        quad.width = destination.width
        quad.height = destination.height
        quad.alpha = state.alpha.coerceIn(0f, 1f)
        // The mode in force applies whether or not there is a shader in the way.
        device.drawEffect(effect, picture.texture, quad, Blend.of(state.blend, premultiplied = true))

        batch().blend(state.blend, premultiplied = false, reason = BatchBreak.Layer)
    }

    private fun clipX(x: Float) = x * projection[0] + projection[12]

    private fun clipY(y: Float) = y * projection[5] + projection[13]

    /** The projection handed over by [raw] measures y upwards, so a design y goes through [flip]. */
    override fun rawY(y: Float): Float = flip(y)

    /** A layer's left edge is in the projection, so a design x is already right. */
    override fun rawX(x: Float): Float = x

    override val handsOverRaw: Boolean get() = true

    override val movesRawOrigin: Boolean get() = true

    /**
     * What [raw] hands a block. The frame's projection and viewport by default; a backend hands its
     * own type. [projection] is a copy the block may keep.
     */
    protected open fun handOver(projection: FloatArray, viewport: Viewport): Any = RenderFrame(projection, viewport)

    /**
     * Runs [block] with [lent], with the engine's state already handed back. A backend whose drawing
     * object must be opened and closed round a block — a sprite batch — does that here.
     */
    protected open fun lend(lent: Any, projection: FloatArray, block: (Any) -> Unit) = block(lent)

    /** The bottom-left corner, because the projection measures y upwards. */
    override fun raw(destination: Rect, block: (Any) -> Unit) {
        batch().flush(BatchBreak.Raw)
        val moved = transformed(projection.copyOf()).also {
            it[12] += rawX(destination.left) * it[0]
            it[13] += rawY(destination.bottom) * it[5]
        }
        runRaw(block, moved)
    }

    override fun raw(block: (Any) -> Unit) {
        // Our own quads first, so the game's drawing lands on top of what came before it.
        batch().flush(BatchBreak.Raw)
        runRaw(block, transformed(projection.copyOf()))
    }

    /**
     * [into] with a pushed transform folded in, so a game drawing through the hatch in the
     * coordinates it was handed lands where everything else does. In the projection's own y-up
     * coordinates a design y of `base - y` has to end up at `base - (y × scale + move)`, which is a
     * scale and a move of `base × (1 - scale) - move`.
     */
    private fun transformed(into: FloatArray): FloatArray {
        if (!state.isTransformed) return into
        val grow = state.transformScale
        into[12] += into[0] * state.transformX
        into[13] += into[5] * (flipBase() * (1f - grow) - state.transformY)
        into[0] *= grow
        into[5] *= grow
        return into
    }

    private fun runRaw(block: (Any) -> Unit, projection: FloatArray) {
        val lent = handOver(projection, viewport)
        if (!drawing) {
            lend(lent, projection, block)
            return
        }
        device.suspend()
        try {
            lend(lent, projection, block)
        } finally {
            device.resume()
        }
    }

    // --- scenes ---

    override val drawsScenes: Boolean get() = offscreen

    /** The device's biggest texture, which is where a [RenderTarget] would cut a picture down anyway. */
    override val maxSceneSize: Int get() = device.limits.maxTextureSize.coerceAtLeast(1)

    /**
     * Renders a game's scene into a picture with a depth buffer, outside any frame.
     *
     * The device is taken for the picture alone — its framebuffer bound, the viewport over all of
     * it, the scissor off — and given back when [draw] returns, so the state a scene sets (depth
     * test, culling, its own programs) goes no further. A [SceneTarget.raw] block runs with the
     * picture still bound rather than suspended to the engine's framebuffer: it is the engine's
     * frame object, pointed at the picture.
     *
     * A picture made by another canvas, or closed, is not reused; the caller gives it back.
     */
    override fun scene(surface: SceneSurface?, width: Int, height: Int, draw: (SceneTarget) -> Unit): SceneSurface? {
        check(!drawing) { "scene() inside a frame: scenes are rendered before the frame begins, not in the middle of it" }
        if (width <= 0 || height <= 0) return surface
        if (!device.limits.offscreen) return null
        val mine = (surface as? ScenePicture)?.takeIf { !it.closed && it.device === device }
        val picture = mine?.also { it.target.resize(width, height) }
            ?: ScenePicture(RenderTarget(device, width, height, depth = true), device)

        val into = checkNotNull(picture.target.target)
        val binding = sceneBinding
        device.begin(into)
        try {
            device.target(into, 0, 0, picture.width, picture.height)
            device.noScissor()
            orthographic(binding.projection, picture.width.toFloat(), picture.height.toFloat())
            binding.picture = picture
            draw(binding)
        } finally {
            binding.picture = null
            device.end()
        }
        return picture
    }

    private val sceneBinding = SceneBinding()

    /** What a scene's block is handed. One per canvas, pointed at the picture being rendered. */
    private inner class SceneBinding : SceneTarget {
        var picture: ScenePicture? = null
        val projection = FloatArray(16)

        private fun bound(): ScenePicture = checkNotNull(picture) { "a scene target is only good inside scene()" }

        override val width: Int get() = bound().width

        override val height: Int get() = bound().height

        override fun clear(colour: Colour) {
            val picture = bound()
            // The game may have moved the viewport or switched a scissor on since the last one.
            device.target(checkNotNull(picture.target.target), 0, 0, picture.width, picture.height)
            device.noScissor()
            val alpha = colour.alphaFraction
            device.clear(colour.red / 255f * alpha, colour.green / 255f * alpha, colour.blue / 255f * alpha, alpha)
        }

        override fun raw(block: (Any) -> Unit) {
            val picture = bound()
            val handed = projection.copyOf()
            val viewport = Viewport.oneToOne(Size(picture.width.toFloat(), picture.height.toFloat()))
            lend(handOver(handed, viewport), handed, block)
        }
    }

    /**
     * The device lost its context with every object in it. Forgets them all; the next frame
     * rebuilds programs and buffers and uploads the glyph atlas again from memory.
     */
    fun contextLost() {
        device.contextLost()
        layers.forget()
        ownWhite = null
        whiteSpot = null
        atlas?.forget(device)
        atlas?.sharp?.atlas?.forget(device)
    }

    /**
     * Lets go of everything that was built. What was never built is not built in order to be
     * destroyed: a canvas that drew nothing closes without touching the device at all.
     */
    override fun close() {
        layers.close()
        atlas?.release(device)
        atlas?.sharp?.atlas?.release(device)
        ownWhite?.let(device::delete)
        ownWhite = null
        whiteSpot = null
        device.close()
    }

    /**
     * Where solid colour is sampled from: the glyph atlas's white block when there are fonts, so a
     * panel and its label are one draw call; a one-pixel texture of this canvas's own when not.
     */
    private fun white(): WhiteSpot {
        val fonts = atlas
        // Wherever the glyphs of this draw are coming from, so a panel and its label are still one
        // draw call — the sharp page on a scaled-up frame, and on a zoomed-out plane too.
        val atlas = if (fonts != null && remadeSharp(textQuarter())) fonts.sharp?.atlas ?: fonts else fonts
        val cached = whiteSpot
        if (atlas == null) {
            if (cached != null) return cached
            val texture = device.texture(1, 1, smooth = true)
            device.write(texture, 0, 0, 1, 1, byteArrayOf(-1, -1, -1, -1), 1)
            ownWhite = texture
            return WhiteSpot(texture, 0.5f, 0.5f).also { whiteSpot = it }
        }
        prepareFonts?.invoke()
        val spot = atlas.white
        val texture = spot.page.texture(device)
        if (cached != null && cached.texture === texture && whiteSize == spot.page.size) return cached
        val size = spot.page.size.toFloat()
        val middle = GlyphAtlas.WhiteBlock / 2f
        whiteSize = spot.page.size
        return WhiteSpot(texture, (spot.x + middle) / size, (spot.y + middle) / size).also { whiteSpot = it }
    }

    /** A y measured down from the top becomes one measured up from the bottom of the layer or design. */
    private fun flip(y: Float) = flipBase() - y

    private fun flipBase() = layer?.bounds?.bottom ?: viewport.design.height

    /**
     * What to say about a texture this canvas cannot draw. Nine separately cut pieces throw the
     * toolkit's own [IllegalArgumentException]; a texture from another backend an
     * [IllegalStateException].
     */
    private fun notOnePicture(texture: TextureHandle): Nothing =
        if (texture is NineRegions) throw IllegalArgumentException(NineRegions.NotOnePicture)
        else error("this canvas can only draw textures it made, not ${texture::class}")

    private companion object {

        /** Design coordinates onto the clip cube, column-major, origin bottom-left. */
        fun orthographic(into: FloatArray, width: Float, height: Float, left: Float = 0f) {
            into.fill(0f)
            into[0] = 2f / width
            into[5] = 2f / height
            into[10] = -1f
            // [left] is where a layer starts, so drawing into one uses the same coordinates.
            into[12] = -1f - left * 2f / width
            into[13] = -1f
            into[15] = 1f
        }
    }
}
