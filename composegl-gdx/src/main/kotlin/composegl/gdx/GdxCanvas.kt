package composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.graphics.CanvasState
import composegl.ui.graphics.Colour
import composegl.ui.graphics.TextureHandle
import composegl.ui.graphics.UiCanvas
import composegl.ui.layout.Viewport
import composegl.ui.text.TextLayout
import kotlin.math.roundToInt

/**
 * The LibGDX canvas: one `Batch` for the whole interface.
 *
 * Rectangles are a single white pixel stretched, so a panel, the border around it and the label on
 * it all come from the same batch with no texture switch between them. A hundred nodes cost a
 * handful of draw calls, and the ones they do cost are the font atlas changing.
 *
 * Two conventions meet here and this class is where they are reconciled, once:
 *
 * - the toolkit measures y downwards from the top; LibGDX measures it upwards from the bottom;
 * - the toolkit works in the design resolution; the screen is whatever size it is.
 *
 * The scale and the letterboxing are handled by the GL viewport rather than by arithmetic on every
 * call, so [raw] hands a game a batch set up in design coordinates — which is what a game's own
 * drawing code will expect.
 */
class GdxCanvas(private val batch: Batch) : UiCanvas, Disposable {

    private val white = Texture(Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
        setColor(Color.WHITE)
        fill()
    }).also { it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }

    private val whiteRegion = TextureRegion(white)

    private var state = CanvasState(Rect.Zero)
    private var viewport: Viewport = Viewport.oneToOne(composegl.ui.geometry.Size(1f, 1f))
    private var drawing = false

    private val savedProjection = Matrix4()
    private val savedColour = Color()
    private val scratch = Color()

    /** How many times the batch actually talked to the GPU during the last frame. */
    var renderCalls = 0
        private set

    /**
     * Sets the batch up to draw an interface, and takes a note of how it was so that [end] can put
     * it back. Everything between here and [end] is in design coordinates.
     */
    fun begin(viewport: Viewport) {
        check(!drawing) { "begin() was called twice without an end()" }
        drawing = true
        this.viewport = viewport
        state = CanvasState(Rect.of(0f, 0f, viewport.design.width, viewport.design.height))

        savedProjection.set(batch.projectionMatrix)
        savedColour.set(batch.color)

        // The letterbox and the scale live here, so nothing below has to think about them.
        Gdx.gl.glViewport(
            viewport.origin.x.roundToInt(),
            // GL counts up from the bottom of the window; the viewport counts down from the top.
            (viewport.physical.height - viewport.origin.y - viewport.design.height * viewport.scaleY).roundToInt(),
            (viewport.design.width * viewport.scaleX).roundToInt(),
            (viewport.design.height * viewport.scaleY).roundToInt(),
        )
        batch.projectionMatrix = Matrix4().setToOrtho2D(0f, 0f, viewport.design.width, viewport.design.height)
        batch.begin()
    }

    /** Ends the frame and puts the batch back exactly as it was handed over. */
    fun end() {
        check(drawing) { "end() without a begin()" }

        batch.end()
        renderCalls = (batch as? com.badlogic.gdx.graphics.g2d.SpriteBatch)?.renderCalls ?: 0

        batch.projectionMatrix = savedProjection
        batch.color = savedColour
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
        Gdx.gl.glViewport(0, 0, viewport.physical.width.roundToInt(), viewport.physical.height.roundToInt())
        drawing = false

        // Complained about last, so that an unbalanced frame still leaves the batch tidy for
        // whatever the game draws next.
        check(state.isBalanced) { "a clip or an alpha was pushed and never popped" }
    }

    // --- shapes ---

    override fun rect(rect: Rect, colour: Colour, corner: Float) {
        if (state.isHidden || rect.isEmpty) return
        fill(rect, colour)
    }

    override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) {
        if (state.isHidden || rect.isEmpty || width <= 0f) return
        val thickness = width.coerceAtMost(minOf(rect.width, rect.height) / 2f)
        // Four bars rather than an outline: the same texture, the same batch, no state change.
        fill(Rect(rect.left, rect.top, rect.right, rect.top + thickness), colour)
        fill(Rect(rect.left, rect.bottom - thickness, rect.right, rect.bottom), colour)
        fill(Rect(rect.left, rect.top + thickness, rect.left + thickness, rect.bottom - thickness), colour)
        fill(Rect(rect.right - thickness, rect.top + thickness, rect.right, rect.bottom - thickness), colour)
    }

    /**
     * A shadow, as rings of falling opacity.
     *
     * Honest about what it is: an approximation, and the cheapest one that does not look like a
     * grey box. The shader in the next ticket replaces it with a real distance falloff.
     */
    override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) {
        if (state.isHidden || spread <= 0f) return
        val rings = 4
        repeat(rings) { ring ->
            val reach = spread * (rings - ring) / rings
            val strength = (ring + 1).toFloat() / (rings * rings)
            fill(rect.inset(-reach), colour.scaleAlpha(strength))
        }
    }

    private fun fill(rect: Rect, colour: Colour) {
        tint(colour)
        batch.draw(whiteRegion, rect.left, flip(rect.bottom), rect.width, rect.height)
    }

    // --- text and pictures ---

    override fun text(layout: TextLayout, at: Offset, colour: Colour) {
        if (state.isHidden) return
        val gdx = layout as? GdxTextLayout
            ?: error("this canvas can only draw text measured by GdxFonts, not ${layout::class}")

        batch.setColor(Color.WHITE)
        gdx.font.color = colour.toGdx(scratch, state.alpha)
        gdx.font.draw(batch, gdx.glyphs, at.x, flip(at.y))
    }

    override fun image(texture: TextureHandle, destination: Rect, tint: Colour) {
        if (state.isHidden || destination.isEmpty) return
        val gdx = texture as? GdxTexture
            ?: error("this canvas can only draw textures it made, not ${texture::class}")

        tint(tint)
        batch.draw(gdx.region, destination.left, flip(destination.bottom), destination.width, destination.height)
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
     * The scissor follows the toolkit's clip, which has already intersected the nested clips. The
     * batch is flushed first: anything queued was queued under the old clip.
     */
    private fun applyScissor() {
        batch.flush()
        val clip = state.clip
        if (clip.left <= 0f && clip.top <= 0f &&
            clip.right >= viewport.design.width && clip.bottom >= viewport.design.height
        ) {
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
            return
        }

        val topLeft = viewport.toScreen(Offset(clip.left, clip.top))
        val bottomRight = viewport.toScreen(Offset(clip.right, clip.bottom))
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
        Gdx.gl.glScissor(
            topLeft.x.roundToInt(),
            (viewport.physical.height - bottomRight.y).roundToInt(),
            (bottomRight.x - topLeft.x).roundToInt().coerceAtLeast(0),
            (bottomRight.y - topLeft.y).roundToInt().coerceAtLeast(0),
        )
    }

    override fun raw(block: (Any) -> Unit) {
        block(batch)
        // A game's own drawing may have left anything at all set; put back what we rely on.
        batch.setColor(Color.WHITE)
    }

    override fun dispose() {
        white.dispose()
    }

    // --- the two conventions, reconciled ---

    /** A y measured down from the top becomes one measured up from the bottom. */
    private fun flip(y: Float) = viewport.design.height - y

    private fun tint(colour: Colour) {
        batch.color = colour.toGdx(scratch, state.alpha)
    }

    private fun Colour.toGdx(into: Color, alpha: Float): Color = into.set(
        ((argb shr 16) and 0xFF) / 255f,
        ((argb shr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
        ((argb ushr 24) and 0xFF) / 255f * alpha,
    )
}
