package dev.wildware.composegl.ui.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
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
 * @param busiest how many of the nodes that changed most to list in [FrameReading.busiest]. Zero
 *   lists none, and leaves the tree not counting at all.
 */
class FrameBudget(
    val window: Int = 120,
    val publishEveryMillis: Long = 250L,
    busiest: Int = 0,
) {

    /**
     * How many of the tree's busiest nodes each [reading] names: the ones the most frames changed,
     * most first, counted since the budget started listing them or was [reset].
     *
     * The answer to "what keeps redrawing?" A still screen lists nothing, and a node recomposed with
     * a lambda written inline climbs every frame it recomposes, so it is at the top within a second.
     * The tree it counts is the one [watch] was handed, which a [dev.wildware.composegl.ui.host.UiRenderer]
     * does for itself. Counting costs an increment per change and runs only while this is above zero
     * and the budget [isOn]; the list is made when the reading is published, not every frame.
     */
    var busiest: Int = busiest
        set(value) {
            field = value
            syncWatching()
        }

    private var tree: UiTree? = null

    /** The tree whose counts this budget is relying on right now, or null. */
    private var watching: UiTree? = null

    /**
     * The tree [busiest] is counted on. A renderer calls this with its own; a game writing its frame
     * out itself calls it once with `host.tree`.
     */
    fun watch(tree: UiTree) {
        this.tree = tree
        syncWatching()
    }

    private fun syncWatching() {
        val wanted = if (measuring && busiest > 0) tree else null
        if (wanted === watching) return
        watching?.stopWatchingChanges()
        wanted?.watchChanges()
        watching = wanted
    }

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
            syncWatching()
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

    /**
     * Who cut this frame's batch, and why.
     *
     * A [dev.wildware.composegl.ui.host.UiRenderer] hands it to the canvas and the draw pass while
     * the budget [isOn], so a game drawing through one has it already. A game writing the frame out
     * itself sets `DrawPass.trace` and calls `canvas.traceDrawCalls` with it. Cleared by [endFrame],
     * which publishes what it holds as [FrameReading.culprits].
     */
    val trace = DrawCallTrace()

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
        if (stamp - lastPublishNanos < publishEveryMillis * 1_000_000) {
            trace.clear()
            return
        }
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
            // The published frame's, like the draw call count beside it: a still screen cuts its
            // batch in the same places every frame, and an average of whole calls reads worse.
            culprits = trace.culprits(),
            busiest = watching?.let { busiestIn(it.root, busiest) } ?: emptyList(),
        )
        trace.clear()
    }

    /** The [count] nodes the most frames changed, most first and in tree order where two are level. */
    private fun busiestIn(root: UiNode, count: Int): List<BusyNode> {
        val changed = mutableListOf<UiNode>()
        collectChanged(root, changed)
        return changed.sortedByDescending { it.changes }.take(count).map { BusyNode(it.name, it.testTag, it.changes) }
    }

    /**
     * Every node that has changed, parents first, but the debug overlays and what is inside them: the
     * budget's own numbers change four times a second by design and are not what anybody is looking for.
     */
    private fun collectChanged(node: UiNode, into: MutableList<UiNode>) {
        if (isDebugOverlay(node)) return
        if (node.changes > 0) into += node
        val children = node.children
        for (index in children.indices) collectChanged(children[index], into)
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
        trace.clear()
        reading = FrameReading.Nothing
        watching?.resetChangeCounts()
    }

    companion object {
        /**
         * What the frame budget overlay in `composegl-debug` calls the box it puts the numbers in. A
         * node of this name, and everything under it, is left out of [FrameReading.busiest]: the
         * numbers change four times a second by design and would otherwise top every list.
         */
        const val OverlayName = "frame budget"
    }
}

/**
 * One of the nodes that changed most, as a reading names it.
 *
 * Names rather than the node, so a reading stays a value: equal when nothing about it moved, and
 * holding on to nothing a screen has since thrown away.
 */
data class BusyNode(
    /** What the node is called: `text`, `box`, whatever the widget named it. */
    val name: String,
    /** Its test tag, when it has one. */
    val tag: String?,
    /** How many frames changed it. See [UiNode.changes]. */
    val changes: Int,
) {
    /** The tag as `#tag` when there is one, the name otherwise. What the overlay prints. */
    val label: String get() = tag?.let { "#$it" } ?: name
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
    /**
     * What the published frame's draw calls went on, most first, the frame's closing call left out.
     * Empty when the canvas does not trace them; see
     * [dev.wildware.composegl.ui.graphics.UiCanvas.tracesDrawCalls].
     */
    val culprits: List<DrawCallCulprit> = emptyList(),
    /** The nodes the most frames changed, most first. Empty unless [FrameBudget.busiest] asks. */
    val busiest: List<BusyNode> = emptyList(),
) {
    companion object {
        val Nothing = FrameReading(0f, 0f, 0f, 0f, 0f, -1, 0L, 0L)
    }
}
