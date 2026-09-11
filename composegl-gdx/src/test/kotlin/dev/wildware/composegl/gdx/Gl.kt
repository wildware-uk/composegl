package dev.wildware.composegl.gdx

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * One real OpenGL context, shared by every test that needs one.
 *
 * Almost nothing in this project needs a GPU — the recording canvas answers nearly every question
 * without one. What is left is the handful of things only a GPU can answer: how many times the
 * batch talked to the driver, and what actually came out in the pixels.
 *
 * Booting a window per test would take longer than the tests do, so one is booted on demand and
 * every test posts work to its thread.
 */
object Gl {

    /** True when a display exists. Tests that need one skip themselves rather than failing. */
    val available: Boolean = !System.getenv("DISPLAY").isNullOrBlank()

    private val work = ConcurrentLinkedQueue<() -> Unit>()
    private var started = false

    val size = 400

    private fun start() {
        if (started) return
        started = true
        val ready = CountDownLatch(1)
        val listener = object : ApplicationAdapter() {
            override fun create() = ready.countDown()
            override fun render() {
                while (true) (work.poll() ?: return).invoke()
            }
        }
        val configuration = Lwjgl3ApplicationConfiguration().apply {
            setWindowedMode(size, size)
            setInitialVisible(false)
            setTitle("composegl tests")
            useVsync(false)
            setForegroundFPS(0)
        }
        Thread({ Lwjgl3Application(listener, configuration) }, "gl").apply { isDaemon = true }.start()
        check(ready.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "the GL context never came up" }
    }

    /** Runs [block] on the GL thread and waits for it. Skips the test when there is no display. */
    fun <T> render(block: () -> T): T {
        assumeTrue(available, "no display; this test needs a real GL context")
        start()

        val answer = ArrayBlockingQueue<Result<T>>(1)
        work += { answer.put(runCatching(block)) }
        Gdx.graphics.requestRendering()
        return answer.take().getOrThrow()
    }
}
