package dev.wildware.composegl.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.UiBackend

/**
 * What the window has to say across the top, when something needs fixing.
 */
sealed interface Banner {

    val message: String

    /** The last compile failed. The previews on screen are the last build that worked. */
    data class CompileFailed(val summary: String) : Banner {
        override val message get() = "Compile failed, showing the last good build. $summary"
    }

    /** The build compiled but could not be loaded: a preview discovery refuses, or a class that will not link. */
    data class LoadFailed(val reason: String) : Banner {
        override val message get() = "Could not load the new build, showing the last good one. $reason"
    }

    /** Something that cannot be swapped in changed: a build file, or a library loaded at the start. */
    data class RestartNeeded(val reason: String) : Banner {
        override val message get() = "$reason, which cannot be reloaded: restart the preview."
    }
}

/**
 * Every preview in a module, composed, and the reload that swaps them for a new build.
 *
 * The part of the live window that needs no window: what is loaded, what is selected, and what
 * went wrong. [PreviewWindow] draws it; the tests drive it on a headless backend.
 *
 * **A reload, in order.** Dispose every composition of the old build. Load the new build in a new
 * class loader and find its previews with the discovery `renderPreviews` uses. Compose them. Only
 * then let go of the old build. Disposal comes first because a composition that is still alive is
 * reachable from the Compose runtime's own state, and through it, so is every class of the build it
 * was composed from: skip it and each reload leaks a whole build.
 *
 * If the new build cannot be loaded, the old one is still held, so its previews are composed again
 * and stay on screen under a banner.
 *
 * The selection, gallery mode and the window itself are in the kept layer, so they survive every
 * reload. What a preview remembered does not: each build starts its compositions fresh.
 *
 * Everything here runs on the drawing thread.
 *
 * @param module the module's own output, loaded again on each [reload].
 * @param backend fonts, clipboard and the rest, handed to every preview.
 * @param watchdog told which preview is being composed, so a hang names it.
 */
class PreviewSession(
    val module: ReloadableModule,
    private val backend: UiBackend,
    private val watchdog: HangWatchdog? = null,
) : AutoCloseable {

    private var loaded: LoadedModule? = null

    /** The build on screen, for the leak tests. Null before the first load. */
    internal val classLoader: ClassLoader? get() = loaded?.loader

    /** Every preview of the build on screen, sorted by name. */
    var stages: List<PreviewStage> = emptyList()
        private set

    /** The previews' names, in order. Only strings, so nothing a build declared is held by the state. */
    var names: List<String> by mutableStateOf(emptyList())
        private set

    /** Which preview is shown on its own, and highlighted in the list. */
    var selected: String? by mutableStateOf(null)
        private set

    /** Every preview as a tile, rather than the selected one alone. */
    var gallery: Boolean by mutableStateOf(false)

    var banner: Banner? by mutableStateOf(null)
        private set

    /** Something worth a line that is not wrong: the selection moved, a hang ended. */
    var notice: String? by mutableStateOf(null)
        private set

    /** How the window is being kept up to date: compiling, or watching the output for somebody else's compile. */
    var status: String? by mutableStateOf(null)

    /**
     * Counts every time [stages] is replaced: each load, and each time a failed load puts the last
     * good build back. What the window watches to know its pictures belong to old stages.
     */
    var builds: Int by mutableStateOf(0)
        private set

    /** The stage for [name] in the build on screen. */
    fun stage(name: String): PreviewStage? = stages.firstOrNull { it.name == name }

    /** What is on screen now: every stage in the gallery, the selected one otherwise. */
    fun visible(): List<PreviewStage> = if (gallery) stages else stages.filter { it.name == selected }

    /**
     * Loads the module's current build and shows it. False when nothing changed on screen: the build
     * could not be loaded, or a restart is needed first.
     */
    fun reload(): Boolean {
        if (banner is Banner.RestartNeeded) return false
        val previous = loaded

        // 1. Every composition of the old build goes, before a single class of the new one loads.
        disposeStages()

        // 2. A new class loader, and the previews in it.
        val next = try {
            watchdog?.working("loading the module's classes")
            module.load()
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            banner = Banner.LoadFailed(failure.message ?: failure.toString())
            // The old build was never let go of, so it goes straight back on screen.
            previous?.let(::compose)
            return false
        }

        // 3. Composed. 4. Only now is the old build dropped.
        loaded = next
        compose(next)
        previous?.close()

        if (banner !is Banner.RestartNeeded) banner = null
        keepSelection()
        return true
    }

    /** The compile failed: keep everything on screen and show its first error. */
    fun compileFailed(result: CompileResult.Failed) {
        status = null
        if (banner is Banner.RestartNeeded) return
        banner = Banner.CompileFailed(result.summary)
    }

    /** Something changed that cannot be reloaded. Stays until the window is restarted. */
    fun restartNeeded(reason: String) {
        status = null
        banner = Banner.RestartNeeded(reason)
    }

    /** A preview hung and has finally drawn a frame. */
    fun hangEnded(hang: Hang) {
        notice = "${hang.doing} took ${hang.seconds} s to draw a frame"
    }

    fun select(name: String) {
        if (stage(name) != null) {
            selected = name
            notice = null
        }
    }

    /** The next preview in the list, or the previous one for a negative [step], wrapping round. */
    fun selectNext(step: Int = 1) {
        if (names.isEmpty()) return
        val at = names.indexOf(selected).coerceAtLeast(0)
        select(names[Math.floorMod(at + step, names.size)])
    }

    /**
     * One frame of every visible preview. Returns the ones that changed and need drawing again.
     */
    fun frame(nanos: Long): List<PreviewStage> = visible().filter { stage ->
        watchdog?.working(describe(stage))
        stage.frame(nanos)
    }

    /** How the watchdog names [stage]. */
    fun describe(stage: PreviewStage) = "preview \"${stage.name}\" (${stage.function})"

    private fun compose(build: LoadedModule) {
        stages = build.previews.map { preview ->
            watchdog?.working("preview \"${preview.name}\" (${preview.function})")
            PreviewStage(preview, backend)
        }
        names = stages.map { it.name }
        builds++
    }

    private fun keepSelection() {
        val was = selected
        val now = when {
            names.isEmpty() -> null
            was != null && was in names -> was
            else -> names.first()
        }
        selected = now
        notice = when {
            names.isEmpty() -> "No @Preview functions found."
            was != null && was != now -> "\"$was\" is gone, so \"$now\" is selected instead."
            else -> null
        }
    }

    private fun disposeStages() {
        val old = stages
        stages = emptyList()
        old.forEach { stage -> runCatching { stage.close() } }
    }

    /** Disposes every composition and lets go of the build. */
    override fun close() {
        disposeStages()
        loaded?.close()
        loaded = null
    }
}
