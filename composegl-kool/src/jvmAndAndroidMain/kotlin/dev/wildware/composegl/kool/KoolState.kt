package dev.wildware.composegl.kool

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
        ContextGl.depthFunc(depthFunc)
        ContextGl.cullFace(cullFace)
        ContextGl.lineWidth(lineWidth)
    }

    companion object {
        private const val DEPTH_FUNC = 0x0B74
        private const val CULL_FACE_MODE = 0x0B45

        fun save() = KoolState(
            ContextGl.getInteger(DEPTH_FUNC),
            ContextGl.getInteger(CULL_FACE_MODE),
            ContextGl.currentLineWidth(),
        )
    }
}
