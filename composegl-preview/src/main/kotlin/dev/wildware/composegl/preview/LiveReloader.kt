package dev.wildware.composegl.preview

import java.io.File

/**
 * What the files on disk say the window should do next.
 *
 * Polled rather than notified: one look at the disk per [poll], on a thread of its own in the
 * window. The same code on every operating system, and a test drives it one poll at a time.
 *
 * - A saved source file compiles the module through [compiler], then reloads it, or reports the
 *   compiler's error.
 * - A changed build file, or a compile that rebuilt one of the libraries the window loaded at the
 *   start, cannot be swapped in: the window has to be restarted, and says so, rather than run code
 *   compiled against a build that is not the one running.
 * - With no Gradle to ask, or once it cannot be reached, the compiled output folders are watched
 *   instead, for somebody running `./gradlew -t classes` to fill.
 *
 * @param sources the module's source folders, which a save lands in.
 * @param outputs the module's compiled classes and resources, which a compile writes.
 * @param buildFiles build scripts, version catalogues and property files. Any change means a restart.
 * @param kept the kept layer's classpath: the library jars and folders loaded once. Any change means
 *   a restart.
 * @param compiler what compiles the module, or null to watch [outputs] from the start.
 * @param advice the command to tell somebody to run when the output is what is watched.
 */
class LiveReloader(
    private val sources: List<File>,
    private val outputs: List<File>,
    private val buildFiles: List<File>,
    private val kept: List<File>,
    private var compiler: ModuleCompiler?,
    private val advice: String,
) {

    private var sourcesSeen = FileSnapshot.of(sources)
    private var outputsSeen = FileSnapshot.of(outputs)
    private val buildFilesSeen = FileSnapshot.of(buildFiles)
    private val keptSeen = FileSnapshot.of(kept)

    /** The output as the last poll found it, while it differs from [outputsSeen] and may still be being written. */
    private var outputsSettling: FileSnapshot? = null

    private var restart = false
    private var announced = false

    /** Whether the last compile Gradle ran failed. Nothing reloads until one works. */
    private var failing = false

    /** One look at the disk, and what the window should do about it. Usually nothing. */
    fun poll(): List<LiveEvent> = mutableListOf<LiveEvent>().also { events -> poll(events::add) }

    /**
     * The same look, handing each event to [emit] as it happens. [LiveEvent.Compiling] arrives before
     * the compile starts, so the window can say so while it runs.
     */
    fun poll(emit: (LiveEvent) -> Unit) {
        if (restart) return

        if (compiler == null && !announced) {
            announced = true
            emit(LiveEvent.WatchingOutput("no Gradle build to compile with", advice))
        }

        restartReason()?.let { return emit(restartWith(it)) }

        val active = compiler
        if (active != null) {
            val nowSources = FileSnapshot.of(sources)
            if (nowSources != sourcesSeen) {
                // Taken before the compile, so a file saved while it runs is compiled next time.
                sourcesSeen = nowSources
                emit(LiveEvent.Compiling)
                val result = try {
                    active.compile()
                } catch (failure: Exception) {
                    CompileResult.Failed(null, "the compile could not be run: $failure")
                }
                // Whatever the compile wrote, it wrote. A failed one can still touch the output —
                // resources processed, classes put back — and that is not a new build to load.
                outputsSeen = FileSnapshot.of(outputs)
                outputsSettling = null
                when (result) {
                    CompileResult.Compiled -> {
                        failing = false
                        restartReason()?.let { return emit(restartWith(it)) }
                        emit(LiveEvent.Reload)
                    }

                    is CompileResult.Failed -> {
                        failing = true
                        emit(LiveEvent.CompileFailed(result))
                    }

                    is CompileResult.Unreachable -> {
                        active.close()
                        compiler = null
                        announced = true
                        failing = false
                        emit(LiveEvent.WatchingOutput(result.reason, advice))
                    }
                }
                return
            }
        }

        // Output written by something else: an IDE, or `./gradlew -t classes`. Reloaded once two
        // looks in a row agree, so a compile still writing its classes is not loaded halfway.
        val nowOutputs = FileSnapshot.of(outputs)
        if (nowOutputs == outputsSeen) {
            outputsSettling = null
        } else if (nowOutputs == outputsSettling) {
            outputsSeen = nowOutputs
            outputsSettling = null
            // While Gradle's last compile is failing, the banner stays until one of its compiles works.
            if (!failing) emit(LiveEvent.Reload)
        } else {
            outputsSettling = nowOutputs
        }
    }

    private fun restartReason(): String? {
        FileSnapshot.of(buildFiles).changedFrom(buildFilesSeen).firstOrNull()?.let {
            return "${File(it).name} changed"
        }
        FileSnapshot.of(kept).changedFrom(keptSeen).firstOrNull()?.let {
            return "a library the window loaded at the start changed: ${File(it).name}"
        }
        return null
    }

    private fun restartWith(reason: String): LiveEvent.RestartNeeded {
        restart = true
        compiler?.close()
        return LiveEvent.RestartNeeded(reason)
    }
}

/** Something [LiveReloader] saw that the window acts on. */
sealed interface LiveEvent {

    /** A compile has started, and is still running. */
    data object Compiling : LiveEvent

    /** A new build is in the output folders: load it. */
    data object Reload : LiveEvent

    /** The compile failed; keep what is on screen and show why. */
    data class CompileFailed(val result: CompileResult.Failed) : LiveEvent

    /** Something that cannot be swapped in changed. Nothing reloads until the window is restarted. */
    data class RestartNeeded(val reason: String) : LiveEvent

    /** Gradle is not compiling for the window; the output folders are watched and [advice] fills them. */
    data class WatchingOutput(val reason: String, val advice: String) : LiveEvent
}

/**
 * Every file under some roots, with its size and when it was last written. Two are equal when
 * nothing was added, removed or rewritten.
 */
class FileSnapshot private constructor(private val files: Map<String, Long>) {

    /** The paths added, removed or rewritten since [before]. */
    fun changedFrom(before: FileSnapshot): List<String> =
        (files.keys + before.files.keys).filter { files[it] != before.files[it] }.sorted()

    override fun equals(other: Any?) = other is FileSnapshot && other.files == files

    override fun hashCode() = files.hashCode()

    companion object {

        fun of(roots: List<File>): FileSnapshot {
            val files = HashMap<String, Long>()
            roots.forEach { root ->
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    // Size folded in, so a rewrite inside one tick of a coarse clock still shows.
                    files[file.path] = file.lastModified() * 31 + file.length()
                }
            }
            return FileSnapshot(files)
        }
    }
}
