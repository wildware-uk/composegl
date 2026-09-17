package dev.wildware.composegl.kool

import dev.wildware.composegl.render.gl.Gl
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The renderer's [Gl] inside a Kool game: [ContextGl]'s calls, made only on a thread with Kool's
 * context current.
 *
 * A delete that arrives anywhere else — a canvas closed from a Kool coroutine running on the
 * scene-update thread, say — waits for [deleteWaiting], which the scene host calls at the start of
 * every frame it draws.
 */
internal object KoolGl : Gl by ContextGl {

    private val waiting = ConcurrentLinkedQueue<() -> Unit>()

    /** Whether this thread has an OpenGL context to call. */
    val current: Boolean get() = ContextGl.current

    /** Runs the deletes made where there was no context. Only on a thread that has one. */
    fun deleteWaiting() {
        while (true) (waiting.poll() ?: return).invoke()
    }

    /** Now, where there is a context; otherwise at the start of the next frame drawn. */
    private inline fun later(crossinline delete: () -> Unit) {
        if (current) delete() else waiting += { delete() }
    }

    override fun deleteShader(shader: Int) = later { ContextGl.deleteShader(shader) }
    override fun deleteProgram(program: Int) = later { ContextGl.deleteProgram(program) }
    override fun deleteBuffer(buffer: Int) = later { ContextGl.deleteBuffer(buffer) }
    override fun deleteVertexArray(array: Int) = later { ContextGl.deleteVertexArray(array) }
    override fun deleteTexture(texture: Int) = later { ContextGl.deleteTexture(texture) }
    override fun deleteFramebuffer(framebuffer: Int) = later { ContextGl.deleteFramebuffer(framebuffer) }
    override fun deleteRenderbuffer(renderbuffer: Int) = later { ContextGl.deleteRenderbuffer(renderbuffer) }
}
