package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.CanvasState
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.NineRegions
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.graphics.featherOutline
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextLayout
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The LibGDX canvas.
 *
 * Everything goes through one batch and one shader: a rounded corner, a border and a soft shadow
 * are the same distance calculation, and text and pictures are the same quad with the shape turned
 * off. A hundred nodes cost a handful of draw calls, and the ones they cost are texture changes.
 *
 * Two conventions meet here and are reconciled once:
 *
 * - the toolkit measures y downwards from the top; LibGDX measures it upwards from the bottom;
 * - the toolkit works in the design resolution; the screen is whatever size it is.
 *
 * The scale and the letterboxing are handled by the GL viewport rather than by arithmetic on every
 * call, so a game's own drawing through [raw] gets a projection already in design coordinates.
 *
 * @param spriteBatch what [raw] hands a game. Optional: without one, [raw] is refused rather than
 *   silently handing over something a game's existing code cannot use.
 * @param atlas the glyph atlas, if there is one. Sharing it means solid colour and text come from
 *   the same texture, which is the difference between a screen costing forty draw calls and one.
 */
class GdxCanvas(
    private val spriteBatch: Batch? = null,
    private val atlas: GdxAtlas? = null,
) : UiCanvas, Disposable {

    /**
     * The quads, and the shader they go through. Made the first time something is drawn.
     *
     * Late rather than in the field, so that merely *having* a canvas needs no OpenGL. A game
     * object that owns a canvas alongside its host, its renderer and its focus — which is most of
     * them — can then be built in a plain JVM test, and its input, focus and lifecycle asserted
     * without a window anywhere. See [warmUp] for paying the cost on purpose.
     */
    private var batch: UiShapeBatch? = null

    private fun batch() = batch ?: UiShapeBatch(white = atlas?.white).also { batch = it }

    private var state = CanvasState(Rect.Zero)
    private var viewport: Viewport = Viewport.oneToOne(dev.wildware.composegl.ui.geometry.Size(1f, 1f))
    private var drawing = false

    /**
     * How wide a softened edge is, in design units.
     *
     * One screen pixel, whatever the screen is doing. A corner that fades over one design unit
     * would be a blurry three-pixel smear at 3x, and a hard aliased step at a third.
     */
    private var antialias = 1f

    private val projection = Matrix4()

    private val layers = GdxLayers()

    private var effects: GdxEffects? = null

    private fun effects() = effects ?: GdxEffects().also { effects = it }

    /**
     * The offscreen picture being drawn into, or null when that is the window.
     *
     * Everything that turns a design coordinate into a pixel — the y flip, the scissor — asks this
     * first, so the same drawing code lands in the right place either way and no widget ever finds
     * out which it was.
     */
    private var layer: LayerFrame? = null

    private class LayerFrame(val bounds: Rect, val pixelWidth: Int, val pixelHeight: Int)

    /** Where the GL viewport is right now, so a layer can put it back. */
    private val viewportBox = IntArray(4)

    /**
     * Which framebuffer the canvas believes is bound, and whether the scissor is on.
     *
     * Remembered rather than asked for. Asking costs a pipeline stall — the driver has to catch up
     * with itself before it can answer — and on this backend the answer is wrong anyway: LibGDX's
     * `glGetIntegerv(GL_FRAMEBUFFER_BINDING)` reports zero even with a framebuffer bound, which
     * sends a layer inside a layer back to the screen halfway through a frame.
     */
    private var framebuffer = 0
    private var scissorOn = false

    /** How many times the frame so far has talked to the driver. Nothing drawn yet is none. */
    override val drawCalls: Int get() = batch?.renderCalls ?: 0

    /**
     * Builds the GPU resources now, instead of when something is first drawn.
     *
     * A mesh and a compiled shader cost a few milliseconds, and without this they are paid for in
     * the first frame the player sees — which is exactly the frame a stutter is noticed in. Call
     * it on a loading screen, on the thread that holds the context. Calling it twice does nothing
     * the second time, and never calling it is fine.
     */
    override fun warmUp() {
        batch()
        effects()
    }

    /**
     * Whether the GPU resources exist yet.
     *
     * For the tests, which otherwise have no way to tell a canvas that built its mesh from one
     * that did not: [drawCalls] is zero either way, so a test asserting on it alone would pass
     * with [warmUp] gutted to an empty body — and both halves of this class's contract are about
     * *when* the building happens.
     */
    internal val warmedUp: Boolean get() = batch != null && effects != null

    /**
     * Sets up for a frame in [viewport]'s design coordinates.
     *
     * @param framebuffer what is bound right now, when it is not the screen — a render target's
     *   handle, for an interface being drawn onto a surface in a 3D world. A layer binds its own
     *   and has to know what to put back; nothing else here cares.
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
        viewportBox[0] = viewport.origin.x.roundToInt()
        // GL counts up from the bottom of the window; the viewport counts down from the top.
        viewportBox[1] =
            (viewport.physical.height - viewport.origin.y - viewport.design.height * viewport.scaleY).roundToInt()
        viewportBox[2] = (viewport.design.width * viewport.scaleX).roundToInt()
        viewportBox[3] = (viewport.design.height * viewport.scaleY).roundToInt()
        Gdx.gl.glViewport(viewportBox[0], viewportBox[1], viewportBox[2], viewportBox[3])
        projection.setToOrtho2D(0f, 0f, viewport.design.width, viewport.design.height)
        batch().begin(projection)
    }

    /** Ends the frame and puts the GL state back the way a game expects to find it. */
    override fun end() {
        check(drawing) { "end() without a begin()" }

        batch().end()
        scissor(false)
        Gdx.gl.glViewport(0, 0, viewport.physical.width.roundToInt(), viewport.physical.height.roundToInt())
        drawing = false
        layers.trim()

        // Complained about last, so that an unbalanced frame still leaves things tidy for whatever
        // the game draws next.
        check(state.isBalanced) { "a clip, an alpha, a blend or a tint was pushed and never popped" }
    }

    // --- shapes ---

    override fun rect(rect: Rect, colour: Colour, corner: Float) {
        if (state.isHidden || rect.isEmpty) return
        shape(rect, fill = colour, corner = corner)
    }

    override fun rect(rect: Rect, brush: Brush, corner: Float) {
        if (state.isHidden || rect.isEmpty) return
        // Worked out here, once, in the toolkit's coordinates — then y flipped for the batch, which
        // counts upwards. A radial gradient is symmetric and has no axis to flip.
        val axis = (brush as? Brush.Linear)?.axis(rect.width, rect.height)
        batch().gradient(
            left = rect.left,
            bottom = flip(rect.bottom),
            width = rect.width,
            height = rect.height,
            start = brush.first.packed(state.alpha),
            end = brush.last.packed(state.alpha),
            radial = brush is Brush.Radial,
            axisX = axis?.x ?: 0f,
            axisY = -(axis?.y ?: 0f),
            corner = corner,
            aa = antialias,
        )
    }

    /** Both kinds, straight and radial, through the same shader as every other box. */
    override val drawsGradients: Boolean get() = true

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
        batch().fan(flipped, colour.packed(state.alpha))
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
            left = rect.left,
            bottom = flip(rect.bottom),
            width = rect.width,
            height = rect.height,
            fill = fill.packed(state.alpha),
            // Top is still top: the flip moves the box, and the batch's own up is the screen's up.
            topLeft = topLeft,
            topRight = topRight,
            bottomRight = bottomRight,
            bottomLeft = bottomLeft,
            border = border.packed(state.alpha),
            borderWidth = borderWidth,
            shadow = shadow.packed(state.alpha),
            shadowSpread = shadowSpread,
            aa = antialias,
        )
    }

    // --- text and pictures ---

    override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) {
        if (state.isHidden) return
        val gdx = layout as? GdxTextLayout
            ?: error("this canvas can only draw text measured by GdxFonts, not ${layout::class}")

        val packed = colour.packed(state.alpha)
        val regions = gdx.font.regions
        val data = gdx.font.data
        val top = flip(y)

        // Indexed rather than `forEach`: that asks for an iterator, and an outlined run comes
        // through here nine times, so a HUD of labels would make an object per copy per run per
        // frame for nothing.
        val runs = gdx.glyphs.runs
        for (runIndex in 0 until runs.size) {
            val run = runs.get(runIndex)
            var at = x + run.x
            val baseline = top + run.y
            for (index in 0 until run.glyphs.size) {
                val glyph = run.glyphs[index]
                at += run.xAdvances[index]
                val region = regions[glyph.page]
                batch().textured(
                    texture = region.texture,
                    left = at + glyph.xoffset * data.scaleX,
                    bottom = baseline + glyph.yoffset * data.scaleY,
                    width = glyph.width * data.scaleX,
                    height = glyph.height * data.scaleY,
                    u = glyph.u,
                    v = glyph.v2,
                    u2 = glyph.u2,
                    v2 = glyph.v,
                    colour = packed,
                )
            }
        }
    }

    override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) {
        if (state.isHidden || destination.isEmpty) return
        val gdx = texture as? GdxTexture ?: notOnePicture(texture)

        val region = gdx.region
        slice.of(gdx, source)

        batch().textured(
            texture = region.texture,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            u = slice.left,
            v = slice.top,
            u2 = slice.right,
            v2 = slice.bottom,
            colour = tint.packed(state.alpha),
        )
    }

    /**
     * The corner of the atlas page a picture is cut from, worked out once and read straight after.
     *
     * One of these per canvas rather than one per call. [image] runs once per sprite per frame and
     * a sunburst runs it a dozen times in a row, so a fresh object each time would be rubbish for
     * the collector to sweep for nothing. Nothing here outlives the call that fills it.
     *
     * Shared by the upright call and the turned one, which want exactly the same four numbers —
     * two copies of this arithmetic, guard included, would be two things to keep in step for no
     * gain.
     */
    private val slice = Slice()

    private class Slice {

        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f

        /**
         * [source]'s corner of [gdx]'s region, or the whole of it when there is no sub-rectangle.
         *
         * A TextureRegion measures y downwards, like the toolkit, so `v` is its top edge and `v2`
         * its bottom. The batch takes the coordinate for the quad's top and the quad's bottom in
         * that order, so they go straight across.
         *
         * A BitmapFont glyph is the other way round — `v2` is its top — which is why the text
         * above swaps them and this does not. It is an unhappy asymmetry in LibGDX, not in this
         * file.
         */
        fun of(gdx: GdxTexture, source: Rect?) {
            val region = gdx.region
            if (source == null) {
                left = region.u
                top = region.v
                right = region.u2
                bottom = region.v2
                return
            }
            // A packer may lay a region down a quarter turn to make it fit, and then the
            // sub-rectangle's x and y mean the other two axes. Refused rather than drawn wrongly.
            check(!gdx.rotated) {
                "part of a rotated atlas region cannot be drawn; pack this one without rotation"
            }
            val across = (region.u2 - region.u) / region.regionWidth
            val down = (region.v2 - region.v) / region.regionHeight
            left = region.u + source.left * across
            top = region.v + source.top * down
            right = region.u + source.right * across
            bottom = region.v + source.bottom * down
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
        val gdx = texture as? GdxTexture ?: notOnePicture(texture)
        val region = gdx.region
        slice.of(gdx, source)

        batch().textured(
            texture = region.texture,
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
            colour = tint.packed(state.alpha),
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

    /**
     * No flush and no GL: the tint goes into each vertex's colour as it is queued, so a tinted
     * hotbar batches with the untinted panel behind it.
     */
    override fun pushTint(tint: Colour) = state.pushTint(tint)

    override fun popTint() = state.popTint()

    /** Every call that takes a colour, and every picture drawn inside a layer. Not raw(). */
    override val tints: Boolean get() = true

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
        Gdx.gl.glScissor(
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
        Gdx.gl.glScissor(
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

        // Flushed before a picture is taken from the pool, not after. Making a new FrameBuffer binds
        // the screen when it is done, so whatever an enclosing layer had batched but not yet drawn
        // would otherwise land on the screen instead of in that layer's picture.
        batch().flush()
        val target = layers.acquire(pixelWidth, pixelHeight)
        // And put back what was bound, since the pool may just have moved it.
        bindFramebuffer(framebuffer)

        // Bound by hand rather than with FrameBuffer.begin(), which unbinds to the screen rather
        // than to whatever was bound before it — so layers inside layers would come apart, and the
        // letterboxed viewport would be replaced by the whole window.
        val previousFramebuffer = framebuffer
        val previousViewport = viewportBox.copyOf()
        val previousScissor = scissorOn
        val previousState = state
        val previousLayer = layer
        val previousProjection = Matrix4(projection)

        layer = LayerFrame(bounds, pixelWidth, pixelHeight)
        // Full opacity and a clip of exactly the layer. The opacity out here is applied when the
        // picture is drawn back, which is what makes a group fade as one object.
        // The tint is the exception, and comes in with it: see UiCanvas.pushTint.
        state = previousState.forLayer(bounds)

        bindFramebuffer(target.buffer.framebufferHandle)
        setViewport(0, 0, pixelWidth, pixelHeight)
        scissor(false)
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        projection.setToOrtho2D(bounds.left, 0f, bounds.width, bounds.height)
        batch().projection(projection)
        // A layer's picture starts as transparent black, so adding into it and then compositing
        // that result is not the same as adding onto the screen. The block gets plain blending,
        // the same reset the clip and the opacity get, and the mode out here comes back below.
        applyBlend()

        try {
            block()
            batch().flush()
            check(state.isBalanced) { "a clip, an alpha, a blend or a tint was pushed inside a layer and never popped" }
        } finally {
            layer = previousLayer
            state = previousState
            projection.set(previousProjection)
            batch().projection(projection)
            applyBlend()
            bindFramebuffer(previousFramebuffer)
            setViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
            scissor(previousScissor)
            layers.release(target)
        }

        return target.texture
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, effect: ShaderEffect?) {
        if (state.isHidden || destination.isEmpty) return
        val picture = layer as? GdxTexture
            ?: error("this canvas can only draw layers it made, not ${layer::class}")
        val region = picture.region

        if (effect != null) {
            drawThrough(effect, picture, destination)
            return
        }
        composite(region, destination, mirrorX = false, mirrorY = false)
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, mirrorX: Boolean, mirrorY: Boolean) {
        if (state.isHidden || destination.isEmpty) return
        val picture = layer as? GdxTexture
            ?: error("this canvas can only draw layers it made, not ${layer::class}")
        composite(picture.region, destination, mirrorX, mirrorY)
    }

    /** It really mirrors one: the texture coordinates are swapped on the same quad. */
    override val mirrorsLayers: Boolean get() = true

    /** A layer put down upright, the plain way or with either axis of its picture swapped. */
    private fun composite(region: TextureRegion, destination: Rect, mirrorX: Boolean, mirrorY: Boolean) {
        // The mode in force applies to the composite, so pushing Additive round a drawLayer makes
        // a whole group glow. Premultiplied because that is what the layer's own drawing produced.
        batch().blend(state.blend, premultiplied = true)
        // The opacity goes into all four channels, because a premultiplied colour that faded only
        // its alpha would get brighter as it disappeared.
        val fade = state.alpha.coerceIn(0f, 1f)
        // A mirror is the same quad reading its picture from the other side, so it costs nothing a
        // plain composite does not and batches with it.
        batch().textured(
            texture = region.texture,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            u = if (mirrorX) region.u2 else region.u,
            v = if (mirrorY) region.v2 else region.v,
            u2 = if (mirrorX) region.u else region.u2,
            v2 = if (mirrorY) region.v else region.v2,
            colour = Color.toFloatBits(fade, fade, fade, fade),
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
        val picture = layer as? GdxTexture
            ?: error("this canvas can only draw layers it made, not ${layer::class}")
        val region = picture.region

        // Premultiplied and faded in all four channels for the same reasons the upright composite
        // is; the turn changes where the quad's corners go and nothing else.
        batch().blend(state.blend, premultiplied = true)
        val fade = state.alpha.coerceIn(0f, 1f)
        batch().textured(
            texture = region.texture,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            pivotX = destination.left + destination.width * pivotX,
            // The pivot is a fraction from the top, and this is the one place it meets a y that
            // counts upwards.
            pivotY = flip(destination.top + destination.height * pivotY),
            degrees = degrees,
            u = region.u,
            v = region.v,
            u2 = region.u2,
            v2 = region.v2,
            colour = Color.toFloatBits(fade, fade, fade, fade),
        )
        batch().blend(state.blend, premultiplied = false)
    }

    /** It really turns one, on the same quad the upright composite uses. */
    override val turnsLayers: Boolean get() = true

    override fun drawLayerOnto(layer: TextureHandle, destination: Rect, corners: FloatArray) {
        require(corners.size == 8) { "four corners are eight numbers, not ${corners.size}" }
        if (state.isHidden || destination.isEmpty) return
        val picture = layer as? GdxTexture
            ?: error("this canvas can only draw layers it made, not ${layer::class}")
        val region = picture.region

        // The corners arrive counting y down and the batch counts it up; x is left alone.
        val flipped = FloatArray(8) { if (it % 2 == 0) corners[it] else flip(corners[it]) }

        // Premultiplied and faded in all four channels for the same reasons the upright composite
        // is; four corners change where the quad goes and nothing else.
        batch().blend(state.blend, premultiplied = true)
        val fade = state.alpha.coerceIn(0f, 1f)
        batch().textured(
            texture = region.texture,
            corners = flipped,
            u = region.u,
            v = region.v,
            u2 = region.u2,
            v2 = region.v2,
            colour = Color.toFloatBits(fade, fade, fade, fade),
        )
        batch().blend(state.blend, premultiplied = false)
    }

    /** It really does, on the same quad the upright composite uses. */
    override val drawsLayersOnto: Boolean get() = true

    /**
     * The picture as a fan through [outline], with a ring one screen pixel wide round it that
     * fades to nothing — see [featherOutline]. Same texture, same shader and same batch as every
     * other picture, so no GL state changes beyond the premultiplied blend a layer always needs.
     *
     * The texture coordinates are held inside the picture: the soft ring reaches half a pixel past
     * the outline, and a sample from past the picture's edge would be whatever the texture's wrap
     * mode found there.
     */
    override fun cutLayer(layer: TextureHandle, destination: Rect, outline: FloatArray) {
        if (state.isHidden || destination.isEmpty || outline.size < 6) return
        val picture = layer as? GdxTexture
            ?: error("this canvas can only draw layers it made, not ${layer::class}")
        val region = picture.region
        val texture = region.texture

        // Premultiplied and faded in all four channels, for the reasons drawLayer gives.
        batch().blend(state.blend, premultiplied = true)
        val fade = state.alpha.coerceIn(0f, 1f)
        val solid = Color.toFloatBits(fade, fade, fade, fade)
        val clear = Color.toFloatBits(0f, 0f, 0f, 0f)
        val left = destination.left
        val top = destination.top
        val width = destination.width
        val height = destination.height
        val uSpan = region.u2 - region.u
        val vSpan = region.v2 - region.v

        featherOutline(outline, antialias) { ax, ay, aCover, bx, by, bCover, cx, cy, cCover, dx, dy, dCover ->
            batch().corners(
                texture,
                ax, flip(ay),
                region.u + ((ax - left) / width).coerceIn(0f, 1f) * uSpan,
                region.v + ((ay - top) / height).coerceIn(0f, 1f) * vSpan,
                if (aCover > 0f) solid else clear,
                bx, flip(by),
                region.u + ((bx - left) / width).coerceIn(0f, 1f) * uSpan,
                region.v + ((by - top) / height).coerceIn(0f, 1f) * vSpan,
                if (bCover > 0f) solid else clear,
                cx, flip(cy),
                region.u + ((cx - left) / width).coerceIn(0f, 1f) * uSpan,
                region.v + ((cy - top) / height).coerceIn(0f, 1f) * vSpan,
                if (cCover > 0f) solid else clear,
                dx, flip(dy),
                region.u + ((dx - left) / width).coerceIn(0f, 1f) * uSpan,
                region.v + ((dy - top) / height).coerceIn(0f, 1f) * vSpan,
                if (dCover > 0f) solid else clear,
            )
        }
        batch().blend(state.blend, premultiplied = false)
    }

    /** It really cuts one, with a soft edge, in the same batch as everything else. */
    override val cutsLayers: Boolean get() = true

    /**
     * The same picture, through somebody's shader.
     *
     * The quad is worked out here, in clip space, because this is the class that knows where a
     * design coordinate ends up: the projection, the y flip and the layer's own origin all live
     * here and none of them are the shader's business.
     */
    private fun drawThrough(effect: ShaderEffect, picture: GdxTexture, destination: Rect) {
        // Whatever is queued was queued to land under this, so it goes first.
        batch().flush()

        val region = picture.region
        val values = projection.values
        effects().draw(
            effect = effect,
            texture = region.texture,
            left = clipX(destination.left, values),
            top = clipY(flip(destination.top), values),
            right = clipX(destination.right, values),
            bottom = clipY(flip(destination.bottom), values),
            u = region.u,
            v = region.v,
            u2 = region.u2,
            v2 = region.v2,
            textureWidth = region.regionWidth.toFloat(),
            textureHeight = region.regionHeight.toFloat(),
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
        batch().blend(state.blend, premultiplied = false)
    }

    /** A design x, through the frame's projection, as the clip cube sees it. */
    private fun clipX(x: Float, matrix: FloatArray) = x * matrix[Matrix4.M00] + matrix[Matrix4.M03]

    /** The same for a y that has already been flipped the right way up. */
    private fun clipY(y: Float, matrix: FloatArray) = y * matrix[Matrix4.M11] + matrix[Matrix4.M13]

    private fun setViewport(x: Int, y: Int, width: Int, height: Int) {
        viewportBox[0] = x
        viewportBox[1] = y
        viewportBox[2] = width
        viewportBox[3] = height
        Gdx.gl.glViewport(x, y, width, height)
    }

    private fun bindFramebuffer(handle: Int) {
        framebuffer = handle
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, handle)
    }

    private fun scissor(on: Boolean) {
        scissorOn = on
        if (on) Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST) else Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
    }

    /**
     * Through [flip], which is the same conversion every other call here already makes.
     *
     * Worth having rather than leaving a game to subtract from the design height itself: inside a
     * layer the origin is the layer's own bottom, and a game cannot see that.
     */
    override fun rawY(y: Float): Float = flip(y)

    /**
     * Unchanged, which is the interface's default and is said here anyway.
     *
     * A layer's own left edge goes into the projection — `setToOrtho2D(bounds.left, …)` — not into
     * the coordinates, so a design x is already the x this backend's drawing object wants, inside a
     * layer and out of one. The y is the only axis that needs converting here.
     */
    override fun rawX(x: Float): Float = x

    /** Whether one was passed to the constructor, which is the whole of the question. */
    override val handsOverRaw: Boolean get() = spriteBatch != null

    /** It really moves it: the projection is translated before the batch is opened. */
    override val movesRawOrigin: Boolean get() = true

    /**
     * The bottom-left corner, because LibGDX measures y upwards: a block drawing `0, 0, w, h`
     * fills the node rather than sitting above it.
     */
    override fun raw(destination: Rect, block: (Any) -> Unit) =
        raw(Matrix4(projection).translate(rawX(destination.left), rawY(destination.bottom), 0f), block)

    override fun raw(block: (Any) -> Unit) = raw(projection, block)

    private fun raw(matrix: Matrix4, block: (Any) -> Unit) {
        val sprites = spriteBatch
            ?: error("this canvas was made without a SpriteBatch, so raw() has nothing to hand over")

        // Our own quads first, so the game's drawing lands on top of what came before it.
        batch().flush()
        val savedProjection = Matrix4(sprites.projectionMatrix)
        val savedColour = Color(sprites.color)
        sprites.projectionMatrix = matrix
        sprites.begin()
        block(sprites)
        sprites.end()
        sprites.projectionMatrix = savedProjection
        sprites.color = savedColour
        // A SpriteBatch sets its own blending on the way in and switches blending off altogether
        // on the way out, so this is the canvas tidying up after itself rather than after the
        // block — without it everything drawn after a raw block came out flat and opaque. Two GL
        // calls on a path that has just paid for a whole batch swap. The other backend needs no
        // such line: its raw() hands over a projection and touches no GL state at all.
        applyBlend()
    }

    /**
     * Lets go of everything that was built. What was never built is not built here in order to be
     * destroyed: a canvas that drew nothing disposes without touching the driver at all.
     *
     * The fields are deliberately left pointing at the dead resources. Nulling them would let the
     * next draw quietly build a fresh batch on a context that is going away, which turns use after
     * dispose into a leak nobody notices rather than the mistake it is.
     */
    override fun dispose() {
        batch?.dispose()
        layers.dispose()
        effects?.dispose()
    }

    /**
     * A y measured down from the top becomes one measured up from the bottom.
     *
     * From the bottom of the layer when there is one, which is why drawing into a layer needs no
     * arithmetic of its own anywhere else.
     */
    private fun flip(y: Float) = (layer?.bounds?.bottom ?: viewport.design.height) - y

    /**
     * The colour LibGDX wants: four bytes squeezed into a float, alpha already multiplied, and the
     * tint in force multiplied into the other three.
     */
    private fun Colour.packed(alpha: Float): Float {
        val tint = state.tint
        return Color.toFloatBits(
            ((argb shr 16) and 0xFF) * tint.red / 255,
            ((argb shr 8) and 0xFF) * tint.green / 255,
            (argb and 0xFF) * tint.blue / 255,
            (((argb ushr 24) and 0xFF) * alpha).toInt().coerceIn(0, 255),
        )
    }

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
         * The biggest layer this will ask a driver for, each way.
         *
         * Every driver worth supporting manages 4096; past that the answer is "no" rather than a
         * silent failure halfway through a frame, and the effect is skipped.
         */
        const val MaxLayerPixels = 4096
    }
}
