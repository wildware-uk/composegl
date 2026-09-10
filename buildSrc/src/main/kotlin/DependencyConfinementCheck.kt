import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a module's runtime classpath grows something it was not allowed to grow.
 *
 * `composegl-ui` is only useful on Android and iOS because it depends on the Compose *runtime* and
 * nothing else. That is a promise about a dependency graph, and dependency graphs grow quietly:
 * Compose's own `animation-core` pulls in the whole of Compose UI, and skiko arrives with it. One
 * convenient import in one file and the platform story is gone, with nothing failing until
 * somebody tries to build for a phone months later.
 *
 * So the allowed list is written down and checked, rather than remembered.
 */
abstract class DependencyConfinementCheck : DefaultTask() {

    /** `group:name` of every module allowed on the classpath, transitive ones included. */
    @get:Input
    abstract val allowed: ListProperty<String>

    /** Why the list is what it is, printed when something turns up that is not on it. */
    @get:Input
    abstract val reason: Property<String>

    /**
     * The resolved graph, captured at configuration time so the task itself stays cacheable and
     * does not reach back into the project while it runs.
     */
    @get:Input
    abstract val resolved: ListProperty<String>

    @TaskAction
    fun check() {
        val permitted = allowed.get().toSet()
        val found = resolved.get().sorted()
        val strangers = found.filterNot { it in permitted }

        if (strangers.isNotEmpty()) {
            throw IllegalStateException(
                buildString {
                    appendLine("${path.substringBeforeLast(':')} depends on modules it is not allowed to depend on:")
                    strangers.forEach { appendLine("    $it") }
                    appendLine()
                    appendLine(reason.get())
                    appendLine()
                    appendLine("If one of these is genuinely fine, add it to `allowed` in the build file")
                    append("and say in the same commit why it does not break the platform story.")
                },
            )
        }

        logger.lifecycle("${found.size} modules on the classpath, all of them expected.")
    }

    companion object {
        /** Every module in a resolved graph as `group:name`, the root project itself excluded. */
        fun modulesOf(root: ResolvedComponentResult): List<String> =
            buildSet {
                fun walk(component: ResolvedComponentResult, seen: MutableSet<String>) {
                    val id = component.moduleVersion ?: return
                    if (!seen.add("${id.group}:${id.name}")) return
                    add("${id.group}:${id.name}")
                    component.dependencies
                        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                        .forEach { walk(it.selected, seen) }
                }
                walk(root, mutableSetOf())
            }.toList()
    }
}
