package composegl.lwjgl3

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerEventType
import composegl.ComposeGlContext
import composegl.ComposeSurface
import composegl.RenderTarget
import composegl.SurfaceConfig
import composegl.SurfaceStats
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL32C
import java.nio.ByteBuffer

/**
 * A window-sized Compose layer over a raw LWJGL3 game.
 *
 * ```kotlin
 * val ui = ComposeOverlay(window)
 * ui.setContent { Hud(state) }
 * ui.installCallbacks()          // or call the onXxx methods from your own callbacks
 *
 * while (!glfwWindowShouldClose(window)) {
 *     drawWorld()
 *     ui.update()
 *     ui.draw()
 *     glfwSwapBuffers(window)
 *     glfwPollEvents()
 * }
 * ui.dispose(); ComposeLwjgl.dispose()
 * ```
 *
 * Compose draws into an offscreen framebuffer and only when something changed; [draw] blits it as
 * one quad. A HUD that is not changing costs one quad per frame and no Compose work — see [stats].
 *
 * Every input method returns whether Compose took the event, so `if (ui.onMouseButton(...)) return`
 * is all the click-through your game needs.
 */
class ComposeOverlay(
    private val window: Long,
    config: SurfaceConfig = SurfaceConfig(),
) : AutoCloseable {

    private val context: ComposeGlContext = ComposeLwjgl.context()
    private val host = GlfwHostServices(window)
    private val surface = ComposeSurface(context, host, config)
    private val blit = FullScreenBlit()

    private var frameBuffer = 0
    private var colorTexture = 0
    private var depthStencil = 0
    private var width = 0
    private var height = 0

    private var cursorX = 0f
    private var cursorY = 0f
    private var installed = false

    init {
        resizeToFrameBuffer()
    }

    fun setContent(content: @Composable () -> Unit) = surface.setContent(content)

    /** Call from your framebuffer size callback, or leave [installCallbacks] to do it. */
    fun resize() = resizeToFrameBuffer()

    /** Runs everything Compose has queued. Call once per frame, before [draw]. */
    fun update() = surface.update(System.nanoTime())

    /** Redraws the UI if it changed, restores GL state, then blits it over your frame. */
    fun draw() {
        if (width <= 0 || height <= 0) return

        if (surface.needsRedraw) {
            surface.render(System.nanoTime())
            GlStateFirewall.reset(width, height)
            if (context.debugChecks) {
                GlStateFirewall.reportErrors(surface.stats.composeRenders) { System.err.println(it) }
            }
        }
        // Skia writes premultiplied pixels.
        blit.draw(colorTexture, width, height)
    }

    val stats: SurfaceStats get() = surface.stats

    /** True while a Compose node holds keyboard focus, so your game should ignore keys. */
    val hasKeyboardFocus: Boolean get() = surface.hasKeyboardFocus

    // --- Input. Call these from your own GLFW callbacks, or use installCallbacks().
    // --- Each returns true when Compose consumed the event.

    fun onCursorPos(x: Double, y: Double): Boolean {
        val scale = host.density
        cursorX = x.toFloat() * scale
        cursorY = y.toFloat() * scale
        return surface.sendPointerEvent(PointerEventType.Move, cursorX, cursorY)
    }

    fun onMouseButton(button: Int, action: Int, mods: Int): Boolean = surface.sendPointerEvent(
        type = if (action == GLFW.GLFW_PRESS) PointerEventType.Press else PointerEventType.Release,
        x = cursorX,
        y = cursorY,
        button = composeButtonFor(button),
        modifiers = modifiersOf(mods),
    )

    fun onScroll(deltaX: Double, deltaY: Double): Boolean = surface.sendPointerEvent(
        type = PointerEventType.Scroll,
        x = cursorX,
        y = cursorY,
        // GLFW's wheel points up; Compose's scroll delta points down.
        scrollX = -deltaX.toFloat(),
        scrollY = -deltaY.toFloat(),
    )

    fun onKey(key: Int, action: Int, mods: Int): Boolean {
        // A held key repeats. Compose has no repeat event, so a repeat is another key down.
        val down = action != GLFW.GLFW_RELEASE
        val composeKey = composeKeyFor(key) ?: return false
        return surface.sendKeyEvent(composeKey, down = down, modifiers = modifiersOf(mods))
    }

    /** GLFW has already applied the layout and any dead keys, which is exactly what we want. */
    fun onChar(codePoint: Int): Boolean = surface.sendChar(codePoint)

    /** The pointer left the window, so nothing is hovered any more. */
    fun onCursorLeave(): Boolean {
        surface.cancelPointerInput()
        return false
    }

    /**
     * Installs GLFW callbacks that forward to the methods above and then to whatever callbacks
     * were already there, so your game still sees everything Compose did not take.
     */
    fun installCallbacks() {
        check(!installed) { "callbacks are already installed on this window" }
        installed = true

        val previousCursorPos = GLFW.glfwSetCursorPosCallback(window, null)
        GLFW.glfwSetCursorPosCallback(window) { w, x, y ->
            if (!onCursorPos(x, y)) previousCursorPos?.invoke(w, x, y)
        }

        val previousMouseButton = GLFW.glfwSetMouseButtonCallback(window, null)
        GLFW.glfwSetMouseButtonCallback(window) { w, button, action, mods ->
            if (!onMouseButton(button, action, mods)) previousMouseButton?.invoke(w, button, action, mods)
        }

        val previousScroll = GLFW.glfwSetScrollCallback(window, null)
        GLFW.glfwSetScrollCallback(window) { w, dx, dy ->
            if (!onScroll(dx, dy)) previousScroll?.invoke(w, dx, dy)
        }

        val previousKey = GLFW.glfwSetKeyCallback(window, null)
        GLFW.glfwSetKeyCallback(window) { w, key, scancode, action, mods ->
            if (!onKey(key, action, mods)) previousKey?.invoke(w, key, scancode, action, mods)
        }

        val previousChar = GLFW.glfwSetCharCallback(window, null)
        GLFW.glfwSetCharCallback(window) { w, codePoint ->
            if (!onChar(codePoint)) previousChar?.invoke(w, codePoint)
        }

        val previousSize = GLFW.glfwSetFramebufferSizeCallback(window, null)
        GLFW.glfwSetFramebufferSizeCallback(window) { w, width, height ->
            resize()
            previousSize?.invoke(w, width, height)
        }
    }

    override fun close() {
        surface.dispose()
        host.dispose()
        blit.dispose()
        releaseFrameBuffer()
    }

    private fun resizeToFrameBuffer() {
        val size = IntArray(2)
        val heightBuffer = IntArray(1)
        GLFW.glfwGetFramebufferSize(window, size, heightBuffer)
        resizeTo(size[0], heightBuffer[0])
    }

    private fun resizeTo(newWidth: Int, newHeight: Int) {
        releaseFrameBuffer()
        width = newWidth
        height = newHeight

        if (newWidth <= 0 || newHeight <= 0) {
            // A minimised window. Keep the scene, draw nothing, come back with a real size.
            surface.setRenderTarget(RenderTarget.Gl(framebufferId = 0, width = 0, height = 0))
            return
        }

        frameBuffer = GL32C.glGenFramebuffers()
        GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, frameBuffer)

        colorTexture = GL32C.glGenTextures()
        GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, colorTexture)
        GL32C.glTexImage2D(
            GL32C.GL_TEXTURE_2D, 0, GL32C.GL_RGBA8, newWidth, newHeight, 0,
            GL32C.GL_RGBA, GL32C.GL_UNSIGNED_BYTE, null as ByteBuffer?,
        )
        GL32C.glTexParameteri(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MIN_FILTER, GL32C.GL_LINEAR)
        GL32C.glTexParameteri(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MAG_FILTER, GL32C.GL_LINEAR)
        GL32C.glFramebufferTexture2D(
            GL32C.GL_FRAMEBUFFER, GL32C.GL_COLOR_ATTACHMENT0, GL32C.GL_TEXTURE_2D, colorTexture, 0,
        )

        // Packed depth-stencil: Skia needs the stencil for path clipping, and stencil-only
        // attachments are unreliable across drivers.
        depthStencil = GL32C.glGenRenderbuffers()
        GL32C.glBindRenderbuffer(GL32C.GL_RENDERBUFFER, depthStencil)
        GL32C.glRenderbufferStorage(GL32C.GL_RENDERBUFFER, GL32C.GL_DEPTH24_STENCIL8, newWidth, newHeight)
        GL32C.glFramebufferRenderbuffer(
            GL32C.GL_FRAMEBUFFER, GL32C.GL_DEPTH_STENCIL_ATTACHMENT, GL32C.GL_RENDERBUFFER, depthStencil,
        )

        val status = GL32C.glCheckFramebufferStatus(GL32C.GL_FRAMEBUFFER)
        check(status == GL32C.GL_FRAMEBUFFER_COMPLETE) {
            "ComposeGL could not create a ${newWidth}x$newHeight framebuffer: status 0x${status.toString(16)}"
        }

        GlStateFirewall.reset(newWidth, newHeight)
        surface.setRenderTarget(RenderTarget.Gl(frameBuffer, newWidth, newHeight))
    }

    private fun releaseFrameBuffer() {
        if (colorTexture != 0) GL32C.glDeleteTextures(colorTexture)
        if (depthStencil != 0) GL32C.glDeleteRenderbuffers(depthStencil)
        if (frameBuffer != 0) GL32C.glDeleteFramebuffers(frameBuffer)
        colorTexture = 0
        depthStencil = 0
        frameBuffer = 0
    }
}
