package dev.wildware.composegl.preview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What the window hears when files change: compile, reload, show an error, or ask for a restart.
 *
 * The compiler is a fake that answers what the test tells it to, so none of this needs Gradle. Each
 * poll is one look at the disk; nothing here waits for a clock.
 */
class LiveReloaderTest {

    @TempDir
    lateinit var temp: File

    private lateinit var sources: File
    private lateinit var outputs: File
    private lateinit var buildFile: File
    private lateinit var keptJar: File
    private val compiler = FakeCompiler()

    /** Every file write moves its time on by a second, so no filesystem's clock resolution hides one. */
    private var stamp = 1_700_000_000_000L

    @BeforeEach
    fun layOut() {
        sources = File(temp, "src").apply { mkdirs() }
        outputs = File(temp, "classes").apply { mkdirs() }
        buildFile = File(temp, "build.gradle.kts")
        keptJar = File(temp, "libs/game-core.jar").apply { parentFile.mkdirs() }
        write(File(sources, "Menus.kt"), "fun menus() {}")
        write(File(outputs, "MenusKt.class"), "v1")
        write(buildFile, "plugins {}")
        write(keptJar, "jar v1")
    }

    private fun write(file: File, text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
        stamp += 1_000L
        file.setLastModified(stamp)
    }

    private fun reloader(compiler: ModuleCompiler? = this.compiler) = LiveReloader(
        sources = listOf(sources),
        outputs = listOf(outputs),
        buildFiles = listOf(buildFile),
        kept = listOf(keptJar),
        compiler = compiler,
        advice = "./gradlew -t :game:classes",
    )

    @Test
    fun `nothing changed, nothing happens`() {
        val live = reloader()
        repeat(5) { assertEquals(emptyList<LiveEvent>(), live.poll()) }
        assertEquals(0, compiler.calls)
    }

    @Test
    fun `a saved source file compiles the module and reloads it`() {
        val live = reloader()
        compiler.onCompile = { write(File(outputs, "MenusKt.class"), "v2") }

        write(File(sources, "Menus.kt"), "fun menus() { println() }")

        assertEquals(listOf(LiveEvent.Compiling, LiveEvent.Reload), live.poll())
        assertEquals(1, compiler.calls)
        // What that compile wrote is not a second change to reload for.
        assertEquals(emptyList<LiveEvent>(), live.poll())
        assertEquals(emptyList<LiveEvent>(), live.poll())
    }

    @Test
    fun `a new or deleted source file counts as a change`() {
        val live = reloader()
        write(File(sources, "menus/Pause.kt"), "fun pause() {}")
        assertEquals(listOf(LiveEvent.Compiling, LiveEvent.Reload), live.poll())

        File(sources, "menus/Pause.kt").delete()
        assertEquals(listOf(LiveEvent.Compiling, LiveEvent.Reload), live.poll())
    }

    @Test
    fun `a failed compile is reported and nothing reloads`() {
        val live = reloader()
        val failure = CompileResult.Failed(CompileError("/src/Menus.kt", 1, 15, "Expecting '}'."), "e: …")
        compiler.next = failure

        write(File(sources, "Menus.kt"), "fun menus() {")

        assertEquals(listOf(LiveEvent.Compiling, LiveEvent.CompileFailed(failure)), live.poll())
        assertEquals(emptyList<LiveEvent>(), live.poll())
    }

    @Test
    fun `a failed compile that touched the output does not reload, so its banner stays until a compile works`() {
        val live = reloader()
        val failure = CompileResult.Failed(CompileError("/src/Menus.kt", 1, 15, "Expecting '}'."), "e: …")
        compiler.next = failure
        // Resources processed, and the old classes put back with new times, before compileKotlin failed.
        compiler.onCompile = { write(File(outputs, "MenusKt.class"), "v1 restored") }

        write(File(sources, "Menus.kt"), "fun menus() {")
        assertEquals(listOf(LiveEvent.Compiling, LiveEvent.CompileFailed(failure)), live.poll())
        repeat(4) { assertEquals(emptyList<LiveEvent>(), live.poll()) }

        // Even somebody else's compile does not clear it: only one of Gradle's that works.
        write(File(outputs, "MenusKt.class"), "written by an IDE")
        repeat(3) { assertEquals(emptyList<LiveEvent>(), live.poll()) }

        compiler.next = CompileResult.Compiled
        compiler.onCompile = {}
        write(File(sources, "Menus.kt"), "fun menus() {}")
        assertEquals(listOf(LiveEvent.Compiling, LiveEvent.Reload), live.poll())
    }

    @Test
    fun `a compile that throws is reported as a failed compile, not lost`() {
        val live = reloader()
        compiler.onCompile = { throw IllegalStateException("the daemon went away mid-build") }

        write(File(sources, "Menus.kt"), "fun menus() { println() }")
        val failed = live.poll().last() as LiveEvent.CompileFailed
        assertTrue("the daemon went away" in failed.result.summary, failed.result.summary)
    }

    @Test
    fun `compiling is announced before the compile starts`() {
        val live = reloader()
        val heard = mutableListOf<LiveEvent>()
        compiler.onCompile = { assertEquals(listOf(LiveEvent.Compiling), heard, "the window heard nothing while compiling") }

        write(File(sources, "Menus.kt"), "fun menus() { println() }")
        live.poll { heard += it }

        assertEquals(listOf(LiveEvent.Compiling, LiveEvent.Reload), heard)
        assertEquals(1, compiler.calls)
    }

    @Test
    fun `a file saved while the compile ran is compiled again`() {
        val live = reloader()
        compiler.onCompile = { if (compiler.calls == 1) write(File(sources, "Menus.kt"), "saved mid-compile") }

        write(File(sources, "Menus.kt"), "first save")
        assertEquals(listOf(LiveEvent.Compiling, LiveEvent.Reload), live.poll())
        assertEquals(listOf(LiveEvent.Compiling, LiveEvent.Reload), live.poll())
        assertEquals(2, compiler.calls)
    }

    @Test
    fun `a changed build file asks for a restart and stops compiling`() {
        val live = reloader()
        write(buildFile, "plugins { kotlin(\"jvm\") }")

        val events = live.poll()
        assertEquals(1, events.size)
        val restart = events.single() as LiveEvent.RestartNeeded
        assertTrue("build.gradle.kts" in restart.reason, restart.reason)

        write(File(sources, "Menus.kt"), "fun menus() { println() }")
        assertEquals(emptyList<LiveEvent>(), live.poll())
        assertEquals(0, compiler.calls, "code compiled against a build that is not the one running would be stale")
    }

    @Test
    fun `a compile that rebuilt a dependency asks for a restart instead of reloading`() {
        val live = reloader()
        // The module's compile rebuilt a library the window loaded once, at the start.
        compiler.onCompile = { write(keptJar, "jar v2") }

        write(File(sources, "Menus.kt"), "uses the new library")

        val events = live.poll()
        assertEquals(LiveEvent.Compiling, events.first())
        val restart = events.last() as LiveEvent.RestartNeeded
        assertTrue("game-core.jar" in restart.reason, restart.reason)
        assertTrue(LiveEvent.Reload !in events)
    }

    @Test
    fun `a dependency rebuilt by something else asks for a restart as well`() {
        val live = reloader()
        write(keptJar, "jar v2")
        val restart = live.poll().single() as LiveEvent.RestartNeeded
        assertTrue("game-core.jar" in restart.reason, restart.reason)
    }

    @Test
    fun `classes compiled by something else are reloaded once they stop changing`() {
        val live = reloader()
        write(File(outputs, "MenusKt.class"), "v2")
        assertEquals(emptyList<LiveEvent>(), live.poll(), "still being written, perhaps")
        assertEquals(listOf(LiveEvent.Reload), live.poll())
        assertEquals(emptyList<LiveEvent>(), live.poll())
    }

    @Test
    fun `Gradle unreachable falls back to watching the compiled output`() {
        val live = reloader()
        compiler.next = CompileResult.Unreachable("Could not connect to the Gradle daemon.")

        write(File(sources, "Menus.kt"), "fun menus() { println() }")
        val events = live.poll()
        assertEquals(LiveEvent.Compiling, events.first())
        val fallback = events.last() as LiveEvent.WatchingOutput
        assertTrue("Could not connect" in fallback.reason, fallback.reason)
        assertEquals("./gradlew -t :game:classes", fallback.advice)

        // From here on a source change is somebody else's to compile; the output is what is watched.
        write(File(sources, "Menus.kt"), "another save")
        assertEquals(emptyList<LiveEvent>(), live.poll())
        assertEquals(1, compiler.calls)

        write(File(outputs, "MenusKt.class"), "v2")
        assertEquals(emptyList<LiveEvent>(), live.poll())
        assertEquals(listOf(LiveEvent.Reload), live.poll())
    }

    @Test
    fun `with no Gradle at all it says so on the first poll and watches the output`() {
        val live = reloader(compiler = null)
        val fallback = live.poll().single() as LiveEvent.WatchingOutput
        assertEquals("./gradlew -t :game:classes", fallback.advice)

        write(File(outputs, "MenusKt.class"), "v2")
        live.poll()
        assertEquals(listOf(LiveEvent.Reload), live.poll())
    }

    class FakeCompiler : ModuleCompiler {
        var calls = 0
        var next: CompileResult = CompileResult.Compiled
        var onCompile: () -> Unit = {}

        override fun compile(): CompileResult {
            calls++
            onCompile()
            return next
        }
    }
}
