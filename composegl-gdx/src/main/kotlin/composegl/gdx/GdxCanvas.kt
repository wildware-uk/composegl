package composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.Batch
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
        Gdx.gl.glViewport(
            viewport.origin.x.roundToInt(),
            // GL counts up from the bottom of the window; the viewport counts down from the top.
            (viewport.physical.height - viewport.origin.y - viewport.design.height * viewport.scaleY).roundToInt(),
            (viewport.design.width * viewport.scaleX).roundToInt(),
            (viewport.design.height * viewport.scaleY).roundToInt(),
        )
        projection.setToOrtho2D(0f, 0f, viewport.design.width, viewport.design.height)
        batch.begin(projection)
    }

    /** Ends the frame and puts the GL state back the way a game expects to find it. */
    fun end() {
        check(drawing) { "end() without a begin()" }

        batch.end()
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
        Gdx.gl.glViewport(0, 0, viewport.physical.width.roundToInt(), viewport.physical.height.roundToInt())
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

    override fun text(layout: TextLayout, at: Offset, colour: Colour) {
        if (state.isHidden) return
        val gdx = layout as? GdxTextLayout
            ?: error("this canvas can only draw text measured by GdxFonts, not ${layout::class}")

        val packed = colour.packed(state.alpha)
        val regions = gdx.font.regions
        val data = gdx.font.data
        val top = flip(at.y)

        gdx.glyphs.runs.forEach { run ->
            var x = at.x + run.x
            val y = top + run.y
            for (index in 0 until run.glyphs.size) {
                val glyph = run.glyphs[index]
                x += run.xAdvances[index]
                val region = regions[glyph.page]
                batch.textured(
                    texture = region.texture,
                    left = x + glyph.xoffset * data.scaleX,
                    bottom = y + glyph.yoffset * data.scaleY,
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
    }

    /** A y measured down from the top becomes one measured up from the bottom. */
    private fun flip(y: Float) = viewport.design.height - y

    /** The colour LibGDX wants: four bytes squeezed into a float, alpha already multiplied. */
    private fun Colour.packed(alpha: Float): Float = Color.toFloatBits(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
        (((argb ushr 24) and 0xFF) * alpha).toInt().coerceIn(0, 255),
    )
}
