package dev.wildware.composegl.preview

import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.BuildException
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Compiles the module the window is showing.
 *
 * An interface so the reload logic can be tested with a compiler that answers what a test says,
 * and run for real with [GradleCompiler].
 */
fun interface ModuleCompiler : AutoCloseable {

    /** Compiles, waiting until it is done. Called off the drawing thread. */
    fun compile(): CompileResult

    override fun close() {}
}

/** How a compile went. */
sealed interface CompileResult {

    /** The module's output folders hold the new build. */
    data object Compiled : CompileResult

    /**
     * The build ran and failed.
     *
     * @param error the compiler's first error, or null when the build failed some other way.
     * @param output what the build printed, for when there is no [error] to show.
     */
    data class Failed(val error: CompileError?, val output: String) : CompileResult {

        /** One thing to put on the banner: the first error, or the build's own account of what went wrong. */
        val summary: String
            get() = error?.toString() ?: whatWentWrong(output)

        private fun whatWentWrong(output: String): String {
            val lines = output.lines()
            val start = lines.indexOfFirst { it.trim() == "* What went wrong:" }
            val picked = if (start >= 0) {
                lines.drop(start + 1).takeWhile { it.isNotBlank() && !it.startsWith("* ") }
            } else {
                lines.filter { it.isNotBlank() }.takeLast(SummaryLines)
            }
            return picked.take(SummaryLines).joinToString("\n").trim().ifEmpty { "the build failed" }
        }
    }

    /** Gradle could not be reached at all: no daemon, no project there, no Tooling API. */
    data class Unreachable(val reason: String) : CompileResult

    private companion object {
        const val SummaryLines = 6
    }
}

/**
 * Compiles through the Gradle Tooling API: the same build, the same daemon and the same incremental
 * compile as `./gradlew`, asked for [tasks] and nothing else.
 *
 * @param projectDir the build's root folder.
 * @param tasks what to run, such as `:my-game:previewClasses`.
 * @param gradleHome the Gradle installation to use, so nothing is downloaded. Null uses the build's
 *   wrapper.
 */
class GradleCompiler(
    private val projectDir: File,
    private val tasks: List<String>,
    private val gradleHome: File? = null,
) : ModuleCompiler {

    @Volatile
    private var connection: ProjectConnection? = null

    override fun compile(): CompileResult {
        val output = ByteArrayOutputStream()
        return try {
            val project = connection ?: connect().also { connection = it }
            project.newBuild()
                .forTasks(*tasks.toTypedArray())
                .setStandardOutput(output)
                .setStandardError(output)
                .setColorOutput(false)
                .run()
            CompileResult.Compiled
        } catch (_: BuildException) {
            failedWith(output)
        } catch (cancelled: BuildCancelledException) {
            CompileResult.Failed(null, output.toString() + "\n" + cancelled.message)
        } catch (unreachable: GradleConnectionException) {
            close()
            CompileResult.Unreachable(unreachable.message ?: unreachable.toString())
        } catch (unreachable: IllegalStateException) {
            // What the connector throws for a folder that is not there.
            close()
            CompileResult.Unreachable(unreachable.message ?: unreachable.toString())
        } catch (missing: LinkageError) {
            // The Tooling API is not on the classpath at all.
            CompileResult.Unreachable("the Gradle Tooling API could not be loaded: $missing")
        }
    }

    private fun failedWith(output: ByteArrayOutputStream): CompileResult.Failed {
        val text = output.toString()
        return CompileResult.Failed(CompileError.firstIn(text), text)
    }

    private fun connect(): ProjectConnection {
        val connector = GradleConnector.newConnector().forProjectDirectory(projectDir)
        gradleHome?.takeIf { it.isDirectory }?.let(connector::useInstallation)
        return connector.connect()
    }

    override fun close() {
        runCatching { connection?.close() }
        connection = null
    }
}
