package composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import composegl.ui.effect.ShaderEffect
import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.graphics.CanvasState
import composegl.ui.graphics.Colour
import composegl.ui.graphics.TextureHandle
import composegl.ui.graphics.UiCanvas
import composegl.ui.layout.Viewport
import composegl.ui.text.TextLayout
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
    atlas: GdxAtlas? = null,
) : UiCanvas, Disposable {

    private val batch = UiShapeBatch(white = atlas?.white)

    private var state = CanvasState(Rect.Zero)
    private var viewport: Viewport = Viewport.oneToOne(composegl.ui.geometry.Size(1f, 1f))
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

    private val effects = GdxEffects()

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

    /** How many times the frame so far has talked to the driver. */
    override val drawCalls: Int get() = batch.renderCalls

    /**
     * Sets up for a frame in [viewport]'s design coordinates.
     *
     * @param framebuffer what is bound right now, when it is not the screen — a render target's
     *   handle, for an interface being drawn onto a surface in a 3D world. A layer binds its own
     *   and has to know what to put back; nothing else here cares.
     */
    fun begin(viewport: Viewport, framebuffer: Int = 0) {
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
        batch.begin(projection)
    }

    /** Ends the frame and puts the GL state back the way a game expects to find it. */
    fun end() {
        check(drawing) { "end() without a begin()" }

        batch.end()
        scissor(false)
        Gdx.gl.glViewport(0, 0, viewport.physical.width.roundToInt(), viewport.physical.height.roundToInt())
        drawing = false
        layers.trim()

        // Complained about last, so that an unbalanced frame still leaves things tidy for whatever
        // the game draws next.
        check(state.isBalanced) { "a clip or an alpha was pushed and never popped" }
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
        // Flipped here, like every other call: the toolkit counts y downwards and the batch up.
        val flipped = FloatArray(points.size)
        var at = 0
        while (at < points.size) {
            flipped[at] = points[at]
            flipped[at + 1] = flip(points[at + 1])
            at += 2
        }
        batch.fan(flipped, colour.packed(state.alpha))
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
        batch.shape(
            left = rect.left,
            bottom = flip(rect.bottom),
            width = rect.width,
            height = rect.height,
            fill = fill.packed(state.alpha),
            corner = corner,
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

        gdx.glyphs.runs.forEach { run ->
            var at = x + run.x
            val baseline = top + run.y
            for (index in 0 until run.glyphs.size) {
                val glyph = run.glyphs[index]
                at += run.xAdvances[index]
                val region = regions[glyph.page]
                batch.textured(
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
        val gdx = texture as? GdxTexture
            ?: error("this canvas can only draw textures it made, not ${texture::class}")

        val region = gdx.region

        // A TextureRegion measures y downwards, like the toolkit, so `v` is its top edge and `v2`
        // its bottom. The batch takes the coordinate for the quad's top and the quad's bottom in
        // that order, so they go straight across.
        //
        // A BitmapFont glyph is the other way round — `v2` is its top — which is why the text above
        // swaps them and this does not. It is an unhappy asymmetry in LibGDX, not in this file.
        var left = region.u
        var right = region.u2
        var top = region.v
        var bottom = region.v2

        if (source != null) {
            check(!gdx.rotated) {
                "part of a rotated atlas region cannot be drawn; pack this one without rotation"
            }
            val across = (region.u2 - region.u) / region.regionWidth
            val down = (region.v2 - region.v) / region.regionHeight
            left = region.u + source.left * across
            right = region.u + source.right * across
            top = region.v + source.top * down
            bottom = region.v + source.bottom * down
        }

        batch.textured(
            texture = region.texture,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            u = left,
            v = top,
            u2 = right,
            v2 = bottom,
            colour = tint.packed(state.alpha),
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
     * The scissor follows the toolkit's clip, which has already intersected the nested clips — so
     * the backend cannot get nesting wrong, because it never works it out. The batch is flushed
     * first: whatever is queued was queued under the old clip.
     */
    private fun applyScissor() {
        batch.flush()
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

        // Bound by hand rather than with FrameBuffer.begin(), which unbinds to the screen rather
        // than to whatever was bound before it — so layers inside layers would come apart, and the
        // letterboxed viewport would be replaced by the whole window.
        val previousFramebuffer = framebuffer
        val previousViewport = viewportBox.copyOf()
        val previousScissor = scissorOn
        val previousState = state
        val previousLayer = layer
        val previousProjection = Matrix4(projection)

        batch.flush()
        layer = LayerFrame(bounds, pixelWidth, pixelHeight)
        // Full opacity and a clip of exactly the layer. The opacity out here is applied when the
        // picture is drawn back, which is what makes a group fade as one object.
        state = CanvasState(bounds)

        bindFramebuffer(target.buffer.framebufferHandle)
        setViewport(0, 0, pixelWidth, pixelHeight)
        scissor(false)
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        projection.setToOrtho2D(bounds.left, 0f, bounds.width, bounds.height)
        batch.projection(projection)

        try {
            block()
            batch.flush()
            check(state.isBalanced) { "a clip or an alpha was pushed inside a layer and never popped" }
        } finally {
            layer = previousLayer
            state = previousState
            projection.set(previousProjection)
            batch.projection(projection)
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

        batch.premultiplied(true)
        // The opacity goes into all four channels, because a premultiplied colour that faded only
        // its alpha would get brighter as it disappeared.
        val fade = state.alpha.coerceIn(0f, 1f)
        batch.textured(
            texture = region.texture,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            u = region.u,
            v = region.v,
            u2 = region.u2,
            v2 = region.v2,
            colour = Color.toFloatBits(fade, fade, fade, fade),
        )
        batch.premultiplied(false)
    }

    /**
     * The same picture, through somebody's shader.
     *
     * The quad is worked out here, in clip space, because this is the class that knows where a
     * design coordinate ends up: the projection, the y flip and the layer's own origin all live
     * here and none of them are the shader's business.
     */
    private fun drawThrough(effect: ShaderEffect, picture: GdxTexture, destination: Rect) {
        // Whatever is queued was queued to land under this, so it goes first.
        batch.flush()

        val region = picture.region
        val values = projection.values
        effects.draw(
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
        )

        // The batch set the blending and the program it wants at the start of the frame, and the
        // shader has just changed both.
        batch.premultiplied(false)
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

    override fun raw(block: (Any) -> Unit) {
        val sprites = spriteBatch
            ?: error("this canvas was made without a SpriteBatch, so raw() has nothing to hand over")

        // Our own quads first, so the game's drawing lands on top of what came before it.
        batch.flush()
        val savedProjection = Matrix4(sprites.projectionMatrix)
        val savedColour = Color(sprites.color)
        sprites.projectionMatrix = projection
        sprites.begin()
        block(sprites)
        sprites.end()
        sprites.projectionMatrix = savedProjection
        sprites.color = savedColour
    }

    override fun dispose() {
        batch.dispose()
        layers.dispose()
        effects.dispose()
    }

    /**
     * A y measured down from the top becomes one measured up from the bottom.
     *
     * From the bottom of the layer when there is one, which is why drawing into a layer needs no
     * arithmetic of its own anywhere else.
     */
    private fun flip(y: Float) = (layer?.bounds?.bottom ?: viewport.design.height) - y

    /** The colour LibGDX wants: four bytes squeezed into a float, alpha already multiplied. */
    private fun Colour.packed(alpha: Float): Float = Color.toFloatBits(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
        (((argb ushr 24) and 0xFF) * alpha).toInt().coerceIn(0, 255),
    )

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
