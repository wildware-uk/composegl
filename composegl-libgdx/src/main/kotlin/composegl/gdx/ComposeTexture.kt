package composegl.gdx

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.utils.Disposable
import composegl.ComposeSurface
import composegl.RenderTarget
import composegl.SurfaceConfig
import composegl.SurfaceStats

/**
 * A fixed-size Compose UI as a plain texture, for putting a panel inside the world — on a monitor
 * in a room, a tablet in the player's hands, a sign on a wall.
 *
 * Unlike [ComposeOverlay] this draws nothing on its own. You get a [texture] and put it wherever
 * you like, and because you decide where it is, you also decide what a click on it means: raycast
 * into the world, convert the hit to a pixel on this texture, and call [sendPointer].
 *
 * ```kotlin
 * val panel = ComposeTexture(512, 512)
 * panel.setContent { ControlRoomUi(state) }
 *
 * // each frame, before drawing the world
 * panel.update()
 * panel.render()
 * modelBatch.render(screenQuad)   // its material samples panel.texture
 *
 * // when the player clicks
 * val hit = raycast(ray) ?: return
 * panel.sendPointer(PointerEventType.Press, hit.u * 512, hit.v * 512, PointerButton.Primary)
 * ```
 *
 * The texture holds **premultiplied** alpha, so draw it with
 * `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`. It shares the one [ComposeGdx] context with
 * every overlay, so a game can have as many of these as it likes.
 */
class ComposeTexture(
    val width: Int,
    val height: Int,
    config: SurfaceConfig = SurfaceConfig(),
) : Disposable {

    init {
        require(width > 0 && height > 0) { "A ComposeTexture needs a real size, got ${width}x$height" }
    }

    private val surface = ComposeSurface(ComposeGdx.context(), ComposeGdx.hostServices, config)

    // Depth is unused by Skia, but LibGDX's stencil-only path is unreliable across drivers, so
    // ask for packed depth-stencil like everywhere else.
    private val frameBuffer = FrameBuffer(Pixmap.Format.RGBA8888, width, height, true, true)

    init {
        surface.setRenderTarget(RenderTarget.Gl(frameBuffer.framebufferHandle, width, height))
    }

    fun setContent(content: @Composable () -> Unit) = surface.setContent(content)

    /** Runs everything Compose has queued. Call once per frame, before [render]. */
    fun update() = surface.update(System.nanoTime())

    /**
     * Redraws the panel if it changed, then restores GL state. No blit: the texture is yours to
     * draw. Safe and cheap to call every frame — a panel nobody is touching costs nothing.
     */
    fun render() {
        if (!surface.needsRedraw) return
        surface.render(System.nanoTime())
        GlStateFirewall.reset()
    }

    /** Premultiplied alpha. Blend with `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`. */
    val texture: Texture get() = frameBuffer.colorBufferTexture

    /**
     * Delivers a pointer event in this texture's own pixels, with (0, 0) at the top-left.
     *
     * @return true when Compose consumed it, so the game knows whether the click also counts as
     *   a click on the object the panel is painted on.
     */
    fun sendPointer(
        type: PointerEventType,
        x: Float,
        y: Float,
        button: PointerButton? = null,
        pointerId: Int = 0,
    ): Boolean = surface.sendPointerEvent(
        type = type,
        x = x,
        y = y,
        pointerId = pointerId,
        button = button,
        pointerType = PointerType.Touch,
        modifiers = currentModifiers(),
    )

    /** @param keycode a LibGDX `Input.Keys` value. Unmapped keys are dropped. */
    fun sendKey(keycode: Int, down: Boolean): Boolean {
        val key = composeKeyFor(keycode) ?: return false
        return surface.sendKeyEvent(key, down = down, modifiers = currentModifiers())
    }

    fun sendChar(character: Char): Boolean = surface.sendChar(character.code)

    /** Drops any in-progress gesture — the player looked away mid-drag. */
    fun cancelPointerInput() = surface.cancelPointerInput()

    val stats: SurfaceStats get() = surface.stats

    val hasKeyboardFocus: Boolean get() = surface.hasKeyboardFocus

    override fun dispose() {
        surface.dispose()
        frameBuffer.dispose()
    }
}
