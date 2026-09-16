package dev.wildware.composegl.preview

import dev.wildware.composegl.lwjgl3.GlfwWindow
import dev.wildware.composegl.lwjgl3.Lwjgl3Backend
import dev.wildware.composegl.lwjgl3.StbFonts
import org.lwjgl.glfw.GLFW
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Opens the live preview window and keeps it open until it is closed.
 *
 * The session's kept layer is whatever loaded this class: the JDK, the Compose runtime, composegl
 * and the module's libraries, on the classpath `previewLive` built. The module's own classes must
 * not be on that classpath; they are loaded, and reloaded, from [PreviewLiveOptions.classes].
 *
 * Two threads besides the window's: one looks at the files twice a second and compiles when a source
 * was saved, and one watches for a frame that never finishes and names the preview it was drawing.
 */
fun runPreviewLive(options: PreviewLiveOptions, log: (String) -> Unit = System.err::println) {
    // Before the window: a mistyped path is a mistake in the command, not in a preview.
    options.fonts.forEach { font ->
        require(font.file.isFile) { "there is no font at ${font.file} for \"${font.family}\"" }
    }

    val loadedFrom = PreviewSession::class.java.classLoader
    val module = ReloadableModule(options.classes, options.resources, loadedFrom, options.packageName)
    val compiler = if (options.compiles) GradleCompiler(checkNotNull(options.projectDir), options.tasks, options.gradleHome) else null
    val reloader = LiveReloader(
        sources = options.sources,
        outputs = options.classes + options.resources,
        buildFiles = options.buildFiles,
        kept = keptClasspath(options),
        compiler = compiler,
        advice = options.advice,
    )
    val watchdog = HangWatchdog()
    val running = AtomicBoolean(true)

    GlfwWindow("composegl previews", 1400, 900).use { window ->
        val fonts = StbFonts(pageSize = 2048)
        options.fonts.forEach { font -> fonts.register(font.family, font.file.readBytes(), font.sizes) }
        Lwjgl3Backend(window, fonts).use { backend ->
            PreviewSession(module, backend, watchdog).use { session ->
                PreviewWindow(window, backend, session, watchdog).use { live ->
                    // The window and fonts are made; the clock starts now, and the watchdog is already
                    // watching when the first build is composed, so a preview that hangs straight
                    // away is still named.
                    watchdog.frameDone()
                    startThreads(reloader, watchdog, live, running, log)
                    session.reload()
                    try {
                        while (!window.shouldClose()) {
                            val busy = live.frame(System.nanoTime())
                            window.present()
                            // Nothing moving: wait for input, or the next look at the files, rather
                            // than drawing the same frame as fast as the driver allows.
                            if (!busy) GLFW.glfwWaitEventsTimeout(IdleSeconds)
                        }
                    } finally {
                        running.set(false)
                        compiler?.close()
                    }
                }
            }
        }
    }
}

private fun startThreads(
    reloader: LiveReloader,
    watchdog: HangWatchdog,
    live: PreviewWindow,
    running: AtomicBoolean,
    log: (String) -> Unit,
) {
    thread(isDaemon = true, name = "composegl-preview files") {
        while (running.get()) {
            try {
                reloader.poll(live::post)
            } catch (failure: Exception) {
                log("previewLive: could not check for changes: $failure")
            }
            Thread.sleep(PollMillis)
        }
    }
    thread(isDaemon = true, name = "composegl-preview watchdog") {
        while (running.get()) {
            // The window cannot say anything while it is stuck, so this says it where the command was run.
            watchdog.check()?.let { hang -> log("previewLive: ${hang.message}") }
            Thread.sleep(PollMillis)
        }
    }
}

/**
 * The kept layer's classpath, less the module's own output: the libraries loaded once. A change to
 * any of them cannot be swapped in, so [LiveReloader] watches them to ask for a restart.
 */
private fun keptClasspath(options: PreviewLiveOptions): List<File> {
    val module = (options.classes + options.resources).map { it.absoluteFile }.toSet()
    return System.getProperty("java.class.path").orEmpty()
        .split(File.pathSeparatorChar)
        .filter { it.isNotEmpty() }
        .map { File(it).absoluteFile }
        .filter { it !in module && it.exists() }
}

private const val PollMillis = 500L
private const val IdleSeconds = 1.0 / 60.0

/**
 * `previewLive --classes build/classes/kotlin/main --sources src/main/kotlin --font default=font.ttf
 * --project-dir . --task :my-game:previewClasses`
 *
 * What a module's `previewLive` Gradle task runs. See the wiki's Live previews page for the task.
 */
fun main(args: Array<String>) {
    runPreviewLive(PreviewLiveOptions.parse(args.toList()))
}
