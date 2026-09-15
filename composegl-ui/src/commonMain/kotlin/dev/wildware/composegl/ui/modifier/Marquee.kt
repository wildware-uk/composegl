package dev.wildware.composegl.ui.modifier

import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.draw.RectCache
import dev.wildware.composegl.ui.input.FrameWaiter
import dev.wildware.composegl.ui.layout.ConstraintsCache
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree

/** How many times a marquee goes round when it is told to go round for ever. */
const val MarqueeForever: Int = Int.MAX_VALUE

/**
 * What a [marquee] asked for. A data class, so a recomposition that says the same thing again is
 * the same element and the scroll carries on rather than starting over.
 */
data class MarqueeElement(
    val speed: Float,
    val delayMillis: Int,
    val spacing: Float,
    val iterations: Int,
    val clock: Clock,
) : Modifier.Element {

    init {
        require(speed > 0f && speed.isFinite()) { "a marquee has to move, so speed must be positive, was $speed" }
        require(delayMillis >= 0) { "a marquee cannot wait a negative time, was $delayMillis" }
        require(spacing >= 0f && spacing.isFinite()) { "a marquee's spacing cannot be negative, was $spacing" }
        require(iterations >= 1) { "a marquee goes round at least once, was $iterations" }
    }

    private val delayNanos: Long get() = delayMillis * 1_000_000L

    /** How long one trip across [distance] takes, never less than a nanosecond. */
    private fun travelNanos(distance: Float): Long =
        (distance / speed * 1_000_000_000.0).toLong().coerceAtLeast(1L)

    /**
     * How far the contents have moved left, [elapsed] nanoseconds of [clock] after the scroll began,
     * when one trip round is [distance] — the contents' width plus the [spacing].
     *
     * Each trip waits out the delay at rest and then travels. At the end of a trip the second copy,
     * which followed the first in at [distance], is exactly where the first one started, so going
     * back to zero is a jump nobody can see. Worked out from the time alone, so a frame dropped or a
     * clock paused lands on the same place it would have anyway.
     */
    internal fun offsetAt(elapsed: Long, distance: Float): Float {
        if (elapsed <= 0L || distance <= 0f || isFinishedAt(elapsed, distance)) return 0f
        val cycle = delayNanos + travelNanos(distance)
        val into = elapsed % cycle - delayNanos
        if (into <= 0L) return 0f
        return (into / 1_000_000_000.0 * speed).toFloat().coerceAtMost(distance)
    }

    /** Whether every trip asked for has been made, [elapsed] into the scroll. Never, for ever. */
    internal fun isFinishedAt(elapsed: Long, distance: Float): Boolean {
        if (iterations == MarqueeForever) return false
        val cycle = delayNanos + travelNanos(distance)
        // Divided rather than multiplied, so a long delay times many trips cannot run off the end.
        return elapsed / cycle >= iterations
    }
}

/**
 * Scrolls what this widget holds sideways, round and round, when it is too wide for its slot.
 *
 * ```kotlin
 * Text(trackName, Modifier.width(160f).marquee(speed = 30f, delayMillis = 1500))
 * ```
 *
 * A song title, a player's name, an item name in a narrow hotbar tooltip: text that would otherwise
 * wrap onto a line there is no room for, or lose its end to an ellipsis.
 *
 * The contents are measured with no limit on their width — a label stays on one line — and the
 * widget keeps the width it was given. When the contents fit, that is all that happens: nothing
 * moves, nothing is clipped, a centred label is still centred, and it costs no frames. When they do
 * not, the contents wait [delayMillis] at rest, slide left at [speed], and come round again with a
 * copy following [spacing] behind, so the loop has no seam. Everything is cut to the widget's box
 * inside its padding.
 *
 * It runs on [clock]. [Clock.Ui], the default, keeps going under a pause menu; a name on a panel in
 * the world wants [Clock.World] and stops with the game. Only the drawing moves: nothing
 * recomposes, layout does not change, and a frame is asked for only while the contents are actually
 * moving, so the wait at the start of each trip is free. A test harness waiting for the screen to
 * settle does not wait for a marquee, which would be waiting for ever.
 *
 * Where it sits in the chain does not matter; a widget scrolls or it does not. What it does not do
 * is move where things are clicked — it is for labels. A button inside a marquee is pressed where
 * layout put it, not where it has scrolled to.
 *
 * @param speed how fast the contents move, in the interface's units a second.
 * @param delayMillis how long they rest at the start of every trip, including the first.
 * @param spacing the gap between the end of the contents and the copy coming in behind them.
 * @param iterations how many trips to make before resting for good. [MarqueeForever] by default.
 * @param clock the clock the delay and the movement are measured on.
 * @throws IllegalArgumentException if [speed] is not positive, [delayMillis] or [spacing] is
 *   negative, or [iterations] is less than one.
 */
fun Modifier.marquee(
    speed: Float = 30f,
    delayMillis: Int = 1500,
    spacing: Float = 32f,
    iterations: Int = MarqueeForever,
    clock: Clock = Clock.Ui,
): Modifier = then(MarqueeElement(speed, delayMillis, spacing, iterations, clock))

/**
 * Something drawn from words, saying what they are. A marquee over it starts again from rest when
 * they change, even when the new ones measure exactly as wide.
 */
internal interface MarqueeWords {
    val marqueeWords: Any
}

/**
 * One node's marquee, as it is going: how wide the slot and the contents came out, and how far
 * the contents have moved.
 *
 * Kept on the node, made the first time layout meets a [MarqueeElement] there. Layout tells it what
 * it measured, and it waits on the tree's frames — the same list a held press waits on — only while
 * the contents overflow and it still has trips to make. None of it is snapshot state: moving the
 * drawing must not recompose anything, so a change of [offset] asks the tree for a redraw instead.
 */
internal class MarqueeRun(private val node: UiNode) : FrameWaiter {

    /** The room the contents are offered: the node's own, with no limit on the width. */
    val offers = ConstraintsCache()

    /** The content box, clipped to while scrolling. See [dev.wildware.composegl.ui.draw.DrawPass]. */
    val window = RectCache()

    private var element: MarqueeElement? = null

    /** How wide the node's content box is. */
    var visible = 0f
        private set

    /** How wide the contents measured, with nothing holding them in. */
    var content = 0f
        private set

    /** How far the contents have moved left. Zero at rest. */
    var offset = 0f
        private set

    /** One trip round: the contents, then the gap before the copy behind them. */
    val distance: Float get() = content + (element?.spacing ?: 0f)

    /**
     * Whether the contents are wider than the slot, which is the only time a marquee does anything.
     *
     * A hundredth of a unit of slack, so a label measured a rounding error wider than the width it
     * was fixed to does not start sliding by a hair.
     */
    val isScrolling: Boolean get() = content - visible > Slack

    private var waitingOn: UiTree? = null
    private var startedAt = 0L
    private var finished = false

    /**
     * The words the node said last pass, for a node that says any. A new song exactly as wide as
     * the last — which a monospaced font makes easy — still counts as new. Not the drawing itself:
     * a label makes a new one for a new colour too, and a title that lights up under the pointer,
     * or pulses, has to carry on from where it was.
     */
    private var words: Any? = null

    /** What layout found this pass. Starts, restarts or stops the scroll to match. */
    fun measured(element: MarqueeElement, visible: Float, content: Float) {
        // A different marquee, a different slot or different contents — a new song — starts from
        // rest, with the delay, rather than carrying on from wherever the last one had got to.
        val words = (node.measurePolicy as? MarqueeWords)?.marqueeWords
        // Contents with no end to them cannot go round: treated as fitting, rather than a trip
        // that takes for ever and a time that runs off the end of a Long.
        val wide = if (content.isFinite()) content else 0f
        if (element != this.element || visible != this.visible || wide != this.content || words != this.words) {
            stop()
            this.element = element
            this.visible = visible
            this.content = wide
            this.words = words
        }
        val tree = node.tree
        if (!isScrolling || tree == null) {
            stop()
            return
        }
        if (waitingOn == null && !finished) start(tree, element)
    }

    private fun start(tree: UiTree, element: MarqueeElement) {
        val clocks = tree.clocks
        // A clock nobody has asked about is not being wound on, and this one has to be.
        clocks.register(element.clock)
        startedAt = clocks.time(element.clock)
        waitingOn = tree
        tree.wait(this)
    }

    /** Back to rest, waiting on nothing, ready to start again. For a node leaving the tree too. */
    fun stop() {
        waitingOn?.stopWaiting(this)
        waitingOn = null
        finished = false
        if (offset != 0f) {
            offset = 0f
            node.tree?.redraw(node)
        }
    }

    override fun onFrame(clocks: Clocks) {
        val element = element ?: return
        val tree = waitingOn ?: return
        val elapsed = clocks.time(element.clock) - startedAt
        val distance = distance
        val next = element.offsetAt(elapsed, distance)
        // Only a real move asks for a frame. The rest at the start of a trip, and a stopped clock,
        // write the same number and so draw nothing.
        if (next != offset) {
            offset = next
            tree.redraw(node)
        }
        if (element.isFinishedAt(elapsed, distance)) {
            tree.stopWaiting(this)
            waitingOn = null
            finished = true
        }
    }

    private companion object {
        const val Slack = 0.01f
    }
}
