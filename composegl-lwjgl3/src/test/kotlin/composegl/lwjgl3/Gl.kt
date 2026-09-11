package composegl.lwjgl3

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11

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

    private var window: GlfwWindow? = null

    /** Runs [block] with a current GL context. Skips the test when there is no display. */
    fun <T> render(block: () -> T): T {
        assumeTrue(available, "no display; this test needs a real GL context")
        if (window == null) {
            window = GlfwWindow("composegl tests", size, size, visible = false, vsync = false)
        }
        return block()
    }

    /**
     * The bottom-left [width] by [height] of the framebuffer, as `0xRRGGBB`, y down from the top.
     *
     * OpenGL hands back the bottom row first; every caller thinks in the toolkit's coordinates, so
     * the rows are turned over once, here.
     */
    fun readPixels(width: Int, height: Int): IntArray {
        val bytes = BufferUtils.createByteBuffer(width * height * 4)
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes)

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
}
