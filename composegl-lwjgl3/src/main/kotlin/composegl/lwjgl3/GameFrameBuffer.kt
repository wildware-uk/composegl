package composegl.lwjgl3

import composegl.ComposeGlContext
import composegl.GameTexture
import org.jetbrains.skia.SurfaceOrigin
import org.lwjgl.opengl.GL32C
import java.nio.ByteBuffer

/**
 * A framebuffer for the game to render into, so its picture can appear inside the Compose UI.
 *
 * This is editor mode: Compose owns the window, and the game draws into a viewport that is one
 * node in the layout. Bind [framebufferId], draw your frame, and Compose shows it — same GL
 * context, no copy, no upload.
 *
 * ```kotlin
 * val viewport = GameFrameBuffer(1280, 720)
 *
 * ui.setContent {
 *     Row {
 *         ToolPalette(Modifier.width(240.dp))
 *         GameView(viewport.texture, Modifier.weight(1f).fillMaxHeight())
 *     }
 * }
 *
 * while (running) {
 *     viewport.bind()
 *     drawWorld()
 *     viewport.unbind()
 *     ui.update(); ui.draw()
 * }
 * ```
 *
 * The colour texture belongs to Skia once [texture] exists, so this class never deletes it — see
 * [GameTexture]. [close] and [resize] go through the texture, which does the deleting.
 */
class GameFrameBuffer(
    width: Int,
    height: Int,
    private val context: ComposeGlContext = ComposeLwjgl.context(),
) : AutoCloseable {

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    private var frameBuffer = 0
    private var depthStencil = 0

    /** Hand this to [composegl.GameView]. Replaced by [resize], so read it fresh each frame. */
    var texture: GameTexture private set

    /** The framebuffer to bind before drawing your frame. */
    val framebufferId: Int get() = frameBuffer

    init {
        texture = create(width, height)
    }

    /** Binds the framebuffer and sets the viewport to its size. */
    fun bind() {
        GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, frameBuffer)
        GL32C.glViewport(0, 0, width, height)
    }

    /**
     * Puts the window's framebuffer back, and tells Compose the viewport has a new frame — which
     * is the only way it could know.
     */
    fun unbind(windowWidth: Int = width, windowHeight: Int = height) {
        GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, 0)
        GL32C.glViewport(0, 0, windowWidth, windowHeight)
        texture.invalidate()
    }

    /** Recreates everything at a new size and returns the new [texture]. */
    fun resize(newWidth: Int, newHeight: Int): GameTexture {
        if (newWidth == width && newHeight == height) return texture
        release()
        texture = create(newWidth, newHeight)
        return texture
    }

    override fun close() = release()

    private fun create(newWidth: Int, newHeight: Int): GameTexture {
        require(newWidth > 0 && newHeight > 0) { "got ${newWidth}x$newHeight" }
        width = newWidth
        height = newHeight

        val colorTexture = GL32C.glGenTextures()
        GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, colorTexture)
        GL32C.glTexImage2D(
            GL32C.GL_TEXTURE_2D, 0, GL32C.GL_RGBA8, newWidth, newHeight, 0,
            GL32C.GL_RGBA, GL32C.GL_UNSIGNED_BYTE, null as ByteBuffer?,
        )
        GL32C.glTexParameteri(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MIN_FILTER, GL32C.GL_LINEAR)
        GL32C.glTexParameteri(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MAG_FILTER, GL32C.GL_LINEAR)

        frameBuffer = GL32C.glGenFramebuffers()
        GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, frameBuffer)
        GL32C.glFramebufferTexture2D(
            GL32C.GL_FRAMEBUFFER, GL32C.GL_COLOR_ATTACHMENT0, GL32C.GL_TEXTURE_2D, colorTexture, 0,
        )

        // A game viewport wants depth; the stencil comes along because packed is what drivers like.
        depthStencil = GL32C.glGenRenderbuffers()
        GL32C.glBindRenderbuffer(GL32C.GL_RENDERBUFFER, depthStencil)
        GL32C.glRenderbufferStorage(GL32C.GL_RENDERBUFFER, GL32C.GL_DEPTH24_STENCIL8, newWidth, newHeight)
        GL32C.glFramebufferRenderbuffer(
            GL32C.GL_FRAMEBUFFER, GL32C.GL_DEPTH_STENCIL_ATTACHMENT, GL32C.GL_RENDERBUFFER, depthStencil,
        )

        val status = GL32C.glCheckFramebufferStatus(GL32C.GL_FRAMEBUFFER)
        check(status == GL32C.GL_FRAMEBUFFER_COMPLETE) {
            "Could not create a ${newWidth}x$newHeight game framebuffer: status 0x${status.toString(16)}"
        }
        GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, 0)
        GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, 0)
        GL32C.glBindRenderbuffer(GL32C.GL_RENDERBUFFER, 0)

        // OpenGL's origin is the bottom-left, and so is what a game renders here.
        return GameTexture.adopt(context, colorTexture, newWidth, newHeight, SurfaceOrigin.BOTTOM_LEFT)
    }

    private fun release() {
        // Deliberately no glDeleteTextures: the colour texture belongs to Skia now.
        texture.close()
        if (depthStencil != 0) GL32C.glDeleteRenderbuffers(depthStencil)
        if (frameBuffer != 0) GL32C.glDeleteFramebuffers(frameBuffer)
        depthStencil = 0
        frameBuffer = 0
    }
}
