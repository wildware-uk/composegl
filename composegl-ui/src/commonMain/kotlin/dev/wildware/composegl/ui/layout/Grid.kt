package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.modifier.Modifier

/**
 * How many columns a [Grid] has.
 *
 * Two answers cover the screens a grid is for. An inventory is six slots across however wide the
 * window is: [Fixed]. A level select fits as many tiles as the width allows and wraps the rest:
 * [Adaptive].
 */
sealed interface GridCells {

    /** Exactly [count] columns, sharing the width out equally. */
    data class Fixed(val count: Int) : GridCells {
        init { require(count >= 1) { "a grid needs at least one column, was $count" } }
    }

    /**
     * As many columns as fit with each at least [minSize] wide, and never fewer than one.
     *
     * Whatever is left over once they fit is shared between them, so the columns fill the width
     * rather than leaving a ragged strip down the right-hand side.
     */
    data class Adaptive(val minSize: Float) : GridCells {
        init {
            require(minSize > 0f && minSize.isFinite()) { "an adaptive column needs a size above zero, was $minSize" }
        }
    }
}

/**
 * Children in rows and columns, in the order they were written: across, then down.
 *
 * Every column is the same width. With a bounded width the columns share it out, as three weights
 * in a row would; with an unbounded one — a grid inside something that scrolls sideways — every
 * column is as wide as the widest child, because there is nothing to share.
 *
 * Every row is as tall as the tallest child in it, and rows hand out height the way a column does:
 * in order, until it runs out. A child is offered the whole cell to shrink inside and is placed in
 * it by its own `Modifier.align`, or by [contentAlignment] when it said nothing.
 *
 * It is a data class so that recomposing with the same arguments produces an equal policy and the
 * node reports no change.
 */
internal data class GridPolicy(
    val columns: GridCells,
    val horizontalSpacing: Float,
    val verticalSpacing: Float,
    val contentAlignment: Alignment,
) : MeasurePolicy {

    init {
        // Finite too: an infinite gap makes the first column's position zero times infinity.
        require(
            horizontalSpacing >= 0f && verticalSpacing >= 0f &&
                horizontalSpacing.isFinite() && verticalSpacing.isFinite(),
        ) {
            "grid spacing cannot be negative, was $horizontalSpacing across and $verticalSpacing down"
        }
    }

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val count = measurables.size
        if (count == 0) return layout(constraints.minWidth, constraints.minHeight, 0)

        val bounded = constraints.hasBoundedWidth
        val across = columnCount(constraints.maxWidth)
        // Not (count + across - 1) / across: that overflows for a very large Fixed count.
        val rows = (count - 1) / across + 1
        val gaps = horizontalSpacing * (across - 1)

        // With a width to share, each column's share is known before anything is measured. Without
        // one, it is the widest child, and only known after.
        var cell = if (bounded) ((constraints.maxWidth - gaps) / across).coerceAtLeast(0f) else 0f

        // Lent by the node and used again next frame; see MeasureScope.
        val placeables = placeables(count)
        val heights = sizes(rows)
        val offers = offers(rows)

        var used = 0f
        for (row in 0 until rows) {
            if (row > 0) used += verticalSpacing
            val room =
                if (constraints.hasBoundedHeight) (constraints.maxHeight - used).coerceAtLeast(0f)
                else Float.POSITIVE_INFINITY
            // Every child in a row is offered the same thing, so one remembered offer per row.
            val offer = offers[row].of(0f, if (bounded) cell else Float.POSITIVE_INFINITY, 0f, room)

            var tallest = 0f
            val first = row * across
            for (index in first until minOf(first + across, count)) {
                val placeable = measurables[index].measure(offer)
                placeables[index] = placeable
                if (placeable.height > tallest) tallest = placeable.height
                if (!bounded && placeable.width > cell) cell = placeable.width
            }
            heights[row] = tallest
            used += tallest
        }

        val width = constraints.constrainWidth(cell * across + gaps)
        val height = constraints.constrainHeight(used)

        // Where each child goes is known now, so it is written down rather than closed over; see
        // MeasureScope.layout.
        val placements = placements(count)
        var top = 0f
        for (row in 0 until rows) {
            val rowHeight = heights[row]
            val first = row * across
            for (index in first until minOf(first + across, count)) {
                val placeable = placeables[index] ?: continue
                val alignment = measurables[index].layoutData.alignment ?: contentAlignment
                val column = index - first
                placements[index * 2] = column * (cell + horizontalSpacing) + alignment.xIn(cell, placeable.width)
                placements[index * 2 + 1] = top + alignment.yIn(rowHeight, placeable.height)
            }
            top += rowHeight + verticalSpacing
        }

        return layout(width, height, count)
    }

    /** How many columns fit in [available], which may be unbounded. */
    internal fun columnCount(available: Float): Int = when (columns) {
        is GridCells.Fixed -> columns.count
        is GridCells.Adaptive -> {
            check(available.isFinite()) {
                "Grid(columns = GridCells.Adaptive(${columns.minSize})) was offered an unbounded width, " +
                    "so there is no telling how many columns fit. Give it a width, or use GridCells.Fixed."
            }
            // n columns and n - 1 gaps fit when n * (size + gap) <= available + gap. The nudge is
            // for widths that fit exactly: 204 / 68 in floats is 2.9999998 as often as it is 3.
            val fit = ((available + horizontalSpacing) / (columns.minSize + horizontalSpacing) + 1e-4f).toInt()
            fit.coerceAtLeast(1)
        }
    }
}

/**
 * Children in rows and columns: an inventory, a level select, a wall of achievements.
 *
 * ```kotlin
 * Grid(columns = GridCells.Fixed(6), spacing = 4f) {
 *     items.forEach { item -> key(item.id) { Slot(item) } }
 * }
 * Grid(columns = GridCells.Adaptive(minSize = 64f)) { … }
 * ```
 *
 * The children are one flat list rather than rows of rows, which is what makes the rest simple:
 * `key` works on each item as it does anywhere, Tab walks them across and then down, and the pad
 * moves between cells by where they are on screen — right goes to the cell to the right, down to
 * the one below — with no wiring.
 *
 * @param spacing the gap between cells, both ways. [horizontalSpacing] and [verticalSpacing]
 *   overrule it for one direction.
 * @param contentAlignment where a child smaller than its cell sits in it, unless its own
 *   `Modifier.align(...)` says otherwise.
 */
@Composable
fun Grid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    spacing: Float = 0f,
    horizontalSpacing: Float = spacing,
    verticalSpacing: Float = spacing,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit,
) {
    val policy = remember(columns, horizontalSpacing, verticalSpacing, contentAlignment) {
        GridPolicy(columns, horizontalSpacing, verticalSpacing, contentAlignment)
    }
    Layout(modifier, name = "grid", content = content, measurePolicy = policy)
}
