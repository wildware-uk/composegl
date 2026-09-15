package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.CanvasState
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.NineRegions
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.graphics.featherOutline
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextLayout
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * What [UiCanvas.raw] hands a game on this backend.
 *
 * There is no drawing object to pass — the drawing object is OpenGL, and it is global. What a
 * game's own render code actually needs is where to draw: the frame's projection, already in the
 * interface's coordinates, and the viewport it was set up with.
 *
 * The interface's own quads have been flushed by the time this arrives, so whatever the game draws
 * lands on top of them. Put the GL state back as you found it.
 */
class GlFrame(val projection: FloatArray, val viewport: Viewport)

/**
 * The raw OpenGL canvas.
 *
 * The other half of the pair that keeps this toolkit honest. It shares no code with the LibGDX
 * backend — not the batch, not the shader source, not the font handling — and draws the same
 * scenes. Anything the toolkit accidentally assumes about LibGDX shows up here as a picture that
 * came out wrong.
 *
 * Two conventions meet here and are reconciled once:
 *
 * - the toolkit measures y downwards from the top; OpenGL measures it upwards from the bottom;
 * - the toolkit works in the design resolution; the window is whatever size it is.
 *
 * The scale and the letterboxing go into the GL viewport rather than into arithmetic on every
 * call, so a game's own drawing through [raw] gets a projection already in design coordinates.
 *
 * @param fonts where text is measured, and where solid colour is sampled from. Sharing the glyph
 *   atlas is what makes a screen of panels and labels one draw call. Without it the canvas keeps
 *   a one-pixel white texture of its own and cannot draw text at all.
 */
class GlCanvas(private val fonts: StbFonts? = null) : UiCanvas, AutoCloseable {

    /**
     * The quads, and the shader they go through. Made the first time something is drawn.
     *
     * Late rather than in the field, so that merely *having* a canvas needs no OpenGL. A game
     * object that owns a canvas alongside its host, its renderer and its focus — which is most of
     * them — can then be built in a plain JVM test, and its input, focus and lifecycle asserted
     * without a window anywhere. See [warmUp] for paying the cost on purpose.
     */
    private var batch: GlShapeBatch? = null

    private fun batch() = batch ?: GlShapeBatch().also { batch = it }

    private var state = CanvasState(Rect.Zero)
    private var viewport: Viewport = Viewport.oneToOne(Size(1f, 1f))
    private var drawing = false

    /**
     * How wide a softened edge is, in design units.
     *
     * One screen pixel, whatever the screen is doing. A corner that fades over one design unit is
     * a blurry three-pixel smear at 3x and a hard aliased step at a third.
     */
    private var antialias = 1f

    private val projection = FloatArray(16)

    private var ownWhite: GlTexture? = null

    private val layers = GlLayers()

    private var effects: GlEffects? = null

    private fun effects() = effects ?: GlEffects().also { effects = it }

    /**
     * The offscreen picture being drawn into, or null when that is the window.
     *
     * Everything that turns a design coordinate into a pixel — the y flip, the scissor — asks this
     * first, so the same drawing code lands in the right place either way and no widget ever finds
     * out which it was.
     */
    private var layer: LayerFrame? = null

    private class LayerFrame(val bounds: Rect, val pixelWidth: Int, val pixelHeight: Int)

    /**
     * Which framebuffer the canvas believes is bound, where the GL viewport is, and whether the
     * scissor is on.
     *
     * Remembered rather than asked for. Asking costs a pipeline stall — the driver has to catch up
     * with itself before it can answer — and a layer would ask three times, twice a layer, every
     * frame.
     */
    private var framebuffer = 0
    private val viewportBox = IntArray(4)
    private var scissorOn = false

    /** How many times the frame so far has talked to the driver. Nothing drawn yet is none. */
    override val drawCalls: Int get() = batch?.renderCalls ?: 0

    /**
     * Builds the GPU resources now, instead of when something is first drawn.
     *
     * A buffer, a compiled shader and the texture solid colour is sampled from cost a few
     * milliseconds, and without this they are paid for in the first frame the player sees — which
     * is exactly the frame a stutter is noticed in. Call it on a loading screen, on the thread
     * that holds the context. Calling it twice does nothing the second time, and never calling it
     * is fine.
     */
    override fun warmUp() {
        batch()
        effects()
        white()
    }

    /**
     * Whether the GPU resources exist yet.
     *
     * For the tests, which otherwise have no way to tell a canvas that built its buffer from one
     * that did not: [drawCalls] is zero either way, so a test asserting on it alone would pass
     * with [warmUp] gutted to an empty body — and both halves of this class's contract are about
     * *when* the building happens.
     */
    internal val warmedUp: Boolean get() = batch != null && effects != null

    /**
     * Sets up for a frame in [viewport]'s design coordinates.
     *
     * @param framebuffer what is bound right now, when it is not the screen — a render target's
     *   name, for an interface being drawn onto a surface in a 3D world. A layer binds its own and
     *   has to know what to put back; nothing else here cares.
     */
    override fun begin(viewport: Viewport) = begin(viewport, 0)

    fun begin(viewport: Viewport, framebuffer: Int) {
        check(!drawing) { "begin() was called twice without an end()" }
        drawing = true
        this.framebuffer = framebuffer
        scissor(false)
        this.viewport = viewport
        state = CanvasState(Rect.of(0f, 0f, viewport.design.width, viewport.design.height))
        antialias = 1f / minOf(viewport.scaleX, viewport.scaleY).coerceAtLeast(0.0001f)

        // The letterbox and the scale live here, so nothing below has to think about them.
        setViewport(
            viewport.origin.x.roundToInt(),
            // GL counts up from the bottom of the window; the viewport counts down from the top.
            (viewport.physical.height - viewport.origin.y - viewport.design.height * viewport.scaleY).roundToInt(),
            (viewport.design.width * viewport.scaleX).roundToInt(),
            (viewport.design.height * viewport.scaleY).roundToInt(),
        )
        orthographic(projection, viewport.design.width, viewport.design.height)
        batch().begin(projection)
    }

    /** Ends the frame and puts the GL state back the way a game expects to find it. */
    override fun end() {
        check(drawing) { "end() without a begin()" }

        batch().end()
        scissor(false)
        setViewport(0, 0, viewport.physical.width.roundToInt(), viewport.physical.height.roundToInt())
        drawing = false
        layers.trim()

        // Complained about last, so that an unbalanced frame still leaves things tidy for whatever
        // the game draws next.
        check(state.isBalanced) { "a clip, an alpha or a blend was pushed and never popped" }
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

    // Four radii are four more numbers on the same vertex, so a tab and a plain panel beside it
    // still batch together: the shader picks a corner's radius by which quarter a pixel is in.

    override fun rect(rect: Rect, colour: Colour, corners: Corners) {
        if (state.isHidden || rect.isEmpty) return
        shape(rect, fill = colour, corners = corners)
    }

    override fun border(rect: Rect, colour: Colour, width: Float, corners: Corners) {
        if (state.isHidden || rect.isEmpty || width <= 0f) return
        shape(rect, corners = corners, border = colour, borderWidth = width)
    }

    override fun shadow(rect: Rect, colour: Colour, spread: Float, corners: Corners) {
        if (state.isHidden || spread <= 0f) return
        shape(rect, corners = corners, shadow = colour, shadowSpread = spread)
    }

    /** Each corner by its own radius, in the same shader as everything else. */
    override val roundsCornersSeparately: Boolean get() = true

    override fun fan(points: FloatArray, colour: Colour) {
        if (state.isHidden || points.size < 6) return
        // Flipped here, like every other call: the toolkit counts y downwards and the batch up.
        val flipped = FloatArray(points.size)
        var at = 0
        while (at < points.size) {
            flipped[at] = points[at]
            flipped[at + 1] = flip(points[at + 1])
            at += 2
        }
        batch().fan(white(), flipped, colour.scaleAlpha(state.alpha))
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

    private fun shape(
        rect: Rect,
        fill: Colour = Colour.Transparent,
        corners: Corners,
        border: Colour = Colour.Transparent,
        borderWidth: Float = 0f,
        shadow: Colour = Colour.Transparent,
        shadowSpread: Float = 0f,
    ) = shape(
        rect, fill,
        corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft,
        border, borderWidth, shadow, shadowSpread,
    )

    /**
     * The one place a box reaches the batch. Four floats rather than a [Corners], so that the
     * single-radius calls — which are most of a frame — make no object on their way through.
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
            white = white(),
            left = rect.left,
            bottom = flip(rect.bottom),
            width = rect.width,
            height = rect.height,
            fill = fill.scaleAlpha(state.alpha),
            // Top is still top: the flip moves the box, and the batch's own up is the screen's up.
            topLeft = topLeft,
            topRight = topRight,
            bottomRight = bottomRight,
            bottomLeft = bottomLeft,
            border = border.scaleAlpha(state.alpha),
            borderWidth = borderWidth,
            shadow = shadow.scaleAlpha(state.alpha),
            shadowSpread = shadowSpread,
            aa = antialias,
        )
    }

    // --- text and pictures ---

    override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) {
        if (state.isHidden) return
        val measured = layout as? StbTextLayout
            ?: error("this canvas can only draw text measured by StbFonts, not ${layout::class}")
        val atlas = checkNotNull(fonts) { "this canvas was made without fonts, so it cannot draw text" }.texture()

        val tint = colour.scaleAlpha(state.alpha)

        // Indexed rather than `forEach`: that asks the list for an iterator, and an outlined run
        // comes through here nine times, so a HUD of labels would make an object per copy per run
        // per frame for nothing.
        val placedGlyphs = measured.placed
        for (index in placedGlyphs.indices) {
            val placed = placedGlyphs[index]
            batch().textured(
                name = atlas.name,
                left = x + placed.left,
                bottom = flip(y + placed.top + placed.height),
                width = placed.width,
                height = placed.height,
                u = placed.glyph.u,
                v = placed.glyph.v,
                u2 = placed.glyph.u2,
                v2 = placed.glyph.v2,
                tint = tint,
            )
        }
    }

    override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) {
        if (state.isHidden || destination.isEmpty) return
        val picture = texture as? GlTexture ?: notOnePicture(texture)
        slice.of(picture, source)

        batch().textured(
            name = picture.name,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            u = slice.left,
            v = slice.top,
            u2 = slice.right,
            v2 = slice.bottom,
            tint = tint.scaleAlpha(state.alpha),
        )
    }

    /**
     * The corner of the texture a picture is cut from, worked out once and read straight after.
     *
     * One of these per canvas rather than one per call. [image] runs once per sprite per frame and
     * a sunburst runs it a dozen times in a row, so a fresh object each time would be rubbish for
     * the collector to sweep for nothing. Nothing here outlives the call that fills it.
     *
     * Shared by the upright call and the turned one, which want exactly the same four numbers —
     * two copies of this arithmetic would be two things to keep in step for no gain.
     */
    private val slice = Slice()

    private class Slice {

        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f

        /**
         * [source]'s corner of [picture], or the whole of it when there is no sub-rectangle.
         *
         * Texture coordinates here count y downwards, like the toolkit, so `v` is the top edge and
         * it goes straight across to the quad's top with no swap anywhere.
         */
        fun of(picture: GlTexture, source: Rect?) {
            if (source == null) {
                left = picture.u
                top = picture.v
                right = picture.u2
                bottom = picture.v2
                return
            }
            val across = (picture.u2 - picture.u) / picture.width
            val down = (picture.v2 - picture.v) / picture.height
            left = picture.u + source.left * across
            top = picture.v + source.top * down
            right = picture.u + source.right * across
            bottom = picture.v + source.bottom * down
        }
    }

    /**
     * The same picture, turned — four corners on the processor and the same quad in the same batch.
     *
     * No new GL state and no flush, so a sunburst of rays batches with itself and with anything
     * else drawn from the same texture. It does *not* join a panel behind it unless that panel's
     * colour comes from the same texture, which it does only when the art is packed into the glyph
     * atlas — the batch flushes on a texture change, and that rule has not moved.
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
        val picture = texture as? GlTexture ?: notOnePicture(texture)
        slice.of(picture, source)

        batch().textured(
            name = picture.name,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            pivotX = destination.left + destination.width * pivotX,
            // The pivot is a fraction from the top, and this is the one place it meets a y that
            // counts upwards.
            pivotY = flip(destination.top + destination.height * pivotY),
            degrees = degrees,
            u = slice.left,
            v = slice.top,
            u2 = slice.right,
            v2 = slice.bottom,
            tint = tint.scaleAlpha(state.alpha),
        )
    }

    /** It really turns one, and turning costs no draw call. */
    override val rotatesImages: Boolean get() = true

    /**
     * Both of them, on every path a picture can reach the screen by: the batch sets the blend
     * function, and so does the shader that a [drawLayer] with an effect on it goes through.
     */
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

    /**
     * The batch's blending follows the toolkit's blend stack, which has already decided that the
     * innermost mode wins — so the backend cannot get nesting wrong, because it never works it out.
     * The batch flushes first: whatever is queued was queued to blend the old way.
     *
     * `batch?` rather than `batch()`: outside a frame there is nothing queued and no GL state worth
     * setting, and building a mesh and a shader because somebody pushed a mode would undo the point
     * of building them late. Inside a frame [begin] has always made one already.
     */
    private fun applyBlend() {
        batch?.blend(state.blend, premultiplied = false)
    }

    /**
     * The scissor follows the toolkit's clip, which has already intersected the nested clips — so
     * the backend cannot get nesting wrong, because it never works it out. The batch is flushed
     * first: whatever is queued was queued under the old clip.
     */
    private fun applyScissor() {
        batch().flush()
        val clip = state.clip
        val into = layer
        if (into != null) {
            scissorLayer(into, clip)
            return
        }
        if (clip.left <= 0f && clip.top <= 0f &&
            clip.right >= viewport.design.width && clip.bottom >= viewport.design.height
        ) {
            scissor(false)
            return
        }

        val topLeft = viewport.toScreen(Offset(clip.left, clip.top))
        val bottomRight = viewport.toScreen(Offset(clip.right, clip.bottom))
        scissor(true)
        GL11.glScissor(
            topLeft.x.roundToInt(),
            (viewport.physical.height - bottomRight.y).roundToInt(),
            (bottomRight.x - topLeft.x).roundToInt().coerceAtLeast(0),
            (bottomRight.y - topLeft.y).roundToInt().coerceAtLeast(0),
        )
    }

    /**
     * The same, in a layer's pixels: its own origin, its own height, and no letterbox — a layer is
     * exactly the picture and nothing around it.
     */
    private fun scissorLayer(into: LayerFrame, clip: Rect) {
        if (clip.left <= into.bounds.left && clip.top <= into.bounds.top &&
            clip.right >= into.bounds.right && clip.bottom >= into.bounds.bottom
        ) {
            scissor(false)
            return
        }

        val left = ((clip.left - into.bounds.left) * viewport.scaleX).roundToInt()
        val right = ((clip.right - into.bounds.left) * viewport.scaleX).roundToInt()
        val top = ((clip.top - into.bounds.top) * viewport.scaleY).roundToInt()
        val bottom = ((clip.bottom - into.bounds.top) * viewport.scaleY).roundToInt()
        scissor(true)
        GL11.glScissor(
            left,
            into.pixelHeight - bottom,
            (right - left).coerceAtLeast(0),
            (bottom - top).coerceAtLeast(0),
        )
    }

    // --- layers ---

    /** Framebuffers, so yes. */
    override val drawsLayers: Boolean get() = true

    override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? {
        check(drawing) { "layer() outside a frame" }
        if (bounds.isEmpty) return null

        // Screen resolution, not design resolution: a layer that is blurred and drawn back should
        // be as sharp as everything around it. Rounded up, so nothing falls off the right or the
        // bottom edge of a picture whose size is not a whole number of pixels.
        val pixelWidth = ceil(bounds.width * viewport.scaleX).toInt()
        val pixelHeight = ceil(bounds.height * viewport.scaleY).toInt()
        if (pixelWidth <= 0 || pixelHeight <= 0) return null
        if (pixelWidth > MaxLayerPixels || pixelHeight > MaxLayerPixels) return null

        val target = layers.acquire(pixelWidth, pixelHeight)

        val previousFramebuffer = framebuffer
        val previousViewport = viewportBox.copyOf()
        val previousScissor = scissorOn
        val previousState = state
        val previousLayer = layer
        val previousProjection = projection.copyOf()

        batch().flush()
        layer = LayerFrame(bounds, pixelWidth, pixelHeight)
        // Full opacity and a clip of exactly the layer. The opacity out here is applied when the
        // picture is drawn back, which is what makes a group fade as one object.
        state = CanvasState(bounds)

        bindFramebuffer(target.framebufferName)
        setViewport(0, 0, pixelWidth, pixelHeight)
        scissor(false)
        GL11.glClearColor(0f, 0f, 0f, 0f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        orthographic(projection, bounds.width, bounds.height, bounds.left)
        batch().projection(projection)
        // A layer's picture starts as transparent black, so adding into it and then compositing
        // that result is not the same as adding onto the screen. The block gets plain blending,
        // the same reset the clip and the opacity get, and the mode out here comes back below.
        applyBlend()

        try {
            block()
            batch().flush()
            check(state.isBalanced) { "a clip, an alpha or a blend was pushed inside a layer and never popped" }
        } finally {
            layer = previousLayer
            state = previousState
            previousProjection.copyInto(projection)
            batch().projection(projection)
            applyBlend()
            bindFramebuffer(previousFramebuffer)
            setViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
            scissor(previousScissor)
            layers.release(target)
        }

        // A framebuffer's first row is its bottom one, so the picture is handed back with its
        // vertical texture coordinates swapped and everything downstream can ignore that entirely.
        return GlTexture(target.textureName, pixelWidth, pixelHeight, u = 0f, v = 1f, u2 = 1f, v2 = 0f)
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, effect: ShaderEffect?) {
        if (state.isHidden || destination.isEmpty) return
        val picture = layer as? GlTexture
            ?: error("this canvas can only draw layers it made, not ${layer::class}")

        if (effect != null) {
            drawThrough(effect, picture, destination)
            return
        }
        composite(picture, destination, mirrorX = false, mirrorY = false)
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, mirrorX: Boolean, mirrorY: Boolean) {
        if (state.isHidden || destination.isEmpty) return
        val picture = layer as? GlTexture
            ?: error("this canvas can only draw layers it made, not ${layer::class}")
        composite(picture, destination, mirrorX, mirrorY)
    }

    /** It really mirrors one: the texture coordinates are swapped on the same quad. */
    override val mirrorsLayers: Boolean get() = true

    /** A layer put down upright, the plain way or with either axis of its picture swapped. */
    private fun composite(picture: GlTexture, destination: Rect, mirrorX: Boolean, mirrorY: Boolean) {
        // The mode in force applies to the composite, so pushing Additive round a drawLayer makes
        // a whole group glow. Premultiplied because that is what the layer's own drawing produced.
        batch().blend(state.blend, premultiplied = true)
        // The opacity goes into all four channels, because a premultiplied colour that faded only
        // its alpha would get brighter as it disappeared.
        val fade = state.alpha.coerceIn(0f, 1f)
        val grey = (fade * 255f).roundToInt().coerceIn(0, 255)
        // A mirror is the same quad reading its picture from the other side, so it costs nothing a
        // plain composite does not and batches with it.
        batch().textured(
            name = picture.name,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            u = if (mirrorX) picture.u2 else picture.u,
            v = if (mirrorY) picture.v2 else picture.v,
            u2 = if (mirrorX) picture.u else picture.u2,
            v2 = if (mirrorY) picture.v else picture.v2,
            tint = Colour((grey shl 24) or (grey shl 16) or (grey shl 8) or grey),
        )
        batch().blend(state.blend, premultiplied = false)
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
        val picture = layer as? GlTexture
            ?: error("this canvas can only draw layers it made, not ${layer::class}")

        // Premultiplied and faded in all four channels for the same reasons the upright composite
        // is; the turn changes where the quad's corners go and nothing else.
        batch().blend(state.blend, premultiplied = true)
        val fade = state.alpha.coerceIn(0f, 1f)
        val grey = (fade * 255f).roundToInt().coerceIn(0, 255)
        batch().textured(
            name = picture.name,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            pivotX = destination.left + destination.width * pivotX,
            // The pivot is a fraction from the top, and this is the one place it meets a y that
            // counts upwards.
            pivotY = flip(destination.top + destination.height * pivotY),
            degrees = degrees,
            u = picture.u,
            v = picture.v,
            u2 = picture.u2,
            v2 = picture.v2,
            tint = Colour((grey shl 24) or (grey shl 16) or (grey shl 8) or grey),
        )
        batch().blend(state.blend, premultiplied = false)
    }

    /** It really turns one, on the same quad the upright composite uses. */
    override val turnsLayers: Boolean get() = true

    /**
     * The picture as a fan through [outline], with a ring one screen pixel wide round it that
     * fades to nothing — see [featherOutline]. Same program and same batch as every other picture.
     *
     * The texture coordinates are held inside the picture: the soft ring reaches half a pixel past
     * the outline, and a framebuffer texture left on the driver's default wrap would repeat the
     * opposite edge into it.
     */
    override fun cutLayer(layer: TextureHandle, destination: Rect, outline: FloatArray) {
        if (state.isHidden || destination.isEmpty || outline.size < 6) return
        val picture = layer as? GlTexture
            ?: error("this canvas can only draw layers it made, not ${layer::class}")

        // Premultiplied and faded in all four channels, for the reasons drawLayer gives.
        batch().blend(state.blend, premultiplied = true)
        val fade = state.alpha.coerceIn(0f, 1f)
        val grey = (fade * 255f).roundToInt().coerceIn(0, 255)
        val solid = Colour((grey shl 24) or (grey shl 16) or (grey shl 8) or grey)
        val clear = Colour.Transparent
        val left = destination.left
        val top = destination.top
        val width = destination.width
        val height = destination.height
        val uSpan = picture.u2 - picture.u
        val vSpan = picture.v2 - picture.v

        featherOutline(outline, antialias) { ax, ay, aCover, bx, by, bCover, cx, cy, cCover, dx, dy, dCover ->
            batch().corners(
                picture.name,
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
        batch().blend(state.blend, premultiplied = false)
    }

    /** It really cuts one, with a soft edge, in the same batch as everything else. */
    override val cutsLayers: Boolean get() = true

    override fun drawLayerOnto(layer: TextureHandle, destination: Rect, corners: FloatArray) {
        require(corners.size == 8) { "four corners are eight numbers, not ${corners.size}" }
        if (state.isHidden || destination.isEmpty) return
        val picture = layer as? GlTexture
            ?: error("this canvas can only draw layers it made, not ${layer::class}")

        // The corners arrive counting y down and the batch counts it up; x is left alone.
        val flipped = FloatArray(8) { if (it % 2 == 0) corners[it] else flip(corners[it]) }

        // Premultiplied and faded in all four channels for the same reasons the upright composite
        // is; four corners change where the quad goes and nothing else.
        batch().blend(state.blend, premultiplied = true)
        val fade = state.alpha.coerceIn(0f, 1f)
        val grey = (fade * 255f).roundToInt().coerceIn(0, 255)
        batch().textured(
            name = picture.name,
            corners = flipped,
            u = picture.u,
            v = picture.v,
            u2 = picture.u2,
            v2 = picture.v2,
            tint = Colour((grey shl 24) or (grey shl 16) or (grey shl 8) or grey),
        )
        batch().blend(state.blend, premultiplied = false)
    }

    /** It really does, on the same quad the upright composite uses. */
    override val drawsLayersOnto: Boolean get() = true

    private fun bindFramebuffer(name: Int) {
        framebuffer = name
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, name)
    }

    private fun setViewport(x: Int, y: Int, width: Int, height: Int) {
        viewportBox[0] = x
        viewportBox[1] = y
        viewportBox[2] = width
        viewportBox[3] = height
        GL11.glViewport(x, y, width, height)
    }

    private fun scissor(on: Boolean) {
        scissorOn = on
        if (on) GL11.glEnable(GL11.GL_SCISSOR_TEST) else GL11.glDisable(GL11.GL_SCISSOR_TEST)
    }

    /**
     * The same picture, through somebody's shader.
     *
     * The quad is worked out here, in clip space, because this is the class that knows where a
     * design coordinate ends up: the projection, the y flip and the layer's own origin all live
     * here and none of them are the shader's business.
     */
    private fun drawThrough(effect: ShaderEffect, picture: GlTexture, destination: Rect) {
        // Whatever is queued was queued to land under this, so it goes first.
        batch().flush()

        val bottom = flip(destination.bottom)
        val top = flip(destination.top)
        effects().draw(
            effect = effect,
            texture = picture.name,
            left = clipX(destination.left),
            top = clipY(top),
            right = clipX(destination.right),
            bottom = clipY(bottom),
            u = picture.u,
            v = picture.v,
            u2 = picture.u2,
            v2 = picture.v2,
            textureWidth = picture.width.toFloat(),
            textureHeight = picture.height.toFloat(),
            designWidth = destination.width,
            designHeight = destination.height,
            alpha = state.alpha.coerceIn(0f, 1f),
            // The mode in force applies to the composite whether or not there is a shader in the
            // way, so a blurred group inside a pushBlend glows like an unblurred one.
            mode = state.blend,
        )

        // The effect set the blending and the program it wanted, behind the batch's back. Put back
        // whatever the canvas's blend stack says, unconditionally — a batch that remembered what it
        // had last set would believe this was already true and skip it.
        GL20.glUseProgram(0)
        batch().blend(state.blend, premultiplied = false)
    }

    /** A design x, through the frame's projection, as the clip cube sees it. */
    private fun clipX(x: Float) = x * projection[0] + projection[12]

    /** The same for a y that has already been flipped the right way up. */
    private fun clipY(y: Float) = y * projection[5] + projection[13]

    /**
     * Through [flip], the same conversion every other call in this class already makes.
     *
     * The projection handed over in a [GlFrame] measures y upwards from the bottom — of the layer
     * when there is one, of the design space when there is not — because that is what OpenGL wants
     * and the whole class reconciles the two conventions here. Leaving this as the interface's
     * identity default said the opposite, and a block that believed it drew its art flipped about
     * the middle of the frame.
     */
    override fun rawY(y: Float): Float = flip(y)

    /**
     * Unchanged, which is the interface's default and is said here anyway.
     *
     * A layer's own left edge goes into the projection — `orthographic(…, left)` — not into the
     * coordinates, so a design x is already the x a [GlFrame]'s projection wants.
     */
    override fun rawX(x: Float): Float = x

    /** OpenGL is always there to hand over, so there is nothing that can be missing. */
    override val handsOverRaw: Boolean get() = true

    /** It really moves it: the projection in the [GlFrame] is translated before it is handed over. */
    override val movesRawOrigin: Boolean get() = true

    /**
     * The bottom-left corner, because this backend's projection measures y upwards: a block drawing
     * `0, 0, w, h` fills the node rather than sitting above it.
     */
    override fun raw(destination: Rect, block: (Any) -> Unit) {
        batch().flush()
        block(GlFrame(projection.translated(rawX(destination.left), rawY(destination.bottom)), viewport))
    }

    override fun raw(block: (Any) -> Unit) {
        // Our own quads first, so the game's drawing lands on top of what came before it.
        batch().flush()
        block(GlFrame(projection.copyOf(), viewport))
    }

    /**
     * A copy of an orthographic projection with its origin moved to ([x], [y]).
     *
     * Only the translation column moves, which is all a scale-and-translate matrix has to change:
     * a point the block gives as (0, 0) has to come out where ([x], [y]) came out before.
     */
    private fun FloatArray.translated(x: Float, y: Float) = copyOf().also {
        it[12] += x * it[0]
        it[13] += y * it[5]
    }

    /**
     * Lets go of everything that was built. What was never built is not built here in order to be
     * destroyed: a canvas that drew nothing closes without touching the driver at all.
     *
     * [batch] and [effects] are deliberately left pointing at the dead resources. Nulling them
     * would let the next draw quietly build a fresh batch on a context that is going away, which
     * turns use after close into a leak nobody notices rather than the mistake it is. [ownWhite]
     * is cleared because [white] has always remade it on demand, and changing that here would be
     * a second change hiding inside this one.
     */
    override fun close() {
        batch?.close()
        layers.close()
        effects?.close()
        ownWhite?.close()
        ownWhite = null
    }

    /**
     * Where solid colour is sampled from.
     *
     * The glyph atlas's white block when there are fonts, so a panel and the label on it are the
     * same texture and the same draw call. A texture of this canvas's own when there are not,
     * which works and costs a draw call every time the interface alternates between the two.
     */
    private fun white(): GlTexture = fonts?.white() ?: ownWhite ?: makeWhite()

    private fun makeWhite(): GlTexture {
        val pixel = BufferUtils.createByteBuffer(4)
        repeat(4) { pixel.put(0xFF.toByte()) }
        pixel.flip()
        return GlTexture.rgba(1, 1, pixel).also { ownWhite = it }
    }

    /**
     * A y measured down from the top becomes one measured up from the bottom.
     *
     * From the bottom of the layer when there is one, which is why drawing into a layer needs no
     * arithmetic of its own anywhere else.
     */
    private fun flip(y: Float) = (layer?.bounds?.bottom ?: viewport.design.height) - y

    /**
     * What to say about a texture this canvas cannot draw.
     *
     * Nine separately-cut nine-patch pieces are the interesting case: they *are* a TextureHandle,
     * so they arrive here looking like a picture, and "this canvas can only draw textures it made"
     * would send the reader off hunting for a backend mismatch that is not the problem at all.
     *
     * They throw the same [IllegalArgumentException] the toolkit's own refusal throws, because it
     * is the same complaint about the same argument, and a host that catches one of them has to
     * catch both. A texture from another backend keeps the [IllegalStateException] it has always
     * thrown.
     */
    private fun notOnePicture(texture: TextureHandle): Nothing =
        if (texture is NineRegions) throw IllegalArgumentException(NineRegions.NotOnePicture)
        else error("this canvas can only draw textures it made, not ${texture::class}")

    private companion object {

        /**
         * The whole of the matrix maths this backend needs: design coordinates onto the clip cube.
         *
         * Column-major, because that is what OpenGL reads, and the four values that are not one or
         * zero are the scale and the shift that put the origin in the bottom-left corner.
         */
        fun orthographic(into: FloatArray, width: Float, height: Float, left: Float = 0f) {
            into.fill(0f)
            into[0] = 2f / width
            into[5] = 2f / height
            into[10] = -1f
            // [left] is where a layer starts. Drawing into one uses the same coordinates as
            // drawing onto the screen, and the shift that makes that true lives here, once.
            into[12] = -1f - left * 2f / width
            into[13] = -1f
            into[15] = 1f
        }

        /**
         * The biggest layer this will ask a driver for, each way.
         *
         * Every driver worth supporting manages 4096; past that the answer is "no" rather than a
         * silent failure halfway through a frame, and the effect is skipped.
         */
        const val MaxLayerPixels = 4096
    }
}
