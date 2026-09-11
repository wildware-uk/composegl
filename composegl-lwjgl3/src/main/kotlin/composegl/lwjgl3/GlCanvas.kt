package composegl.lwjgl3

import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.geometry.Size
import composegl.ui.graphics.CanvasState
import composegl.ui.graphics.Colour
import composegl.ui.graphics.TextureHandle
import composegl.ui.graphics.UiCanvas
import composegl.ui.layout.Viewport
import composegl.ui.text.TextLayout
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
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

    /** How many times the last frame talked to the driver. */
    val renderCalls: Int get() = batch.renderCalls

    /** Sets up for a frame in [viewport]'s design coordinates. */
    fun begin(viewport: Viewport) {
        check(!drawing) { "begin() was called twice without an end()" }
        drawing = true
        this.viewport = viewport
        state = CanvasState(Rect.of(0f, 0f, viewport.design.width, viewport.design.height))
        antialias = 1f / minOf(viewport.scaleX, viewport.scaleY).coerceAtLeast(0.0001f)

        // The letterbox and the scale live here, so nothing below has to think about them.
        GL11.glViewport(
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
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glViewport(0, 0, viewport.physical.width.roundToInt(), viewport.physical.height.roundToInt())
        drawing = false

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

    override fun text(layout: TextLayout, at: Offset, colour: Colour) {
        if (state.isHidden) return
        val measured = layout as? StbTextLayout
            ?: error("this canvas can only draw text measured by StbFonts, not ${layout::class}")
        val atlas = checkNotNull(fonts) { "this canvas was made without fonts, so it cannot draw text" }.texture()

        val tint = colour.scaleAlpha(state.alpha)
        measured.placed.forEach { placed ->
            batch.textured(
                name = atlas.name,
                left = at.x + placed.left,
                bottom = flip(at.y + placed.top + placed.height),
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
        if (clip.left <= 0f && clip.top <= 0f &&
            clip.right >= viewport.design.width && clip.bottom >= viewport.design.height
        ) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            return
        }

        val topLeft = viewport.toScreen(Offset(clip.left, clip.top))
        val bottomRight = viewport.toScreen(Offset(clip.right, clip.bottom))
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(
            topLeft.x.roundToInt(),
            (viewport.physical.height - bottomRight.y).roundToInt(),
            (bottomRight.x - topLeft.x).roundToInt().coerceAtLeast(0),
            (bottomRight.y - topLeft.y).roundToInt().coerceAtLeast(0),
        )
    }

    override fun raw(block: (Any) -> Unit) {
        // Our own quads first, so the game's drawing lands on top of what came before it.
        batch.flush()
        block(GlFrame(projection.copyOf(), viewport))
    }

    override fun close() {
        batch.close()
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

    /** A y measured down from the top becomes one measured up from the bottom. */
    private fun flip(y: Float) = viewport.design.height - y

    private companion object {

        /**
         * The whole of the matrix maths this backend needs: design coordinates onto the clip cube.
         *
         * Column-major, because that is what OpenGL reads, and the four values that are not one or
         * zero are the scale and the shift that put the origin in the bottom-left corner.
         */
        fun orthographic(into: FloatArray, width: Float, height: Float) {
            into.fill(0f)
            into[0] = 2f / width
            into[5] = 2f / height
            into[10] = -1f
            into[12] = -1f
            into[13] = -1f
            into[15] = 1f
        }
    }
}
