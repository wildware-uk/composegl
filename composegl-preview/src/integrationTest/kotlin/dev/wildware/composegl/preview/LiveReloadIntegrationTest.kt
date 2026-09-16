package dev.wildware.composegl.preview

import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.graphics.DrawCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End to end, with a real Gradle: a small sample project is built, a source file is edited, and the
 * compile the window asks for through the Tooling API is what the reloaded previews come from.
 *
 * `./gradlew :composegl-preview:integrationTest`. Not part of `check`: it starts a Gradle daemon of
 * its own and compiles Kotlin three times.
 *
 * The window's drawing is left out — the real-GL test covers that — so the previews draw into a
 * recording canvas and "what the window shows" is the text drawn. Everything between a saved file and
 * that text is the real thing.
 */
class LiveReloadIntegrationTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `a saved file is compiled through Gradle and reloaded, a broken one is reported, a build file asks for a restart`() {
        val project = File(temp, "sample").apply { mkdirs() }
        writeSample(project)
        val hud = File(project, "game/src/main/kotlin/sample/Hud.kt")
        save(hud, hudSource("health 10"))

        val gradleHome = System.getProperty("composegl.preview.it.gradleHome").orEmpty().takeIf { it.isNotEmpty() }?.let(::File)
        GradleCompiler(project, listOf(":game:classes"), gradleHome).use { compiler ->
            assertEquals(CompileResult.Compiled, compiler.compile(), "the sample project did not build")

            val classes = File(project, "game/build/classes/kotlin/main")
            val resources = File(project, "game/build/resources/main")
            val backend = HeadlessBackend()
            val module = ReloadableModule(listOf(classes), listOf(resources), scratch = File(temp, "scratch"))
            val live = LiveReloader(
                sources = listOf(File(project, "game/src/main/kotlin")),
                outputs = listOf(classes, resources),
                buildFiles = listOf(File(project, "game/build.gradle.kts"), File(project, "settings.gradle.kts")),
                kept = emptyList(),
                compiler = compiler,
                advice = "./gradlew -t :game:classes",
            )

            PreviewSession(module, backend).use { session ->
                assertTrue(session.reload())
                assertEquals(listOf("health 10"), texts(session, backend))

                // Saved: compiled through Gradle, reloaded, drawn.
                save(hud, hudSource("health 20"))
                assertEquals(listOf(LiveEvent.Compiling, LiveEvent.Reload), apply(live.poll(), session))
                assertEquals(listOf("health 20"), texts(session, backend))

                // Broken: the compiler's first error, with its file and line, and the last good build stays.
                save(hud, hudSource("health 30").replace("Text(\"health 30\")", "Text(notDefinedAnywhere)"))
                val failed = apply(live.poll(), session)
                val error = checkNotNull((failed.last() as LiveEvent.CompileFailed).result.error) {
                    "no compiler error was found in:\n${(failed.last() as LiveEvent.CompileFailed).result.output}"
                }
                assertTrue(error.file.endsWith("sample/Hud.kt"), error.toString())
                assertEquals(HudTextLine, error.line, error.toString())
                assertTrue("notDefinedAnywhere" in error.message, error.toString())
                assertTrue(session.banner is Banner.CompileFailed)
                assertEquals(listOf("health 20"), texts(session, backend))

                // Fixed: the banner goes.
                save(hud, hudSource("health 30"))
                assertEquals(listOf(LiveEvent.Compiling, LiveEvent.Reload), apply(live.poll(), session))
                assertNull(session.banner)
                assertEquals(listOf("health 30"), texts(session, backend))

                // A build file cannot be swapped in.
                save(File(project, "game/build.gradle.kts"), File(project, "game/build.gradle.kts").readText() + "\n// changed\n")
                val restart = apply(live.poll(), session).single()
                assertTrue(restart is LiveEvent.RestartNeeded, restart.toString())
                assertTrue(session.banner is Banner.RestartNeeded)
            }
        }
    }

    /** What the window does with events, without a window. */
    private fun apply(events: List<LiveEvent>, session: PreviewSession): List<LiveEvent> = events.onEach { event ->
        when (event) {
            LiveEvent.Reload -> session.reload()
            is LiveEvent.CompileFailed -> session.compileFailed(event.result)
            is LiveEvent.RestartNeeded -> session.restartNeeded(event.reason)
            else -> Unit
        }
    }

    private fun texts(session: PreviewSession, backend: HeadlessBackend): List<String> {
        val stage = checkNotNull(session.stage("hud")) { "no hud preview; found ${session.names}" }
        repeat(3) { stage.frame(it * 16_666_667L) }
        backend.canvas.clear()
        backend.canvas.begin(stage.viewport)
        stage.draw(DrawPass(backend.canvas))
        backend.canvas.end()
        return backend.canvas.calls.filterIsInstance<DrawCall.Text>().map { it.text }
    }

    private var stamp = System.currentTimeMillis()

    /** Written with a time that always moves on, so no filesystem clock hides a save. */
    private fun save(file: File, text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
        stamp += 2_000L
        file.setLastModified(stamp)
    }

    private fun hudSource(label: String) = """
        package sample

        import androidx.compose.runtime.Composable
        import dev.wildware.composegl.ui.preview.Preview
        import dev.wildware.composegl.ui.widget.Text

        @Preview(width = 200, height = 40, name = "hud")
        @Composable
        fun HudPreview() {
            Text("$label")
        }
    """.trimIndent() + "\n"

    private fun writeSample(project: File) {
        val kotlin = checkNotNull(System.getProperty("composegl.preview.it.kotlin")) { "run this through Gradle" }
        val classpath = checkNotNull(System.getProperty("composegl.preview.it.classpath")) { "run this through Gradle" }
        File(project, "ui-classpath.txt").writeText(classpath.split(File.pathSeparator).joinToString("\n"))
        File(project, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            dependencyResolutionManagement { repositories { mavenCentral(); google() } }
            rootProject.name = "sample"
            include(":game")
            """.trimIndent(),
        )
        File(project, "gradle.properties").writeText(
            """
            org.gradle.jvmargs=-Xmx768m
            org.gradle.daemon.idletimeout=60000
            kotlin.compiler.execution.strategy=in-process
            """.trimIndent(),
        )
        File(project, "game/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            plugins {
                kotlin("jvm") version "$kotlin"
                id("org.jetbrains.kotlin.plugin.compose") version "$kotlin"
            }
            dependencies {
                implementation(files(rootProject.file("ui-classpath.txt").readLines().filter { it.isNotBlank() }))
            }
            """.trimIndent(),
        )
    }

    private companion object {
        /** The line of `hudSource` the label is on. */
        const val HudTextLine = 10
    }
}
