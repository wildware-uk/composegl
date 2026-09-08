import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Keeps an unstable API confined to one file.
 *
 * Compose's scene API is `@InternalComposeUiApi` and changes between releases. That is fine as
 * long as every use of it is in one place, because then a Compose upgrade is a diff against one
 * file plus the integration suite. It stops being fine the moment someone adds an opt-in
 * somewhere convenient, which is why this is checked and not just written down.
 */
@CacheableTask
abstract class OptInConfinementCheck : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The annotation that may only appear in [allowedFileName]. */
    @get:Input
    abstract val annotation: Property<String>

    @get:Input
    abstract val allowedFileName: Property<String>

    @TaskAction
    fun check() {
        val marker = annotation.get()
        val allowed = allowedFileName.get()

        val offenders = sources.asFileTree.matching { include("**/*.kt") }
            .filter { it.name != allowed && it.readText().contains(marker) }
            .map { it.path }
            .sorted()

        if (offenders.isNotEmpty()) {
            throw IllegalStateException(
                buildString {
                    appendLine("$marker must only appear in $allowed, but it is also in:")
                    offenders.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine(
                        "Compose's internal API changes between releases. Keeping every use in " +
                            "one file is what makes upgrading it a job with a known size.",
                    )
                },
            )
        }
        logger.lifecycle("${project.name}: $marker is confined to $allowed")
    }
}
