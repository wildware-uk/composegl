package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.backend.Haptics
import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.DirectionHandler
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.LocalUiSounds
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.input.UiSounds
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onFocusDirection
import dev.wildware.composegl.ui.modifier.pointerHoverIcon
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.styled

/**
 * Two panes sharing one space, with a divider between them the player drags to give one more of it:
 * a hierarchy on the left and its properties on the right, a map over a log.
 *
 * ```kotlin
 * var split by remember { mutableStateOf(0.3f) }
 * Splitter(
 *     fraction = split,
 *     onFractionChange = { split = it },
 *     modifier = Modifier.fillMaxSize(),
 *     minFirst = 120f,
 *     minSecond = 200f,
 *     first = { Hierarchy() },
 *     second = { Properties() },
 * )
 * ```
 *
 * [Orientation.Horizontal] puts the panes side by side with an upright divider between them, and
 * [Orientation.Vertical] stacks them with a flat one. The splitter takes all the room it is given,
 * the divider is [thickness] of it, and [fraction] is how much of the rest the first pane gets. Each
 * pane is clipped to its share, so a pane squeezed smaller than its contents cuts them off rather
 * than drawing over its neighbour.
 *
 * Three ways to move the divider, as for a [Slider]:
 *
 * - **The mouse or a finger.** Over the divider the cursor becomes a resize arrow. A press takes the
 *   pointer, so the drag carries on however far it wanders, and the divider stays under it — held
 *   back at a pane's minimum until the pointer comes back past it.
 * - **Arrow keys or the pad.** The divider is focusable. Focused, it takes the directions across it
 *   and moves [step] of the space each press, the way the arrow points. At a pane's minimum it stops
 *   taking them, so one more press carries focus out rather than grinding against the end.
 * - **A double click** puts it back at [defaultFraction]. Two quick presses of Enter or the pad's
 *   South on the focused divider do the same, since they are a double click on it too.
 *
 * [minFirst] and [minSecond] are kept whatever [fraction] says. When there is not room for both, the
 * space is shared out in proportion to them, and the divider cannot move.
 *
 * On a right-to-left screen a horizontal splitter mirrors: the first pane is on the right, and the
 * divider still moves the way it is dragged or the way the arrow points.
 *
 * Like [Slider], it draws and reports and the screen holds the answer: [onFractionChange] is told
 * what the player asked for, already kept within both minimums, and nothing moves until [fraction]
 * says so.
 *
 * The divider's look is the skin's: `"<style>"`, in its hovered, pressed, focused and disabled
 * states. The panes draw nothing of their own.
 *
 * @param fraction how much of the space, less the divider, the first pane gets: `0f` to `1f`.
 * @param onFractionChange called with the new fraction when the player moves or resets the divider.
 * @param minFirst the narrowest (or, stacked, the shortest) the first pane may be made.
 * @param minSecond the same for the second pane.
 * @param defaultFraction where a double click puts the divider. Where [fraction] started, unless
 *   given.
 * @param step how far one arrow press or pad nudge moves the divider, as a fraction of the space.
 * @param thickness how thick the divider is, which is also how much of the space it takes and how
 *   wide a target it is for the pointer.
 */
@Composable
fun Splitter(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Horizontal,
    minFirst: Float = 0f,
    minSecond: Float = 0f,
    defaultFraction: Float = remember { fraction },
    step: Float = 0.05f,
    thickness: Float = 6f,
    style: String = "splitter",
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    require(minFirst >= 0f && minSecond >= 0f) { "a pane cannot be smaller than nothing: $minFirst, $minSecond" }
    require(thickness >= 0f) { "a divider cannot be $thickness thick" }
    require(step >= 0f) { "a step cannot be negative, was $step" }

    val states = rememberStates(interaction, enabled)
    val horizontal = orientation == Orientation.Horizontal

    // One object the pointer, the keys and layout all share, as a slider's is, so a drag works out
    // the next fraction from what layout measured without waiting to be recomposed.
    val splitter = remember { SplitterLogic() }
    splitter.fraction = fraction.coerceIn(0f, 1f)
    splitter.defaultFraction = defaultFraction.coerceIn(0f, 1f)
    splitter.minFirst = minFirst
    splitter.minSecond = minSecond
    splitter.step = step
    splitter.horizontal = horizontal
    splitter.enabled = enabled
    splitter.report = onFractionChange
    splitter.sounds = LocalUiSounds.current
    splitter.haptics = LocalHaptics.current

    val directions = remember(splitter) { DirectionHandler { splitter.nudge(it) } }
    val start = remember(splitter) { { _: Offset -> splitter.dragStart() } }
    val drag = remember(splitter) { { delta: Offset -> splitter.drag(delta) } }
    val end = remember(splitter) { { splitter.dragEnd() } }
    val reset = remember(splitter) { { splitter.reset() } }
    // Everything layout reads is in the policy, so a new fraction or minimum is a new policy and the
    // splitter is measured again; the same ones compare equal and cost nothing.
    val policy = SplitterPolicy(splitter, splitter.fraction, minFirst, minSecond, horizontal, thickness)

    val divider = Modifier
        .interaction(interaction)
        .focusable(interaction, enabled = enabled, initial = initialFocus)
        .onFocusDirection(directions)
        // Clickable only for its double click: a single click on a divider means nothing. It also
        // keeps a press that never moves from falling through to whatever is under the splitter.
        .clickable(enabled = enabled, onDoubleClick = reset, onClick = NoClick)
        .draggable(enabled = enabled, slop = DividerSlop, onDragStart = start, onDragEnd = end, onDrag = drag)
        .then(
            if (enabled) {
                Modifier.pointerHoverIcon(if (horizontal) PointerIcon.ResizeHorizontal else PointerIcon.ResizeVertical)
            } else {
                Modifier
            },
        )
        .styled(style, states)

    Layout(
        modifier = modifier,
        name = "splitter",
        content = {
            Box(Modifier.clip()) { first() }
            LeafLayout(divider, name = "splitter.divider")
            Box(Modifier.clip()) { second() }
        },
        measurePolicy = policy,
    )
}

private val NoClick: () -> Unit = {}

/**
 * How far a press may wander before it is a drag. Small, because the divider should follow the hand
 * at once, but not nothing, so a double click from a hand that moved a pixel is still a double click.
 */
private const val DividerSlop = 3f

/**
 * Where the panes and the divider go.
 *
 * The splitter is all the room it is offered along its axis; with no limit there, it is just big
 * enough for both minimums. Across, it fills a bounded space and is as thick as its thicker pane in
 * an unbounded one.
 */
private data class SplitterPolicy(
    private val splitter: SplitterLogic,
    private val fraction: Float,
    private val minFirst: Float,
    private val minSecond: Float,
    private val horizontal: Boolean,
    private val thickness: Float,
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val maxAlong = if (horizontal) constraints.maxWidth else constraints.maxHeight
        val minAlong = if (horizontal) constraints.minWidth else constraints.minHeight
        val along = if (maxAlong.isFinite()) {
            maxAlong
        } else {
            maxOf(minAlong, minFirst + thickness + minSecond)
        }
        val maxAcross = if (horizontal) constraints.maxHeight else constraints.maxWidth
        val minAcross = if (horizontal) constraints.minHeight else constraints.minWidth

        val available = (along - thickness).coerceAtLeast(0f)
        splitter.available = available
        val mirrored = horizontal && layoutDirection == LayoutDirection.Rtl
        splitter.mirrored = mirrored

        val firstSize = splitter.firstSize(fraction)
        val secondSize = available - firstSize

        val acrossRange = if (maxAcross.isFinite()) maxAcross..maxAcross else minAcross..Float.POSITIVE_INFINITY
        val a = measurables[0].measure(slot(firstSize, acrossRange))
        val b = measurables[2].measure(slot(secondSize, acrossRange))
        val panesAcross = if (horizontal) maxOf(a.height, b.height) else maxOf(a.width, b.width)
        val across = if (maxAcross.isFinite()) maxAcross else maxOf(minAcross, panesAcross)
        val line = measurables[1].measure(slot(thickness, across..across))

        val width = if (horizontal) along else across
        val height = if (horizontal) across else along
        return layout(width, height) {
            if (!horizontal) {
                a.at(0f, 0f)
                line.at(0f, firstSize)
                b.at(0f, firstSize + thickness)
            } else if (mirrored) {
                // The first pane reads first, and on this screen that is the right.
                a.at(along - firstSize, 0f)
                line.at(along - firstSize - thickness, 0f)
                b.at(0f, 0f)
            } else {
                a.at(0f, 0f)
                line.at(firstSize, 0f)
                b.at(firstSize + thickness, 0f)
            }
        }
    }

    /** Exactly [along] on the axis, and within [across] the other way. */
    private fun slot(along: Float, across: ClosedFloatingPointRange<Float>) = if (horizontal) {
        Constraints(along, along, across.start, across.endInclusive)
    } else {
        Constraints(across.start, across.endInclusive, along, along)
    }
}

/**
 * What a splitter knows, in one mutable object that outlives a recomposition.
 *
 * The arithmetic is here because the pointer and the keys ask for it outside composition, in the
 * middle of a drag, from what layout last measured.
 */
private class SplitterLogic {

    var fraction = 0.5f
    var defaultFraction = 0.5f
    var minFirst = 0f
    var minSecond = 0f
    var step = 0.05f
    var horizontal = true
    var enabled = true
    var report: (Float) -> Unit = {}
    var sounds: UiSounds = UiSounds.None
    var haptics: Haptics = Haptics.None

    /** The space the two panes share, less the divider. Written by layout, read by input. */
    var available = 0f

    /** Whether the first pane is on the right, in a right-to-left screen. Written by layout. */
    var mirrored = false

    /**
     * Where the pointer has taken the first pane's edge since the drag began, before the minimums
     * are applied — so dragging past a minimum and back does not move the divider until the pointer
     * has come back to it.
     */
    private var dragAt = 0f

    /** Whether this drag has asked for a new fraction yet, so letting go sounds once for it. */
    private var dragged = false

    /** How big the first pane is at [wanted], kept within both minimums. */
    fun firstSize(wanted: Float): Float {
        val space = available
        if (space <= 0f) return 0f
        val mins = minFirst + minSecond
        // Not room for both: each gets its share of what there is, in proportion to what it asked for.
        if (mins >= space) return if (mins <= 0f) wanted * space else space * minFirst / mins
        return (wanted * space).coerceIn(minFirst, space - minSecond)
    }

    /** The fraction the first pane has at [size], within both minimums. */
    private fun fractionAt(size: Float): Float =
        if (available <= 0f) fraction else firstSize((size / available).coerceIn(0f, 1f)) / available

    fun dragStart() {
        dragAt = firstSize(fraction)
        dragged = false
    }

    fun drag(delta: Offset) {
        if (!enabled || available <= 0f) return
        // A drag towards the second pane grows the first. Mirrored, the second pane is on the left.
        val along = if (horizontal) (if (mirrored) -delta.x else delta.x) else delta.y
        dragAt += along
        val wanted = fractionAt(dragAt)
        if (wanted == current()) return
        dragged = true
        report(wanted)
    }

    fun dragEnd() {
        if (dragged) sounds.change()
        dragged = false
    }

    /** A direction across the divider. False for anything else, or at a minimum, so focus can move on. */
    fun nudge(direction: FocusDirection): Boolean {
        if (!enabled || available <= 0f) return false
        val by = when (direction) {
            // The divider moves the way the arrow points; mirrored, left is towards the second pane.
            FocusDirection.Left -> if (!horizontal) return false else if (mirrored) 1f else -1f
            FocusDirection.Right -> if (!horizontal) return false else if (mirrored) -1f else 1f
            FocusDirection.Up -> if (horizontal) return false else -1f
            FocusDirection.Down -> if (horizontal) return false else 1f
            else -> return false
        }
        val now = current()
        val wanted = fractionAt((now + by * step) * available)
        if (wanted == now) return false
        sounds.change()
        haptics.perform(Haptic.Tick)
        report(wanted)
        return true
    }

    fun reset() {
        if (!enabled) return
        sounds.change()
        report(defaultFraction)
    }

    /** Where the divider is on screen, as a fraction: [fraction] once the minimums have had their say. */
    private fun current(): Float = if (available <= 0f) fraction else firstSize(fraction) / available
}
