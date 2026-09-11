package composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.graphics.UiCanvas
import composegl.ui.input.InteractionState
import composegl.ui.input.PointerEvent
import composegl.ui.input.PointerHandler
import composegl.ui.layout.Constraints
import composegl.ui.layout.LeafLayout
import composegl.ui.layout.Measurable
import composegl.ui.layout.MeasurePolicy
import composegl.ui.layout.MeasureResult
import composegl.ui.layout.MeasureScope
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.interaction
import composegl.ui.modifier.onPointer
import composegl.ui.skin.ResolvedStyle
import composegl.ui.skin.rememberStates
import composegl.ui.skin.rememberStyle
import kotlin.math.abs
import kotlin.math.pow

/**
 * One axis that can be scrolled, as everything that moves one needs to see it.
 *
 * Written as an interface because there are two quite different things behind it. A [ScrollArea]
 * knows exactly how big its contents are, because it measured them; a lazy list does not, because
 * most of its contents have never existed. Both can say where they are, how far they can go, and
 * move by an amount — and that is all a wheel, a drag, a fling or a scrollbar ever asks.
 */
internal interface ScrollAxis {

    /** How far along, in the interface's units. Zero is the start. */
    val position: Float

    /** The furthest [position] may be. Zero when there is nowhere to go. */
    val maximum: Float

    /** How much is seen at once. */
    val visible: Float

    /** How long the whole thing is. A lazy list's is an estimate, and it improves as it is used. */
    val total: Float

    val canScroll: Boolean get() = maximum > 0f

    /** Straight there, clamped to the ends. Stops a fling. */
    fun scrollTo(position: Float)

    /** By this much, clamped. True when anything actually moved. */
    fun scrollBy(delta: Float): Boolean

    /** Whatever it was doing on its own, it stops. */
    fun stop()

    /** Keeps going at this speed, in units per second, after the finger has gone. */
    fun fling(velocity: Float)

    val isFlinging: Boolean

    /** One frame of a fling. [seconds] is real time, so the feel does not follow the frame rate. */
    fun advance(seconds: Float)
}

/**
 * An axis whose contents have all been measured: a position, two ends, and a fling.
 *
 * The fling decays exponentially rather than in a straight line, because a flick that stops dead
 * reads as the list hitting something. Hitting an actual end does stop it dead, which is the one
 * time that reading is right.
 */
internal class MeasuredAxis(initial: Float = 0f) : ScrollAxis {

    override var position: Float by mutableStateOf(initial)
        private set

    override var visible: Float = 0f
        private set

    override var total: Float = 0f
        private set

    override val maximum: Float get() = (total - visible).coerceAtLeast(0f)

    private var velocity = 0f

    override val isFlinging: Boolean get() = velocity != 0f

    override fun scrollTo(position: Float) {
        stop()
        settle(position)
    }

    override fun scrollBy(delta: Float): Boolean {
        val before = position
        settle(position + delta)
        return position != before
    }

    override fun stop() {
        velocity = 0f
    }

    override fun fling(velocity: Float) {
        this.velocity = if (abs(velocity) < MinimumFling) 0f else velocity
    }

    override fun advance(seconds: Float) {
        if (seconds <= 0f || !isFlinging) return
        val before = position
        settle(position - velocity * seconds)
        if (position == before) {
            velocity = 0f
            return
        }
        velocity *= Retained.pow(seconds)
        if (abs(velocity) < MinimumFling) velocity = 0f
    }

    /** What layout found. Also the moment a scroll past a shrunken end is pulled back. */
    fun measured(visible: Float, total: Float) {
        this.visible = visible
        this.total = total
        settle(position)
    }

    private fun settle(wanted: Float) {
        position = wanted.coerceIn(0f, maximum)
    }

    private companion object {
        /** The share of a fling's speed left after one second. */
        const val Retained = 0.02f

        /** Below this, in units per second, it has stopped. */
        const val MinimumFling = 40f
    }
}

/**
 * The wheel, a drag, and a flick, for one or two axes.
 *
 * Shared because a scrolling area and a lazy list want exactly the same gestures and differ only
 * in what is underneath them. Either axis may be absent, which is what "this one does not scroll
 * sideways" means.
 *
 * The pointer arrives between frames, in the middle of a drag, where there is no `remember` to
 * read and no composition to be in — so this is a plain object the widget keeps hold of.
 */
internal class ScrollGestures {

    var horizontal: ScrollAxis? = null
    var vertical: ScrollAxis? = null

    private var dragging = false
    private var lastAt = Offset.Zero
    private var lastTime = 0L
    private var speedX = 0f
    private var speedY = 0f

    fun onPointer(event: PointerEvent): Boolean = when (event) {
        is PointerEvent.Press -> {
            // Catching a moving list is the first half of every scroll gesture a player makes.
            horizontal?.stop()
            vertical?.stop()
            dragging = horizontal?.canScroll == true || vertical?.canScroll == true
            lastAt = event.position
            lastTime = event.timeMillis
            speedX = 0f
            speedY = 0f
            dragging
        }

        is PointerEvent.Move -> when {
            !dragging || event.pressed.isEmpty() -> false
            else -> {
                val moved = event.position - lastAt
                track(moved, event.timeMillis)
                lastAt = event.position
                // Dragged left means scrolled right: the contents follow the finger.
                horizontal?.scrollBy(-moved.x)
                vertical?.scrollBy(-moved.y)
                true
            }
        }

        is PointerEvent.Release -> {
            if (dragging) {
                horizontal?.fling(speedX)
                vertical?.fling(speedY)
            }
            dragging = false
            true
        }

        // Taken away rather than let go, so it stops where it is. A cancelled gesture must not
        // leave a list sailing off on its own.
        is PointerEvent.Cancel -> {
            dragging = false
            true
        }

        is PointerEvent.Scroll -> {
            val moved = horizontal?.scrollBy(event.delta.x * WheelStep) == true
            vertical?.scrollBy(event.delta.y * WheelStep) == true || moved
        }

        is PointerEvent.Exit -> false
    }

    /**
     * The speed of the drag, in units per second, smoothed.
     *
     * Smoothed because one sample is whatever happened in the last few milliseconds: a finger that
     * paused for a frame before letting go would otherwise fling at nothing, and one fast sample at
     * the end would fling across the whole list.
     */
    private fun track(moved: Offset, timeMillis: Long) {
        val elapsed = (timeMillis - lastTime).toFloat() / 1000f
        lastTime = timeMillis
        // A backend that does not timestamp its events gets no fling rather than a division by
        // zero. Everything else about the drag still works.
        if (elapsed <= 0f) return
        speedX = speedX * (1f - Smoothing) + (moved.x / elapsed) * Smoothing
        speedY = speedY * (1f - Smoothing) + (moved.y / elapsed) * Smoothing
    }

    /**
     * Brings [area] — a focused child, in the scrolling node's own coordinates — into view.
     *
     * The smallest move that works, so focus arriving from above lands the child against the top
     * edge and focus arriving from below lands it against the bottom, which is what makes a long
     * list feel like it is following the player rather than jumping.
     */
    fun reveal(area: Rect): Boolean {
        val across = horizontal
        val down = vertical
        val dx = if (across == null) 0f else shift(area.left, area.right, across.visible)
        val dy = if (down == null) 0f else shift(area.top, area.bottom, down.visible)
        if (dx == 0f && dy == 0f) return false
        across?.scrollTo(across.position + dx)
        down?.scrollTo(down.position + dy)
        return true
    }

    private fun shift(start: Float, end: Float, visible: Float): Float = when {
        visible <= 0f -> 0f
        start < 0f -> start
        end > visible -> minOf(end - visible, start)
        else -> 0f
    }

    private companion object {
        /** One notch of a wheel, in interface units. */
        const val WheelStep = 48f

        /** How much of the newest sample a velocity estimate takes. */
        const val Smoothing = 0.4f
    }
}

/**
 * Keeps a fling going, one frame at a time.
 *
 * The loop watches every frame and does nothing on almost all of them. Waking a coroutine costs a
 * resume; starting the loop only when a fling begins would cost the first two frames of the fling,
 * which is the part a player can feel.
 */
@Composable
internal fun DriveFling(vararg axes: ScrollAxis?) {
    val moving = remember(axes.size) { axes.filterNotNull() }
    LaunchedEffect(moving) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val seconds = (now - last).toFloat() / 1_000_000_000f
                last = now
                moving.forEach { if (it.isFlinging) it.advance(seconds) }
            }
        }
    }
}

/**
 * One scrollbar: a track with something to drag on it.
 *
 * One node rather than two, drawing both pieces itself, because a track and a thumb that are
 * separate nodes are two more nodes on every scrolling list in the game and the thumb's position is
 * arithmetic either way. It hides itself when its axis has nowhere to go.
 *
 * @param length how long the bar is. Layout knows; the node is told, because the arithmetic that
 *   turns a press into a position needs it between frames.
 */
@Composable
internal fun ScrollBar(axis: ScrollAxis, vertical: Boolean, style: String, gestures: ScrollGestures) {
    val touch = remember { InteractionState() }
    val states = rememberStates(touch)
    val track = rememberStyle("$style.track", states)
    val thumb = rememberStyle("$style.thumb", states)

    val bar = remember(axis, vertical) { ScrollBarLogic(axis, vertical, gestures) }
    val pointer = remember(bar) { PointerHandler { bar.onPointer(it) } }
    val painter = remember(bar, track, thumb) { BarPainter(bar, track, thumb) }

    LeafLayout(
        modifier = Modifier.interaction(touch).onPointer(pointer),
        name = if (vertical) "scroll.bar.y" else "scroll.bar.x",
        measurePolicy = bar,
        draw = painter.draw,
    )
}

/**
 * A bar's arithmetic: how long the thumb is, where it sits, and what a press means.
 *
 * All of it derived from the axis rather than stored, so a bar can never disagree with the contents
 * about where they are. It measures itself, which is how it learns how long it is without layout
 * having to reach back into it.
 */
internal class ScrollBarLogic(
    private val axis: ScrollAxis,
    val vertical: Boolean,
    private val gestures: ScrollGestures,
) : MeasurePolicy {

    private var length = 0f

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val width = constraints.minWidth
        val height = constraints.minHeight
        length = if (vertical) height else width
        return layout(width, height) {}
    }

    val isNeeded: Boolean get() = axis.canScroll && length > 0f

    /** How long the thumb is: its share of the whole, never so small it cannot be grabbed. */
    val thumbLength: Float
        get() {
            if (axis.total <= 0f) return length
            return (length * axis.visible / axis.total).coerceIn(minOf(MinimumThumb, length), length)
        }

    /** How far along the bar the thumb starts. */
    val thumbStart: Float
        get() {
            val travel = length - thumbLength
            return if (axis.maximum <= 0f || travel <= 0f) 0f else axis.position / axis.maximum * travel
        }

    private var grabbed = false

    /** Where on the thumb it was grabbed, so it does not jump under the pointer. */
    private var grabAt = 0f

    fun onPointer(event: PointerEvent): Boolean {
        if (!isNeeded) return false
        return when (event) {
            is PointerEvent.Press -> {
                val along = along(event.position)
                grabAt = if (along >= thumbStart && along <= thumbStart + thumbLength) {
                    along - thumbStart
                } else {
                    // A press on the empty part of the track takes the thumb there, centred, and
                    // carries on as a drag: pressing the track and then moving is one gesture.
                    thumbLength / 2f
                }
                grabbed = true
                moveTo(along)
                true
            }

            is PointerEvent.Move -> when {
                !grabbed || event.pressed.isEmpty() -> false
                else -> {
                    moveTo(along(event.position))
                    true
                }
            }

            is PointerEvent.Release, is PointerEvent.Cancel -> {
                grabbed = false
                true
            }

            // The wheel over a scrollbar scrolls what the scrollbar is for.
            is PointerEvent.Scroll -> gestures.onPointer(event)

            is PointerEvent.Exit -> false
        }
    }

    private fun along(position: Offset) = if (vertical) position.y else position.x

    private fun moveTo(along: Float) {
        val travel = length - thumbLength
        if (travel <= 0f) return
        axis.scrollTo(((along - grabAt) / travel).coerceIn(0f, 1f) * axis.maximum)
    }

    private companion object {
        const val MinimumThumb = 24f
    }
}

/** The track, and the thumb on it. Both from the skin, so a game can make them art. */
internal class BarPainter(
    private val bar: ScrollBarLogic,
    private val track: ResolvedStyle,
    private val thumb: ResolvedStyle,
) {

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        // An axis with nowhere to go has no bar at all, rather than a full-length thumb that
        // refuses to move.
        if (bar.isNeeded) {
            track.background.drawInto(this, bounds, track.tint)
            val start = bar.thumbStart
            val end = start + bar.thumbLength
            val rect = if (bar.vertical) {
                Rect(bounds.left, bounds.top + start, bounds.right, bounds.top + end)
            } else {
                Rect(bounds.left + start, bounds.top, bounds.left + end, bounds.bottom)
            }
            thumb.background.drawInto(this, rect, thumb.tint)
        }
    }
}
