package dev.wildware.composegl.korge

import dev.wildware.composegl.render.AtlasTextLayout
import dev.wildware.composegl.render.FrameTarget
import dev.wildware.composegl.render.GlyphAtlas
import dev.wildware.composegl.render.RenderCanvas
import dev.wildware.composegl.render.TextureResolver
import dev.wildware.composegl.render.gl.GlConst
import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.GlDeviceTarget
import dev.wildware.composegl.render.gl.HostState
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.SceneSurface
import dev.wildware.composegl.ui.graphics.SceneTarget
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextLayout
import korlibs.graphics.gl.AGOpengl
import korlibs.kgl.getIntegerv
import korlibs.korge.render.RenderContext

/**
 * The shared renderer, inside a KorGE render.
 *
 * All the drawing is [RenderCanvas]'s. This class only says which GL to call (the frame's `KmlGl`),
 * which textures it can draw ([KorgeTexture]) and what a game gets from `raw` (the frame's
 * [RenderContext]) — and it keeps KorGE's renderer honest. KorGE remembers the GL state it last set
 * and believes it, so the device hands the context back with [HostState.Restore]: it saves what KorGE
 * left, and puts every value back when the frame ends and around `raw`. Around a scene's `raw`, where
 * the picture has to stay bound, KorGE is also made to forget what it remembers, so it sets what it
 * needs again.
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
    private var inScene = false

    /** Pictures by way of the frame's context, which binding them through KorGE needs. */
    private class Resolver : TextureResolver {
        var context: RenderContext? = null
        var ag: AGOpengl? = null

        override fun resolve(handle: dev.wildware.composegl.ui.graphics.TextureHandle) = when (handle) {
            is KorgeTexture -> handle.bind(checkNotNull(context), checkNotNull(ag))
            is KorgeScenePicture -> handle.bind(checkNotNull(ag))
            else -> null
        }
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
        val ag = openGlOf(context)
        // KorGE's own batch may be holding quads the game queued before this; they go first, so the
        // interface lands on top of them.
        context.flush()
        // KorGE binds a framebuffer when it next draws into it. Bound now, through KorGE, so the
        // state the device saves is the framebuffer this frame is really for.
        val framebuffer = context.currentFrameBuffer
        ag.bindFrameBuffer(framebuffer.base, framebuffer.info)

        attach(context, ag)
        try {
            // KorGE draws into a texture the other way up and reads it back that way too.
            begin(viewport, FrameTarget.Host, clear = null, topRowFirst = framebuffer.isTexture && context.flipRenderTexture)
            if (warmUpWanted) {
                warmUpWanted = false
                super.warmUp()
            }
        } catch (failure: Throwable) {
            detach()
            throw failure
        }
    }

    /** The device on KorGE's GL, and pictures bound through [context], until [detach]. */
    private fun attach(context: RenderContext, ag: AGOpengl) {
        binding.gl = ag.gl
        if (drewOn != null && (drewOn !== ag || drewVersion != ag.contextVersion)) contextLost()
        drewOn = ag
        drewVersion = ag.contextVersion
        resolver.context = context
        resolver.ag = ag
        this.context = context
    }

    private fun detach() {
        context = null
        resolver.context = null
        binding.gl = null
    }

    private fun openGlOf(context: RenderContext): AGOpengl = context.ag as? AGOpengl
        ?: error("the interface draws through KorGE's OpenGL renderer, and this context has ${context.ag::class.simpleName}")

    override fun end() {
        checkNotNull(context) { "end() without a begin()" }
        try {
            super.end()
        } finally {
            detach()
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
     * Yes, inside a KorGE render: a scene is rendered through the frame's [renderContext], so it
     * has to be set, as it has to be for a frame. [ComposeGlView] sets it.
     */
    override val drawsScenes: Boolean get() = true

    /**
     * The device's biggest texture, read on the frame's [renderContext]: KorGE lends its GL only
     * while it renders, and the prepass asks before [scene] has attached it.
     */
    override val maxSceneSize: Int
        get() {
            if (this.context != null) return super.maxSceneSize
            val context = renderContext ?: return Int.MAX_VALUE
            attach(context, openGlOf(context))
            try {
                return super.maxSceneSize
            } finally {
                detach()
            }
        }

    /**
     * Renders a game's scene into a KorGE framebuffer with depth and stencil, before the frame.
     *
     * The picture is KorGE's own framebuffer rather than one the device makes, because that is the
     * only kind KorGE's batch draws into: it is pushed onto [renderContext]'s framebuffer stack for
     * the scene, so a `raw` block handed the context draws into it with `useBatcher`, a view's
     * `render(ctx)`, or anything else that draws through the context. Everything else — the clear,
     * the viewport, handing KorGE's GL state back — is the shared renderer's, exactly as on every
     * other frontend.
     *
     * The picture is the way up KorGE draws a render texture, so what the game drew with the
     * context comes out in the panel the way it drew it.
     */
    override fun scene(surface: SceneSurface?, width: Int, height: Int, draw: (SceneTarget) -> Unit): SceneSurface? {
        check(this.context == null) { "scene() inside a frame: scenes are rendered before the frame begins, not in the middle of it" }
        if (width <= 0 || height <= 0) return surface
        val context = checkNotNull(renderContext) {
            "this canvas renders scenes inside a KorGE render: set renderContext for the frame, or use ComposeGlView"
        }
        val ag = openGlOf(context)
        // What the game queued before the interface goes where the game meant it to.
        context.flush()
        attach(context, ag)
        try {
            if (!device.limits.offscreen) return null
            val most = device.limits.maxTextureSize
            val picture = (surface as? KorgeScenePicture)?.takeIf { !it.closed && it.canvas === this } ?: KorgeScenePicture(this)
            picture.frameBuffer.setSize(width.coerceAtMost(most), height.coerceAtMost(most))
            picture.topRowFirst = context.flipRenderTexture
            context.pushFrameBuffer(picture.frameBuffer)
            try {
                // Bound through KorGE, which makes its GL objects and keeps its record of them true.
                val frameBuffer = context.currentFrameBuffer
                ag.bindFrameBuffer(frameBuffer.base, frameBuffer.info)
                val name = ag.gl.getIntegerv(GlConst.FRAMEBUFFER_BINDING)
                inScene = true
                renderScene(GlDeviceTarget.adopt(name, 0, picture.width, picture.height, depth = true), draw)
            } finally {
                inScene = false
                context.popFrameBuffer()
            }
            return picture
        } finally {
            detach()
        }
    }

    // --- the escape hatch ---

    /**
     * The frame's [RenderContext], with this canvas's quads already on the screen.
     *
     * KorGE's batch is flushed again afterwards, while KorGE's own state is still in force, so
     * whatever the block queued lands under whatever the interface draws next rather than on top of
     * it. The block draws in KorGE's coordinates — the stage's — not the design's.
     */
    override fun raw(block: (Any) -> Unit) {
        checkNotNull(context) { "raw() outside a frame" }
        super.raw(block)
    }

    /**
     * Runs the block, then flushes KorGE's batch while KorGE's own state is still in force, so what
     * it queued is drawn before the device takes the context back — onto the frame, or onto a
     * scene's picture.
     *
     * In a scene KorGE is also made to forget the GL state it remembers, before the block and after
     * it. Before, because the scene's picture, viewport and scissor are not what KorGE last set.
     * After, because a raw GL call inside the block — behind KorGE's back — may have left something
     * KorGE will not know to set again, and the scene's framebuffer, viewport and scissor are the
     * engine's own again when it ends. KorGE then sets everything it needs the next time it draws,
     * as it does at the start of every frame.
     */
    override fun lend(lent: Any, projection: FloatArray, block: (Any) -> Unit) {
        val context = checkNotNull(context)
        if (inScene) forget(context)
        block(lent)
        context.flush()
        if (inScene) forget(context)
    }

    /**
     * KorGE's own way to forget what it remembers, the one it takes at the start of every frame.
     * That also reads the bound framebuffer as the window's, which inside a scene it is not, so the
     * window's is put back; and it limits the texture on unit 0 to one mip level, so no texture of
     * the game's is bound there when it does.
     */
    private fun forget(context: RenderContext) {
        val ag = openGlOf(context)
        val window = ag.backBufferFrameBufferBinding
        ag.gl.activeTexture(GlConst.TEXTURE0)
        ag.gl.bindTexture(GlConst.TEXTURE_2D, 0)
        ag.startFrame()
        ag.backBufferFrameBufferBinding = window
    }

    /** KorGE draws a raw block in its own coordinates, so there is no origin to move. */
    override fun raw(destination: Rect, block: (Any) -> Unit) = raw(block)

    override val movesRawOrigin: Boolean get() = false

    override fun rawX(x: Float): Float = x

    override fun rawY(y: Float): Float = y

    override fun handOver(projection: FloatArray, viewport: Viewport): Any = checkNotNull(context)
}
