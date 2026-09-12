package dev.wildware.composegl.lwjgl3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Having a canvas costs no OpenGL.
 *
 * The LibGDX backend's question asked again of the renderer that shares none of its code, and for
 * the same reason: a game object that owns a canvas next to its host, its renderer and its focus
 * manager has to be buildable in a plain JVM test, or focus, input routing and lifecycle all get
 * dragged onto a GPU that has nothing to do with them.
 *
 * The work happens on a thread of its own, and that is what makes the test honest rather than one
 * that passes because it happened to run first. A GL context is current **per thread**: the window
 * [Gl] opens is made current on the JUnit thread, and under Xvfb some other test has usually opened
 * it by the time this runs — so a canvas built on that thread would quietly succeed and prove
 * nothing. This thread has never had a context and never will. Please do not "fix" this by dropping
 * the thread and calling it simpler; the thread *is* the assertion.
 *
 * How it fails, so that nobody is surprised by it: a GL call with no context does not throw here,
 * it aborts the process — LWJGL prints "No context is current…" and the JVM exits 134, taking this
 * module's test task with it. Loud and unmissable, with `GlCanvas.<init>` at the top of the fatal
 * stack, which is the whole diagnosis. It is deliberately not guarded by an assumption about a
 * display: needing no display is the claim being tested.
 */
class GlCanvasHeadlessTest {

    @Test
    fun `building, reading and closing a canvas touches no GL`() {
        val failure = AtomicReference<Throwable?>()

        val thread = Thread({
            try {
                val canvas = GlCanvas()
                assertEquals(0, canvas.drawCalls, "a canvas that has drawn nothing has made no draw calls")
                // Closing must never build the thing it is about to destroy.
                canvas.close()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "no-gl-context")

        thread.start()
        thread.join(30_000)

        failure.get()?.let { throw AssertionError("building a canvas reached OpenGL: ${it.message}", it) }
    }
}
