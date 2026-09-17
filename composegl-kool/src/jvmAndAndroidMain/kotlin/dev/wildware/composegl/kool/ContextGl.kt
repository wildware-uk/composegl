package dev.wildware.composegl.kool

import dev.wildware.composegl.render.gl.Gl

/**
 * The renderer's [Gl], one call for one, on the OpenGL context Kool draws with on this platform, and the
 * few calls outside [Gl] that Kool's remembered state needs put back ([KoolState]).
 *
 * On the desktop that is LWJGL on the context Kool made current; on Android it is `android.opengl` on
 * the OpenGL ES 3 context of Kool's `GLSurfaceView`.
 */
internal expect object ContextGl : Gl {

    /** Whether this thread has Kool's context current, so a call can be made here at all. */
    val current: Boolean

    fun depthFunc(func: Int)

    fun cullFace(mode: Int)

    fun lineWidth(width: Float)

    /** The line width in force: the one piece of state [KoolState] saves that is not an integer. */
    fun currentLineWidth(): Float
}
