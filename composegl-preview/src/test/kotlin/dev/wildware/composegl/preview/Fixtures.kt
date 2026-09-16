package dev.wildware.composegl.preview

import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.graphics.DrawCall
import java.io.File
import java.lang.ref.WeakReference

/**
 * The fixture modules, compiled by the build into folders of their own, and a module output folder
 * to copy them into.
 *
 * The folder stands in for a module's `build/classes/kotlin/main` and `build/resources/main`, and
 * [install] is a compile: it replaces what is there with one fixture's build of some packages.
 */
class ModuleFolder(root: File) {

    val classes = File(root, "classes").apply { mkdirs() }
    val resources = File(root, "resources").apply { mkdirs() }

    /** Replaces the folder's contents with [packages] from fixture [build] (`fixtureV1`, …). */
    fun install(build: String, vararg packages: String) {
        classes.deleteRecursively()
        resources.deleteRecursively()
        classes.mkdirs()
        resources.mkdirs()
        add(build, *packages)
    }

    /** Adds [packages] from fixture [build] to what is already there. */
    fun add(build: String, vararg packages: String) {
        packages.forEach { name ->
            val path = "dev/wildware/composegl/preview/fixture/$name"
            roots(build, "classes").forEach { copy(File(it, path), File(classes, path)) }
            roots(build, "resources").forEach { copy(File(it, path), File(resources, path)) }
        }
    }

    fun module(scratch: File) = ReloadableModule(
        classes = listOf(classes),
        resources = listOf(resources),
        scratch = scratch,
    )

    private fun copy(from: File, to: File) {
        if (from.exists()) from.copyRecursively(to, overwrite = true)
    }

    private fun roots(build: String, kind: String): List<File> {
        val property = "composegl.preview.$build.$kind"
        val value = checkNotNull(System.getProperty(property)) { "$property is not set; run the tests through Gradle" }
        return value.split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File)
    }
}

/** Every piece of text [session] draws for the preview called [name], in one frame. */
fun PreviewSession.textsOf(name: String, backend: HeadlessBackend, nanos: Long = 0L): List<String> {
    val stage = checkNotNull(stage(name)) { "no preview called $name; there are ${names}" }
    // A few frames, so a coroutine the preview started has had its say.
    repeat(4) { frame -> stage.frame(nanos + frame * 16_666_667L) }
    backend.canvas.clear()
    backend.canvas.begin(stage.viewport)
    stage.draw(DrawPass(backend.canvas))
    backend.canvas.end()
    return backend.canvas.calls.filterIsInstance<DrawCall.Text>().map { it.text }
}

/**
 * Asks the collector for [reference]'s object until it is gone, a bounded number of times.
 *
 * A collection is a request, not a promise, so one call proves nothing either way. Each round
 * allocates a little to give the collector a reason, and nothing here waits on a clock.
 */
fun collected(reference: WeakReference<*>, attempts: Int = 50): Boolean {
    repeat(attempts) {
        if (reference.get() == null) return true
        System.gc()
        @Suppress("UNUSED_VARIABLE")
        val pressure = ByteArray(1 shl 20)
    }
    return reference.get() == null
}
