package dev.wildware.composegl.ui.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.time.TimeSource

/**
 * What one frame of interface cost, split into the three halves that can be fixed separately.
 *
 * When a game misses sixteen milliseconds because of its inventory screen, the first question is
 * always which part of it: the runtime working out what changed, the layout pass, or the drawing.
 * Those are three different bugs with three different fixes, and guessing between them is how an
 * afternoon goes missing.
 *
 * A [dev.wildware.composegl.ui.host.UiRenderer] keeps one and fills it in, so a game that draws its interface
 * with `ui.render(viewport, nanos)` already has this and only has to switch it on. A game that
 * writes the frame out itself does the wrapping:
 *
 * ```kotlin
 * val budget = FrameBudget()
 * val draw = DrawPass(canvas)
 * // in the game loop:
 * val changed = budget.recompose { host.frame(nanos) }
 * budget.layout { MeasurePass().run(host.root, viewport) }
 * canvas.begin(viewport)
 * budget.draw { draw.draw(host.root) }
 * canvas.end()
 * budget.endFrame(canvas.drawCalls, changed)
 * ```
 *
 * **Switched off it costs a boolean.** The three wrappers are inline, so a disabled budget compiles
 * down to a comparison and the call that was there anyway: no clock read, no allocation, no state
 * written. That matters because the whole point of this is to be left in a shipped game and turned
 * on when somebody complains, rather than to be a thing you add while debugging and then remove.
 *
 * The numbers the overlay reads are only refreshed every [publishEveryMillis], for two reasons: a
 * figure that changes sixty times a second cannot be read, and writing Compose state every frame
 * would make the overlay itself the thing that redraws the screen.
 *
 * Times are wall clock, which is what a frame budget is measured in. On a machine with no
 * high-resolution clock — a browser with its timers deliberately blunted, say — they will be
 * coarse rather than wrong.
 *
 * @param window how many frames the averages and the worst case are taken over.
 * @param publishEveryMillis how often [reading] is refreshed.
 */
class FrameBudget(
    val window: Int = 120,
    val publishEveryMillis: Long = 250L,
) {

    /**
     * Whether to measure anything at all.
     *
     * Readable from a composition — an overlay switched on with a key appears on the next frame —
     * and read on the hot path from a plain field, so measuring nothing really does cost nothing.
     */
    var isOn: Boolean
        get() = shown
        set(value) {
            if (measuring == value) return
            measuring = value
            shown = value
            if (!value) reading = FrameReading.Nothing
        }

    private var shown: Boolean by mutableStateOf(true)

    /** The plain mirror of [isOn]. Public only because the wrappers below are inline. */
    @PublishedApi
    internal var measuring: Boolean = true
        private set

    /** The last published numbers. Refreshed every [publishEveryMillis] while [isOn]. */
    var reading: FrameReading by mutableStateOf(FrameReading.Nothing)
        private set

    private val recomposeNanos = LongArray(window)
    private val layoutNanos = LongArray(window)
    private val drawNanos = LongArray(window)
    private val totalNanos = LongArray(window)
    private var at = 0
    private var filled = 0

    private var frameRecompose = 0L
    private var frameLayout = 0L
    private var frameDraw = 0L

    private var frames = 0L
    private var redraws = 0L

    /** One mark, made once: the difference between two readings off it is monotonic elapsed time. */
    private val started = TimeSource.Monotonic.markNow()
    private var lastPublishNanos = -publishEveryMillis * 1_000_000

    fun toggle() {
        isOn = !measuring
    }

    /** Times the Compose runtime's own work: everything [dev.wildware.composegl.ui.host.UiHost.frame] does. */
    inline fun <T> recompose(block: () -> T): T {
        if (!measuring) return block()
        val start = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            addRecompose(start.elapsedNow().inWholeNanoseconds)
        }
    }

    /** Times the layout pass. */
    inline fun <T> layout(block: () -> T): T {
        if (!measuring) return block()
        val start = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            addLayout(start.elapsedNow().inWholeNanoseconds)
        }
    }

    /** Times the draw pass. Not the flush: what the GPU then does with it is not ours to claim. */
    inline fun <T> draw(block: () -> T): T {
        if (!measuring) return block()
        val start = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            addDraw(start.elapsedNow().inWholeNanoseconds)
        }
    }

    @PublishedApi
    internal fun addRecompose(nanos: Long) {
        frameRecompose += nanos
    }

    @PublishedApi
    internal fun addLayout(nanos: Long) {
        frameLayout += nanos
    }

    @PublishedApi
    internal fun addDraw(nanos: Long) {
        frameDraw += nanos
    }

    /**
     * Closes the frame off and, every so often, publishes what the overlay shows.
     *
     * @param drawCalls what the canvas says the frame cost, or -1 when the backend does not count.
     *   [dev.wildware.composegl.ui.graphics.UiCanvas.drawCalls] is where it comes from.
     * @param redrew whether the tree actually changed, which is what `UiHost.frame` returned. The
     *   ratio of those to frames is the number that says whether building on the Compose runtime
     *   is earning its keep in this game.
     */
    fun endFrame(drawCalls: Int = -1, redrew: Boolean = true) {
        if (!measuring) return

        recomposeNanos[at] = frameRecompose
        layoutNanos[at] = frameLayout
        drawNanos[at] = frameDraw
        totalNanos[at] = frameRecompose + frameLayout + frameDraw
        at = (at + 1) % window
        if (filled < window) filled++

        frameRecompose = 0L
        frameLayout = 0L
        frameDraw = 0L

        frames++
        if (redrew) redraws++

        val stamp = started.elapsedNow().inWholeNanoseconds
        if (stamp - lastPublishNanos < publishEveryMillis * 1_000_000) return
        lastPublishNanos = stamp
        reading = FrameReading(
            recomposeMillis = mean(recomposeNanos),
            layoutMillis = mean(layoutNanos),
            drawMillis = mean(drawNanos),
            totalMillis = mean(totalNanos),
            worstMillis = worst(totalNanos),
            drawCalls = drawCalls,
            redraws = redraws,
            frames = frames,
        )
    }

    private fun mean(values: LongArray): Float {
        if (filled == 0) return 0f
        var sum = 0L
        for (index in 0 until filled) sum += values[index]
        return sum / filled / 1_000_000f
    }

    private fun worst(values: LongArray): Float {
        var most = 0L
        for (index in 0 until filled) if (values[index] > most) most = values[index]
        return most / 1_000_000f
    }

    /** Forgets everything measured so far. What a game calls after loading a level. */
    fun reset() {
        recomposeNanos.fill(0L)
        layoutNanos.fill(0L)
        drawNanos.fill(0L)
        totalNanos.fill(0L)
        at = 0
        filled = 0
        frameRecompose = 0L
        frameLayout = 0L
        frameDraw = 0L
        frames = 0L
        redraws = 0L
        lastPublishNanos = -publishEveryMillis * 1_000_000
        reading = FrameReading.Nothing
    }
}

/**
 * One published set of numbers, in milliseconds.
 *
 * Averages rather than the last frame, because a single frame of a sixty-frame second says almost
 * nothing and cannot be read off the screen anyway. [worstMillis] is the one that catches the
 * stutter an average hides.
 */
data class FrameReading(
    val recomposeMillis: Float,
    val layoutMillis: Float,
    val drawMillis: Float,
    val totalMillis: Float,
    val worstMillis: Float,
    /** -1 when the backend does not count them. */
    val drawCalls: Int,
    /** How many frames actually changed something. */
    val redraws: Long,
    /** How many frames there have been. */
    val frames: Long,
) {
    companion object {
        val Nothing = FrameReading(0f, 0f, 0f, 0f, 0f, -1, 0L, 0L)
    }
}
