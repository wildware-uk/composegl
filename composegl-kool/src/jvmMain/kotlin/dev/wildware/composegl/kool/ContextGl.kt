package dev.wildware.composegl.kool

import dev.wildware.composegl.lwjgl3.LwjglGl
import dev.wildware.composegl.render.gl.Gl
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11

/** Kool's desktop OpenGL is LWJGL on the context Kool makes current, so the calls are [LwjglGl]'s. */
internal actual object ContextGl : Gl by LwjglGl {

    actual val current: Boolean get() = GLFW.glfwGetCurrentContext() != 0L

    actual fun depthFunc(func: Int) = GL11.glDepthFunc(func)

    actual fun cullFace(mode: Int) = GL11.glCullFace(mode)

    actual fun lineWidth(width: Float) = GL11.glLineWidth(width)

    actual fun currentLineWidth(): Float = GL11.glGetFloat(GL11.GL_LINE_WIDTH)
}
