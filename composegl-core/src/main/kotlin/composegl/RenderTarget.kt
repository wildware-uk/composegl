package composegl

/**
 * What the engine gives Compose to draw into.
 *
 * The engine owns the target; ComposeGL only draws into it. Only OpenGL is implemented in v1 —
 * the sealed type is the door for Metal and Vulkan later, at no cost now.
 */
sealed interface RenderTarget {
    val width: Int
    val height: Int

    /**
     * An OpenGL framebuffer object owned by the engine.
     *
     * **The caller must restore GL state after [ComposeSurface.render].** Skia caches GL state and
     * assumes nothing else touched it; so does every engine. ComposeGL tells Skia its state is
     * stale before drawing, and the adapter is responsible for putting GL back to defaults
     * afterwards. Engines rebind what they need at each `begin()`, so defaults are enough:
     *
     * ```
     * glBindFramebuffer(GL_FRAMEBUFFER, 0)
     * glViewport(0, 0, backBufferWidth, backBufferHeight)
     * glUseProgram(0)
     * glBindVertexArray(0)
     * glBindBuffer(GL_ARRAY_BUFFER, 0); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0)
     * glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, 0)
     * glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST)
     * glDepthMask(true); glColorMask(true, true, true, true)
     * glDisable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
     * glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
     * ```
     *
     * Pixels come out **premultiplied**, so blit the result with
     * `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`.
     *
     * @param framebufferId the engine's FBO handle. 0 means the default framebuffer.
     * @param stencilBits Skia needs a stencil buffer for path clipping. Allocate the FBO with a
     *   packed depth-stencil attachment; stencil-only attachments are unreliable across drivers.
     * @param sampleCount leave at 0. Skia anti-aliases analytically; MSAA costs memory and buys
     *   nothing here.
     */
    data class Gl(
        val framebufferId: Int,
        override val width: Int,
        override val height: Int,
        val stencilBits: Int = 8,
        val sampleCount: Int = 0,
    ) : RenderTarget

    /**
     * A CPU bitmap. No GL context needed, so this is what the headless tests use, and it is also
     * the way to render a Compose frame on a machine with no GPU.
     *
     * Read the result back with [ComposeSurface.readPixels].
     */
    data class Raster(
        override val width: Int,
        override val height: Int,
    ) : RenderTarget
}
