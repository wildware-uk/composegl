package composegl.lwjgl3

import composegl.ui.geometry.Size
import composegl.ui.layout.Padding
import composegl.ui.layout.ScalePolicy
import composegl.ui.layout.Viewport
import org.lwjgl.glfw.Callbacks
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryStack

/**
 * A window with an OpenGL context in it, and nothing else.
 *
 * The smallest thing that can host an interface: GLFW makes the window, OpenGL draws into it, and
 * the toolkit never hears about either. There is no application class, no asset loader and no main
 * loop — a game writes its own loop and calls [present] at the end of it.
 *
 * Desktop only, deliberately. This backend exists to prove the toolkit's seams are real, and the
 * things a game actually needs beyond a window — audio, assets, a soft keyboard, Android and iOS —
 * are exactly what LibGDX already ships.
 *
 * @param title what the window manager shows.
 * @param width the window, in logical units. On a scaled display the framebuffer is larger, and
 *   [framebuffer] is the one that matters for drawing.
 * @param visible false for a window that draws without appearing, which is how the screenshot
 *   tests work.
 */
class GlfwWindow(
    title: String,
    width: Int = 1280,
    height: Int = 720,
    visible: Boolean = true,
    vsync: Boolean = true,
) : AutoCloseable {

    val handle: Long

    init {
        synchronized(Lock) {
            if (open == 0) {
                GLFWErrorCallback.createPrint(System.err).set()
                check(GLFW.glfwInit()) { "GLFW would not start; there is probably no display" }
            }
            open++
        }

        GLFW.glfwDefaultWindowHints()
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, if (visible) GLFW.GLFW_TRUE else GLFW.GLFW_FALSE)
        // No version or profile asked for, so the driver gives its most capable compatible
        // context. The shader here is old enough to compile on any of them.
        handle = GLFW.glfwCreateWindow(width, height, title, 0L, 0L)
        check(handle != 0L) { "GLFW could not make a ${width}x$height window with an OpenGL context" }

        GLFW.glfwMakeContextCurrent(handle)
        // Binds this thread's GL functions. Nothing in this module may be called before it.
        GL.createCapabilities()
        GLFW.glfwSwapInterval(if (vsync) 1 else 0)
    }

    /** The framebuffer, in real pixels. Larger than the window on a scaled display. */
    val framebuffer: Size
        get() = MemoryStack.stackPush().use { stack ->
            val width = stack.mallocInt(1)
            val height = stack.mallocInt(1)
            GLFW.glfwGetFramebufferSize(handle, width, height)
            Size(width[0].toFloat(), height[0].toFloat())
        }

    /** Framebuffer pixels per logical window unit. What a pointer position has to be multiplied by. */
    val pixelScale: Float
        get() = MemoryStack.stackPush().use { stack ->
            val windowWidth = stack.mallocInt(1)
            val bufferWidth = stack.mallocInt(1)
            GLFW.glfwGetWindowSize(handle, windowWidth, stack.mallocInt(1))
            GLFW.glfwGetFramebufferSize(handle, bufferWidth, stack.mallocInt(1))
            if (windowWidth[0] <= 0) 1f else bufferWidth[0].toFloat() / windowWidth[0]
        }

    /** How [design] is fitted onto this window as it is right now. */
    fun viewport(
        design: Size,
        policy: ScalePolicy = ScalePolicy.Fit,
        safeArea: Padding = Padding.None,
    ) = Viewport(design = design, physical = framebuffer, policy = policy, safeArea = safeArea)

    /** True once the player has asked for the window to go away. */
    fun shouldClose(): Boolean = GLFW.glfwWindowShouldClose(handle)

    /** Shows what was drawn and collects what the player did. The end of a frame. */
    fun present() {
        GLFW.glfwSwapBuffers(handle)
        GLFW.glfwPollEvents()
    }

    override fun close() {
        // The callbacks are native allocations that outlive the window unless somebody says so.
        Callbacks.glfwFreeCallbacks(handle)
        GLFW.glfwDestroyWindow(handle)
        synchronized(Lock) {
            open--
            if (open == 0) {
                GLFW.glfwTerminate()
                GLFW.glfwSetErrorCallback(null)?.free()
            }
        }
    }

    private companion object {

        /**
         * GLFW is one global thing, started before the first window and stopped after the last.
         *
         * Counted rather than left running, because a test suite that opens and closes windows
         * would otherwise leave the library initialised for the life of the JVM.
         */
        val Lock = Any()

        @Volatile
        var open = 0
    }
}
