package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.modifier.Modifier

/**
 * A row or a column that wraps: children in a line until the line is full, then a new line.
 *
 * Like [LinearPolicy], one policy serves both with the axes swapped. "Main" is the way the children
 * run — across for a flow row — and "cross" is the way the lines stack.
 *
 * Every child is measured once, offered the whole main axis. A child too big for a line on its own
 * gets a line to itself and is held to the width of the flow rather than being pushed off the end.
 * On an axis with no limit there is nothing to wrap against, so the flow is a single line.
 *
 * Weights mean nothing here. A share of "what is left" needs a line to be left over from, and the
 * line is only known once everything on it has been measured.
 *
 * It is a data class so that recomposing with the same settings produces an equal policy and the
 * node reports no change. The scratch arrays below are in the body, so they are not part of that.
 */
internal data class FlowPolicy(
    val horizontal: Boolean,
    val mainSpacing: Float,
    val crossSpacing: Float,
    val arrangement: Arrangement,
    val crossAlignment: Alignment,
    val maxItemsInEachLine: Int,
) : MeasurePolicy {

    init {
        require(maxItemsInEachLine >= 1) { "a flow needs room for at least one item a line, not $maxItemsInEachLine" }
    }

    // One line's sizes from index zero, and where the arrangement put them. An arrangement only
    // reads from the front of an array, and a line is somewhere in the middle of the children.
    // Kept rather than made per frame. They are only touched after every child has been measured,
    // so a flow nested inside a flow cannot be halfway through using them.
    private var lineSizes = FloatArray(0)
    private var linePositions = FloatArray(0)

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val count = measurables.size
        val mainMax = if (horizontal) constraints.maxWidth else constraints.maxHeight
        val crossMax = if (horizontal) constraints.maxHeight else constraints.maxWidth
        val gap = mainSpacing + arrangement.spacing

        // Lent by the node and used again next frame; see MeasureScope.
        val placeables = placeables(count)
        val sizes = sizes(count)
        val offers = offers(count)

        for (index in 0 until count) {
            val offer = offers[index]
            val offered = if (horizontal) offer.of(0f, mainMax, 0f, crossMax) else offer.of(0f, crossMax, 0f, mainMax)
            val placeable = measurables[index].measure(offered)
            placeables[index] = placeable
            sizes[index] = placeable.main
        }

        // First walk over the lines: how long the longest is and how deep they are together, which
        // is the size of the flow. The second walk, below, places them inside that size.
        var longest = 0f
        var depth = 0f
        var lines = 0
        var start = 0
        while (start < count) {
            val end = lineEnd(start, count, sizes, mainMax, gap)
            val length = lineLength(start, end, sizes, gap)
            if (length > longest) longest = length
            depth += lineDepth(start, end, measurables, placeables)
            lines++
            start = end
        }
        if (lines > 1) depth += crossSpacing * (lines - 1)

        val main = if (horizontal) constraints.constrainWidth(longest) else constraints.constrainHeight(longest)
        val crossSize = if (horizontal) constraints.constrainHeight(depth) else constraints.constrainWidth(depth)
        val width = if (horizontal) main else crossSize
        val height = if (horizontal) crossSize else main

        val placements = placements(count)
        var lineTop = 0f
        start = 0
        while (start < count) {
            val end = lineEnd(start, count, sizes, mainMax, gap)
            val items = end - start
            val lineCross = lineDepth(start, end, measurables, placeables)
            val above = lineAbove(start, end, measurables, placeables)
            if (lineSizes.size < items) {
                lineSizes = FloatArray(count)
                linePositions = FloatArray(count)
            }
            for (index in 0 until items) lineSizes[index] = sizes[start + index]

            // The arrangement is given the line with the fixed gaps taken out, and the gaps are put
            // back afterwards. That way Centre centres the whole line, gaps included, and a
            // spacedBy arrangement adds its own gap on top of the flow's rather than instead of it.
            arrangement.arrange(main - mainSpacing * (items - 1), lineSizes, items, linePositions)

            for (index in 0 until items) {
                val child = start + index
                val placeable = checkNotNull(placeables[child]) { "a child of this flow was never measured" }
                val along = linePositions[index] + mainSpacing * index
                val alignment = measurables[child].layoutData.alignment ?: crossAlignment
                if (horizontal) {
                    val baseline = baselineOf(measurables[child], placeable)
                    placements[child * 2] = along
                    placements[child * 2 + 1] =
                        lineTop + if (baseline.isNaN()) alignment.yIn(lineCross, placeable.height) else above - baseline
                } else {
                    placements[child * 2] = lineTop + alignment.xIn(lineCross, placeable.width)
                    placements[child * 2 + 1] = along
                }
            }
            lineTop += lineCross + crossSpacing
            start = end
        }

        return layout(width, height, count)
    }

    /**
     * The index one past the last child that fits on the line starting at [start].
     *
     * The first child always goes on, however big: a line with nothing on it would never end.
     */
    private fun lineEnd(start: Int, count: Int, sizes: FloatArray, mainMax: Float, gap: Float): Int {
        var used = sizes[start]
        var end = start + 1
        while (end < count && end - start < maxItemsInEachLine) {
            val next = used + gap + sizes[end]
            // A hair of slack for the floats. Three children a third of the width each add up to a
            // sliver over the width, and wrapping the last of them onto a line of its own is a bug
            // nobody could see the reason for.
            if (next > mainMax + Slack) break
            used = next
            end++
        }
        return end
    }

    private fun lineLength(start: Int, end: Int, sizes: FloatArray, gap: Float): Float {
        var length = gap * (end - start - 1)
        for (index in start until end) length += sizes[index]
        return length
    }

    /**
     * How deep a line is: its deepest child, or, when some of a flow row's children line up by their
     * text, the reach above that shared line plus the reach below it, if that is deeper. Each line
     * lines up on its own, as a [Row] of its own would.
     */
    private fun lineDepth(start: Int, end: Int, measurables: List<Measurable>, placeables: Array<Placeable?>): Float {
        var deepest = 0f
        var below = 0f
        for (index in start until end) {
            val placeable = placeables[index] ?: continue
            if (placeable.cross > deepest) deepest = placeable.cross
            val baseline = baselineOf(measurables[index], placeable)
            if (!baseline.isNaN() && placeable.height - baseline > below) below = placeable.height - baseline
        }
        val lined = lineAbove(start, end, measurables, placeables) + below
        return if (lined > deepest) lined else deepest
    }

    /** The furthest any child of the line reaches above the line its text stands on; zero if none. */
    private fun lineAbove(start: Int, end: Int, measurables: List<Measurable>, placeables: Array<Placeable?>): Float {
        var above = 0f
        for (index in start until end) {
            val placeable = placeables[index] ?: continue
            val baseline = baselineOf(measurables[index], placeable)
            if (!baseline.isNaN() && baseline > above) above = baseline
        }
        return above
    }

    /** Where [placeable]'s text stands, if it is in a flow row lining children up by it; NaN if not. */
    private fun baselineOf(measurable: Measurable, placeable: Placeable): Float {
        if (!horizontal) return Float.NaN
        val alignment = measurable.layoutData.alignment ?: crossAlignment
        return if (alignment.vertical == VerticalAlignment.Baseline) placeable.firstBaseline else Float.NaN
    }

    // Written out rather than left to the default, which runs measure over stand-ins that take no
    // room on the other axis. A line of those never fills, so a flow asked how tall it is at a width
    // would answer one line, and asked how narrow it can be would answer everything end to end.
    //
    // Along the lines: at its narrowest a flow is its widest child, one to a line; at its widest,
    // every line as long as the item limit lets it be. Across: the lines the length makes, each
    // child as long as it would like within the length, each line as deep as its deepest child.

    override fun MeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
        if (horizontal) along(measurables, max = false) else across(measurables, height, max = false)

    override fun MeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
        if (horizontal) along(measurables, max = true) else across(measurables, height, max = true)

    override fun MeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
        if (horizontal) across(measurables, width, max = false) else along(measurables, max = false)

    override fun MeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
        if (horizontal) across(measurables, width, max = true) else along(measurables, max = true)

    private fun MeasureScope.along(measurables: List<IntrinsicMeasurable>, max: Boolean): Float {
        val count = measurables.size
        val gap = mainSpacing + arrangement.spacing
        if (!max) {
            var widest = 0f
            for (index in 0 until count) {
                val wanted = measurables[index].main(Float.POSITIVE_INFINITY, max = false)
                if (wanted > widest) widest = wanted
            }
            return widest
        }
        val sizes = sizes(count)
        for (index in 0 until count) sizes[index] = measurables[index].main(Float.POSITIVE_INFINITY, max = true)
        var longest = 0f
        var start = 0
        while (start < count) {
            val end = lineEnd(start, count, sizes, Float.POSITIVE_INFINITY, gap)
            val length = lineLength(start, end, sizes, gap)
            if (length > longest) longest = length
            start = end
        }
        return longest
    }

    private fun MeasureScope.across(measurables: List<IntrinsicMeasurable>, length: Float, max: Boolean): Float {
        val count = measurables.size
        if (count == 0) return 0f
        val gap = mainSpacing + arrangement.spacing
        val sizes = sizes(count)
        for (index in 0 until count) {
            val wanted = measurables[index].main(Float.POSITIVE_INFINITY, max = true)
            sizes[index] = if (length.isFinite()) wanted.coerceAtMost(length) else wanted
        }
        var depth = 0f
        var lines = 0
        var start = 0
        while (start < count) {
            val end = lineEnd(start, count, sizes, length, gap)
            var deepest = 0f
            for (index in start until end) {
                val thickness = measurables[index].cross(sizes[index], max)
                if (thickness > deepest) deepest = thickness
            }
            depth += deepest
            lines++
            start = end
        }
        return depth + crossSpacing * (lines - 1)
    }

    private fun IntrinsicMeasurable.main(cross: Float, max: Boolean) = when {
        horizontal && max -> maxIntrinsicWidth(cross)
        horizontal -> minIntrinsicWidth(cross)
        max -> maxIntrinsicHeight(cross)
        else -> minIntrinsicHeight(cross)
    }

    private fun IntrinsicMeasurable.cross(main: Float, max: Boolean) = when {
        horizontal && max -> maxIntrinsicHeight(main)
        horizontal -> minIntrinsicHeight(main)
        max -> maxIntrinsicWidth(main)
        else -> minIntrinsicWidth(main)
    }

    private val Placeable.main get() = if (horizontal) width else height
    private val Placeable.cross get() = if (horizontal) height else width

    private companion object {
        const val Slack = 0.01f
    }
}

/**
 * Children left to right, wrapping onto a new line below when a line is full.
 *
 * For the things that come in a number nobody knows in advance: tag chips, the status effects
 * under a health bar, a hotbar on a phone held upright.
 *
 * ```kotlin
 * FlowRow(horizontalSpacing = 4f, verticalSpacing = 4f) {
 *     buffs.forEach { BuffIcon(it) }
 * }
 * ```
 *
 * @param horizontalSpacing the gap between neighbours on a line.
 * @param verticalSpacing the gap between one line and the next.
 * @param horizontalArrangement how each line shares out the width left over, line by line. The last
 *   line of a centred flow is centred on its own.
 * @param verticalAlignment where a child sits inside a line taller than it. `Baseline` stands the
 *   text of each line on one line, line by line. A child's own `Modifier.align` wins, as in a [Row].
 * @param maxItemsInEachRow wraps after this many even when there is room: a grid of four across.
 */
@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalSpacing: Float = 0f,
    verticalSpacing: Float = 0f,
    horizontalArrangement: Arrangement = Arrangement.Start,
    verticalAlignment: VerticalAlignment = VerticalAlignment.Top,
    maxItemsInEachRow: Int = Int.MAX_VALUE,
    content: @Composable () -> Unit,
) {
    val policy = remember(horizontalSpacing, verticalSpacing, horizontalArrangement, verticalAlignment, maxItemsInEachRow) {
        FlowPolicy(
            horizontal = true,
            mainSpacing = horizontalSpacing,
            crossSpacing = verticalSpacing,
            arrangement = horizontalArrangement,
            crossAlignment = Alignment(vertical = verticalAlignment),
            maxItemsInEachLine = maxItemsInEachRow,
        )
    }
    Layout(modifier, name = "flowRow", content = content, measurePolicy = policy)
}

/**
 * Children top to bottom, wrapping into a new column to the right when a column is full.
 *
 * A column only fills up if something limits its height, so give it one — a fixed height, or a
 * parent that fills — or it is one long column like any other.
 *
 * @param horizontalSpacing the gap between one column and the next.
 * @param verticalSpacing the gap between neighbours in a column.
 * @param verticalArrangement how each column shares out the height left over.
 * @param horizontalAlignment where a child sits inside a column wider than it.
 * @param maxItemsInEachColumn wraps after this many even when there is room.
 * @see FlowRow
 */
@Composable
fun FlowColumn(
    modifier: Modifier = Modifier,
    horizontalSpacing: Float = 0f,
    verticalSpacing: Float = 0f,
    verticalArrangement: Arrangement = Arrangement.Top,
    horizontalAlignment: HorizontalAlignment = HorizontalAlignment.Start,
    maxItemsInEachColumn: Int = Int.MAX_VALUE,
    content: @Composable () -> Unit,
) {
    val policy = remember(horizontalSpacing, verticalSpacing, verticalArrangement, horizontalAlignment, maxItemsInEachColumn) {
        FlowPolicy(
            horizontal = false,
            mainSpacing = verticalSpacing,
            crossSpacing = horizontalSpacing,
            arrangement = verticalArrangement,
            crossAlignment = Alignment(horizontal = horizontalAlignment),
            maxItemsInEachLine = maxItemsInEachColumn,
        )
    }
    Layout(modifier, name = "flowColumn", content = content, measurePolicy = policy)
}
