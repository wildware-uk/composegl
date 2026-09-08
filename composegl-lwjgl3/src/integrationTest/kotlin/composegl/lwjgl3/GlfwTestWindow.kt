package composegl.lwjgl3

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL32C
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A hidden GLFW window with a GL 3.2 core context, for tests that need a real driver.
 * On CI that driver is Mesa's llvmpipe under Xvfb.
 */
class GlfwTestWindow(val width: Int = 400, val height: Int = 300) : AutoCloseable {

    var handle: Long = 0L
        private set

    fun open() {
        check(GLFW.glfwInit()) { "GLFW failed to start. On a headless box, run under xvfb-run." }
        GLFW.glfwDefaultWindowHints()
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2)
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
        handle = GLFW.glfwCreateWindow(width, height, "composegl-lwjgl3-test", 0L, 0L)
        check(handle != 0L) { "GLFW could not create a GL 3.2 core window." }
        GLFW.glfwMakeContextCurrent(handle)
        GLFW.glfwSwapInterval(0)
        GL.createCapabilities()
    }

    fun clear(red: Float, green: Float, blue: Float) {
        GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, 0)
        GL32C.glViewport(0, 0, width, height)
        GL32C.glClearColor(red, green, blue, 1f)
        GL32C.glClear(GL32C.GL_COLOR_BUFFER_BIT)
    }

    /** ARGB at (x, y) measured from the top-left, the way Compose thinks. */
    fun pixelAt(x: Int, y: Int): Int {
        val buffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, 0)
        GL32C.glReadPixels(x, height - 1 - y, 1, 1, GL32C.GL_RGBA, GL32C.GL_UNSIGNED_BYTE, buffer)
        fun byteAt(i: Int) = buffer.get(i).toInt() and 0xff
        return (byteAt(3) shl 24) or (byteAt(0) shl 16) or (byteAt(1) shl 8) or byteAt(2)
    }

    fun glError(): Int = GL32C.glGetError()

    override fun close() {
        if (handle != 0L) GLFW.glfwDestroyWindow(handle)
        GLFW.glfwTerminate()
    }
}

fun hex(argb: Int): String = "#%08X".format(argb)
