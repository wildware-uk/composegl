package composegl

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

/**
 * @param fontScale multiplies every `sp` size, on top of the host's density.
 * @param onError called when the content throws during composition, layout, or draw. The default
 *   rethrows, so a bug in a HUD is loud. If the handler returns instead, the surface is marked
 *   failed and the last good frame keeps being shown.
 */
data class SurfaceConfig(
    val fontScale: Float = 1f,
    val onError: (Throwable) -> Unit = { throw it },
)

/**
 * How much work the surface has actually done. The point of ComposeGL is that a static HUD
 * costs no Compose renders at all, and this is how you see that.
 */
data class SurfaceStats(
    /** Times Compose actually redrew. */
    val composeRenders: Long = 0,
    /** Times [ComposeSurface.update] ran. */
    val frames: Long = 0,
    /** Wall time of the last redraw. */
    val lastRenderNanos: Long = 0,
)

/**
 * One Compose layer: a scene, a render target, and the plumbing that drives them from a game
 * loop.
 *
 * The whole lifecycle is: [setContent] once, [setRenderTarget] on creation and every resize,
 * then [update] and — when [needsRedraw] says so — [render] each frame.
 *
 * Every method must be called on the thread the [ComposeGlContext] was created on.
 */
class ComposeSurface(
    private val context: ComposeGlContext,
    private val host: HostServices,
    private val config: SurfaceConfig = SurfaceConfig(),
) {

    private val bridge = SceneBridge(
        dispatcher = context.dispatcher,
        host = host,
        fontScale = config.fontScale,
        invalidate = host::requestFrame,
    )

    private var skiaSurface: Surface? = null
    private var backendTarget: BackendRenderTarget? = null

    /** The target is remembered even when it is too small to make a surface for. */
    private var target: RenderTarget? = null
    private var targetChanged = false

    private var hasContent = false
    private var failed = false
    private var disposed = false

    private var composeRenders = 0L
    private var frames = 0L
    private var lastRenderNanos = 0L

    init {
        context.register(this)
    }

    /** True once content has thrown and [SurfaceConfig.onError] chose not to rethrow. */
    val isFailed: Boolean get() = failed

    val stats: SurfaceStats
        get() = SurfaceStats(composeRenders, frames, lastRenderNanos)

    /** Replaces the content. Clears the failed state, so this is also how you recover from one. */
    fun setContent(content: @Composable () -> Unit) {
        context.assertGlThread()
        checkOpen()
        failed = false
        hasContent = true
        targetChanged = true
        // Composition runs here, so content that throws on its very first pass is caught too.
        guard { bridge.setContent(content) }
    }

    /**
     * Points the surface at an engine-owned target. Call it once on creation and again on every
     * size or density change; it recreates the Skia surface.
     *
     * A zero-sized target (a minimised window) is legal: the scene is kept, no Skia surface
     * exists, and [render] does nothing until a real size arrives.
     */
    fun setRenderTarget(target: RenderTarget) {
        context.assertGlThread()
        checkOpen()
        require(target.width >= 0 && target.height >= 0) {
            "Render target size cannot be negative, got ${target.width}x${target.height}"
        }
        if (target is RenderTarget.Gl && context.directContext == null) {
            throw ComposeGlUnsupportedException(
                "This ComposeGlContext was created with createRaster(), so it has no GPU context " +
                    "and cannot draw into an OpenGL framebuffer. Use ComposeGlContext.create().",
            )
        }

        this.target = target
        releaseSkiaSurface()
        targetChanged = true

        // Density can change without the size changing — a window dragged to another monitor.
        bridge.setDensity(host.density, config.fontScale)
        bridge.setSize(target.width, target.height)

        if (target.width == 0 || target.height == 0) return
        skiaSurface = createSkiaSurface(target)
    }

    private fun createSkiaSurface(target: RenderTarget): Surface = when (target) {
        is RenderTarget.Raster -> Surface.makeRaster(
            ImageInfo.makeN32(target.width, target.height, ColorAlphaType.PREMUL, ColorSpace.sRGB),
        )

        is RenderTarget.Gl -> {
            val backend = BackendRenderTarget.makeGL(
                target.width,
                target.height,
                target.sampleCount,
                target.stencilBits,
                target.framebufferId,
                FramebufferFormat.GR_GL_RGBA8,
            )
            backendTarget = backend
            Surface.makeFromBackendRenderTarget(
                context.directContext!!,
                backend,
                // S1-b: TOP_LEFT is what makes an unflipped engine texture come out upright.
                SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB,
            ) ?: throw ComposeGlUnsupportedException(
                "Skia could not open framebuffer ${target.framebufferId} " +
                    "(${target.width}x${target.height}, ${target.stencilBits} stencil bits). " +
                    "Allocate the FBO with a packed depth-stencil attachment.",
            )
        }
    }

    /**
     * Runs everything Compose has queued and lets it recompose and lay out. Call it once per game
     * frame, before [render].
     *
     * @param frameTimeNanos drives Compose animations. Pass the game's frame time; if the game
     *   stops calling this, animations freeze, which is the correct behaviour for a paused game.
     */
    fun update(frameTimeNanos: Long) {
        context.assertGlThread()
        if (disposed || failed) return
        frames++
        guard {
            context.dispatcher.drain()
            bridge.performFrame(frameTimeNanos)
            context.dispatcher.drain()
            bridge.measureAndLayout()
        }
    }

    /**
     * True when the last drawn frame is stale. False for static content, which is the whole point:
     * a HUD that is not changing costs one blit per frame and no Compose work at all.
     */
    val needsRedraw: Boolean
        get() = !disposed && !failed && skiaSurface != null && (targetChanged || bridge.hasInvalidations)

    /**
     * Draws into the render target. The caller binds nothing — Skia binds the framebuffer itself —
     * but **must** restore GL state afterwards; see [RenderTarget.Gl].
     */
    fun render(frameTimeNanos: Long) {
        context.assertGlThread()
        if (disposed || failed) return
        val surface = skiaSurface ?: return
        val started = System.nanoTime()
        guard {
            // The engine has been drawing with the same GL context, so Skia's cached state is a lie.
            context.directContext?.resetAll()
            surface.canvas.clear(TRANSPARENT)
            bridge.draw(surface.canvas)
            context.directContext?.flush()
        }
        targetChanged = false
        composeRenders++
        lastRenderNanos = System.nanoTime() - started
    }

    /**
     * Reads the rendered pixels back as premultiplied ARGB, row by row from the top.
     * Only meaningful for [RenderTarget.Raster]; returns null when there is no surface.
     */
    fun readPixels(): IntArray? {
        val surface = skiaSurface ?: return null
        val info = ImageInfo.makeN32(surface.width, surface.height, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        val bitmap = Bitmap()
        bitmap.allocPixels(info)
        try {
            if (!surface.readPixels(bitmap, 0, 0)) return null
            val bytes = bitmap.readPixels(info, info.minRowBytes, 0, 0) ?: return null
            val out = IntArray(bytes.size / 4)
            for (i in out.indices) {
                val b = bytes[i * 4].toInt() and 0xff
                val g = bytes[i * 4 + 1].toInt() and 0xff
                val r = bytes[i * 4 + 2].toInt() and 0xff
                val a = bytes[i * 4 + 3].toInt() and 0xff
                out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return out
        } finally {
            bitmap.close()
        }
    }

    /** Frees the scene and the Skia surface. Safe to call twice. */
    fun dispose() {
        if (disposed) return
        disposed = true
        bridge.close()
        releaseSkiaSurface()
        context.unregister(this)
    }

    private fun releaseSkiaSurface() {
        skiaSurface?.close()
        skiaSurface = null
        backendTarget?.close()
        backendTarget = null
    }

    private fun checkOpen() {
        check(!disposed) { "This ComposeSurface has been disposed" }
    }

    /**
     * Content that throws must not take the game down by default, and must not leave a
     * half-drawn scene being re-entered every frame.
     */
    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            failed = true
            config.onError(t)
        }
    }

    private companion object {
        val TRANSPARENT = Color.Transparent.toArgb()
    }
}
