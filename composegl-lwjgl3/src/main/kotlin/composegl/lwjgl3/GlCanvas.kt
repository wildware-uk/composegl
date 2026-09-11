package composegl.lwjgl3

import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.geometry.Size
import composegl.ui.effect.ShaderEffect
import composegl.ui.graphics.CanvasState
import composegl.ui.graphics.Colour
import composegl.ui.graphics.TextureHandle
import composegl.ui.graphics.UiCanvas
import composegl.ui.layout.Viewport
import composegl.ui.text.TextLayout
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

    private val batch = GlShapeBatch()

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

    private val effects = GlEffects()

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

    /** How many times the frame so far has talked to the driver. */
    override val drawCalls: Int get() = batch.renderCalls

    /**
     * Sets up for a frame in [viewport]'s design coordinates.
     *
     * @param framebuffer what is bound right now, when it is not the screen — a render target's
     *   name, for an interface being drawn onto a surface in a 3D world. A layer binds its own and
     *   has to know what to put back; nothing else here cares.
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
        setViewport(
            viewport.origin.x.roundToInt(),
            // GL counts up from the bottom of the window; the viewport counts down from the top.
            (viewport.physical.height - viewport.origin.y - viewport.design.height * viewport.scaleY).roundToInt(),
            (viewport.design.width * viewport.scaleX).roundToInt(),
            (viewport.design.height * viewport.scaleY).roundToInt(),
        )
        orthographic(projection, viewport.design.width, viewport.design.height)
        batch.begin(projection)
    }

    /** Ends the frame and puts the GL state back the way a game expects to find it. */
    fun end() {
        check(drawing) { "end() without a begin()" }

        batch.end()
        scissor(false)
        setViewport(0, 0, viewport.physical.width.roundToInt(), viewport.physical.height.roundToInt())
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
        batch.fan(white(), flipped, colour.scaleAlpha(state.alpha))
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
            white = white(),
            left = rect.left,
            bottom = flip(rect.bottom),
            width = rect.width,
            height = rect.height,
            fill = fill.scaleAlpha(state.alpha),
            corner = corner,
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
        measured.placed.forEach { placed ->
            batch.textured(
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
        val picture = texture as? GlTexture
            ?: error("this canvas can only draw textures it made, not ${texture::class}")

        // Texture coordinates here count y downwards, like the toolkit, so `v` is the top edge and
        // it goes straight across to the quad's top with no swap anywhere.
        var left = picture.u
        var right = picture.u2
        var top = picture.v
        var bottom = picture.v2

        if (source != null) {
            val across = (picture.u2 - picture.u) / picture.width
            val down = (picture.v2 - picture.v) / picture.height
            left = picture.u + source.left * across
            right = picture.u + source.right * across
            top = picture.v + source.top * down
            bottom = picture.v + source.bottom * down
        }

        batch.textured(
            name = picture.name,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            u = left,
            v = top,
            u2 = right,
            v2 = bottom,
            tint = tint.scaleAlpha(state.alpha),
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

        batch.flush()
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
        batch.projection(projection)

        try {
            block()
            batch.flush()
            check(state.isBalanced) { "a clip or an alpha was pushed inside a layer and never popped" }
        } finally {
            layer = previousLayer
            state = previousState
            previousProjection.copyInto(projection)
            batch.projection(projection)
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

        batch.premultiplied(true)
        // The opacity goes into all four channels, because a premultiplied colour that faded only
        // its alpha would get brighter as it disappeared.
        val fade = state.alpha.coerceIn(0f, 1f)
        val grey = (fade * 255f).roundToInt().coerceIn(0, 255)
        batch.textured(
            name = picture.name,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            u = picture.u,
            v = picture.v,
            u2 = picture.u2,
            v2 = picture.v2,
            tint = Colour((grey shl 24) or (grey shl 16) or (grey shl 8) or grey),
        )
        batch.premultiplied(false)
    }

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
        batch.flush()

        val bottom = flip(destination.bottom)
        val top = flip(destination.top)
        effects.draw(
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
        )

        // The batch set the blending and the program it wants at the start of the frame, and the
        // shader has just changed both.
        GL20.glUseProgram(0)
        batch.premultiplied(false)
    }

    /** A design x, through the frame's projection, as the clip cube sees it. */
    private fun clipX(x: Float) = x * projection[0] + projection[12]

    /** The same for a y that has already been flipped the right way up. */
    private fun clipY(y: Float) = y * projection[5] + projection[13]

    override fun raw(block: (Any) -> Unit) {
        // Our own quads first, so the game's drawing lands on top of what came before it.
        batch.flush()
        block(GlFrame(projection.copyOf(), viewport))
    }

    override fun close() {
        batch.close()
        layers.close()
        effects.close()
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
