package uk.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import uk.wildware.composegl.ui.focus.RevealHandler
import uk.wildware.composegl.ui.input.PointerHandler
import uk.wildware.composegl.ui.layout.Box
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.Layout
import uk.wildware.composegl.ui.layout.Measurable
import uk.wildware.composegl.ui.layout.MeasurePolicy
import uk.wildware.composegl.ui.layout.MeasureResult
import uk.wildware.composegl.ui.layout.MeasureScope
import uk.wildware.composegl.ui.layout.Placeable
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.clip
import uk.wildware.composegl.ui.modifier.onPointer
import uk.wildware.composegl.ui.modifier.onReveal

/**
 * Where a lazy list is, and what it has learned about its own contents.
 *
 * A [ScrollArea] can ask its contents how big they are, because they all exist. A lazy list cannot:
 * most of its items have never been composed, and composing them to find out would be the whole
 * problem again. So it keeps what it has measured, averages that for everything it has not, and
 * scrolls against the estimate.
 *
 * The consequence is worth being plain about. A list of rows that are all the same height — which
 * is nearly every list in a game — is exact from the second frame. A list whose rows differ wildly
 * has an approximate scrollbar until enough of it has been seen, and it gets better as it is used.
 */
class LazyListState(initialPosition: Float = 0f) {

    internal val axis = MeasuredAxis(initialPosition)

    /** What each item measured, by index. An estimate is used for everything not in here. */
    private val sizes = HashMap<Int, Float>()
    private var measuredSum = 0f

    private var count = 0
    private var spacing = 0f
    private var lastVisible = -1f

    /**
     * Bumped whenever something that decides *which* items to compose has changed.
     *
     * Read during composition, written by layout. That is the loop the whole design turns on: a
     * frame composes its best guess, layout finds out what the items really were, and the next
     * frame composes the right ones. It settles in a frame or two and then stops changing, because
     * a size that has been measured does not move.
     */
    internal var revision: Int by mutableStateOf(0)
        private set

    /** How far down the list, in units, counting the estimate for everything unmeasured. */
    val position: Float get() = axis.position

    /** The first item with any part of it on screen. */
    val firstVisibleItem: Int get() = indexAt(axis.position)

    val isFlinging: Boolean get() = axis.isFlinging

    /** Puts [index] at the top of the window. [offset] scrolls that item too. */
    fun scrollToItem(index: Int, offset: Float = 0f) =
        axis.scrollTo(startOf(index.coerceIn(0, (count - 1).coerceAtLeast(0))) + offset)

    fun scrollBy(delta: Float): Boolean = axis.scrollBy(delta)

    fun stopFling() = axis.stop()

    // --- what it has learned ---------------------------------------------------------------------

    /** The size to assume for an item nobody has measured. */
    internal val average: Float
        get() = if (sizes.isEmpty()) DefaultItem else measuredSum / sizes.size

    internal fun sizeOf(index: Int): Float = sizes[index] ?: average

    /** Where an item starts, counting the estimate for the ones above it. */
    internal fun startOf(index: Int): Float {
        var at = 0f
        for (i in 0 until index) at += sizeOf(i) + spacing
        return at
    }

    /** Which item contains [position]. */
    internal fun indexAt(position: Float): Int {
        var at = 0f
        for (i in 0 until count) {
            at += sizeOf(i) + spacing
            if (position < at) return i
        }
        return (count - 1).coerceAtLeast(0)
    }

    /**
     * Which items to compose: everything on screen, plus [overscan] either side.
     *
     * The overscan is what makes focus and a fast flick work. Focus can only move to something that
     * exists, so a list with nothing composed below the fold is a list a pad cannot walk down; two
     * spare items either side are enough for the next press to land on something real, and cheap
     * enough that nobody notices.
     */
    internal fun window(overscan: Int): IntRange {
        if (count <= 0) return IntRange.EMPTY
        val first = indexAt(axis.position)
        // Before it has ever been laid out there is no window to fill, so it composes a screenful
        // of guesses and corrects itself next frame.
        val room = if (axis.visible > 0f) axis.visible else average * Guess
        var last = first
        var filled = startOf(first) + sizeOf(first) - axis.position
        while (filled < room && last < count - 1) {
            last++
            filled += sizeOf(last) + spacing
        }
        return (first - overscan).coerceAtLeast(0)..(last + overscan).coerceAtMost(count - 1)
    }

    // --- what layout tells it ----------------------------------------------------------------------

    internal fun describe(count: Int, spacing: Float) {
        if (this.spacing != spacing) {
            this.spacing = spacing
            revision++
        }
        if (this.count == count) return
        this.count = count
        // Sizes for items that no longer exist are worse than no sizes: they would be averaged in.
        val gone = sizes.keys.filter { it >= count }
        gone.forEach { index -> sizes.remove(index)?.let { measuredSum -= it } }
        revision++
    }

    internal fun measuredItem(index: Int, size: Float) {
        val before = sizes.put(index, size)
        if (before == size) return
        measuredSum += size - (before ?: 0f)
        revision++
    }

    /** The window's own size, and with it the estimate of the whole. */
    internal fun measuredViewport(visible: Float) {
        axis.measured(visible, estimatedTotal())
        if (visible == lastVisible) return
        lastVisible = visible
        revision++
    }

    private fun estimatedTotal(): Float {
        if (count <= 0) return 0f
        val guessed = (count - sizes.size).coerceAtLeast(0)
        return measuredSum + guessed * average + spacing * (count - 1)
    }

    private companion object {
        /** What an item is assumed to be before anything has been measured. */
        const val DefaultItem = 48f

        /** How many of those to compose on the very first frame, before there is a window. */
        const val Guess = 12
    }
}

@Composable
fun rememberLazyListState(initialPosition: Float = 0f): LazyListState =
    remember { LazyListState(initialPosition) }

/**
 * A list that only builds what can be seen.
 *
 * The difference between an inventory of twenty items and an inventory of two thousand: a
 * [ScrollArea] with a `Column` in it composes, measures and lays out every row whether or not
 * anybody will ever look at it, and this composes about a screenful.
 *
 * ```kotlin
 * LazyColumn(count = inventory.size, key = { inventory[it].id }) { index ->
 *     ItemRow(inventory[index])
 * }
 * ```
 *
 * @param key what identifies an item, so its state follows it when the list is sorted or something
 *   is removed from the middle. Without one, item state belongs to the *position* — the third row
 *   stays the third row's — which is right for a fixed list and wrong for anything a player edits.
 * @param overscan how many extra items to build either side of the window. Two is enough for focus
 *   to have somewhere to go and for a fast flick not to show a gap.
 * @param item what one row looks like. Called with the item's index.
 */
@Composable
fun LazyColumn(
    count: Int,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    key: ((Int) -> Any)? = null,
    spacing: Float = 0f,
    overscan: Int = 2,
    bars: Boolean = true,
    style: String = "scrollbar",
    barThickness: Float = 8f,
    item: @Composable (Int) -> Unit,
) = LazyList(true, count, modifier, state, key, spacing, overscan, bars, style, barThickness, item)

/** The same thing lying down: a hotbar, a row of cards, a filmstrip of save games. */
@Composable
fun LazyRow(
    count: Int,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    key: ((Int) -> Any)? = null,
    spacing: Float = 0f,
    overscan: Int = 2,
    bars: Boolean = true,
    style: String = "scrollbar",
    barThickness: Float = 8f,
    item: @Composable (Int) -> Unit,
) = LazyList(false, count, modifier, state, key, spacing, overscan, bars, style, barThickness, item)

@Composable
private fun LazyList(
    vertical: Boolean,
    count: Int,
    modifier: Modifier,
    state: LazyListState,
    key: ((Int) -> Any)?,
    spacing: Float,
    overscan: Int,
    bars: Boolean,
    style: String,
    barThickness: Float,
    item: @Composable (Int) -> Unit,
) {
    state.describe(count, spacing)

    val gestures = remember { ScrollGestures() }
    gestures.horizontal = if (vertical) null else state.axis
    gestures.vertical = if (vertical) state.axis else null

    val drag = remember(gestures) { PointerHandler { gestures.onPointer(it) } }
    val reveal = remember(gestures) { RevealHandler { gestures.reveal(it) } }
    DriveFling(state.axis)

    // Both of these are snapshot state, and between them they are the whole of "which items should
    // exist": where the list is, and everything layout has learned about how big things are.
    state.revision
    val window = state.window(overscan)

    Layout(
        modifier = modifier.onReveal(reveal).onPointer(drag).clip(),
        name = if (vertical) "lazyColumn" else "lazyRow",
        content = {
            for (index in window) {
                // One node per item whatever the item emits, so layout can match children to
                // indices by counting. The key is what keeps an item's state with the item when
                // the list is reordered rather than with the slot it happened to be in.
                key(key?.invoke(index) ?: index) {
                    Box { LazyItem(index, item) }
                }
            }
            if (bars) ScrollBar(state.axis, vertical = vertical, style = style, gestures = gestures)
        },
        measurePolicy = LazyPolicy(state, window, state.position, vertical, spacing, bars, barThickness),
    )
}

/**
 * One item, in its own restartable group.
 *
 * The reason this exists rather than calling [item] straight: a composable of its own can be
 * skipped. Scrolling a list moves which indices are composed, and every row whose index has not
 * changed is skipped entirely rather than rebuilt — which is the difference between scrolling being
 * free and scrolling being the most expensive thing on the frame.
 */
@Composable
private fun LazyItem(index: Int, item: @Composable (Int) -> Unit) {
    item(index)
}

/**
 * Where the composed items go.
 *
 * Only a window's worth of children exist, and each one is placed where the estimate says its index
 * belongs, less how far the list has scrolled. Everything outside the window is nowhere at all —
 * it is not measured, not placed, and not drawn, because it does not exist.
 */
private class LazyPolicy(
    private val state: LazyListState,
    private val window: IntRange,
    private val position: Float,
    private val vertical: Boolean,
    private val spacing: Float,
    private val bars: Boolean,
    private val thickness: Float,
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val itemCount = measurables.size - if (bars) 1 else 0
        val room = if (vertical) {
            Constraints(maxWidth = constraints.maxWidth)
        } else {
            Constraints(maxHeight = constraints.maxHeight)
        }

        val placeables = ArrayList<Placeable>(itemCount)
        for (slot in 0 until itemCount) {
            val placeable = measurables[slot].measure(room)
            placeables += placeable
            state.measuredItem(window.first + slot, if (vertical) placeable.height else placeable.width)
        }

        // A lazy list fills the room it was given. With no room at all — a column that did not say
        // how tall, say — it falls back to what it happens to be showing, which at least appears.
        val along = if (vertical) constraints.maxHeight else constraints.maxWidth
        val shown = placeables.sumOf { (if (vertical) it.height else it.width).toDouble() }.toFloat()
        val length = if (along.isFinite()) along else shown
        val across = placeables.maxOfOrNull { if (vertical) it.width else it.height } ?: 0f

        val width = if (vertical) constraints.constrainWidth(across) else constraints.constrainWidth(length)
        val height = if (vertical) constraints.constrainHeight(length) else constraints.constrainHeight(across)

        // Sizes first, then the window: the estimate of the whole list is built out of the sizes,
        // and scrolling is clamped against that estimate.
        state.measuredViewport(if (vertical) height else width)
        val scrolled = state.position

        val bar = if (bars) {
            measurables[itemCount].measure(
                if (vertical) Constraints.fixed(thickness, height) else Constraints.fixed(width, thickness),
            )
        } else {
            null
        }

        return layout(width, height) {
            var at = state.startOf(window.first) - scrolled
            placeables.forEach { placeable ->
                if (vertical) placeable.at(0f, at) else placeable.at(at, 0f)
                at += (if (vertical) placeable.height else placeable.width) + spacing
            }
            if (vertical) bar?.at(width - thickness, 0f) else bar?.at(0f, height - thickness)
        }
    }
}
