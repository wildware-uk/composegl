package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.backend.Clipboard
import korlibs.render.GameWindow
import korlibs.render.TextClipboardData
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.EventQueue

/**
 * The system clipboard, as KorGE's window reaches it.
 *
 * KorGE's clipboard is asynchronous — on desktop it hops onto AWT's event thread and back — and the
 * toolkit's is not: a paste wants the text now. KorGE runs its game loop on a thread of its own, so
 * waiting here for the event thread is a short, bounded wait and not a deadlock. On the event thread
 * itself it would be one, so there, and whenever the window has no clipboard, this answers with the
 * last text it was given instead.
 *
 * @param window the game's window, asked for each time rather than held, because a game builds its
 *   interface before KorGE has made one.
 */
class KorgeClipboard(private val window: () -> GameWindow?) : Clipboard {

    /** What this clipboard last wrote. The answer when the system's cannot be asked. */
    private var remembered: String? = null

    /**
     * What is on the clipboard, or null if there is nothing to paste. An empty string is nothing to
     * paste too, because a platform with nothing on its clipboard reports the two interchangeably.
     */
    override fun read(): String? {
        val system = window()
            ?.takeIf { !EventQueue.isDispatchThread() }
            ?.let { engine -> waitFor { (engine.clipboardRead() as? TextClipboardData)?.text } }
        return (system ?: remembered)?.takeIf { it.isNotEmpty() }
    }

    override fun write(text: String) {
        remembered = text
        val engine = window() ?: return
        if (EventQueue.isDispatchThread()) return
        waitFor { engine.clipboardWrite(TextClipboardData(text)) }
    }

    /** [block], given long enough to reach the event thread and come back, and null if it did not. */
    private fun <T> waitFor(block: suspend () -> T): T? =
        runCatching { runBlocking { withTimeoutOrNull(TimeoutMillis) { block() } } }.getOrNull()

    private companion object {
        const val TimeoutMillis = 500L
    }
}
