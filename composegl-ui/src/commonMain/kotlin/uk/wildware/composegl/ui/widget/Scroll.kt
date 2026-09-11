package uk.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import uk.wildware.composegl.ui.focus.RevealHandler
import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.input.PointerHandler
import uk.wildware.composegl.ui.layout.Box
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.Layout
import uk.wildware.composegl.ui.layout.Measurable
import uk.wildware.composegl.ui.layout.MeasurePolicy
import uk.wildware.composegl.ui.layout.MeasureResult
import uk.wildware.composegl.ui.layout.MeasureScope
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.clip
import uk.wildware.composegl.ui.modifier.onPointer
import uk.wildware.composegl.ui.modifier.onReveal

/**
 * How far a [ScrollArea] has been scrolled, and everything that can move it.
 *
 * Held outside the widget so a screen can scroll a list from somewhere else — a "back to top"
 * button, a jump to a search result, a position restored from a save.
 *
 * The two offsets are how far the contents have moved *up and left*, so zero is the top-left corner
 * and the maximum is the bottom-right. They are always inside the range: a list that shrinks
 * underneath a player scrolled to the bottom pulls them back rather than leaving it looking at
 * nothing.
 */
class ScrollState(initialX: Float = 0f, initialY: Float = 0f) {

    internal val across = MeasuredAxis(initialX)
    internal val down = MeasuredAxis(initialY)

    val x: Float get() = across.position

    val y: Float get() = down.position

    /** The room the contents are seen through, in the interface's units. Written by layout. */
    val viewport: Size get() = Size(across.visible, down.visible)

    /** How big the contents turned out to be. Written by layout. */
    val content: Size get() = Size(across.total, down.total)

    val maxX: Float get() = across.maximum

    val maxY: Float get() = down.maximum

    val canScrollX: Boolean get() = across.canScroll

    val canScrollY: Boolean get() = down.canScroll

    val isFlinging: Boolean get() = across.isFlinging || down.isFlinging

    /** Straight to a position, clamped to the ends. Stops a fling. */
    fun scrollTo(x: Float = this.x, y: Float = this.y) {
        across.scrollTo(x)
        down.scrollTo(y)
    }

    /** By this much, clamped to the ends. True when anything actually moved. */
    fun scrollBy(dx: Float = 0f, dy: Float = 0f): Boolean {
        val moved = across.scrollBy(dx)
        return down.scrollBy(dy) || moved
    }

    /** Whatever it was doing, it stops. A touch on a flinging list catches it, as a player expects. */
    fun stopFling() {
        across.stop()
        down.stop()
    }

    internal fun measured(viewport: Size, content: Size) {
        across.measured(viewport.width, content.width)
        down.measured(viewport.height, content.height)
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
 * Everything inside is composed, whether it can be seen or not. That is the right trade for a
 * settings page or a briefing, and the wrong one for an inventory of two thousand items —
 * [LazyColumn] is for that.
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
    val gestures = remember { ScrollGestures() }
    gestures.horizontal = if (horizontal) state.across else null
    gestures.vertical = if (vertical) state.down else null

    val drag = remember(gestures) { PointerHandler { gestures.onPointer(it) } }
    val reveal = remember(gestures) { RevealHandler { gestures.reveal(it) } }
    DriveFling(state.across, state.down)

    Layout(
        modifier = modifier.onReveal(reveal).onPointer(drag).clip(),
        name = "scroll",
        content = {
            Box { content() }
            if (bars) {
                ScrollBar(state.down, vertical = true, style = style, gestures = gestures)
                ScrollBar(state.across, vertical = false, style = style, gestures = gestures)
            }
        },
        measurePolicy = ScrollPolicy(state, state.x, state.y, horizontal, vertical, bars, barThickness),
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
    private val state: ScrollState,
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
        state.measured(Size(width, height), Size(inside.width, inside.height))

        // Read back rather than reused: measuring clamps, and a list that shrank is already
        // somewhere else by now.
        val x = state.x
        val y = state.y

        val barY = if (bars) measurables[1].measure(Constraints.fixed(thickness, height)) else null
        val barX = if (bars) measurables[2].measure(Constraints.fixed(width, thickness)) else null

        return layout(width, height) {
            inside.at(-x, -y)
            barY?.at(width - thickness, 0f)
            barX?.at(0f, height - thickness)
        }
    }
}
