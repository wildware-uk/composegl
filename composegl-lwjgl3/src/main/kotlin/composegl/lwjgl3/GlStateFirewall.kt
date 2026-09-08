package composegl.lwjgl3

import org.lwjgl.opengl.GL32C

/**
 * Puts OpenGL back to defaults after Skia has drawn.
 *
 * Skia caches GL state and assumes nothing else touched it, and so does whatever draws your game.
 * Skia is told its cache is stale before it draws; this is the other half of that deal.
 *
 * Skipping it does not look like a UI bug. It looks like a world that renders black every other
 * frame, or your shader drawing with Skia's blend mode.
 */
object GlStateFirewall {

    private val reported = mutableSetOf<Int>()

    /**
     * @param viewportWidth the framebuffer you want left bound and set up — normally the window's.
     */
    fun reset(viewportWidth: Int, viewportHeight: Int) {
        GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, 0)
        GL32C.glViewport(0, 0, viewportWidth, viewportHeight)
        GL32C.glUseProgram(0)
        GL32C.glBindVertexArray(0)
        GL32C.glBindBuffer(GL32C.GL_ARRAY_BUFFER, 0)
        GL32C.glBindBuffer(GL32C.GL_ELEMENT_ARRAY_BUFFER, 0)
        GL32C.glActiveTexture(GL32C.GL_TEXTURE0)
        GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, 0)
        GL32C.glDisable(GL32C.GL_SCISSOR_TEST)
        GL32C.glDisable(GL32C.GL_STENCIL_TEST)
        GL32C.glDepthMask(true)
        GL32C.glColorMask(true, true, true, true)
        GL32C.glDisable(GL32C.GL_BLEND)
        GL32C.glBlendFunc(GL32C.GL_SRC_ALPHA, GL32C.GL_ONE_MINUS_SRC_ALPHA)
        GL32C.glPixelStorei(GL32C.GL_UNPACK_ALIGNMENT, 4)
    }

    /** Reports GL errors once per code, so a driver problem is one line rather than a flood. */
    fun reportErrors(composeRenders: Long, log: (String) -> Unit) {
        while (true) {
            val error = GL32C.glGetError()
            if (error == GL32C.GL_NO_ERROR) return
            if (reported.add(error)) {
                log(
                    "ComposeGL: glGetError() returned 0x${error.toString(16)} after Compose " +
                        "render #$composeRenders. Reported once per error code.",
                )
            }
        }
    }
}
