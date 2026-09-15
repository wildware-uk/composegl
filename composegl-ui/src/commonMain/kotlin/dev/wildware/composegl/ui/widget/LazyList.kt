package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.focus.RevealHandler
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.IntrinsicMeasurable
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.Placeable
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.PlacementFrame
import dev.wildware.composegl.ui.modifier.PlacementFrameElement
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.onReveal
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.saveable.rememberSaveable
import dev.wildware.composegl.ui.saveable.rememberSaveableStateHolder

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

    /** Every item is a line of its own. A grid shares the same arithmetic with several to a line. */
    internal val lines = LazyLines(initialPosition)

    internal val axis: MeasuredAxis get() = lines.axis

    /** How far down the list, in units, counting the estimate for everything unmeasured. */
    val position: Float get() = lines.axis.position

    /** The first item with any part of it on screen. */
    val firstVisibleItem: Int get() = lines.indexAt(lines.axis.position)

    val isFlinging: Boolean get() = lines.axis.isFlinging

    /** Puts [index] at the top of the window. [offset] scrolls that item too. */
    fun scrollToItem(index: Int, offset: Float = 0f) = lines.scrollToLine(index, offset)

    fun scrollBy(delta: Float): Boolean = lines.axis.scrollBy(delta)

    fun stopFling() = lines.axis.stop()

    /**
     * The item a sticky header is pinned for now, or null when none is. Written by layout, and
     * snapshot state, so a "you are in: Armour" label that reads it follows the list.
     */
    var pinnedHeader: Int? by mutableStateOf(null)
        internal set

    /** Where the pinned header was placed along the list, and where it ends. Written by layout. */
    internal var pinnedStart = 0f
    internal var pinnedEnd = 0f

    /**
     * [area] as focus should see it: a row under the pinned header is not in view, so the window is
     * treated as starting where the header ends. The header itself is left alone, or focusing it
     * would scroll the list away from it.
     */
    internal fun clearOfHeader(area: Rect, vertical: Boolean): Rect {
        if (pinnedHeader == null || pinnedEnd <= 0f) return area
        val start = if (vertical) area.top else area.left
        val end = if (vertical) area.bottom else area.right
        if (start >= pinnedStart - Slack && end <= pinnedEnd + Slack) return area
        return if (vertical) area.copy(top = area.top - pinnedEnd) else area.copy(left = area.left - pinnedEnd)
    }

    private companion object {
        const val Slack = 0.5f
    }
}

/**
 * The part of a lazy list that does not care what is on a line: sizes learned, sizes guessed, and
 * which lines to build.
 *
 * A list has one item to a line. A grid has a row of them, and scrolls exactly as a list of its rows
 * would — which is how a grid gets windowing, scroll-to-item and focus reveal without a second copy
 * of any of it.
 */
internal class LazyLines(initialPosition: Float) {

    val axis = MeasuredAxis(initialPosition)

    /** What each line measured, by index. An estimate is used for everything not in here. */
    private val sizes = HashMap<Int, Float>()
    private var measuredSum = 0f

    var count = 0
        private set
    private var spacing = 0f
    private var lastVisible = -1f

    /**
     * Bumped whenever something that decides *which* lines to compose has changed.
     *
     * Read during composition, written by layout. That is the loop the whole design turns on: a
     * frame composes its best guess, layout finds out what the lines really were, and the next
     * frame composes the right ones. It settles in a frame or two and then stops changing, because
     * a size that has been measured does not move.
     */
    var revision: Int by mutableStateOf(0)
        private set

    fun scrollToLine(index: Int, offset: Float) =
        axis.scrollTo(startOf(index.coerceIn(0, (count - 1).coerceAtLeast(0))) + offset)

    // --- what it has learned ---------------------------------------------------------------------

    /** The size to assume for a line nobody has measured. */
    val average: Float
        get() = if (sizes.isEmpty()) seed else measuredSum / sizes.size

    /** The guess for a line before any has been measured, or since [forget]. */
    private var seed = DefaultItem

    fun sizeOf(index: Int): Float = sizes[index] ?: average

    /** Where a line starts, counting the estimate for the ones above it. */
    fun startOf(index: Int): Float {
        var at = 0f
        for (i in 0 until index) at += sizeOf(i) + spacing
        return at
    }

    /** Which line contains [position]. */
    fun indexAt(position: Float): Int {
        var at = 0f
        for (i in 0 until count) {
            at += sizeOf(i) + spacing
            if (position < at) return i
        }
        return (count - 1).coerceAtLeast(0)
    }

    /**
     * Which lines to compose: everything on screen, plus [overscan] either side.
     *
     * The overscan is what makes focus and a fast flick work. Focus can only move to something that
     * exists, so a list with nothing composed below the fold is a list a pad cannot walk down; two
     * spare lines either side are enough for the next press to land on something real, and cheap
     * enough that nobody notices.
     */
    fun window(overscan: Int): IntRange {
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

    fun describe(count: Int, spacing: Float) {
        if (this.spacing != spacing) {
            this.spacing = spacing
            revision++
        }
        if (this.count == count) return
        this.count = count
        // Sizes for lines that no longer exist are worse than no sizes: they would be averaged in.
        val gone = sizes.keys.filter { it >= count }
        gone.forEach { index -> sizes.remove(index)?.let { measuredSum -= it } }
        revision++
    }

    /**
     * Everything measured so far is about lines that are not the same lines any more.
     *
     * A grid that goes from four columns to three has regrouped every row, so the third row's height
     * says nothing about what the third row is now.
     */
    fun forget() {
        // The sizes go, but not what they averaged: a grid's rows are nearly always one height,
        // and the old average is a far better guess for the new rows than a made-up constant.
        seed = average
        sizes.clear()
        measuredSum = 0f
        revision++
    }

    /** Works the estimate of the whole out again now, rather than at the next layout. */
    fun refreshTotal() = axis.measured(axis.visible, estimatedTotal())

    fun measuredLine(index: Int, size: Float) {
        val before = sizes.put(index, size)
        if (before == size) return
        measuredSum += size - (before ?: 0f)
        revision++
    }

    /** The window's own size, and with it the estimate of the whole. */
    fun measuredViewport(visible: Float) {
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
        /** What a line is assumed to be before anything has been measured. */
        const val DefaultItem = 48f

        /** How many of those to compose on the very first frame, before there is a window. */
        const val Guess = 12
    }
}

/**
 * A [LazyListState] that is still where the player left it when its screen comes back, under a
 * [dev.wildware.composegl.ui.saveable.SaveableStateHolder]. Outside one it is plain `remember`.
 */
@Composable
fun rememberLazyListState(initialPosition: Float = 0f): LazyListState =
    rememberSaveable { LazyListState(initialPosition) }

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
) = LazyList(true, Counted(count, key, item), modifier, state, spacing, overscan, bars, style, barThickness)

/**
 * A list made of sections, whose headers stay at the top while their items scroll under them.
 *
 * ```kotlin
 * LazyColumn(Modifier.fillMaxSize()) {
 *     stickyHeader { Header("Weapons") }
 *     items(weapons, key = { it.id }) { WeaponRow(it) }
 *     stickyHeader { Header("Armour") }
 *     items(armour, key = { it.id }) { ArmourRow(it) }
 * }
 * ```
 *
 * A header scrolls in like any row, and stops at the top edge once it gets there. The next header
 * pushes it off as it arrives rather than sliding over it, so there is only ever one at the top and
 * always the one for the section being read.
 *
 * While pinned it is on top of the rows under it, for the pointer as well as the eye: a click on
 * the header does not fall through to a row nobody can see, and a drag on it still scrolls the
 * list. Focus moving up onto a row hidden by it scrolls far enough for the row to come out from
 * under it.
 *
 * Everything else — only building what can be seen, keys, scrolling, focus — is exactly the
 * count-and-index [LazyColumn]'s.
 *
 * @param content the list, in order: [LazyListScope.item], [LazyListScope.items] and
 *   [LazyListScope.stickyHeader] as many times as it takes. It may read state; the list is rebuilt
 *   when that state changes.
 */
@Composable
fun LazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    spacing: Float = 0f,
    overscan: Int = 2,
    bars: Boolean = true,
    style: String = "scrollbar",
    barThickness: Float = 8f,
    content: LazyListScope.() -> Unit,
) = LazyList(true, rememberSections(content), modifier, state, spacing, overscan, bars, style, barThickness)

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
) = LazyList(false, Counted(count, key, item), modifier, state, spacing, overscan, bars, style, barThickness)

/** The same sections lying down, each header held at the left edge while its items pass under it. */
@Composable
fun LazyRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    spacing: Float = 0f,
    overscan: Int = 2,
    bars: Boolean = true,
    style: String = "scrollbar",
    barThickness: Float = 8f,
    content: LazyListScope.() -> Unit,
) = LazyList(false, rememberSections(content), modifier, state, spacing, overscan, bars, style, barThickness)

/** What goes into a sectioned [LazyColumn] or [LazyRow], in the order it is shown. */
interface LazyListScope {

    /** One row. [key] does what a key does anywhere in a lazy list; without one it is its position. */
    fun item(key: Any? = null, content: @Composable () -> Unit)

    /** [count] rows, each called with its index within this run — the first of them is 0. */
    fun items(count: Int, key: ((Int) -> Any)? = null, item: @Composable (Int) -> Unit)

    /**
     * A header that stays at the top while the rows after it scroll, until the next header comes up
     * and pushes it off.
     */
    fun stickyHeader(key: Any? = null, content: @Composable () -> Unit)
}

/** A row for each of [items], called with the item rather than its index. */
fun <T> LazyListScope.items(
    items: List<T>,
    key: ((T) -> Any)? = null,
    item: @Composable (T) -> Unit,
) = items(items.size, if (key == null) null else { index -> key(items[index]) }) { index -> item(items[index]) }

/** Everything the list needs to know about what is in it, however it was described. */
internal interface LazyListContent {
    val count: Int

    /** The indices that are sticky headers, in order. */
    val headers: List<Int>

    fun keyOf(index: Int): Any

    @Composable
    fun Item(index: Int)

    /** The header [index] is under: the last one at or before it, or -1 when it comes before them all. */
    fun headerFor(index: Int): Int {
        val all = headers
        var low = 0
        var high = all.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (all[middle] <= index) {
                found = all[middle]
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }

    /** Whether [index] is a sticky header. */
    fun isHeader(index: Int): Boolean = headers.isNotEmpty() && headerFor(index) == index

    /** The first header after [header], or -1. */
    fun headerAfter(header: Int): Int {
        // Searched rather than scanned: an A to Z of names, or a log by day, can have a lot of them.
        val all = headers
        var low = 0
        var high = all.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (all[middle] <= header) low = middle + 1 else high = middle
        }
        return if (low < all.size) all[low] else -1
    }
}

/** A count and an index: the plain [LazyColumn], with no headers. */
private class Counted(
    override val count: Int,
    private val key: ((Int) -> Any)?,
    private val item: @Composable (Int) -> Unit,
) : LazyListContent {
    override val headers: List<Int> get() = emptyList()

    override fun keyOf(index: Int): Any = key?.invoke(index) ?: index

    @Composable
    override fun Item(index: Int) = LazyItem(index, item)
}

/**
 * The block, run into [LazyListSections] only when something it reads changes.
 *
 * Not on every composition: the block is run again whenever the list scrolls, and running it makes
 * new row lambdas, and a row handed a new lambda cannot be skipped. A derived state reruns it when
 * the block itself is new or a piece of state it reads has moved, and hands back the same sections
 * otherwise.
 */
@Composable
private fun rememberSections(content: LazyListScope.() -> Unit): LazyListContent {
    val latest = rememberUpdatedState(content)
    val sections by remember { derivedStateOf { LazyListSections().apply(latest.value) } }
    return sections
}

/** Runs of rows and the headers between them, laid end to end. */
internal class LazyListSections : LazyListScope, LazyListContent {

    private class Run(
        val start: Int,
        val count: Int,
        val key: ((Int) -> Any)?,
        val single: (@Composable () -> Unit)?,
        val many: (@Composable (Int) -> Unit)?,
    )

    private val runs = ArrayList<Run>()
    private val stuck = ArrayList<Int>()

    override var count = 0
        private set

    override val headers: List<Int> get() = stuck

    override fun item(key: Any?, content: @Composable () -> Unit) {
        runs += Run(count, 1, key?.let { fixed -> { _: Int -> fixed } }, content, null)
        count++
    }

    override fun items(count: Int, key: ((Int) -> Any)?, item: @Composable (Int) -> Unit) {
        require(count >= 0) { "a run of items cannot have a negative count, was $count" }
        if (count == 0) return
        runs += Run(this.count, count, key, null, item)
        this.count += count
    }

    override fun stickyHeader(key: Any?, content: @Composable () -> Unit) {
        stuck += count
        item(key, content)
    }

    override fun keyOf(index: Int): Any {
        val run = runAt(index)
        // Wrapped, so a row with no key at position 3 is not the same row as one keyed 3.
        return run.key?.invoke(index - run.start) ?: Position(index)
    }

    @Composable
    override fun Item(index: Int) {
        val run = runAt(index)
        val single = run.single
        if (single != null) LazySingle(single) else LazyItem(index - run.start, run.many!!)
    }

    private fun runAt(index: Int): Run {
        var low = 0
        var high = runs.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (runs[middle].start <= index) low = middle else high = middle - 1
        }
        return runs[low]
    }

    private data class Position(val index: Int)
}

@Composable
private fun LazyList(
    vertical: Boolean,
    content: LazyListContent,
    modifier: Modifier,
    state: LazyListState,
    spacing: Float,
    overscan: Int,
    bars: Boolean,
    style: String,
    barThickness: Float,
) {
    state.lines.describe(content.count, spacing)

    val gestures = remember { ScrollGestures() }
    gestures.horizontal = if (vertical) null else state.axis
    gestures.vertical = if (vertical) state.axis else null
    // A row in a right-to-left screen starts on the right and scrolls towards the left.
    val mirrored = !vertical && LocalLayoutDirection.current == LayoutDirection.Rtl
    gestures.mirrored = mirrored

    val drag = remember(gestures) { PointerHandler { gestures.onPointer(it) } }
    val reveal = remember(gestures, state, vertical, mirrored) {
        RevealHandler { area ->
            // The header is pinned against the start, so the area is turned round to be measured
            // from there before it is cleared of it.
            val visible = state.axis.visible
            val fromStart = if (mirrored) area.copy(left = visible - area.right, right = visible - area.left) else area
            val clear = state.clearOfHeader(fromStart, vertical)
            gestures.reveal(if (mirrored) clear.copy(left = visible - clear.right, right = visible - clear.left) else clear)
        }
    }
    // An item that slides is measured against the list with the scroll taken out, so scrolling is
    // not every row moving.
    val frame = remember(state, vertical, mirrored) {
        object : PlacementFrame {
            override val scrolledX: Float get() = if (vertical) 0f else if (mirrored) -state.position else state.position
            override val scrolledY: Float get() = if (vertical) state.position else 0f
        }
    }
    DriveFling(state.axis)

    // Both of these are snapshot state, and between them they are the whole of "which items should
    // exist": where the list is, and everything layout has learned about how big things are.
    state.lines.revision
    val window = state.lines.window(overscan)

    // The header for the section at the top has to exist even when its own place is long gone above
    // the window, or there would be nothing to pin. It goes first, in the same loop as the rest, so
    // it keeps its node and its state as it scrolls out of the window and back.
    val pinned = if (window.isEmpty()) -1 else content.headerFor(state.firstVisibleItem)
    val composed = if (pinned in 0 until window.first) listOf(pinned) + window else window.toList()

    // A row scrolled out of the window leaves the tree, and without this its rememberSaveable state
    // would go with it. Each row is a screen of its own here, kept under its key until it is back.
    val rows = rememberSaveableStateHolder()

    Layout(
        modifier = modifier.onReveal(reveal).onPointer(drag).clip().then(PlacementFrameElement(frame)),
        name = if (vertical) "lazyColumn" else "lazyRow",
        content = {
            for (index in composed) {
                // One node per item whatever the item emits, so layout can match children to
                // indices by counting. The key is what keeps an item's state with the item when
                // the list is reordered rather than with the slot it happened to be in.
                val itemKey = content.keyOf(index)
                key(itemKey) {
                    // A header is drawn over the rows and asked about the pointer before them, so
                    // pinned it hides them for the mouse too. It scrolls the list rather than
                    // swallowing a drag, and lets through what the list does not want.
                    val header = if (content.isHeader(index)) {
                        Modifier.zIndex(1f).onPointer(drag)
                    } else {
                        Modifier
                    }
                    Box(header) { rows.SaveableStateProvider(itemKey) { content.Item(index) } }
                }
            }
            if (bars) ScrollBar(state.axis, vertical = vertical, style = style, gestures = gestures)
        },
        measurePolicy = LazyPolicy(state, content, composed, window, vertical, spacing, bars, barThickness),
    )
}

/** One single item, in its own restartable group, for the same reason as [LazyItem]. */
@Composable
private fun LazySingle(content: @Composable () -> Unit) {
    content()
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
internal fun LazyItem(index: Int, item: @Composable (Int) -> Unit) {
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
    private val content: LazyListContent,
    private val composed: List<Int>,
    private val window: IntRange,
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
            state.lines.measuredLine(composed[slot], if (vertical) placeable.height else placeable.width)
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
        state.lines.measuredViewport(if (vertical) height else width)
        val scrolled = state.position

        val bar = if (bars) {
            measurables[itemCount].measure(
                if (vertical) Constraints.fixed(thickness, height) else Constraints.fixed(width, thickness),
            )
        } else {
            null
        }

        // The window runs end to end from where the estimate puts its first item. A header pulled in
        // from above the window is not part of that run and goes where its own index belongs.
        val starts = FloatArray(itemCount)
        var at = if (window.isEmpty()) 0f else state.lines.startOf(window.first) - scrolled
        for (slot in 0 until itemCount) {
            val index = composed[slot]
            val size = if (vertical) placeables[slot].height else placeables[slot].width
            if (window.isEmpty() || index < window.first) {
                starts[slot] = state.lines.startOf(index) - scrolled
            } else {
                starts[slot] = at
                at += size + spacing
            }
        }

        pin(starts, placeables, scrolled)

        val mirrored = !vertical && layoutDirection == LayoutDirection.Rtl
        return layout(width, height) {
            placeables.forEachIndexed { slot, placeable ->
                when {
                    vertical -> placeable.at(0f, starts[slot])
                    mirrored -> placeable.at(width - starts[slot] - placeable.width, 0f)
                    else -> placeable.at(starts[slot], 0f)
                }
            }
            if (vertical) bar?.at(width - thickness, 0f) else bar?.at(0f, height - thickness)
        }
    }

    /**
     * Holds the header for the section at the top against the top edge, and lets the next header
     * push it up and off as it arrives.
     *
     * Worked out from where the list is now rather than trusted from composition, which picked the
     * header a frame ago. When the one it should be was not composed — a jump far down a list — none
     * is pinned for that frame, and the next frame composes it.
     */
    private fun pin(starts: FloatArray, placeables: List<Placeable>, scrolled: Float) {
        // Worked out first and written once: pinnedHeader is snapshot state, and clearing it to
        // write the same header straight back would count as a change and recompose its readers
        // on every frame of a list that is standing still.
        val header = if (content.headers.isEmpty() || content.count <= 0) -1
        else content.headerFor(state.lines.indexAt(scrolled))
        val slot = if (header < 0) -1 else composed.indexOf(header)
        if (slot < 0) {
            state.pinnedHeader = null
            state.pinnedStart = 0f
            state.pinnedEnd = 0f
            return
        }

        val size = if (vertical) placeables[slot].height else placeables[slot].width
        var place = maxOf(starts[slot], 0f)
        val next = composed.indexOf(content.headerAfter(header))
        if (next >= 0) place = minOf(place, starts[next] - spacing - size)
        starts[slot] = place

        state.pinnedHeader = header
        state.pinnedStart = place
        state.pinnedEnd = place + size
    }

    // Written out rather than left to the default, which runs measure: measuring records each
    // item's size and the window's, and bumps the state's revision when they change. A question
    // asked with made-up room would record made-up sizes, recompose, and ask again, every frame.
    // The answer is about the items that exist now, end to end along the list and the widest across.

    override fun MeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
        if (vertical) across(measurables) { minIntrinsicWidth(Float.POSITIVE_INFINITY) }
        else along(measurables) { minIntrinsicWidth(height) }

    override fun MeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
        if (vertical) across(measurables) { maxIntrinsicWidth(Float.POSITIVE_INFINITY) }
        else along(measurables) { maxIntrinsicWidth(height) }

    override fun MeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
        if (vertical) along(measurables) { minIntrinsicHeight(width) }
        else across(measurables) { minIntrinsicHeight(Float.POSITIVE_INFINITY) }

    override fun MeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
        if (vertical) along(measurables) { maxIntrinsicHeight(width) }
        else across(measurables) { maxIntrinsicHeight(Float.POSITIVE_INFINITY) }

    private inline fun along(measurables: List<IntrinsicMeasurable>, size: IntrinsicMeasurable.() -> Float): Float {
        val items = measurables.size - if (bars) 1 else 0
        var total = spacing * (items - 1).coerceAtLeast(0)
        for (index in 0 until items) total += measurables[index].size()
        return total
    }

    private inline fun across(measurables: List<IntrinsicMeasurable>, size: IntrinsicMeasurable.() -> Float): Float {
        val items = measurables.size - if (bars) 1 else 0
        var widest = 0f
        for (index in 0 until items) {
            val each = measurables[index].size()
            if (each > widest) widest = each
        }
        return widest
    }
}
