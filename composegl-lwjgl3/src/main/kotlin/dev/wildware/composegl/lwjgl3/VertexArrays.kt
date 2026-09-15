package dev.wildware.composegl.lwjgl3

import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL30

/**
 * A vertex array object, where the context has them, and nothing where it does not.
 *
 * A GL 3.2 core context draws nothing from buffers described with no vertex array object bound:
 * "GL_INVALID_OPERATION in glVertexAttribPointer(no array object bound)". [GlfwWindow] asks for a
 * compatibility context, which forgives it, but a game that hands this backend a core context of
 * its own would see a blank interface. Binding one costs nothing on a context that forgives it.
 *
 * Zero means "this context has none", and binding zero is then never called.
 */
internal object VertexArrays {

    fun create(): Int = if (GL.getCapabilities().OpenGL30) GL30.glGenVertexArrays() else 0

    fun bind(name: Int) {
        if (name != 0) GL30.glBindVertexArray(name)
    }

    fun unbind(name: Int) {
        if (name != 0) GL30.glBindVertexArray(0)
    }

    fun delete(name: Int) {
        if (name != 0) GL30.glDeleteVertexArrays(name)
    }
}
