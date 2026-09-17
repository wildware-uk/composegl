package dev.wildware.composegl.kool

import dev.wildware.composegl.lwjgl3.LwjglGl
import dev.wildware.composegl.render.gl.Gl
import org.lwjgl.glfw.GLFW
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The renderer's [Gl], on the OpenGL context of Kool's desktop window.
 *
 * Kool's desktop OpenGL backend is LWJGL on a context Kool makes current on its render thread, so the
 * calls are [LwjglGl]'s, one for one. What this adds is where they may be made: only on a thread with
 * Kool's context current. A delete that arrives anywhere else — a canvas closed from a Kool
 * coroutine running on the scene-update thread, say — waits for [deleteWaiting], which the scene
 * host calls at the start of every frame it draws.
 */
internal object KoolGl : Gl by LwjglGl {

    private val waiting = ConcurrentLinkedQueue<() -> Unit>()

    /** Whether this thread has an OpenGL context to call. */
    val current: Boolean get() = GLFW.glfwGetCurrentContext() != 0L

    /** Runs the deletes made where there was no context. Only on a thread that has one. */
    fun deleteWaiting() {
        while (true) (waiting.poll() ?: return).invoke()
    }

    /** Now, where there is a context; otherwise at the start of the next frame drawn. */
    private inline fun later(crossinline delete: () -> Unit) {
        if (current) delete() else waiting += { delete() }
    }

    override fun deleteShader(shader: Int) = later { LwjglGl.deleteShader(shader) }
    override fun deleteProgram(program: Int) = later { LwjglGl.deleteProgram(program) }
    override fun deleteBuffer(buffer: Int) = later { LwjglGl.deleteBuffer(buffer) }
    override fun deleteVertexArray(array: Int) = later { LwjglGl.deleteVertexArray(array) }
    override fun deleteTexture(texture: Int) = later { LwjglGl.deleteTexture(texture) }
    override fun deleteFramebuffer(framebuffer: Int) = later { LwjglGl.deleteFramebuffer(framebuffer) }
    override fun deleteRenderbuffer(renderbuffer: Int) = later { LwjglGl.deleteRenderbuffer(renderbuffer) }
}
