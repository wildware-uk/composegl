package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.render.gl.GlDevice
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengles.GLES20
import java.nio.ByteBuffer
import dev.wildware.composegl.render.gl.Gl as GlBinding

/**
 * One hidden window with a real OpenGL context, shared by every test that needs one.
 *
 * Almost nothing in this project needs a GPU. What is left is the handful of things only a GPU can
 * answer: how many times the batch talked to the driver, and what actually came out in the pixels.
 * Opening a window per test would take longer than the tests do, so one is opened on demand and
 * kept for the life of the JVM.
 *
 * GLFW wants its window made and its events pumped on one thread. JUnit runs these on one thread,
 * so there is no queue here — unlike the LibGDX harness, which has an application loop of its own
 * to post work to.
 */
object Gl {

    /** True when a display exists. Tests that need one skip themselves rather than failing. */
    val available: Boolean = !System.getenv("DISPLAY").isNullOrBlank()

    const val size = 400

    /**
     * Which OpenGL the suite runs on: desktop GL for `test` and `testGl30`, OpenGL ES 3 for
     * `testGles3`, ES 2 for `testGles2` — the `composegl.lwjgl3.context` property those tasks set.
     */
    val context: GlfwContext get() = GlfwContext.Default

    /** The binding for [context], for the few calls a test makes itself: a clear, a read-back. */
    val gl: GlBinding get() = context.binding

    private var shared: GlfwWindow? = null

    /** The shared window, for a test that needs one to hand a backend. Only inside [render]. */
    val window: GlfwWindow get() = checkNotNull(shared) { "the GL window only exists inside Gl.render" }

    /** Runs [block] with a current GL context. Skips the test when there is no display. */
    fun <T> render(block: () -> T): T {
        assumeTrue(available, "no display; this test needs a real GL context")
        val window = shared ?: GlfwWindow("composegl tests", size, size, visible = false, vsync = false)
            .also { shared = it; describe() }
        // Taken back every time: a test that opened and closed a window of its own — the preview
        // renderer does — left no context current on this thread.
        window.makeCurrent()
        return block()
    }

    /** Runs [block] with the shared window itself, for the calls that take a window. */
    fun <T> window(block: (GlfwWindow) -> T): T = render { block(checkNotNull(window)) }

    /**
     * The bottom-left [width] by [height] of the framebuffer, as `0xRRGGBB`, y down from the top.
     *
     * OpenGL hands back the bottom row first; every caller thinks in the toolkit's coordinates, so
     * the rows are turned over once, here.
     */
    fun readPixels(width: Int, height: Int): IntArray {
        val bytes = readBytes(width, height)

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val at = ((height - 1 - y) * width + x) * 4
                pixels[y * width + x] =
                    (bytes.get(at).toInt() and 0xFF shl 16) or
                        (bytes.get(at + 1).toInt() and 0xFF shl 8) or
                        (bytes.get(at + 2).toInt() and 0xFF)
            }
        }
        return pixels
    }

    /** The bottom-left [width] by [height] of whatever framebuffer is bound, as RGBA bytes, bottom row first. */
    fun readBytes(width: Int, height: Int): ByteBuffer {
        val bytes = gl.bytes(width * height * 4)
        gl.readPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes)
        return (bytes as LwjglGl.Bytes).buffer
    }

    /** Whether [name] is a framebuffer on the current context. */
    fun isFramebuffer(name: Int): Boolean =
        if (context == GlfwContext.Desktop) GL30.glIsFramebuffer(name) else GLES20.glIsFramebuffer(name)

    /** The driver's GL_VERSION, as the current context reports it. Only inside [render]. */
    fun version(): String =
        (if (context == GlfwContext.Desktop) GL11.glGetString(GL11.GL_VERSION) else GLES20.glGetString(GLES20.GL_VERSION)).orEmpty()

    /** The driver's GL_SHADING_LANGUAGE_VERSION. Only inside [render]. */
    fun shadingVersion(): String = (
        if (context == GlfwContext.Desktop) GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION) else GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)
        ).orEmpty()

    /** Says, once, which context the suite really got: the driver's own words, not what was asked for. */
    private fun describe() {
        println(
            "composegl tests: $context context, GL_VERSION \"${version()}\", " +
                "GL_SHADING_LANGUAGE_VERSION \"${shadingVersion()}\", dialect ${GlDevice(gl).dialect}",
        )
    }
}
