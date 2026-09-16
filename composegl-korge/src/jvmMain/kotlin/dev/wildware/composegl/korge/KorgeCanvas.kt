package dev.wildware.composegl.korge

import dev.wildware.composegl.render.AtlasTextLayout
import dev.wildware.composegl.render.FrameTarget
import dev.wildware.composegl.render.GlyphAtlas
import dev.wildware.composegl.render.RenderCanvas
import dev.wildware.composegl.render.TextureResolver
import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.HostState
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextLayout
import korlibs.graphics.gl.AGOpengl
import korlibs.korge.render.RenderContext

/**
 * The shared renderer, inside a KorGE render.
 *
 * All the drawing is [RenderCanvas]'s. This class only says which GL to call (the frame's `KmlGl`),
 * which textures it can draw ([KorgeTexture]) and what a game gets from `raw` (the frame's
 * [RenderContext]) — and it keeps KorGE's renderer honest. KorGE remembers the GL state it last set
 * and believes it, so the device hands the context back with [HostState.Restore]: it saves what KorGE
 * left, and puts every value back when the frame ends and around `raw`.
 *
 * A canvas draws into the framebuffer KorGE has bound — the window, or a texture a filter is drawing
 * into — so it needs that frame's [RenderContext]. [ComposeGlView] hands it over; a game drawing
 * through a canvas by hand sets [renderContext] around its frame, or calls the [begin] that takes one.
 *
 * Merely having a canvas needs no OpenGL, so a game object that owns one can be built in a plain test.
 *
 * @param atlas the fonts' glyph atlas, if there is one. Sharing it means solid colour and text come
 *   from the same texture, which is the difference between a screen costing forty draw calls and one.
 */
class KorgeCanvas private constructor(
    private val binding: KorgeKmlGl,
    atlas: GlyphAtlas?,
    private val resolver: Resolver,
) : RenderCanvas(GlDevice(binding, HostState.Restore), atlas, resolver, null) {

    constructor(atlas: GlyphAtlas? = null) : this(KorgeKmlGl(), atlas, Resolver())

    /** The frame's render context, for the plain [begin] the toolkit calls. Set it around a frame. */
    var renderContext: RenderContext? = null

    private var context: RenderContext? = null
    private var drewOn: AGOpengl? = null
    private var drewVersion = -1
    private var warmUpWanted = false

    /** Pictures by way of the frame's context, which binding them through KorGE needs. */
    private class Resolver : TextureResolver {
        var context: RenderContext? = null
        var ag: AGOpengl? = null

        override fun resolve(handle: dev.wildware.composegl.ui.graphics.TextureHandle) =
            (handle as? KorgeTexture)?.bind(checkNotNull(context), checkNotNull(ag))
    }

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
        val ag = context.ag as? AGOpengl
            ?: error("the interface draws through KorGE's OpenGL renderer, and this context has ${context.ag::class.simpleName}")
        // KorGE's own batch may be holding quads the game queued before this; they go first, so the
        // interface lands on top of them.
        context.flush()
        // KorGE binds a framebuffer when it next draws into it. Bound now, through KorGE, so the
        // state the device saves is the framebuffer this frame is really for.
        val framebuffer = context.currentFrameBuffer
        ag.bindFrameBuffer(framebuffer.base, framebuffer.info)

        binding.gl = ag.gl
        if (drewOn != null && (drewOn !== ag || drewVersion != ag.contextVersion)) contextLost()
        drewOn = ag
        drewVersion = ag.contextVersion
        resolver.context = context
        resolver.ag = ag
        this.context = context
        try {
            // KorGE draws into a texture the other way up and reads it back that way too.
            begin(viewport, FrameTarget.Host, clear = null, topRowFirst = framebuffer.isTexture && context.flipRenderTexture)
            if (warmUpWanted) {
                warmUpWanted = false
                super.warmUp()
            }
        } catch (failure: Throwable) {
            this.context = null
            binding.gl = null
            throw failure
        }
    }

    override fun end() {
        checkNotNull(context) { "end() without a begin()" }
        try {
            super.end()
        } finally {
            context = null
            resolver.context = null
            binding.gl = null
        }
    }

    /**
     * Builds what can be built now. The GL half needs KorGE's context, so outside a frame it is
     * built at the start of the next one.
     */
    override fun warmUp() {
        if (context != null) super.warmUp() else warmUpWanted = true
    }

    override val warmedUp: Boolean get() = warmUpWanted || super.warmedUp

    override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) = super.text(measured(layout), x, y, colour)

    override fun textRing(layout: TextLayout, x: Float, y: Float, colour: Colour) = super.textRing(measured(layout), x, y, colour)

    private fun measured(layout: TextLayout): TextLayout =
        layout as? AtlasTextLayout ?: error("this canvas can only draw text measured by KorgeFonts, not ${layout::class}")

    // --- scenes ---

    /**
     * Not yet on KorGE: the device only has KorGE's GL while a frame is open, and a scene is
     * rendered before the frame. A `SceneView` here shows nothing rather than failing; the
     * handover that makes it work is issue #228.
     */
    override val drawsScenes: Boolean get() = false

    override fun scene(
        surface: dev.wildware.composegl.ui.graphics.SceneSurface?,
        width: Int,
        height: Int,
        draw: (dev.wildware.composegl.ui.graphics.SceneTarget) -> Unit,
    ): dev.wildware.composegl.ui.graphics.SceneSurface? = null

    // --- the escape hatch ---

    /**
     * The frame's [RenderContext], with this canvas's quads already on the screen.
     *
     * KorGE's batch is flushed again afterwards, while KorGE's own state is still in force, so
     * whatever the block queued lands under whatever the interface draws next rather than on top of
     * it. The block draws in KorGE's coordinates — the stage's — not the design's.
     */
    override fun raw(block: (Any) -> Unit) {
        val context = checkNotNull(context) { "raw() outside a frame" }
        super.raw { lent ->
            block(lent)
            context.flush()
        }
    }

    /** KorGE draws a raw block in its own coordinates, so there is no origin to move. */
    override fun raw(destination: Rect, block: (Any) -> Unit) = raw(block)

    override val movesRawOrigin: Boolean get() = false

    override fun rawX(x: Float): Float = x

    override fun rawY(y: Float): Float = y

    override fun handOver(projection: FloatArray, viewport: Viewport): Any = checkNotNull(context)
}
