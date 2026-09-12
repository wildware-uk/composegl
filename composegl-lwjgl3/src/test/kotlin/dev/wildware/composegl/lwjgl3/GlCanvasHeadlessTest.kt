package dev.wildware.composegl.lwjgl3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Having a canvas costs no OpenGL.
 *
 * The LibGDX backend's question asked again of the renderer that shares none of its code, and for
 * the same reason: a game object that owns a canvas next to its host, its renderer and its focus
 * manager has to be buildable in a plain JVM test, or focus, input routing and lifecycle all get
 * dragged onto a GPU that has nothing to do with them.
 *
 * It runs in a JVM of its own, and that is not ceremony — it is what keeps a regression readable.
 * A GL call with no context does not throw here, it *aborts*: LWJGL prints "No context is current"
 * and the process dies with signal 6. A dying process writes no JUnit report, so doing this in the
 * test JVM would leave this module's XML saying one test, skipped, nothing failed, while the ten
 * other test classes in the module silently never ran — only Gradle's exit code would know. In a
 * child process the abort is a non-zero exit code that this test reads and reports as one ordinary
 * red test, with everything the child printed attached to it.
 *
 * A thread would not do instead. A context is current *per thread*, so a canvas built on a fresh
 * thread does reach a driver with no context — but a thread that aborts takes this JVM down just
 * the same, and a thread that hangs is indistinguishable from one that passed. The process
 * boundary is the assertion; please do not swap it back for something that looks simpler.
 *
 * It needs no display, so it runs on the headless build as well as under Xvfb: needing no display
 * is the claim being tested.
 */
class GlCanvasHeadlessTest {

    @Test
    fun `building, reading and closing a canvas touches no GL`() {
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        // Straight to a file rather than to a pipe: a child that hangs with a full pipe would hang
        // this test too, and the timeout below is here precisely so that it cannot.
        val log = File.createTempFile("no-gl-context", ".log").apply { deleteOnExit() }

        val process = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            GlCanvasNoContextProbe::class.java.name,
        ).redirectErrorStream(true).redirectOutput(log).start()

        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val said = log.readText().ifBlank { "(nothing)" }

        assertTrue(finished, "building a canvas with no GL context never finished. It said:\n$said")
        assertEquals(0, process.exitValue(), "building a canvas reached OpenGL. It said:\n$said")
        assertTrue(GlCanvasNoContextProbe.Done in said, "the probe stopped without saying so:\n$said")
    }
}
