package dev.wildware.composegl.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.UiBackend
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.preview.PreviewFunction
import dev.wildware.composegl.ui.widget.ProvideClipboard
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.ProvideHaptics
import dev.wildware.composegl.ui.widget.ProvideSoftKeyboard

/**
 * One preview, composed and running: a composition of its own, at the preview's own size.
 *
 * Each preview gets its own so that one that throws takes out its own tile and nothing else. What it
 * threw is kept as text in [failure] and its composition is disposed at once; the rest of the window
 * carries on.
 *
 * Holds the preview's build. [close] it before letting go of that build's [LoadedModule].
 */
class PreviewStage internal constructor(preview: PreviewFunction, backend: UiBackend) : AutoCloseable {

    /** The preview's name, as the sidebar shows it. */
    val name: String = preview.name

    /** Where it was declared, as `package.FileKt.function`, for messages. */
    val function: String = preview.function

    val width: Int = preview.width
    val height: Int = preview.height

    /** Laid out and drawn one to one at the preview's size. */
    val viewport: Viewport = Viewport.oneToOne(preview.size)

    /**
     * What the preview threw, as text, or null while it works.
     *
     * Text and not the exception: an exception of a class the preview's module declares would hold
     * that whole build in memory for as long as the window showed the message.
     */
    var failure: String? by mutableStateOf(null)
        private set

    private val host = UiHost()

    init {
        guard(Unit) {
            host.setContent {
                ProvideFonts(backend.fonts) {
                    ProvideClipboard(backend.clipboard) {
                        ProvideSoftKeyboard(backend.softKeyboard) {
                            ProvideHaptics(backend.haptics, preview.screen)
                        }
                    }
                }
            }
        }
    }

    /** One frame at [nanos]: recompose and lay out. True when something changed and it needs drawing. */
    fun frame(nanos: Long): Boolean {
        if (failure != null) return false
        return guard(false) { host.settle(viewport, nanos = nanos) }
    }

    /**
     * Draws the preview through [pass], inside a frame the caller has begun on the pass's canvas at
     * [viewport]. False, and nothing drawn, once it has failed.
     */
    fun draw(pass: DrawPass): Boolean {
        if (failure != null) return false
        return guard(false) {
            pass.draw(host.root)
            true
        }
    }

    override fun close() = host.dispose()

    private inline fun <T> guard(otherwise: T, block: () -> T): T = try {
        block()
    } catch (thrown: Throwable) {
        // A preview left as TODO() throws an Error, and is still only one broken preview. Running
        // out of the machine is not.
        if (thrown is VirtualMachineError) throw thrown
        failure = describe(thrown)
        runCatching { host.dispose() }
        otherwise
    }

    internal companion object {

        /** How many lines of a stack trace a tile has room for. */
        const val TraceLines = 12

        fun describe(thrown: Throwable): String =
            thrown.stackTraceToString().lineSequence().take(TraceLines).joinToString("\n").trimEnd()
    }
}
