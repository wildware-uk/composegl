package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.lwjgl.glfw.Callbacks
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import dev.wildware.composegl.render.gl.Gl
import org.lwjgl.opengl.GL
import org.lwjgl.opengles.GLES
import org.lwjgl.system.MemoryStack

/**
 * Which OpenGL a [GlfwWindow] asks for, and the [Gl] binding that draws on it.
 *
 * Desktop GL unless told otherwise. The ES contexts come through EGL, which is how a phone's GPU is
 * reached and, on Linux, what Mesa hands out as real OpenGL ES 2 and ES 3.
 */
enum class GlfwContext(val binding: Gl, internal val esMajor: Int) {
    /** Whatever desktop OpenGL the driver gives when nothing is asked for. */
    Desktop(LwjglGl, 0),

    /** OpenGL ES 3.0 or later, through EGL. */
    Es3(LwjglGles, 3),

    /** OpenGL ES 2.0, through EGL. */
    Es2(LwjglGles, 2),
    ;

    /** Binds LWJGL's functions on this thread to the context current on it. */
    internal fun createCapabilities() {
        if (this == Desktop) GL.createCapabilities() else GLES.createCapabilities()
    }

    companion object {
        /**
         * What a window or canvas uses when it is not told: the `composegl.lwjgl3.context` system
         * property (`desktop`, `es3` or `es2`), and desktop GL without it. The tests run the whole
         * suite on each by setting it.
         */
        val Default: GlfwContext = when (System.getProperty("composegl.lwjgl3.context")?.lowercase()) {
            null, "", "desktop", "gl" -> Desktop
            "es3" -> Es3
            "es2" -> Es2
            else -> error("composegl.lwjgl3.context must be desktop, es3 or es2")
        }
    }
}

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
 * @param context desktop OpenGL, or OpenGL ES 2 or 3 through EGL. Draw on it with [context]'s binding.
 */
class GlfwWindow(
    title: String,
    width: Int = 1280,
    height: Int = 720,
    visible: Boolean = true,
    vsync: Boolean = true,
    val context: GlfwContext = GlfwContext.Default,
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
        if (context == GlfwContext.Desktop) {
            // No version or profile asked for, so the driver gives its most capable compatible
            // context. The renderer's shaders compile on any of them.
        } else {
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_ES_API)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_EGL_CONTEXT_API)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, context.esMajor)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 0)
        }
        handle = GLFW.glfwCreateWindow(width, height, title, 0L, 0L)
        check(handle != 0L) { "GLFW could not make a ${width}x$height window with a $context OpenGL context" }

        makeCurrent()
        GLFW.glfwSwapInterval(if (vsync) 1 else 0)
    }

    /**
     * Makes this window's context current on the calling thread, and binds LWJGL's GL functions
     * there to it. Nothing in this module may draw before it.
     */
    fun makeCurrent() {
        GLFW.glfwMakeContextCurrent(handle)
        context.createCapabilities()
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
