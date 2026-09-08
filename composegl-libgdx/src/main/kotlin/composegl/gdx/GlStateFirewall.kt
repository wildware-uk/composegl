package composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30

/**
 * Puts OpenGL back to defaults after Skia has drawn.
 *
 * Skia and LibGDX both cache GL state and both assume nothing else touched it. Skia is told its
 * cache is stale before it draws (`DirectContext.resetAll()`); this is the other half. LibGDX
 * rebinds what it needs at each `begin()`, so restoring defaults is enough — we do not have to
 * save and restore the engine's exact state.
 *
 * Without this you get bugs that look like anything but a UI library: a world that renders black
 * every other frame, a sprite batch drawing with the HUD's shader, depth writes silently off.
 */
internal object GlStateFirewall {

    private val reportedErrors = mutableSetOf<Int>()

    fun reset() {
        val gl = Gdx.gl30 ?: error("ComposeGL needs a GL 3.0+ context; Gdx.gl30 is null")
        gl.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
        gl.glViewport(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        gl.glUseProgram(0)
        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0)
        gl.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, 0)
        gl.glActiveTexture(GL30.GL_TEXTURE0)
        gl.glBindTexture(GL30.GL_TEXTURE_2D, 0)
        gl.glDisable(GL30.GL_SCISSOR_TEST)
        gl.glDisable(GL30.GL_STENCIL_TEST)
        gl.glDepthMask(true)
        gl.glColorMask(true, true, true, true)
        gl.glDisable(GL30.GL_BLEND)
        gl.glBlendFunc(GL30.GL_SRC_ALPHA, GL30.GL_ONE_MINUS_SRC_ALPHA)
        gl.glPixelStorei(GL30.GL_UNPACK_ALIGNMENT, 4)
    }

    /**
     * Reports GL errors once per code, with the render count, so a driver problem shows up as one
     * line rather than sixty a second.
     */
    fun reportErrors(composeRenders: Long) {
        while (true) {
            val error = Gdx.gl.glGetError()
            if (error == GL20.GL_NO_ERROR) return
            if (reportedErrors.add(error)) {
                Gdx.app.error(
                    "ComposeGL",
                    "glGetError() returned 0x${error.toString(16)} after Compose render " +
                        "#$composeRenders. Reported once per error code.",
                )
            }
        }
    }
}
