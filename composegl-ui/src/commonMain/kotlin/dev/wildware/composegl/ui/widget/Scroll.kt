package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.focus.RevealHandler
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.IntrinsicMeasurable
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.NodeLayout
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.PlacementFrame
import dev.wildware.composegl.ui.modifier.PlacementFrameElement
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.onReveal
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.saveable.rememberSaveable

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

    internal val across = MeasuredAxis(initialX, ::moved)
    internal val down = MeasuredAxis(initialY, ::moved)

    /**
     * The nodes of the [ScrollArea]s showing this: nearly always one, and none while it is off the
     * screen.
     *
     * A scroll moves nothing but where the contents are placed, so a step marks these nodes for
     * layout and nothing is composed again for it: not an area, not its bars. Every area showing the
     * state is marked, so two panes scrolling together — or the same list in two windows — all
     * follow. An area adds its node when it starts showing this state and takes away only its own
     * when it stops, so a state remembered past its screen keeps none of that screen alive.
     */
    internal val nodes = ArrayList<UiNode>(1)

    private fun moved() {
        // By index: a fling steps this every frame, and an iterator per step is garbage per frame.
        for (index in nodes.indices) nodes[index].invalidate()
    }

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

    /**
     * Whether a flick is still carrying it. Snapshot state: a composable that reads it is recomposed
     * when a fling starts and when it stops, and not on the frames between.
     */
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

/**
 * A [ScrollState] that is still scrolled where the player left it when its screen comes back, under
 * a [dev.wildware.composegl.ui.saveable.SaveableStateHolder]. Outside one it is plain `remember`.
 */
@Composable
fun rememberScrollState(initialX: Float = 0f, initialY: Float = 0f): ScrollState =
    rememberSaveable { ScrollState(initialX, initialY) }

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
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    gestures.mirrored = mirrored

    val drag = remember(gestures) { PointerHandler { gestures.onPointer(it) } }
    val reveal = remember(gestures) { RevealHandler { gestures.reveal(it) } }
    DriveFling(state.across, state.down)
    // The same as a lazy list: what slides inside is measured with the scroll taken out. Mirrored,
    // scrolling moves the contents right rather than left.
    val frame = remember(state, mirrored) {
        object : PlacementFrame {
            override val scrolledX: Float get() = if (mirrored) -state.x else state.x
            override val scrolledY: Float get() = state.y
        }
    }

    // Nothing here reads where the area is scrolled to. A step marks the node for layout through the
    // state instead, and the policy reads the offsets as it places, so scrolling composes nothing.
    val policy = remember(state, horizontal, vertical, bars, barThickness) {
        ScrollPolicy(state, horizontal, vertical, bars, barThickness)
    }
    val made = remember { MadeNode() }
    DisposableEffect(state) {
        // Made by now: the node is made as the composition is applied, and effects start after.
        val node = made.node ?: return@DisposableEffect onDispose {}
        state.nodes += node
        // Only its own: another area showing the same state still wants its steps.
        onDispose { state.nodes -= node }
    }

    NodeLayout(
        modifier = modifier.onReveal(reveal).onPointer(drag).clip().then(PlacementFrameElement(frame)),
        name = "scroll",
        content = {
            Box { content() }
            if (bars) {
                ScrollBar(state.down, vertical = true, style = style, gestures = gestures)
                ScrollBar(state.across, vertical = false, style = style, gestures = gestures)
            }
        },
        measurePolicy = policy,
        made = { made.node = it },
    )
}

/** The node a [ScrollArea] made, once it has made one, for its state to mark when it scrolls. */
private class MadeNode {
    var node: UiNode? = null
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

        // Read here, after the state has been told its size, rather than while composing: telling it
        // clamps, so a list that shrank is already somewhere else by now, and a scroll step reaches
        // this through the node being marked for layout, not through a new policy.
        val x = state.x
        val y = state.y

        val barY = if (bars) measurables[1].measure(Constraints.fixed(thickness, height)) else null
        val barX = if (bars) measurables[2].measure(Constraints.fixed(width, thickness)) else null

        // In a right-to-left screen the contents start against the right, and x is how far they have
        // moved right from there.
        val rtl = layoutDirection == LayoutDirection.Rtl
        val left = if (rtl) width - inside.width + x else -x
        return layout(width, height) {
            inside.at(left, -y)
            // The up-and-down bar hangs on the edge the lines end at, which a mirrored screen puts on
            // the left. On the right it would lie across the first letter of every line.
            barY?.at(if (rtl) 0f else width - thickness, 0f)
            barX?.at(0f, height - thickness)
        }
    }

    // Written out rather than left to the default, which runs measure: measuring tells the state
    // how big the window is and clamps the position to it, and a question must not scroll anything.
    // The area would like to be as big as its contents; the bars sit on top and want nothing.

    override fun MeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
        measurables[0].minIntrinsicWidth(if (vertical) Float.POSITIVE_INFINITY else height)

    override fun MeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
        measurables[0].maxIntrinsicWidth(if (vertical) Float.POSITIVE_INFINITY else height)

    override fun MeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
        measurables[0].minIntrinsicHeight(if (horizontal) Float.POSITIVE_INFINITY else width)

    override fun MeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
        measurables[0].maxIntrinsicHeight(if (horizontal) Float.POSITIVE_INFINITY else width)
}
