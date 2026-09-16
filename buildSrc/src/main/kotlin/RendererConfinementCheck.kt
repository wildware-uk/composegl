import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when renderer code creeps back into a backend.
 *
 * The library draws the interface itself, in `composegl-render`. A backend is a thin wrapper: a
 * `Gl` binding of one-liners, a glyph rasteriser, a texture bridge and its input. This is the check
 * that keeps it that way, because the drift back to "every backend carries its own renderer" is one
 * convenient shader or draw call at a time.
 *
 * It reads the backend's main source, comments aside, and fails when:
 *
 * 1. shader text appears anywhere — shaders live in `composegl-render`;
 * 2. a draw, shader, blend, vertex-layout or framebuffer call appears outside the binding file (and
 *    any file named as also allowed, such as an engine's state-restore file);
 * 3. the binding file is missing, does not implement `Gl`, or is longer than [maxBindingLines] — a
 *    sign that logic crept into it.
 *
 * Switched on per backend with one line: `confineRenderer("LwjglGl.kt")`.
 */
@CacheableTask
abstract class RendererConfinementCheck : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** File names that implement `render.gl.Gl`. The first one must exist. */
    @get:Input
    abstract val bindingFile: Property<String>

    /** Further file names allowed to make GL state calls — an engine's state-restore file. */
    @get:Input
    abstract val alsoAllowed: ListProperty<String>

    @get:Input
    abstract val maxBindingLines: Property<Int>

    /**
     * The module's name, for the messages. Read here rather than from `project` at execution time,
     * which the configuration cache forbids.
     */
    @get:Internal
    abstract val moduleName: Property<String>

    init {
        alsoAllowed.convention(emptyList())
        maxBindingLines.convention(400)
        moduleName.convention(project.name)
    }

    @TaskAction
    fun check() {
        val offences = mutableListOf<String>()
        val binding = bindingFile.get()
        val allowed = setOf(binding) + alsoAllowed.get()
        val files = sources.asFileTree.matching { include("**/*.kt") }.files.sortedBy { it.path }

        val bindings = files.filter { it.name == binding }
        if (bindings.isEmpty()) offences += "  there is no $binding: a backend draws through one Gl binding file"
        bindings.forEach { file ->
            val text = file.readText()
            val lines = text.lines().size
            if (lines > maxBindingLines.get()) {
                offences += "  ${file.name} is $lines lines, over ${maxBindingLines.get()}: logic has crept into the binding"
            }
            if (!Regex(""":\s*Gl\s*\{""").containsMatchIn(text)) {
                offences += "  ${file.name} does not implement dev.wildware.composegl.render.gl.Gl"
            }
        }

        files.forEach { file ->
            file.readLines().forEachIndexed { index, raw ->
                // Documentation may name a GL call, as a game's own drawing would make it.
                val trimmed = raw.trimStart()
                if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return@forEachIndexed
                val line = raw.substringBefore(" // ")
                ShaderText.find(line)?.let {
                    offences += "  ${file.name}:${index + 1} has shader text (\"${it.value}\"); shaders live in composegl-render"
                }
                if (file.name !in allowed) {
                    DrawCalls.find(line)?.let {
                        offences += "  ${file.name}:${index + 1} calls ${it.value.trimEnd('(')} outside $binding"
                    }
                }
            }
        }

        if (offences.isNotEmpty()) {
            throw IllegalStateException(
                buildString {
                    appendLine("${offences.size} renderer offence(s) in ${moduleName.get()}:")
                    offences.forEach { appendLine(it) }
                    appendLine()
                    append(
                        "The interface is drawn by composegl-render. A backend supplies a Gl binding, a " +
                            "glyph rasteriser and a texture resolver, and nothing that draws.",
                    )
                },
            )
        }
        logger.lifecycle("${moduleName.get()}: renderer confined to composegl-render (${files.size} files checked)")
    }

    private companion object {
        val ShaderText = Regex("""gl_FragColor|gl_Position|texture2D\(|#version|precision mediump|varying |attribute vec""")

        val DrawCalls = Regex(
            """glDrawElements|drawElements\(|glDrawArrays|drawArrays\(|glShaderSource|shaderSource\(|""" +
                """glCreateProgram|createProgram\(|glBlendFunc|blendFunc|glVertexAttribPointer|""" +
                """vertexAttribPointer\(|glBindFramebuffer|bindFramebuffer\(""",
        )
    }
}

/**
 * Switches [RendererConfinementCheck] on for this backend and hangs it off `check`.
 *
 * @param binding the file name of the backend's `Gl` implementation.
 * @param alsoAllowed file names that may also make GL state calls, such as a state-restore file.
 */
fun Project.confineRenderer(binding: String, alsoAllowed: List<String> = emptyList()) {
    val task = tasks.register("checkRendererConfinement", RendererConfinementCheck::class.java) {
        description = "Fails if renderer code (shaders, draw calls) appears outside the Gl binding."
        group = "verification"
        sources.from(fileTree("src") { include("*main/**/*.kt", "*Main/**/*.kt") })
        bindingFile.set(binding)
        this.alsoAllowed.set(alsoAllowed)
    }
    tasks.named("check") { dependsOn(task) }
}
