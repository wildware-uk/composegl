package composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import composegl.ui.focus.RevealHandler
import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.geometry.Size
import composegl.ui.graphics.UiCanvas
import composegl.ui.input.InteractionState
import composegl.ui.input.PointerEvent
import composegl.ui.input.PointerHandler
import composegl.ui.layout.Box
import composegl.ui.layout.Constraints
import composegl.ui.layout.Layout
import composegl.ui.layout.LeafLayout
import composegl.ui.layout.Measurable
import composegl.ui.layout.MeasurePolicy
import composegl.ui.layout.MeasureResult
import composegl.ui.layout.MeasureScope
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.clip
import composegl.ui.modifier.interaction
import composegl.ui.modifier.onPointer
import composegl.ui.modifier.onReveal
import composegl.ui.skin.ResolvedStyle
import composegl.ui.skin.rememberStates
import composegl.ui.skin.rememberStyle
import kotlin.math.abs
import kotlin.math.pow

/**
 * How far a [ScrollArea] has been scrolled, and everything that can move it.
 *
 * Held outside the widget so a screen can scroll a list from somewhere else — a "back to top"
 * button, a jump to a search result, a position restored from a save.
 *
 * The two offsets are how far the content has moved *up and left*, so zero is the top-left corner
 * and the maximum is the bottom-right. They are always inside the range: a list that shrinks
 * underneath a player scrolled to the bottom pulls them back rather than leaving it looking at
 * nothing.
 */
class ScrollState(initialX: Float = 0f, initialY: Float = 0f) {

    var x: Float by mutableStateOf(initialX)
        private set

    var y: Float by mutableStateOf(initialY)
        private set

    /** The room the contents are seen through, in the interface's units. Written by layout. */
    var viewport = Size.Zero
        private set

    /** How big the contents turned out to be. Written by layout. */
    var content = Size.Zero
        private set

    val maxX: Float get() = (content.width - viewport.width).coerceAtLeast(0f)

    val maxY: Float get() = (content.height - viewport.height).coerceAtLeast(0f)

    val canScrollX: Boolean get() = maxX > 0f

    val canScrollY: Boolean get() = maxY > 0f

    /** Straight to a position, clamped to the ends. Stops a fling. */
    fun scrollTo(x: Float = this.x, y: Float = this.y) {
        stopFling()
        settle(x, y)
    }

    /** By this much, clamped to the ends. True when anything actually moved. */
    fun scrollBy(dx: Float = 0f, dy: Float = 0f): Boolean {
        val before = x to y
        settle(x + dx, y + dy)
        return (x to y) != before
    }

    // --- the fling ------------------------------------------------------------------------------
    //
    // A flick has to keep going after the finger has gone, or a long list is a chore. The velocity
    // comes from the drag itself, and then decays by a fixed fraction per second — an exponential
    // rather than a straight line, because a fling that stops dead reads as the list hitting
    // something.

    private var velocityX = 0f
    private var velocityY = 0f

    val isFlinging: Boolean get() = velocityX != 0f || velocityY != 0f

    /** Whatever it was doing, it stops. A touch on a flinging list catches it, as a player expects. */
    fun stopFling() {
        velocityX = 0f
        velocityY = 0f
    }

    /** Starts a fling at this speed, in units per second. */
    internal fun fling(vx: Float, vy: Float) {
        velocityX = if (abs(vx) < MinimumFling) 0f else vx
        velocityY = if (abs(vy) < MinimumFling) 0f else vy
    }

    /** One frame of a fling. [seconds] is real time, so the feel does not depend on the frame rate. */
    internal fun advance(seconds: Float) {
        if (seconds <= 0f || !isFlinging) return

        val before = x to y
        settle(x - velocityX * seconds, y - velocityY * seconds)
        // Hitting an end ends the fling on that axis. Sliding along a wall for a second afterwards
        // is the sort of thing nobody notices until they do, and then cannot unsee.
        if (x == before.first) velocityX = 0f
        if (y == before.second) velocityY = 0f

        val kept = Retained.pow(seconds)
        velocityX *= kept
        velocityY *= kept
        if (abs(velocityX) < MinimumFling) velocityX = 0f
        if (abs(velocityY) < MinimumFling) velocityY = 0f
    }

    // --- what layout tells it -------------------------------------------------------------------

    /** Layout reporting what it found. Also the moment a scroll past a shrunken end is corrected. */
    internal fun measured(viewport: Size, content: Size) {
        this.viewport = viewport
        this.content = content
        settle(x, y)
    }

    private fun settle(x: Float, y: Float) {
        this.x = x.coerceIn(0f, maxX)
        this.y = y.coerceIn(0f, maxY)
    }

    private companion object {
        /** The share of a fling's speed left after one second. */
        const val Retained = 0.02f

        /** Below this, in units per second, it has stopped. */
        const val MinimumFling = 40f
    }
}

@Composable
fun rememberScrollState(initialX: Float = 0f, initialY: Float = 0f): ScrollState =
    remember { ScrollState(initialX, initialY) }

/**
 * A window onto something bigger than itself.
 *
 * The contents are measured with no limit on whichever axis scrolls, so a column inside one is as
 * tall as it likes, and the area shows as much of it as it has room for. Everything outside is
 * clipped by *one* scissor around the whole area rather than one per child, which is what keeps a
 * long list cheap to draw.
 *
 * Four ways to move it, and they are the four a game needs:
 *
 * - the wheel, or a two-finger scroll;
 * - a drag on the contents themselves, which is how a touch screen and a console cursor work;
 * - a drag on the scrollbar;
 * - focus. Moving focus to a child that is off-screen scrolls it into view, which is the rule that
 *   makes a pad usable on a long list — without it a player presses down and the interface appears
 *   to do nothing.
 *
 * A drag on the contents only starts where nothing else took the press, so a list of buttons is
 * still a list of buttons. Dragging by a button is a gesture this does not have yet.
 *
 * ```kotlin
 * val scroll = rememberScrollState()
 * ScrollArea(Modifier.fillMaxSize(), scroll) {
 *     Column { items.forEach { Row(it) } }
 * }
 * ```
 *
 * @param horizontal whether it scrolls sideways. Off by default: nearly every list is vertical, and
 *   an area that scrolls both ways by accident is a menu that wanders.
 * @param bars whether to draw scrollbars. They sit over the contents rather than beside them, so
 *   turning them off changes nothing about the layout.
 * @param style the skin name for the bars. The track is `"<style>.track"` and the part you drag is
 *   `"<style>.thumb"`.
 */
@Composable
fun ScrollArea(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    horizontal: Boolean = false,
    vertical: Boolean = true,
    bars: Boolean = true,
    style: String = "scrollbar",
    barThickness: Float = 8f,
    content: @Composable () -> Unit,
) {
    val logic = remember { ScrollLogic() }
    logic.state = state
    logic.horizontal = horizontal
    logic.vertical = vertical

    val drag = remember(logic) { PointerHandler { logic.contentPointer(it) } }
    val reveal = remember(logic) { RevealHandler { logic.reveal(it) } }

    // A fling is the one thing here that happens without anybody touching anything, so it is the
    // one thing that needs the clock. The loop watches every frame and does nothing on almost all
    // of them: waking a coroutine costs a resume, whereas starting the loop when a fling begins
    // would cost the first two frames of the fling — which is the part a player can feel.
    LaunchedEffect(state) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val seconds = (now - last).toFloat() / 1_000_000_000f
                last = now
                if (state.isFlinging) state.advance(seconds)
            }
        }
    }

    Layout(
        modifier = modifier.onReveal(reveal).onPointer(drag).clip(),
        name = "scroll",
        content = {
            Box { content() }
            if (bars) {
                ScrollBar(logic, vertical = true, style = style)
                ScrollBar(logic, vertical = false, style = style)
            }
        },
        measurePolicy = ScrollPolicy(logic, state.x, state.y, horizontal, vertical, bars, barThickness),
    )
}

/**
 * One scrollbar: a track with something to drag on it.
 *
 * One node rather than two, drawing both pieces itself, because a track and a thumb that are
 * separate nodes are two more nodes on every scrolling list in the game and the thumb's position
 * is arithmetic either way. It hides itself when its axis has nowhere to go.
 */
@Composable
private fun ScrollBar(logic: ScrollLogic, vertical: Boolean, style: String) {
    val touch = remember { InteractionState() }
    val states = rememberStates(touch)
    val track = rememberStyle("$style.track", states)
    val thumb = rememberStyle("$style.thumb", states)

    val bar = remember(logic, vertical) { ScrollBarLogic(logic, vertical) }
    logic.bar(vertical, bar)

    val pointer = remember(bar) { PointerHandler { bar.pointer(it) } }
    val painter = remember(bar, track, thumb) { BarPainter(bar, track, thumb) }

    LeafLayout(
        modifier = Modifier.interaction(touch).onPointer(pointer),
        name = if (vertical) "scroll.bar.y" else "scroll.bar.x",
        draw = painter.draw,
    )
}

/**
 * Where the contents and the bars go.
 *
 * The contents are offered as much room as they want along whichever axis scrolls, and the area
 * itself takes the room it was given. The bars are laid over the contents rather than beside them,
 * so showing or hiding one never moves anything.
 */
private class ScrollPolicy(
    private val logic: ScrollLogic,
    private val offsetX: Float,
    private val offsetY: Float,
    private val horizontal: Boolean,
    private val vertical: Boolean,
    private val bars: Boolean,
    private val thickness: Float,
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val room = Constraints(
            maxWidth = if (horizontal) Float.POSITIVE_INFINITY else constraints.maxWidth,
            maxHeight = if (vertical) Float.POSITIVE_INFINITY else constraints.maxHeight,
        )
        val inside = measurables[0].measure(room)

        val width = constraints.constrainWidth(inside.width)
        val height = constraints.constrainHeight(inside.height)
        val state = logic.state
        state.measured(Size(width, height), Size(inside.width, inside.height))

        // Read back rather than reused: the state clamps, and a list that shrank is already
        // somewhere else by now.
        val x = state.x
        val y = state.y

        val barY = if (bars) measurables[1].measure(Constraints.fixed(thickness, height)) else null
        val barX = if (bars) measurables[2].measure(Constraints.fixed(width, thickness)) else null
        logic.bar(vertical = true)?.length = height
        logic.bar(vertical = false)?.length = width

        return layout(width, height) {
            inside.at(-x, -y)
            barY?.at(width - thickness, 0f)
            barX?.at(0f, height - thickness)
        }
    }
}

/**
 * What a scrolling area knows outside composition.
 *
 * The pointer arrives between frames, in the middle of a drag, where there is no `remember` to read
 * and no composition to be in. This is the object it talks to — the same shape as a slider's, and
 * for the same reason.
 */
private class ScrollLogic {

    lateinit var state: ScrollState
    var horizontal = false
    var vertical = true

    private var barY: ScrollBarLogic? = null
    private var barX: ScrollBarLogic? = null

    fun bar(vertical: Boolean, logic: ScrollBarLogic) {
        if (vertical) barY = logic else barX = logic
    }

    fun bar(vertical: Boolean): ScrollBarLogic? = if (vertical) barY else barX

    // --- dragging the contents --------------------------------------------------------------

    private var dragging = false
    private var lastAt = Offset.Zero
    private var lastTime = 0L
    private var speedX = 0f
    private var speedY = 0f

    fun contentPointer(event: PointerEvent): Boolean = when (event) {
        is PointerEvent.Press -> {
            // Catching a flinging list is the first half of every scroll gesture a player makes.
            state.stopFling()
            dragging = state.canScrollX || state.canScrollY
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
                state.scrollBy(
                    if (horizontal) -moved.x else 0f,
                    if (vertical) -moved.y else 0f,
                )
                true
            }
        }

        is PointerEvent.Release -> {
            if (dragging) state.fling(if (horizontal) speedX else 0f, if (vertical) speedY else 0f)
            dragging = false
            true
        }

        // Taken away rather than let go, so it stops where it is. A cancelled gesture must not
        // leave a list sailing off on its own.
        is PointerEvent.Cancel -> {
            dragging = false
            true
        }

        is PointerEvent.Scroll -> state.scrollBy(
            if (horizontal) event.delta.x * WheelStep else 0f,
            if (vertical) event.delta.y * WheelStep else 0f,
        )

        is PointerEvent.Exit -> false
    }

    /**
     * The speed of the drag, in units per second, smoothed.
     *
     * Smoothed because one sample is whatever happened in the last few milliseconds, and a finger
     * that paused for one frame before letting go would otherwise fling at nothing — or, worse, a
     * single fast sample at the end would fling across the whole list.
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

    // --- focus ---------------------------------------------------------------------------------

    /**
     * Brings [area] — a focused child, in this area's own coordinates — inside the visible part.
     *
     * The smallest move that works, so focus arriving from above lands the child at the top edge
     * and focus arriving from below lands it at the bottom, which is what makes a long list feel
     * like it is following the player rather than jumping.
     */
    fun reveal(area: Rect): Boolean {
        val view = Rect.of(0f, 0f, state.viewport.width, state.viewport.height)
        if (view.isEmpty) return false
        val dx = if (horizontal) shift(area.left, area.right, view.left, view.right) else 0f
        val dy = if (vertical) shift(area.top, area.bottom, view.top, view.bottom) else 0f
        if (dx == 0f && dy == 0f) return false
        state.scrollTo(state.x + dx, state.y + dy)
        return true
    }

    private fun shift(start: Float, end: Float, viewStart: Float, viewEnd: Float): Float = when {
        start < viewStart -> start - viewStart
        end > viewEnd -> minOf(end - viewEnd, start - viewStart)
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
 * One bar's arithmetic: how long the thumb is, where it sits, and what a press on the track means.
 *
 * All of it derived from the state rather than stored, so a bar can never disagree with the
 * contents about where they are.
 */
private class ScrollBarLogic(private val scroll: ScrollLogic, val vertical: Boolean) {

    /** How long the bar is, along its own axis. Written by layout. */
    var length = 0f

    private val state: ScrollState get() = scroll.state

    val maximum: Float get() = if (vertical) state.maxY else state.maxX

    val offset: Float get() = if (vertical) state.y else state.x

    val isNeeded: Boolean get() = maximum > 0f && length > 0f

    /** How long the thumb is: its share of the whole, never so small it cannot be grabbed. */
    val thumbLength: Float
        get() {
            val whole = if (vertical) state.content.height else state.content.width
            val seen = if (vertical) state.viewport.height else state.viewport.width
            if (whole <= 0f) return length
            return (length * seen / whole).coerceIn(minOf(MinimumThumb, length), length)
        }

    /** How far along the bar the thumb starts. */
    val thumbStart: Float
        get() {
            val travel = length - thumbLength
            return if (maximum <= 0f || travel <= 0f) 0f else offset / maximum * travel
        }

    // --- dragging the thumb ---------------------------------------------------------------------

    private var grabbed = false

    /** Where on the thumb it was grabbed, so it does not jump under the pointer. */
    private var grabAt = 0f

    fun pointer(event: PointerEvent): Boolean {
        if (!isNeeded) return false
        return when (event) {
            is PointerEvent.Press -> {
                val along = along(event.position)
                grabAt = if (along >= thumbStart && along <= thumbStart + thumbLength) {
                    along - thumbStart
                } else {
                    // A press on the empty part of the track takes the thumb there, centred, and
                    // carries on as a drag. Pressing the track and then moving is one gesture.
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
            is PointerEvent.Scroll -> scroll.contentPointer(event)

            is PointerEvent.Exit -> false
        }
    }

    private fun along(position: Offset) = if (vertical) position.y else position.x

    private fun moveTo(along: Float) {
        val travel = length - thumbLength
        if (travel <= 0f) return
        val wanted = ((along - grabAt) / travel).coerceIn(0f, 1f) * maximum
        state.scrollTo(
            x = if (vertical) state.x else wanted,
            y = if (vertical) wanted else state.y,
        )
    }

    private companion object {
        const val MinimumThumb = 24f
    }
}

/** The track, and the thumb on it. Both from the skin, so a game can make them art. */
private class BarPainter(
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
