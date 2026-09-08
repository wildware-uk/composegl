package composegl

import org.jetbrains.skia.DirectContext

/** Thrown when the machine, driver, or GL context cannot host Compose rendering. */
class ComposeGlUnsupportedException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * @param resourceCacheBytes how much GPU memory Skia may hold in its own cache. The default is
 *   enough for a full-screen HUD with room to spare; lower it on memory-tight targets.
 * @param debugChecks captures the creating thread and asserts every later call is on it, and
 *   reports GL errors after rendering. Costs a thread comparison per call. Worth it while
 *   developing: without it, a call from the wrong thread is a GL crash several frames later with
 *   a stack trace that points nowhere useful.
 */
data class ContextConfig(
    val resourceCacheBytes: Long = 64L * 1024 * 1024,
    val debugChecks: Boolean = false,
)

/**
 * One per GL context. Owns Skia's `DirectContext` and the dispatcher that all Compose coroutines
 * for its surfaces run on.
 *
 * Create it with the GL context current, and call every method — on the context and on every
 * [ComposeSurface] it owns — on that same thread.
 */
class ComposeGlContext private constructor(
    internal val directContext: DirectContext?,
    internal val config: ContextConfig,
) {

    companion object {
        /**
         * Wraps the GL context that is current on the calling thread.
         *
         * @throws ComposeGlUnsupportedException if Skia cannot wrap it, or if the Skiko native
         *   library is missing.
         */
        fun create(config: ContextConfig = ContextConfig()): ComposeGlContext {
            val direct = try {
                DirectContext.makeGL()
            } catch (e: UnsatisfiedLinkError) {
                throw ComposeGlUnsupportedException(skikoMissingMessage(), e)
            } catch (e: NoClassDefFoundError) {
                throw ComposeGlUnsupportedException(skikoMissingMessage(), e)
            } catch (e: Throwable) {
                throw ComposeGlUnsupportedException(
                    "Skia could not wrap the current OpenGL context. ComposeGL needs OpenGL 3.0 or " +
                        "newer, and a context current on this thread. On LibGDX LWJGL3, call " +
                        "config.useOpenGL3(true, 3, 2) before Lwjgl3Application.",
                    e,
                )
            }
            direct.resourceCacheLimit = config.resourceCacheBytes
            return ComposeGlContext(direct, config)
        }

        /**
         * A context with no GPU behind it. Surfaces on it accept [RenderTarget.Raster] only, and
         * draw on the CPU. Used by the headless tests, and usable anywhere there is no GL context.
         */
        fun createRaster(config: ContextConfig = ContextConfig()): ComposeGlContext =
            ComposeGlContext(null, config)

        private fun skikoMissingMessage(): String {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val arch = System.getProperty("os.arch").orEmpty().lowercase()
            val cpu = if (arch == "aarch64" || arch.startsWith("arm")) "arm64" else "x64"
            val target = when {
                os.contains("mac") || os.contains("darwin") -> "macos-$cpu"
                os.contains("win") -> "windows-$cpu"
                else -> "linux-$cpu"
            }
            return "Skiko native library not found for $target. Add " +
                "runtimeOnly(\"org.jetbrains.skiko:skiko-awt-runtime-$target:<version>\") to the " +
                "module that runs your game."
        }
    }

    /** Shared by every surface on this context, so one drain covers all of them. */
    internal val dispatcher = GameLoopDispatcher()

    private val thread: Thread? = if (config.debugChecks) Thread.currentThread() else null
    private val surfaces = mutableListOf<ComposeSurface>()

    var isDisposed: Boolean = false
        private set

    /**
     * Fails loudly, and early, when a call comes from the wrong thread. Only active with
     * [ContextConfig.debugChecks].
     */
    internal fun assertGlThread() {
        val expected = thread ?: return
        val actual = Thread.currentThread()
        check(expected === actual) {
            "ComposeGL must be used on the thread its context was created on " +
                "(expected '${expected.name}', got '${actual.name}')"
        }
    }

    internal fun register(surface: ComposeSurface) {
        check(!isDisposed) { "This ComposeGlContext has been disposed" }
        surfaces += surface
    }

    internal fun unregister(surface: ComposeSurface) {
        surfaces -= surface
    }

    /** Disposes every surface still open, then Skia's context. Safe to call twice. */
    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        // dispose() unregisters, so iterate a copy
        surfaces.toList().forEach { it.dispose() }
        surfaces.clear()
        directContext?.close()
    }
}
