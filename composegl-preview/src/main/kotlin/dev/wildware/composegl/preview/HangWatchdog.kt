package dev.wildware.composegl.preview

import java.util.Locale

/**
 * Notices when the window has stopped drawing, and says which preview it was drawing at the time.
 *
 * A preview stuck in a loop hangs the thread that draws, so the window cannot say anything itself.
 * The drawing thread tells this what it is [working] on and when a frame is [frameDone]; another
 * thread calls [check] now and then, and gets a [Hang] once no frame has finished for [limitNanos].
 *
 * Nothing in here reads a real clock unless it is given one. A test hands [nanoTime] a number it
 * moves itself, the way `FrameBudget` is tested.
 *
 * @param limitNanos how long without a frame counts as a hang. A few seconds: longer than any
 *   honest frame, short enough that nobody has given up and killed the window yet.
 * @param nanoTime nanoseconds from some fixed point. `System.nanoTime` in the window.
 */
class HangWatchdog(
    val limitNanos: Long = 5_000_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) {

    @Volatile
    private var doing: String = "starting up"

    @Volatile
    private var lastFrame: Long = nanoTime()

    @Volatile
    private var reported: Hang? = null

    /** What the drawing thread is about to do: `preview "pause-menu" (com.game.MenusKt.PausePreview)`. */
    fun working(on: String) {
        doing = on
    }

    /**
     * A frame finished. Returns the hang it had been stuck in, if [check] had already reported one,
     * so the window can say how long it took once it can say anything at all.
     */
    fun frameDone(): Hang? = synchronized(lock) {
        val now = nanoTime()
        val ended = reported?.let { Hang(it.doing, now - lastFrame) }
        lastFrame = now
        reported = null
        ended
    }

    /** A hang, the first time this one is noticed; null otherwise. Safe to call from any thread. */
    fun check(): Hang? = synchronized(lock) {
        if (reported != null) return null
        val stalled = nanoTime() - lastFrame
        if (stalled < limitNanos) return null
        Hang(doing, stalled).also { reported = it }
    }

    /** [check] runs on one thread and [frameDone] on another; each reads what the other writes. */
    private val lock = Any()
}

/**
 * No frame for [nanos], while the window was doing [doing].
 */
data class Hang(val doing: String, val nanos: Long) {

    val seconds: String get() = String.format(Locale.ROOT, "%.1f", nanos / 1_000_000_000.0)

    /** What to tell somebody, naming the preview so they know what to fix. */
    val message: String
        get() = "no frame for $seconds s while drawing $doing. It may be stuck in a loop: fix it, " +
            "then restart the preview."
}
