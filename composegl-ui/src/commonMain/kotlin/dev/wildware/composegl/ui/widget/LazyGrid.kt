package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.focus.RevealHandler
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.GridCells
import dev.wildware.composegl.ui.layout.IntrinsicMeasurable
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.Placeable
import dev.wildware.composegl.ui.layout.countIn
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.onReveal
import dev.wildware.composegl.ui.saveable.rememberSaveable

/**
 * Where a lazy grid is, and what it has learned about its rows.
 *
 * A grid scrolls exactly as a list of its rows would, so underneath this is the same thing a
 * [LazyListState] is — sizes measured, sizes guessed, a window — with several items to a line
 * instead of one. What it adds is knowing how many share a line, which only layout can say when the
 * columns are [GridCells.Adaptive].
 */
class LazyGridState(initialPosition: Float = 0f) {

    internal val lines = LazyLines(initialPosition)

    internal val axis: MeasuredAxis get() = lines.axis

    /** How many items share a line, as it was last laid out. Zero before it ever has been. */
    internal var across = 0
        private set

    /** Whether [across] is composition's first guess at an adaptive grid, not something layout found. */
    internal var guessed = false
        private set

    /** How far along the grid, in units, counting the estimate for every row not yet measured. */
    val position: Float get() = lines.axis.position

    /** The first item in the first row with any part of it on screen. */
    val firstVisibleItem: Int get() = lines.indexAt(lines.axis.position) * across.coerceAtLeast(1)

    val isFlinging: Boolean get() = lines.axis.isFlinging

    /** Puts the row [index] is in at the top of the window. [offset] scrolls that row too. */
    fun scrollToItem(index: Int, offset: Float = 0f) =
        lines.scrollToLine(index.coerceAtLeast(0) / across.coerceAtLeast(1), offset)

    fun scrollBy(delta: Float): Boolean = lines.axis.scrollBy(delta)

    fun stopFling() = lines.axis.stop()

    /**
     * How many items there are and how many fit on a line, from composition's guess or layout's
     * answer.
     *
     * When the number across changes every row is a different set of items, so what was measured is
     * forgotten — and the item that was at the top is put back at the top, because a catalogue that
     * reflows when the window is resized should not lose the player's place in it.
     */
    internal fun describe(count: Int, across: Int, spacing: Float, guess: Boolean = false) {
        val lineCount = if (count <= 0) 0 else (count - 1) / across + 1
        val wasGuess = guessed
        guessed = guess
        if (this.across == across) {
            lines.describe(lineCount, spacing)
            return
        }
        // A position under a guessed number across was never about any item, so it is kept as it
        // is: a grid told to start 400 down starts 400 down, not wherever one column put item ten.
        val top = if (this.across > 0 && !wasGuess && position > 0f) firstVisibleItem else 0
        this.across = across
        lines.forget()
        lines.describe(lineCount, spacing)
        if (top > 0) {
            lines.refreshTotal()
            lines.scrollToLine(top / across, 0f)
        }
    }
}

/**
 * A [LazyGridState] that is still where the player left it when its screen comes back, under a
 * [dev.wildware.composegl.ui.saveable.SaveableStateHolder]. Outside one it is plain `remember`.
 */
@Composable
fun rememberLazyGridState(initialPosition: Float = 0f): LazyGridState =
    rememberSaveable { LazyGridState(initialPosition) }

/**
 * A grid that only builds the rows that can be seen: a shop catalogue, a sprite browser, a bag of a
 * thousand items.
 *
 * ```kotlin
 * LazyVerticalGrid(count = inventory.size, columns = GridCells.Fixed(8), key = { inventory[it].id }) { index ->
 *     Slot(inventory[index])
 * }
 * ```
 *
 * It is a [LazyColumn] whose rows are a line of cells, so everything a lazy list does it does the
 * same way: the wheel, a drag and a flick scroll it, [LazyGridState.scrollToItem] brings a row to
 * the top, and focus moving to a cell below the fold scrolls it into view — with right and left
 * going to the neighbouring cell and down to the one straight below, as in a [Grid][dev.wildware.composegl.ui.layout.Grid].
 *
 * Columns share the width out equally, as a `Grid`'s do, and a cell is offered its column's width
 * to fill or shrink inside. Each row is as tall as its tallest cell.
 *
 * @param columns how many columns: [GridCells.Fixed] for exactly that many, [GridCells.Adaptive] for
 *   as many as fit.
 * @param key what identifies an item, so its state follows it when the items are sorted.
 * @param spacing the gap between cells both ways; [horizontalSpacing] and [verticalSpacing]
 *   overrule it for one direction.
 * @param overscan how many extra rows to build above and below the window, so focus has somewhere
 *   to go and a flick does not show a gap.
 * @param item what one cell looks like. Called with the item's index.
 */
@Composable
fun LazyVerticalGrid(
    count: Int,
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    key: ((Int) -> Any)? = null,
    spacing: Float = 0f,
    horizontalSpacing: Float = spacing,
    verticalSpacing: Float = spacing,
    overscan: Int = 2,
    bars: Boolean = true,
    style: String = "scrollbar",
    barThickness: Float = 8f,
    item: @Composable (Int) -> Unit,
) = LazyGrid(
    vertical = true, count, columns, modifier, state, key,
    along = verticalSpacing, across = horizontalSpacing, overscan, bars, style, barThickness, item,
)

/**
 * The same grid lying down: [rows] of cells that fill down and then across, and scroll sideways.
 * A card collection along the bottom of the screen, a filmstrip two thumbnails tall.
 */
@Composable
fun LazyHorizontalGrid(
    count: Int,
    rows: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    key: ((Int) -> Any)? = null,
    spacing: Float = 0f,
    horizontalSpacing: Float = spacing,
    verticalSpacing: Float = spacing,
    overscan: Int = 2,
    bars: Boolean = true,
    style: String = "scrollbar",
    barThickness: Float = 8f,
    item: @Composable (Int) -> Unit,
) = LazyGrid(
    vertical = false, count, rows, modifier, state, key,
    along = horizontalSpacing, across = verticalSpacing, overscan, bars, style, barThickness, item,
)

@Composable
private fun LazyGrid(
    vertical: Boolean,
    count: Int,
    cells: GridCells,
    modifier: Modifier,
    state: LazyGridState,
    key: ((Int) -> Any)?,
    along: Float,
    across: Float,
    overscan: Int,
    bars: Boolean,
    style: String,
    barThickness: Float,
    item: @Composable (Int) -> Unit,
) {
    // Finite too: an infinite gap puts the second column at infinity and every row after the first.
    require(along >= 0f && across >= 0f && along.isFinite() && across.isFinite()) {
        "grid spacing cannot be negative or infinite, was $along along and $across across"
    }

    // A fixed count is known now. An adaptive one is whatever layout last found, or one column as a
    // first guess — a frame of a narrow grid that corrects itself on the next.
    val perLine = when (cells) {
        is GridCells.Fixed -> cells.count
        is GridCells.Adaptive -> state.across.coerceAtLeast(1)
    }
    state.describe(count, perLine, along, guess = cells is GridCells.Adaptive && (state.across == 0 || state.guessed))

    val gestures = remember { ScrollGestures() }
    gestures.horizontal = if (vertical) null else state.axis
    gestures.vertical = if (vertical) state.axis else null

    val drag = remember(gestures) { PointerHandler { gestures.onPointer(it) } }
    val reveal = remember(gestures) { RevealHandler { gestures.reveal(it) } }
    DriveFling(state.axis)

    // The same two pieces of snapshot state a lazy list composes from; see LazyList.
    state.lines.revision
    val window = state.lines.window(overscan)
    val items = if (window.isEmpty()) {
        IntRange.EMPTY
    } else {
        window.first * perLine until minOf((window.last + 1) * perLine, count)
    }

    Layout(
        modifier = modifier.onReveal(reveal).onPointer(drag).clip(),
        name = if (vertical) "lazyVerticalGrid" else "lazyHorizontalGrid",
        content = {
            for (index in items) {
                // One node per item, as in a lazy list, so layout can match children to indices by
                // counting.
                key(key?.invoke(index) ?: index) {
                    Box { LazyItem(index, item) }
                }
            }
            if (bars) ScrollBar(state.axis, vertical = vertical, style = style, gestures = gestures)
        },
        measurePolicy = LazyGridPolicy(state, items, cells, count, vertical, along, across, bars, barThickness),
    )
}

/**
 * Where the composed cells go.
 *
 * The number across is worked out again here rather than trusted from composition, because only
 * layout knows the width. When it disagrees — the first frame of an adaptive grid, or a window that
 * was just resized — the cells are still placed where they belong for the real number, and the
 * state is told so the next frame composes the right ones.
 */
private class LazyGridPolicy(
    private val state: LazyGridState,
    private val items: IntRange,
    private val cells: GridCells,
    private val count: Int,
    private val vertical: Boolean,
    private val along: Float,
    private val across: Float,
    private val bars: Boolean,
    private val thickness: Float,
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val itemCount = measurables.size - if (bars) 1 else 0
        val room = if (vertical) constraints.maxWidth else constraints.maxHeight
        val perLine = cells.countIn(
            room,
            across,
            what = if (vertical) "LazyVerticalGrid(columns = " else "LazyHorizontalGrid(rows = ",
            dimension = if (vertical) "width" else "height",
        )
        state.describe(count, perLine, along)

        // With room to share, each cell's share is known before anything is measured. Without it,
        // each is as wide as the widest cell on screen, as in a Grid.
        val bounded = room.isFinite()
        val gaps = across * (perLine - 1)
        var cell = if (bounded) ((room - gaps) / perLine).coerceAtLeast(0f) else 0f
        val offer = if (bounded) cell else Float.POSITIVE_INFINITY
        val constraint = if (vertical) Constraints(maxWidth = offer) else Constraints(maxHeight = offer)

        val placeables = ArrayList<Placeable>(itemCount)
        for (slot in 0 until itemCount) {
            val placeable = measurables[slot].measure(constraint)
            placeables += placeable
            val crossSize = if (vertical) placeable.width else placeable.height
            if (!bounded && crossSize > cell) cell = crossSize
        }

        // A line is as long as its longest cell. Only a line composed whole is written down: the
        // edge of a window can cut a line in two, and half a row's tallest cell is not the row's.
        val first = if (itemCount > 0) items.first else 0
        var shown = 0f
        var slot = 0
        while (slot < itemCount) {
            val line = (first + slot) / perLine
            val lineStart = line * perLine
            val lineEnd = minOf(lineStart + perLine, count)
            val lastSlot = minOf(lineEnd - first, itemCount)
            var longest = 0f
            for (s in slot until lastSlot) {
                val size = if (vertical) placeables[s].height else placeables[s].width
                if (size > longest) longest = size
            }
            if (lineStart >= first && lineEnd <= first + itemCount) state.lines.measuredLine(line, longest)
            shown += longest + if (slot > 0) along else 0f
            slot = lastSlot
        }

        // Like a lazy list, it fills the room it was given along the way it scrolls, and falls back
        // to what it is showing when there is none.
        val alongRoom = if (vertical) constraints.maxHeight else constraints.maxWidth
        val length = if (alongRoom.isFinite()) alongRoom else shown
        val breadth = cell * perLine + gaps

        val width = if (vertical) constraints.constrainWidth(breadth) else constraints.constrainWidth(length)
        val height = if (vertical) constraints.constrainHeight(length) else constraints.constrainHeight(breadth)

        // Sizes first, then the window: scrolling is clamped against an estimate built from them.
        state.lines.measuredViewport(if (vertical) height else width)
        val scrolled = state.position

        val bar = if (bars) {
            measurables[itemCount].measure(
                if (vertical) Constraints.fixed(thickness, height) else Constraints.fixed(width, thickness),
            )
        } else {
            null
        }

        return layout(width, height) {
            // Where each line starts, walked forward from the first rather than summed from the top
            // for every cell.
            var line = first / perLine
            var start = state.lines.startOf(line) - scrolled
            placeables.forEachIndexed { slot, placeable ->
                val index = first + slot
                while (index / perLine > line) {
                    start += state.lines.sizeOf(line) + along
                    line++
                }
                val sideways = (index % perLine) * (cell + across)
                if (vertical) placeable.at(sideways, start) else placeable.at(start, sideways)
            }
            if (vertical) bar?.at(width - thickness, 0f) else bar?.at(0f, height - thickness)
        }
    }

    // Written out rather than left to the default, which runs measure: measuring records each
    // row's size, the window's and the number across, and bumps the state's revision when they
    // change. A question asked with made-up room would record made-up answers, recompose, and ask
    // again, every frame. The answer is about the cells that exist now: rows end to end along the
    // grid, and a line of the widest cells across it.

    override fun MeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
        if (vertical) breadth(measurables) { minIntrinsicWidth(Float.POSITIVE_INFINITY) }
        else length(measurables) { minIntrinsicWidth(height) }

    override fun MeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
        if (vertical) breadth(measurables) { maxIntrinsicWidth(Float.POSITIVE_INFINITY) }
        else length(measurables) { maxIntrinsicWidth(height) }

    override fun MeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
        if (vertical) length(measurables) { minIntrinsicHeight(width) }
        else breadth(measurables) { minIntrinsicHeight(Float.POSITIVE_INFINITY) }

    override fun MeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
        if (vertical) length(measurables) { maxIntrinsicHeight(width) }
        else breadth(measurables) { maxIntrinsicHeight(Float.POSITIVE_INFINITY) }

    /** How many share a line without asking layout: a fixed count, or what layout last found. */
    private val perLine: Int
        get() = when (cells) {
            is GridCells.Fixed -> cells.count
            is GridCells.Adaptive -> state.across.coerceAtLeast(1)
        }

    private inline fun length(measurables: List<IntrinsicMeasurable>, size: IntrinsicMeasurable.() -> Float): Float {
        val itemCount = measurables.size - if (bars) 1 else 0
        if (itemCount <= 0) return 0f
        val perLine = perLine
        val first = items.first
        var total = 0f
        var longest = 0f
        var lines = 0
        for (slot in 0 until itemCount) {
            val each = measurables[slot].size()
            if (each > longest) longest = each
            val index = first + slot
            if ((index + 1) % perLine == 0 || slot == itemCount - 1) {
                total += longest
                longest = 0f
                lines++
            }
        }
        return total + along * (lines - 1)
    }

    private inline fun breadth(measurables: List<IntrinsicMeasurable>, size: IntrinsicMeasurable.() -> Float): Float {
        val itemCount = measurables.size - if (bars) 1 else 0
        if (itemCount <= 0) return 0f
        var widest = 0f
        for (slot in 0 until itemCount) {
            val each = measurables[slot].size()
            if (each > widest) widest = each
        }
        val shown = minOf(perLine, itemCount)
        return widest * shown + across * (shown - 1)
    }
}
