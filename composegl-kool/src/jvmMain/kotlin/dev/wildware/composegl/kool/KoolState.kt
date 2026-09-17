package dev.wildware.composegl.kool

import org.lwjgl.opengl.GL11

/**
 * The GL state Kool remembers that the device's own hand-back does not cover: which depth comparison
 * is in force, which faces culling drops, and the line width.
 *
 * Kool 0.19.0 skips setting each of these when it believes it already has (`GlRenderPass.GlState`), and
 * that belief cannot be cleared from outside. [HostState][dev.wildware.composegl.render.gl.HostState]
 * saves and restores everything else Kool remembers — the program, depth writing, whether depth testing
 * and culling are on. These three are saved before a game's `raw` block and put back after it.
 */
internal class KoolState private constructor(private val depthFunc: Int, private val cullFace: Int, private val lineWidth: Float) {

    fun restore() {
        GL11.glDepthFunc(depthFunc)
        GL11.glCullFace(cullFace)
        GL11.glLineWidth(lineWidth)
    }

    companion object {
        fun save() = KoolState(
            GL11.glGetInteger(GL11.GL_DEPTH_FUNC),
            GL11.glGetInteger(GL11.GL_CULL_FACE_MODE),
            GL11.glGetFloat(GL11.GL_LINE_WIDTH),
        )
    }
}
