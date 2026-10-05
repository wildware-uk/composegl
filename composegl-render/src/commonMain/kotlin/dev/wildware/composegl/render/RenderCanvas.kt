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
import dev.wildware.composegl.ui.graphics.Relief
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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
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

    /** The frame's scale in steps: what glyphs are made again at. See [SharpGlyphs.stepOf]. */
    private var step = SharpGlyphs.One

    /** The scale the frame before this one was drawn at, so a steady window can be told from a resize. */
    private var lastFrameScale = Float.NaN

    /** The larger of the frame's two scales, which a pushed transform's text scale multiplies. */
    private var frameScale = 1f

    private val projection = FloatArray(16)

    private var ownWhite: DeviceTexture? = null
    private var whiteSpot: WhiteSpot? = null
    private var whiteSize = 0

    private val layers = LayerPool(device)

    /** The offscreen picture being drawn into, or null when that is the frame's own target. */
    private var layer: LayerFrame? = null

    /** How many pictures deep the drawing is: none on the frame's own target. */
    private var layerDepth = 0

    /** How many clips are pushed in the picture being drawn into, or in the frame outside any. */
    private var clipDepth = 0

    /** The rounded clip in force, or null. One at a time: see [roundsClips]. */
    private var rounding: Rounding? = null

    /** What [clipDepth] was when [rounding] was put on, so the pop that takes it off can be told. */
    private var roundedAt = 0

    /** The rectangle the last [pushClip] asked for, as it was handed in, and how deep it went. */
    private var lastClip: Rect? = null
    private var lastClipDepth = -1

    /**
     * One rounding per picture depth, refilled as rounded clips come and go. A picture inside a
     * rounded clip can have a rounded clip of its own while the outer one waits to be put back, so
     * they cannot share one.
     */
    private val roundings = ArrayList<Rounding>()

    /**
     * A rounded clip in force: the [ClipMask] the device trims with, in its pixels, and the same box
     * in design units — where it lands, through the transform — for [throughMask] to ask about.
     */
    private class Rounding {
        val mask = ClipMask()

        /** The clip in force where it was put on, through the transform: all an opened picture needs. */
        var area: Rect = Rect.Zero
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        var topLeft = 0f
        var topRight = 0f
        var bottomRight = 0f
        var bottomLeft = 0f

        /**
         * Whether everything from [l], [t] to [r], [b] is kept whole: inside the box and clear of
         * every rounded corner, where the mask keeps all of a pixel and trimming it would change
         * nothing.
         */
        fun keepsAll(l: Float, t: Float, r: Float, b: Float): Boolean =
            l >= left && t >= top && r <= right && b <= bottom &&
                (l >= left + topLeft || t >= top + topLeft) &&
                (r <= right - topRight || t >= top + topRight) &&
                (r <= right - bottomRight || b <= bottom - bottomRight) &&
                (l >= left + bottomLeft || b <= bottom - bottomLeft)
    }

    private class LayerFrame(val bounds: Rect, val pixelWidth: Int, val pixelHeight: Int)

    /**
     * The picture a game's own drawing opened inside the rounded clip in force, and what drawing
     * went back to when it closes: see [intoOpened]. Null while there is none.
     */
    private var opened: Opened? = null

    private class Opened(
        val picture: DeviceTarget,
        val area: Rect,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val target: FrameTarget,
        val viewport: IntArray,
        val layer: LayerFrame?,
        val projection: FloatArray,
    )

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
        layer = null
        layerDepth = 0
        clipDepth = 0
        rounding = null
        opened = null
        lastClip = null
        antialias = 1f / minOf(viewport.scaleX, viewport.scaleY).coerceAtLeast(0.0001f)
        frameScale = maxOf(viewport.scaleX, viewport.scaleY)
        step = SharpGlyphs.stepOf(frameScale, steady = frameScale == lastFrameScale)
        lastFrameScale = frameScale
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
        if (brush is Brush.Ramp && ramp(rect, brush, topLeft, topRight, bottomRight, bottomLeft)) return
        val box = state.map(rect)
        // Worked out in the toolkit's coordinates, then y flipped. A radial gradient has no axis.
        val axis = (brush as? Brush.Linear)?.axis(box.width, box.height) ?: (brush as? Brush.Ramp)?.straight?.axis(box.width, box.height)
        batch().gradient(
            white = white(),
            left = box.left,
            bottom = flip(box.bottom),
            width = box.width,
            height = box.height,
            start = brush.first.inForce(),
            end = brush.last.inForce(),
            radial = brush is Brush.Radial || (brush as? Brush.Ramp)?.radial == true,
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

    /**
     * A run of stops, drawn from a strip of the atlas. False when there is nowhere to bake one —
     * a canvas with no fonts, or an atlas with no room left — and the caller falls back to the two
     * colours at the ends, which is what a canvas that cannot draw gradients at all would show.
     */
    @Suppress("LongParameterList")
    private fun ramp(rect: Rect, brush: Brush.Ramp, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float): Boolean {
        val pages = atlas ?: return false
        val spot = pages.ramps.spotFor(brush) ?: return false
        val page = spot.page
        val size = page.size.toFloat()
        val box = state.map(rect)
        val axis = brush.straight?.axis(box.width, box.height)
        // The middle of the first texel to the middle of the last, so the ends are the run's ends.
        val v = (spot.y + 0.5f) / size
        batch().rampGradient(
            white = WhiteSpot(page.texture(device), (spot.x + 0.5f) / size, v),
            left = box.left,
            bottom = flip(box.bottom),
            width = box.width,
            height = box.height,
            tint = Colour.White.inForce(),
            radial = brush.radial,
            axisX = axis?.x ?: 0f,
            axisY = -(axis?.y ?: 0f),
            u = (spot.x + 0.5f) / size,
            v = v,
            u2 = (spot.x + GradientRamps.Texels - 0.5f) / size,
            v2 = v,
            topLeft = state.mapLength(topLeft),
            topRight = state.mapLength(topRight),
            bottomRight = state.mapLength(bottomRight),
            bottomLeft = state.mapLength(bottomLeft),
            border = Colour.Transparent,
            borderWidth = 0f,
            aa = antialias,
        )
        return true
    }

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

    override fun borderOutside(rect: Rect, colour: Colour, width: Float, corner: Float) {
        if (state.isHidden || rect.isEmpty || width <= 0f) return
        shape(rect, Colour.Transparent, corner, corner, corner, corner, colour, -width, Colour.Transparent, 0f)
    }

    override fun borderOutside(rect: Rect, colour: Colour, width: Float, corners: Corners) {
        if (state.isHidden || rect.isEmpty || width <= 0f) return
        shape(
            rect, Colour.Transparent,
            corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft,
            colour, -width, Colour.Transparent, 0f,
        )
    }

    override fun innerShade(rect: Rect, colour: Colour, depth: Float, corner: Float, offsetX: Float, offsetY: Float, hardness: Float) {
        if (state.isHidden || rect.isEmpty || depth <= 0f) return
        // The shade is asked for in the toolkit's units, y down; the batch's y counts up.
        shape(rect, Colour.Transparent, corner, corner, corner, corner, Colour.Transparent, 0f, colour, -depth, offsetX, -offsetY, hardness)
    }

    override fun innerShade(rect: Rect, colour: Colour, depth: Float, corners: Corners, offsetX: Float, offsetY: Float, hardness: Float) {
        if (state.isHidden || rect.isEmpty || depth <= 0f) return
        shape(
            rect, Colour.Transparent,
            corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft,
            Colour.Transparent, 0f, colour, -depth, offsetX, -offsetY, hardness,
        )
    }

    override fun relief(
        rect: Rect,
        corners: Corners,
        shape: Relief,
        depth: Float,
        light: Float,
        elevation: Float,
        strength: Float,
        gloss: Float,
        polish: Float,
        face: Colour,
        faceRun: Brush.Ramp?,
        material: TextureHandle?,
        tiles: Float,
    ) {
        if (state.isHidden || rect.isEmpty || depth <= 0f || strength <= 0f) return
        val box = state.map(rect)
        // A material is laid across the face and tinted by the face's colour. It takes the one
        // texture this quad has, so a face is a material or a run of colours, never both — and a
        // material has to be a texture of its own rather than a region of an atlas, because tiling
        // a region samples its neighbours at every repeat. A layer's picture is not one either: it
        // is the corner of a bigger pooled picture, and was always laid on upside down besides.
        val grain = material?.takeIf { resolve(it) }?.let {
            check(material !is LayerPicture) {
                "a lit face's material is tiled, so it needs a texture of its own rather than a layer's picture"
            }
            check(picture.u == 0f && picture.u2 == 1f) {
                "a lit face's material is tiled, so it needs a texture of its own rather than a region of an atlas"
            }
            picture.texture
        }
        // Where the run of colours sits on the atlas, if there is one and it fitted. A lit quad
        // samples no texture of its own, so its texture coordinate is free to point at the strip;
        // if the atlas is full the face falls back to the one colour, which is the run's first.
        val run = if (grain != null) null else faceRun?.let { atlas?.ramps?.spotFor(it) }
        val runPage = run?.page
        val runSize = runPage?.size?.toFloat() ?: 1f
        val grow = state.transformScale
        val radians = light * PiOver180
        val high = elevation * PiOver180
        val flat = cos(high)
        batch().relief(
            white = when {
                grain != null -> WhiteSpot(grain, 0f, 0f)
                runPage != null -> WhiteSpot(runPage.texture(device), 0f, 0f)
                else -> white()
            },
            left = box.left,
            bottom = flip(box.bottom),
            width = box.width,
            height = box.height,
            topLeft = state.mapLength(corners.topLeft),
            topRight = state.mapLength(corners.topRight),
            bottomRight = state.mapLength(corners.bottomRight),
            bottomLeft = state.mapLength(corners.bottomLeft),
            kind = when (shape) {
                Relief.Chamfer -> ShapeVertex.ReliefChamfer
                Relief.Fillet -> ShapeVertex.ReliefFillet
                Relief.Dome -> ShapeVertex.ReliefDome
            },
            bevel = depth * grow,
            strength = strength,
            lightX = cos(radians) * flat,
            // The toolkit's y counts down and the batch's counts up, so the light turns with it.
            lightY = -sin(radians) * flat,
            lightZ = sin(high),
            gloss = gloss,
            polish = polish,
            face = (if (run != null) Colour.White else face).inForce(),
            faceU = if (run != null) (run.x + 0.5f) / runSize else 0f,
            faceV = if (run != null) (run.y + 0.5f) / runSize else 0f,
            faceWidth = if (run != null) (GradientRamps.Texels - 1f) / runSize else 0f,
            faceTiles = if (grain != null) tiles.coerceAtLeast(0.01f) else 0f,
            aa = antialias,
        )
    }

    override val shadesInside: Boolean get() = true

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
        shadowOffsetX: Float = 0f,
        shadowOffsetY: Float = 0f,
        shadowHardness: Float = 0f,
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
            shadowOffsetX = shadowOffsetX * grow,
            shadowOffsetY = shadowOffsetY * grow,
            shadowHardness = shadowHardness,
        )
    }

    // --- text and pictures ---

    override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) =
        drawText(layout, x, y, 0f, 0f, colour, ring = false)

    /** A ring copy leaves picture glyphs out: an emoji has an edge of its own already. */
    override fun textRing(layout: TextLayout, x: Float, y: Float, colour: Colour) =
        drawText(layout, x, y, 0f, 0f, colour, ring = true)

    /**
     * A ring copy whose letters are snapped where the face's are, then moved exactly [dx], [dy], so
     * every copy of every line sits the same distance off its letter.
     */
    override fun textRing(layout: TextLayout, x: Float, y: Float, dx: Float, dy: Float, colour: Colour) =
        drawText(layout, x, y, dx, dy, colour, ring = true)

    private fun drawText(layout: TextLayout, x: Float, y: Float, dx: Float, dy: Float, colour: Colour, ring: Boolean) {
        if (state.isHidden) return
        val measured = layout as? AtlasTextLayout
            ?: error("this canvas can only draw text measured by its own fonts, not ${layout::class}")

        val tint = colour.inForce()
        // A picture keeps its own colours and takes only the text's fade.
        val pictureTint = Colour.White.scaleAlpha(colour.alphaFraction).inForce()

        var page: AtlasPage? = null
        var texture: DeviceTexture? = null
        val step = textStep()
        val remade = remadeSharp(step)
        val grow = state.transformScale
        val placedGlyphs = measured.placed
        for (index in placedGlyphs.indices) {
            val placed = placedGlyphs[index]
            val glyph = placed.glyph
            if (ring && glyph.colour) continue
            val on = glyph.page ?: continue
            val sharp = if (remade) glyph.sharp(step) else null
            if (sharp != null) {
                drawSharp(sharp, placed, x, y, dx, dy, if (sharp.colour) pictureTint else tint)
                page = null
                continue
            }
            if (on !== page) {
                page = on
                texture = on.texture(device)
            }
            val size = on.size.toFloat()
            val top = state.mapY(y + dy + placed.top)
            batch().textured(
                texture = checkNotNull(texture),
                left = state.mapX(x + dx + placed.left),
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
     *
     * A ring copy's offset [dx], [dy] is added after the letter is snapped, unrounded, so it moves
     * every copy of every line by the same amount. Snapped together with the letter, a ring thinner
     * than a pixel reached the next pixel or not depending on where its line fell between two, and a
     * paragraph drew some lines bold and some thin. Left a fraction of a pixel off, the copy is
     * sampled smoothly and puts down that fraction of a pixel's ink: a ring a fifth of a pixel wide
     * is a faint edge, and a thinner ring a fainter one, on every line alike.
     */
    private fun drawSharp(sharp: Glyph, placed: PlacedGlyph, x: Float, y: Float, dx: Float, dy: Float, tint: Colour) {
        val on = checkNotNull(sharp.page)
        // Every time: a copy made just now is on the page but not yet uploaded.
        val texture = on.texture(device)
        val left: Float
        val top: Float
        val width: Float
        val height: Float
        val grow = state.transformScale
        if (sharp.fillsBox) {
            left = state.mapX(x + dx + placed.left)
            top = state.mapY(y + dy + placed.top)
            width = placed.width * grow
            height = placed.height * grow
        } else {
            val scaleX = viewport.scaleX
            val scaleY = viewport.scaleY
            val originX = layer?.bounds?.left ?: 0f
            val originY = layer?.bounds?.top ?: 0f
            val atX = state.mapX(x + placed.pen + sharp.xOffset / sharp.pixelsPerUnit)
            val atY = state.mapY(y + placed.baseline + sharp.yOffset / sharp.pixelsPerUnit)
            left = originX + floor((atX - originX) * scaleX + 0.5f) / scaleX + dx * grow
            top = originY + floor((atY - originY) * scaleY + 0.5f) / scaleY + dy * grow
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
     * The steps glyphs are made at now: the frame's own, or under a pushed transform the frame's scale
     * times the transform's text scale, snapped to a [TextZoom] step first. A zoom is snapped already,
     * so the size it asks for is taken exactly.
     */
    private fun textStep(): Int {
        val zoom = state.textScale
        if (zoom == 1f) return step
        return SharpGlyphs.stepOf(frameScale * TextZoom.snap(zoom), steady = true).coerceAtLeast(1)
    }

    /**
     * Whether glyphs at [step] come off the sharp atlas rather than their ordinary copies.
     *
     * Either way round. A window smaller than the design shrinks its glyphs as surely as a zoomed-out
     * plane does, and a glyph the GPU shrinks loses the strokes thinner than a pixel: a hyphen, the
     * arms of an E. A copy made at the screen's own pixels keeps them.
     */
    private fun remadeSharp(step: Int): Boolean = step != SharpGlyphs.One

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

        // A layer's own picture drawn as an image is held inside its corner as its composite is.
        if (texture is LayerPicture) holdPicture()
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
        batch().letGo()
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

        if (texture is LayerPicture) holdPicture()
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
        batch().letGo()
    }

    override val rotatesImages: Boolean get() = true

    /** Both, on every path a picture can reach the screen by: the batch and the effect. */
    override fun supports(mode: BlendMode): Boolean = true

    // --- clipping, opacity and blending ---

    override fun pushClip(rect: Rect) {
        state.pushClip(rect)
        clipDepth++
        lastClip = rect
        lastClipDepth = clipDepth
        applyScissor()
    }

    override fun popClip() {
        // A picture opened inside a rounded clip is put down as it ends, while its scissor stands.
        if (opened != null && rounding != null && clipDepth == roundedAt) closeOpened()
        state.popClip()
        // What was queued was queued inside the rounding, so it goes first, still trimmed.
        if (rounding != null && clipDepth == roundedAt) unround()
        lastClip = null
        clipDepth--
        applyScissor()
    }

    /**
     * In place, while nothing in force needs the picture road instead: no other rounded clip
     * (the shader keeps one), and nothing fading or blending, which a picture does as one piece and
     * drawing in place would do part by part.
     */
    override val roundsClips: Boolean
        get() = drawing && rounding == null && device.masks && state.alpha >= 1f && state.blend == BlendMode.SourceOver

    /**
     * The scissor the push set stays for the square edges, and a [ClipMask] the shape shader trims
     * every quad to is added for the rounded ones. Nothing, leaving the clip square, when
     * [roundsClips] says no or the clip just pushed is not the innermost one any more.
     */
    override fun roundClip(corners: Corners) {
        val rect = lastClip
        if (rect == null || lastClipDepth != clipDepth || !roundsClips) return
        while (roundings.size <= layerDepth) roundings += Rounding()
        val rounding = roundings[layerDepth]
        fill(rounding, rect, corners)
        rounding.area = state.clip
        batch().mask(rounding.mask)
        this.rounding = rounding
        roundedAt = clipDepth
    }

    private fun unround() {
        batch().mask(null)
        rounding = null
    }

    /**
     * [rounding] as [rect] lands, through the transform in force, with [corners] grown by it and
     * held to half the shorter side: in design units for [Rounding.keepsAll], and in the pixels of
     * whatever is being drawn into for the device.
     */
    private fun fill(rounding: Rounding, rect: Rect, corners: Corners) {
        val left = state.mapX(rect.left)
        val top = state.mapY(rect.top)
        val right = state.mapX(rect.right)
        val bottom = state.mapY(rect.bottom)
        val half = minOf(right - left, bottom - top) / 2f
        val grow = state.transformScale
        val topLeft = (corners.topLeft * grow).coerceAtMost(half)
        val topRight = (corners.topRight * grow).coerceAtMost(half)
        val bottomRight = (corners.bottomRight * grow).coerceAtMost(half)
        val bottomLeft = (corners.bottomLeft * grow).coerceAtMost(half)
        rounding.left = left
        rounding.top = top
        rounding.right = right
        rounding.bottom = bottom
        rounding.topLeft = topLeft
        rounding.topRight = topRight
        rounding.bottomRight = bottomRight
        rounding.bottomLeft = bottomLeft

        val mask = rounding.mask
        val middleX = (left + right) / 2f
        val middleY = (top + bottom) / 2f
        val into = layer
        // The same pixels the scissor counts in, as the device counts them: up from the bottom.
        // Only a target that keeps its top row first counts down, so its corners turn over.
        val down = into == null && topRowFirst
        mask.centreX = if (into != null) {
            (middleX - into.bounds.left) * viewport.scaleX
        } else {
            viewport.origin.x + middleX * viewport.scaleX
        }
        mask.centreY = when {
            into != null -> into.pixelHeight - (middleY - into.bounds.top) * viewport.scaleY
            down -> viewport.origin.y + middleY * viewport.scaleY
            else -> viewport.physical.height - viewport.origin.y - middleY * viewport.scaleY
        }
        mask.halfWidth = (right - left) / 2f
        mask.halfHeight = (bottom - top) / 2f
        mask.topLeft = if (down) bottomLeft else topLeft
        mask.topRight = if (down) bottomRight else topRight
        mask.bottomRight = if (down) topRight else bottomRight
        mask.bottomLeft = if (down) topLeft else bottomLeft
        mask.pixelsAcross = viewport.scaleX
        mask.pixelsUp = viewport.scaleY
    }

    /**
     * [draw], a shader effect, which does not go through the batch and so cannot be trimmed by the
     * mask, drawn into a picture and put down through the mask instead — so an effect inside a
     * rounded clip loses its corners like everything else.
     *
     * The picture is only where [draw] lands — [left], [top], [right], [bottom], already through
     * the transform — and the clip in force, so a small effect in a big card costs a small picture.
     * None at all when that lies clear of every rounded corner, where the mask would keep it whole,
     * or when there is no rounded clip in force or the picture is refused: then [draw] goes straight
     * to the target, inside the scissor as ever.
     *
     * Straight in, too, while a game's drawing has a picture opened (see [intoOpened]): that
     * picture is trimmed as a whole when the clip ends. The picture is put down at the opacity and
     * in the mode in force, as an effect is outside a rounded clip.
     */
    private inline fun throughMask(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        crossinline draw: () -> Unit,
    ) {
        val rounding = rounding
        val clip = state.clip
        val l = maxOf(left, clip.left)
        val t = maxOf(top, clip.top)
        val r = minOf(right, clip.right)
        val b = minOf(bottom, clip.bottom)
        if (rounding == null || opened != null || state.isHidden || r <= l || b <= t || rounding.keepsAll(l, t, r, b)) {
            draw()
            return
        }
        val area = Rect(l, t, r, b)
        val picture = capture(area) { draw() }
        if (picture == null) {
            draw()
            return
        }
        layerPicture(picture)
        composite(area, mirrorX = false, mirrorY = false)
    }

    /**
     * [draw], a game's own drawing, which does not go through the batch and so cannot be trimmed by
     * the mask, and can land anywhere: [raw]'s place only moves its origin.
     *
     * Inside a rounded clip the first one opens a picture of the whole clip, and everything drawn
     * from then until the clip is popped goes into it, the game's drawing straight in. The pop puts
     * it down once, through the mask. So a clip costs one picture however many games' drawings are
     * in it, as a cut picture did, and what is drawn after one still lands on top of it.
     *
     * Straight to the target when there is no rounded clip in force, when what is drawn is hidden,
     * or when the picture is refused.
     */
    final override var rawDrawings = 0
        private set

    private inline fun intoOpened(draw: () -> Unit) {
        if (rounding != null && opened == null && !state.isHidden) openPicture()
        draw()
    }

    /**
     * Opens a picture of the rounded clip in force and points drawing at it, the clip stack and
     * everything else in force kept as they are. Nothing, leaving drawing where it was, when the
     * picture would be too big or the device draws no pictures.
     */
    private fun openPicture() {
        val area = (rounding ?: return).area
        if (area.isEmpty || !device.limits.offscreen) return
        val pixelWidth = ceil(area.width * viewport.scaleX).toInt()
        val pixelHeight = ceil(area.height * viewport.scaleY).toInt()
        if (pixelWidth <= 0 || pixelHeight <= 0) return
        val most = minOf(LayerPool.MaxLayerPixels, device.limits.maxTextureSize)
        if (pixelWidth > most || pixelHeight > most) return

        // What was queued lands in place, trimmed, before the picture takes over.
        batch().flush(BatchBreak.Layer)
        val picture = layers.acquire(pixelWidth, pixelHeight)
        opened = Opened(picture, area, pixelWidth, pixelHeight, target, viewportBox.copyOf(), layer, projection.copyOf())
        // Drawn whole into the picture: the picture is trimmed as it is put down.
        batch().mask(null)
        layer = LayerFrame(area, pixelWidth, pixelHeight)
        target = picture
        setViewport(0, 0, pixelWidth, pixelHeight)
        // All of it, as [capture] clears.
        device.noScissor()
        device.clear(0f, 0f, 0f, 0f)
        orthographic(projection, area.width, area.height, area.left)
        batch().projection(projection)
        applyScissor()
    }

    /** Points drawing back where [openPicture] found it and puts the picture down through the mask. */
    private fun closeOpened() {
        val open = opened ?: return
        batch().flush(BatchBreak.Layer)
        opened = null
        layer = open.layer
        target = open.target
        open.projection.copyInto(projection)
        batch().projection(projection)
        setViewport(open.viewport[0], open.viewport[1], open.viewport[2], open.viewport[3])
        applyScissor()
        batch().mask(rounding?.mask)
        layerPicture(cornerOf(open.picture, open.pixelWidth, open.pixelHeight))
        // Plainly: where a clip is rounded in place nothing fades or blends, so the opacity and the
        // mode in force are full and ordinary, and a game's drawing knows nothing of either anyway.
        composite(open.area, mirrorX = false, mirrorY = false, tint = Colour.White)
        layers.release(open.picture)
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
        return capture(state.map(bounds), block)
    }

    /** [layer], of [area] as it already lands: through the transform in force. */
    private fun capture(area: Rect, block: () -> Unit): TextureHandle? {
        if (area.isEmpty || !device.limits.offscreen) return null
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
        val previousRounding = rounding
        val previousOpened = opened
        val previousRoundedAt = roundedAt
        val previousClipDepth = clipDepth
        val previousLastClip = lastClip
        val previousLastClipDepth = lastClipDepth

        batch().flush(BatchBreak.Layer)
        layer = LayerFrame(area, pixelWidth, pixelHeight)
        // Full opacity and a clip of exactly the layer; the tint and the transform come in with it.
        state = previousState.forLayer(area)
        // No rounded clip either: the picture is trimmed as it is put down, if it is put down
        // inside one, and what is drawn into it is drawn whole.
        layerDepth++
        clipDepth = 0
        lastClip = null
        opened = null
        if (previousRounding != null) unround()

        target = picture
        setViewport(0, 0, pixelWidth, pixelHeight)
        // All of the picture, not only the corner drawn into: what lies round the corner must read
        // clear when a turned or stretched picture is filtered at its edge, and a scissored clear is
        // not a clear of the attachment, so a tiled phone GPU would load the rest from memory.
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
            layerDepth--
            clipDepth = previousClipDepth
            lastClip = previousLastClip
            lastClipDepth = previousLastClipDepth
            roundedAt = previousRoundedAt
            rounding = previousRounding
            opened = previousOpened
            // Drawing into an opened picture is not trimmed: the picture is, when it is put down.
            batch().mask(if (previousOpened == null) previousRounding?.mask else null)
            applyBlend()
            target = previousTarget
            setViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
            // Worked out again rather than switched back on: the layer set a scissor of its own.
            applyScissor()
            layers.release(picture)
        }

        return cornerOf(picture, pixelWidth, pixelHeight)
    }

    /**
     * The bottom-left [width] by [height] pixels of [picture], where a layer was drawn: the pool
     * hands out a picture at least the size asked for, often a little bigger.
     *
     * A framebuffer's first row is its bottom one, so v and v2 are swapped here, once.
     */
    private fun cornerOf(picture: DeviceTarget, width: Int, height: Int) = LayerPicture(
        picture,
        width,
        height,
        u = 0f,
        v = height.toFloat() / picture.height,
        u2 = width.toFloat() / picture.width,
        v2 = 0f,
    )

    private fun layerPicture(layer: TextureHandle) {
        if (layer is NineRegions || !resolve(layer)) error("this canvas can only draw layers it made, not ${layer::class}")
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, effect: ShaderEffect?) {
        if (state.isHidden || destination.isEmpty) return
        layerPicture(layer)
        val box = state.map(destination)
        if (effect != null) {
            // The shader is somebody else's, so a rounded clip cannot trim it as it draws. The
            // picture it reads is held meanwhile, so the one it draws into is never the same one.
            val source = (layer as? LayerPicture)?.target
            source?.let(layers::hold)
            throughMask(box.left, box.top, box.right, box.bottom) {
                layerPicture(layer)
                drawThrough(effect, box)
            }
            source?.let(layers::release)
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

    /**
     * Holds the batch's reads of the resolved [picture] inside it, half a texel in, until the
     * composite that follows lets go: a layer is often the corner of a bigger pooled picture, and a
     * turned or stretched one would otherwise read the clear strip round the corner at its edge.
     */
    private fun holdPicture() {
        val picture = picture
        val texture = picture.texture
        val halfAcross = 0.5f / texture.width
        val halfUp = 0.5f / texture.height
        batch().holdInside(
            minOf(picture.u, picture.u2) + halfAcross,
            minOf(picture.v, picture.v2) + halfUp,
            maxOf(picture.u, picture.u2) - halfAcross,
            maxOf(picture.v, picture.v2) - halfUp,
        )
    }

    private fun composite(destination: Rect, mirrorX: Boolean, mirrorY: Boolean, tint: Colour = fade()) {
        // The mode in force applies to the composite; premultiplied, because a layer's drawing is.
        batch().blend(state.blend, premultiplied = true, reason = BatchBreak.Layer)
        holdPicture()
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
            tint = tint,
        )
        batch().letGo()
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
        holdPicture()
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
        batch().letGo()
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
        holdPicture()
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
        batch().letGo()
        batch().blend(state.blend, premultiplied = false, reason = BatchBreak.Layer)
    }

    override val cutsLayers: Boolean get() = offscreen

    override fun drawLayerOnto(layer: TextureHandle, destination: Rect, corners: FloatArray) {
        require(corners.size == 8) { "four corners are eight numbers, not ${corners.size}" }
        if (state.isHidden || destination.isEmpty) return
        layerPicture(layer)

        val flipped = FloatArray(8) { if (it % 2 == 0) state.mapX(corners[it]) else flip(state.mapY(corners[it])) }

        batch().blend(state.blend, premultiplied = true, reason = BatchBreak.Layer)
        holdPicture()
        batch().textured(
            texture = picture.texture,
            corners = flipped,
            u = picture.u,
            v = picture.v,
            u2 = picture.u2,
            v2 = picture.v2,
            tint = fade(),
        )
        batch().letGo()
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
        holdPicture()
        batch().projected(
            texture = picture.texture,
            corners = corners,
            u = picture.u,
            v = picture.v,
            u2 = picture.u2,
            v2 = picture.v2,
            tint = fade(),
        )
        batch().letGo()
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

    /**
     * The bottom-left corner, because the projection measures y upwards.
     *
     * [destination] only moves the origin: the block can draw past it, so inside a rounded clip it
     * goes into the picture of the whole clip, as plain [raw] does.
     */
    override fun raw(destination: Rect, block: (Any) -> Unit) = intoOpened {
        rawDrawings++
        batch().flush(BatchBreak.Raw)
        val moved = transformed(projection.copyOf()).also {
            it[12] += rawX(destination.left) * it[0]
            it[13] += rawY(destination.bottom) * it[5]
        }
        runRaw(block, moved)
    }

    override fun raw(block: (Any) -> Unit) = intoOpened {
        rawDrawings++
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
     * engine's state handed back but the picture still bound, rather than the engine's framebuffer:
     * it is the engine's frame object, pointed at the picture.
     *
     * A picture made by another canvas, or closed, is not reused; the caller gives it back.
     */
    override fun scene(surface: SceneSurface?, width: Int, height: Int, draw: (SceneTarget) -> Unit): SceneSurface? {
        check(!drawing) {
            "scene() inside a frame: scenes are rendered before the frame begins, not in the middle of it. " +
                "A WorldPanel with a SceneView in it opens its frame itself: panel.draw(canvas) { tree -> target.draw(canvas) { tree() } }"
        }
        if (width <= 0 || height <= 0) return surface
        if (!device.limits.offscreen) return null
        val mine = (surface as? ScenePicture)?.takeIf { !it.closed && it.device === device }
        val picture = mine?.also { it.target.resize(width, height) }
            ?: ScenePicture(RenderTarget(device, width, height, depth = true), device)

        renderScene(checkNotNull(picture.target.target), draw)
        return picture
    }

    /**
     * The shared half of [scene], into a target the frontend supplies: the device taken for [into]
     * alone, the viewport over all of it, the scissor off, [draw] run, and the engine's state handed
     * back the way the device was told to.
     *
     * For a frontend whose engine has to own the framebuffer so that its own drawing lands in it —
     * KorGE, whose batcher draws into the framebuffer on its render context's stack and nowhere
     * else. The frontend makes that framebuffer, with depth, adopts it as a [DeviceTarget], and
     * calls this; everything drawn is still this canvas's. Every other frontend lets [scene] use the
     * device's own picture.
     *
     * [SceneTarget.raw] inside it hands over what [handOver] makes, through [lend], with the engine's
     * state handed back round it as round a frame's `raw` — but the target stays bound while the game
     * draws: see [GpuDevice.suspendInScene].
     */
    protected fun renderScene(into: DeviceTarget, draw: (SceneTarget) -> Unit) {
        check(!drawing) {
            "scene() inside a frame: scenes are rendered before the frame begins, not in the middle of it. " +
                "A WorldPanel with a SceneView in it opens its frame itself: panel.draw(canvas) { tree -> target.draw(canvas) { tree() } }"
        }
        val binding = sceneBinding
        device.begin(into)
        try {
            device.target(into, 0, 0, into.width, into.height)
            device.noScissor()
            orthographic(binding.projection, into.width.toFloat(), into.height.toFloat())
            binding.into = into
            draw(binding)
        } finally {
            binding.into = null
            device.end()
        }
    }

    private val sceneBinding = SceneBinding()

    /** What a scene's block is handed. One per canvas, pointed at the target being rendered. */
    private inner class SceneBinding : SceneTarget {
        var into: DeviceTarget? = null
        val projection = FloatArray(16)

        private fun bound(): DeviceTarget = checkNotNull(into) { "a scene target is only good inside scene()" }

        override val width: Int get() = bound().width

        override val height: Int get() = bound().height

        override fun clear(colour: Colour) {
            val into = bound()
            // The game may have moved the viewport or switched a scissor on since the last one.
            device.target(into, 0, 0, into.width, into.height)
            device.noScissor()
            val alpha = colour.alphaFraction
            device.clear(colour.red / 255f * alpha, colour.green / 255f * alpha, colour.blue / 255f * alpha, alpha)
        }

        override fun raw(block: (Any) -> Unit) {
            val into = bound()
            val handed = projection.copyOf()
            val viewport = Viewport.oneToOne(Size(into.width.toFloat(), into.height.toFloat()))
            device.suspendInScene()
            try {
                lend(handOver(handed, viewport), handed, block)
            } finally {
                device.resumeInScene()
            }
        }
    }

    /**
     * The game has bound a framebuffer of its own around this canvas's frames, or stopped: a
     * whole-screen colour grade, a capture for a transition. The next frame asks the driver which
     * framebuffer [FrameTarget.Host] is, rather than drawing into the one it remembers. Call it
     * after binding one and again after letting it go; a frame you can name a target for instead
     * needs neither — begin it with that target.
     */
    fun hostTargetChanged() = device.hostTargetChanged()

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
        val atlas = if (fonts != null && remadeSharp(textStep())) fonts.sharp?.atlas ?: fonts else fonts
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

        /** Degrees to radians, spelled out once. */
        const val PiOver180 = 0.017453292f

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
