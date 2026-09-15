package dev.wildware.composegl.gdx

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.GL20
import org.lwjgl.opengl.GL11
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

    /**
     * True when the suite runs on a GL 3.2 context rather than the default GL 2 one.
     *
     * Set by the `testGl30` task. A game on OpenGL ES 3 or desktop GL 3 gets `Gdx.gl30`, and LibGDX
     * then takes different paths — vertex array objects, for one — so the same scenes are drawn
     * both ways.
     */
    val gl30: Boolean = System.getProperty("composegl.gl") == "gl30"

    private val work = ConcurrentLinkedQueue<() -> Unit>()
    private var started = false

    val size = 400

    private fun start() {
        if (started) return
        started = true
        val ready = CountDownLatch(1)
        val listener = object : ApplicationAdapter() {
            override fun create() {
                // Mesa hands out a compatibility context unless told otherwise, and one of those
                // forgives exactly the mistakes a core context exists to catch. Refuse to pass on it.
                if (gl30) {
                    val core = GL11.glGetInteger(GL_CONTEXT_PROFILE_MASK) and GL_CONTEXT_CORE_PROFILE_BIT != 0
                    if (!core || Gdx.gl30 == null) {
                        failure = "asked for a GL 3.2 core context, got ${Gdx.gl.glGetString(GL20.GL_VERSION)}"
                    }
                }
                ready.countDown()
            }
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
            if (gl30) {
                setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 2)
                enableGLDebugOutput(true, System.err)
            }
        }
        Thread({ Lwjgl3Application(listener, configuration) }, "gl").apply { isDaemon = true }.start()
        check(ready.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "the GL context never came up" }
    }

    /** Why the context that came up is not the one asked for, if it is not. */
    @Volatile
    private var failure: String? = null

    private const val GL_CONTEXT_PROFILE_MASK = 0x9126
    private const val GL_CONTEXT_CORE_PROFILE_BIT = 1

    /**
     * Runs [block] on this thread with the GL thread held still.
     *
     * For the one test that has to touch the static `Gdx.gl`. The LibGDX application resets it
     * every time round its loop, so a test that installs a stub there would watch its stub be
     * swapped back out from under it — and then pass for the wrong reason. Holding the loop inside
     * a runnable of ours means nothing resets anything until [block] is done.
     *
     * Starts nothing: with no display there is no loop to hold, and [block] simply runs.
     */
    fun <T> paused(block: () -> T): T {
        if (!started) return block()

        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        work += {
            held.countDown()
            release.await()
        }
        Gdx.graphics.requestRendering()
        check(held.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "the GL thread never stopped" }
        try {
            return block()
        } finally {
            release.countDown()
        }
    }

    /** Runs [block] on the GL thread and waits for it. Skips the test when there is no display. */
    fun <T> render(block: () -> T): T {
        assumeTrue(available, "no display; this test needs a real GL context")
        start()
        failure?.let { error(it) }

        val answer = ArrayBlockingQueue<Result<T>>(1)
        work += { answer.put(runCatching(block)) }
        Gdx.graphics.requestRendering()
        return answer.take().getOrThrow()
    }
}
