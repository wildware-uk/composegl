import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Turns a text file into a Kotlin source file holding its contents.
 *
 * For files that have to be readable on every platform a multiplatform module targets. Kotlin/Native
 * has no resource loader, a phone's assets are reached through the platform rather than through the
 * file system, and a module forbidden any dependency but the Compose runtime cannot bring one that
 * papers over the difference. Source compiles everywhere.
 *
 * The point of doing it this way round — a real file, embedded — rather than writing the contents
 * into Kotlin by hand is that the file stays the thing people edit. It can be opened in an editor
 * that knows its format, read by anybody who does not write Kotlin, and diffed as what it is.
 */
@CacheableTask
abstract class EmbedTextAsSource : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    /** The name of the generated property, e.g. `DEFAULT_SKIN_JSON`. Internal to its module. */
    @get:Input
    abstract val propertyName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = source.get().asFile
        val directory = outputDirectory.get().asFile.resolve(packageName.get().replace('.', '/'))
        directory.mkdirs()

        // A line per line, so that the generated file can be read and a compiler error in it points
        // somewhere recognisable.
        val lines = file.readText().lines().joinToString(",\n") { line -> "    \"${escape(line)}\"" }

        directory.resolve("${propertyName.get()}.kt").writeText(
            """
            |package ${packageName.get()}
            |
            |// Generated from ${file.name} by the embedDefaultSkin task. Edit the file, not this.
            |internal val ${propertyName.get()}: String = listOf(
            |$lines,
            |).joinToString("\n")
            |
            """.trimMargin(),
        )
    }

    private fun escape(line: String) = buildString {
        line.forEach { character ->
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character == '$' -> append("\${'$'}")
                character == '\t' -> append("\\t")
                character.code < 0x20 -> append("\\u%04X".format(character.code))
                else -> append(character)
            }
        }
    }
}
