package dev.wildware.composegl.ui.host

/**
 * PROFILING ONLY (#267): told when [UiRenderer.render] starts and ends, so a probe can tell the
 * toolkit's share of a game's frame from the game's own.
 */
object RenderProbe {
    interface Hook {
        fun begin()
        fun end()
        fun mark(at: Int) {}
    }

    var hook: Hook? = null
}
