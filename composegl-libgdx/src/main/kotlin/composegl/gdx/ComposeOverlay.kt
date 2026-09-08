package composegl.gdx

import androidx.compose.runtime.Composable
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.utils.Disposable
import composegl.ComposeSurface
import composegl.RenderTarget
import composegl.SurfaceConfig
import composegl.SurfaceStats

/**
 * A screen-sized Compose layer over the game. Feels like a scene2d `Stage`.
 *
 * ```kotlin
 * override fun create() {
 *     ui = ComposeOverlay()
 *     ui.setContent { Hud(viewModel) }
 *     Gdx.input.inputProcessor = InputMultiplexer(ui, gameInput)   // UI gets first refusal
 * }
 * override fun resize(w: Int, h: Int) = ui.resize(w, h)
 * override fun render() { drawWorld(); ui.update(); ui.draw() }
 * override fun dispose() { ui.dispose(); ComposeGdx.dispose() }
 * ```
 *
 * Compose draws into an offscreen framebuffer and only when something changed; [draw] blits that
 * framebuffer as one quad every frame. A HUD that is not changing therefore costs one quad per
 * frame and no Compose work at all — see [stats] if you want to watch that happen.
 */
class ComposeOverlay(config: SurfaceConfig = SurfaceConfig()) : Disposable {

    private val context = ComposeGdx.context()
    private val surface = ComposeSurface(context, ComposeGdx.hostServices, config)
    private val batch = SpriteBatch()

    private var frameBuffer: FrameBuffer? = null
    private var region: TextureRegion? = null

    init {
        resizeTo(Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
    }

    fun setContent(content: @Composable () -> Unit) = surface.setContent(content)

    /**
     * Call from `ApplicationListener.resize`.
     *
     * The arguments are the logical window size, which is what LibGDX reports; the framebuffer is
     * sized to the backbuffer instead, because on an HDPI display that is larger. A zero in either
     * argument means the window is minimised: the scene is kept and nothing is drawn until a real
     * size comes back.
     */
    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            resizeTo(0, 0)
        } else {
            resizeTo(Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        }
    }

    /** Runs everything Compose has queued. Call once per frame, before [draw]. */
    fun update() = surface.update(System.nanoTime())

    /**
     * Redraws the UI if it changed, restores GL state, and blits the result over the game.
     *
     * The blit is premultiplied (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`) because that is how Skia
     * writes its pixels.
     */
    fun draw() {
        val region = this.region ?: return

        if (surface.needsRedraw) {
            surface.render(System.nanoTime())
            GlStateFirewall.reset()
            if (context.debugChecks) GlStateFirewall.reportErrors(surface.stats.composeRenders)
        }

        batch.projectionMatrix.setToOrtho2D(0f, 0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        batch.enableBlending()
        batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA)
        batch.begin()
        batch.draw(region, 0f, 0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        batch.end()
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
    }

    /** How much Compose work this overlay has actually done. */
    val stats: SurfaceStats get() = surface.stats

    /** True while a Compose node holds keyboard focus, so the game should not act on keys. */
    val hasKeyboardFocus: Boolean get() = surface.hasKeyboardFocus

    internal val composeSurface: ComposeSurface get() = surface

    override fun dispose() {
        surface.dispose()
        frameBuffer?.dispose()
        frameBuffer = null
        region = null
        batch.dispose()
    }

    private fun resizeTo(width: Int, height: Int) {
        frameBuffer?.dispose()
        frameBuffer = null
        region = null

        if (width <= 0 || height <= 0) {
            // A minimised window. Keep the scene, draw nothing, come back when there is a size.
            surface.setRenderTarget(RenderTarget.Gl(framebufferId = 0, width = 0, height = 0))
            return
        }

        // Packed depth-stencil: Skia needs the stencil for path clipping, and stencil-only
        // attachments are unreliable across drivers.
        val buffer = FrameBuffer(Pixmap.Format.RGBA8888, width, height, true, true)
        frameBuffer = buffer
        region = TextureRegion(buffer.colorBufferTexture)  // upright as-is; see S1-b
        surface.setRenderTarget(RenderTarget.Gl(buffer.framebufferHandle, width, height))
    }
}
