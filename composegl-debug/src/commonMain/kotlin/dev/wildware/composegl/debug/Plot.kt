package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.input.DirectionHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onFocusDirection
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.skin.LocalSkin
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.widget.Text
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// A number that changes every frame, as a picture.
//
// A frame time printed as `7.31 ms` is unreadable at sixty hertz and says nothing about the spike
// that made the game stutter a second ago. The same numbers drawn as a line show the spike, how big
// it was and how often it comes back. imgui calls these `PlotLines` and `PlotHistogram`; these are
// the same idea, drawn by the toolkit so they look the same on every backend.

// --- the numbers --------------------------------------------------------------------------------

/**
 * The values a plot draws: [size] of them, oldest first, in [capacity] columns.
 *
 * [capacity] rather than [size] fixes the width of a column, so a graph filling up grows from one
 * end at a steady scale instead of squashing itself as it goes, and a full one scrolls. A series
 * that is always full — a bar chart of fixed buckets — answers the same number for both.
 *
 * [revision] is how a plot knows to be drawn again: it goes up whenever the values change. A series
 * that cannot say — an array somebody else is writing into — leaves it at zero, and is redrawn
 * whenever the screen recomposes instead.
 */
interface PlotSeries {

    /** How many values there are. */
    val size: Int

    /** How many columns wide the graph is. Never below [size]. */
    val capacity: Int get() = size

    /** The value at [index], counting from the oldest. */
    operator fun get(index: Int): Float

    /** Goes up whenever the values change. Zero from a series that does not keep count. */
    val revision: Int get() = 0

    companion object {

        /**
         * [count] of [values], oldest first, as a series.
         *
         * The values are read as they are when the plot is drawn, not copied, so an array being
         * written into is drawn as it stands. Nothing counts the changes, so a plot of one is drawn
         * again every time the screen it is on recomposes.
         */
        fun of(values: FloatArray, count: Int = values.size): PlotSeries {
            require(count in 0..values.size) { "a series of $count values does not fit in ${values.size}" }
            return ArraySeries(values, count)
        }
    }
}

private class ArraySeries(private val values: FloatArray, override val size: Int) : PlotSeries {
    override fun get(index: Int): Float = values[index]
}

/**
 * A ring of the last [capacity] numbers: push one a frame for ever and nothing is allocated.
 *
 * ```kotlin
 * val latency = rememberPlotBuffer(capacity = 120)
 * latency.add(ping)
 * Plot(latency, Modifier.size(200f, 50f))
 * ```
 *
 * Adding past the end drops the oldest. [get] counts from the oldest, so `buffer[0]` is the sample
 * about to go and `buffer[size - 1]` is the newest — the same order a plot draws them in.
 *
 * **Add from a frame callback, not while composing.** Each [add] moves [revision], which is Compose
 * state, and a plot reads it to know it has something new to draw; writing it from a composition
 * that also reads it would be a composition that never settles.
 *
 * [min], [max] and [average] are worked out on the first read after a change and kept until the
 * next one, so a readout that asks for all three costs one pass over the ring rather than three.
 */
class PlotBuffer(override val capacity: Int) : PlotSeries {

    init {
        require(capacity > 0) { "a plot buffer holds at least one sample, not $capacity" }
    }

    private val values = FloatArray(capacity)

    /** Where the next sample goes, which is also where the oldest one is once the ring is full. */
    private var at = 0

    private var count = 0

    private var changes by mutableIntStateOf(0)

    override val size: Int get() = count

    override val revision: Int get() = changes

    override fun get(index: Int): Float {
        require(index in 0 until count) { "sample $index of a buffer holding $count" }
        return values[(start + index) % capacity]
    }

    /** Where the oldest sample sits in the ring. */
    private val start: Int get() = if (count < capacity) 0 else at

    /** The newest sample, or zero when there is none. */
    val latest: Float get() = if (count == 0) 0f else values[(at + capacity - 1) % capacity]

    /**
     * The smallest sample held, or zero when there are none.
     *
     * Samples that are not numbers are left out, so one NaN in the window does not take the whole
     * readout with it. A buffer holding nothing but those has nothing to report and answers NaN,
     * which [plotNumber] writes as a dash.
     */
    val min: Float get() = summary().let { minimum }

    /** The largest sample held, the same way round. @see min */
    val max: Float get() = summary().let { maximum }

    /** The mean of the samples held, the same way round, over the ones that are numbers. @see min */
    val average: Float get() = summary().let { mean }

    private var minimum = 0f
    private var maximum = 0f
    private var mean = 0f
    private var summarised = -1

    /** Adds [value], dropping the oldest sample once the ring is full. Allocates nothing. */
    fun add(value: Float) {
        values[at] = value
        at = (at + 1) % capacity
        if (count < capacity) count++
        changes++
    }

    /** Forgets every sample. What a game calls after loading a level. */
    fun clear() {
        at = 0
        count = 0
        changes++
    }

    /**
     * Fills the buffer with [count] of [from], oldest first, as if they had been added one at a time.
     *
     * For a plot of numbers somebody else is already keeping — the frame budget's own window, say —
     * where copying them in is cheaper than a second ring holding the same thing twice.
     */
    fun replaceWith(from: FloatArray, count: Int = from.size) {
        require(count in 0..from.size) { "$count values do not fit in ${from.size}" }
        val taken = minOf(count, capacity)
        from.copyInto(values, destinationOffset = 0, startIndex = count - taken, endIndex = count)
        at = taken % capacity
        this.count = taken
        changes++
    }

    /** min, max and mean in one pass, kept until the next change. */
    private fun summary() {
        if (summarised == changes) return
        summarised = changes
        if (count == 0) {
            minimum = 0f
            maximum = 0f
            mean = 0f
            return
        }
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        var sum = 0f
        var counted = 0
        // Samples that are not numbers are skipped, and so is their place in the count. A game's own
        // telemetry can hand over a NaN — a ratio over a zero denominator, a ping with nothing in it
        // yet — and adding one into the total would make min, mean and max all read as a dash for as
        // long as it was in the window, which is exactly what leaving it out of the range avoids.
        for (index in 0 until count) {
            val value = get(index)
            if (!value.isFinite()) continue
            if (value < low) low = value
            if (value > high) high = value
            sum += value
            counted++
        }
        // Nothing but non-numbers held: there is no smallest or largest, and NaN is written as a dash.
        minimum = if (counted == 0) Float.NaN else low
        maximum = if (counted == 0) Float.NaN else high
        mean = if (counted == 0) Float.NaN else sum / counted
    }
}

/** A [PlotBuffer] that lives as long as the screen it is on. */
@Composable
fun rememberPlotBuffer(capacity: Int = DefaultPlotCapacity): PlotBuffer =
    remember(capacity) { PlotBuffer(capacity) }

/** Four seconds at sixty frames a second: long enough to see a spike come back. */
const val DefaultPlotCapacity = 240

// --- the colours --------------------------------------------------------------------------------

/**
 * What a plot is drawn in, when the skin is not being asked.
 *
 * The skin is the usual answer — see [Plot] for the names it reads — and this is the way past it,
 * for a plot that has to read the same over whatever it is drawn on. The frame budget overlay uses
 * it for exactly that reason.
 */
data class PlotColours(
    /** The trace itself. */
    val line: Colour,
    /** Under the trace. Transparent for no fill at all. */
    val fill: Colour,
    /** The horizontal lines at the values `guides` names. */
    val guide: Colour,
    /** The upright line at the sample being read. */
    val cursor: Colour,
    /** A histogram's bars. */
    val bar: Colour,
)

// --- the widgets --------------------------------------------------------------------------------

/**
 * A line through [series], oldest on the left.
 *
 * ```kotlin
 * Plot(frameTimes, Modifier.size(240f, 60f), range = 0f..33f, guides = listOf(16.6f))
 * ```
 *
 * A sample keeps one column, and a column's width is the graph's width divided by the series'
 * `capacity` — so a buffer filling up draws a line that grows from the left at a steady scale, and a
 * full one scrolls. On a right-to-left screen it runs the other way, newest on the left, like
 * everything else the toolkit lays out.
 *
 * With no [range] it scales itself to what it holds, and to whatever [guides] asks for, so a guide
 * is never off the top. A fixed range is the honest one for a frame budget — a graph that rescales
 * itself makes every frame look equally bad — and values outside it are drawn flat against the edge
 * rather than clipped away.
 *
 * **The trace is one batch.** Every piece of it — the guides, the fill, each segment of the line and
 * the cursor — is a quad of the same kind a rectangle is, drawn one after another with nothing in
 * between, so a graph of 240 samples costs the renderer one draw call rather than 240. It is also
 * why nothing here clips: a clip flushes the batch, so the drawing holds itself inside its box by
 * arithmetic instead.
 *
 * **Reading a value off it.** With [hover] on, the pointer picks out the sample under it and shows
 * its value. With [focusable] on it is somewhere Tab and the pad can go, and then Left and Right —
 * arrows, the pad's stick or its D-pad — walk the cursor a sample at a time, Home and End jump to the
 * ends, and Escape puts it away. A plot in an overlay usually wants both off, so that the graph over
 * the game is scenery the pointer passes straight through.
 *
 * Everything it looks like is the skin's, under [style]: the box is `"<style>"`, and the text colour
 * of `"<style>.line"`, `"<style>.fill"`, `"<style>.guide"` and `"<style>.cursor"` is the trace, the
 * area under it, the guides and the cursor. The readout is drawn in `"<style>.label"`, and the value
 * under the cursor in `"<style>.value"`. [colours] is the way past the skin for a plot that has to
 * read the same whatever it is drawn on.
 *
 * @param range the values the top and bottom of the box mean, or null to scale to what is there. A
 *   range with nothing in it is given a little room rather than rejected.
 * @param guides values to draw a horizontal line at: a frame budget, a target latency.
 * @param label a word in the top left corner, or null for none.
 * @param readout whether the smallest, mean and largest of the samples are written under the graph.
 *   Of the samples, not of [range]: a plot with a fixed range still says what is really in it.
 * @param hover whether the pointer picks out a sample and shows its value.
 * @param focusable whether Tab and the pad can land on it and walk the cursor.
 * @param thickness how thick the line is drawn.
 * @param format how a value is written out. The default is two decimal places.
 */
@Composable
fun Plot(
    series: PlotSeries,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float>? = null,
    guides: List<Float> = emptyList(),
    label: String? = null,
    readout: Boolean = true,
    hover: Boolean = true,
    focusable: Boolean = false,
    thickness: Float = 1.5f,
    style: String = "plot",
    colours: PlotColours? = null,
    format: (Float) -> String = ::plotNumber,
) = Graph(series, modifier, range, guides, label, readout, hover, focusable, thickness, style, colours, format, bars = false)

/** The same, over [count] of [values]. @see PlotSeries.of */
@Composable
fun Plot(
    values: FloatArray,
    modifier: Modifier = Modifier,
    count: Int = values.size,
    range: ClosedFloatingPointRange<Float>? = null,
    guides: List<Float> = emptyList(),
    label: String? = null,
    readout: Boolean = true,
    hover: Boolean = true,
    focusable: Boolean = false,
    thickness: Float = 1.5f,
    style: String = "plot",
    colours: PlotColours? = null,
    format: (Float) -> String = ::plotNumber,
) = Plot(
    PlotSeries.of(values, count), modifier, range, guides, label, readout, hover, focusable,
    thickness, style, colours, format,
)

/**
 * A bar for each value in [series], oldest on the left: how often, rather than when.
 *
 * ```kotlin
 * Histogram(buckets, Modifier.size(240f, 60f))
 * ```
 *
 * Everything [Plot] says applies, with one difference: a bar is drawn from zero, or from the bottom
 * of the range when zero is outside it, so a graph of counts stands on the floor. The bars come from
 * `"<style>.bar"`.
 */
@Composable
fun Histogram(
    series: PlotSeries,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float>? = null,
    guides: List<Float> = emptyList(),
    label: String? = null,
    readout: Boolean = true,
    hover: Boolean = true,
    focusable: Boolean = false,
    style: String = "plot",
    colours: PlotColours? = null,
    format: (Float) -> String = ::plotNumber,
) = Graph(series, modifier, range, guides, label, readout, hover, focusable, 1.5f, style, colours, format, bars = true)

/** The same, over [count] of [values]. @see PlotSeries.of */
@Composable
fun Histogram(
    values: FloatArray,
    modifier: Modifier = Modifier,
    count: Int = values.size,
    range: ClosedFloatingPointRange<Float>? = null,
    guides: List<Float> = emptyList(),
    label: String? = null,
    readout: Boolean = true,
    hover: Boolean = true,
    focusable: Boolean = false,
    style: String = "plot",
    colours: PlotColours? = null,
    format: (Float) -> String = ::plotNumber,
) = Histogram(
    PlotSeries.of(values, count), modifier, range, guides, label, readout, hover, focusable,
    style, colours, format,
)

// --- what both of them are ----------------------------------------------------------------------

@Suppress("LongParameterList", "CyclomaticComplexMethod")
@Composable
private fun Graph(
    series: PlotSeries,
    modifier: Modifier,
    range: ClosedFloatingPointRange<Float>?,
    guides: List<Float>,
    label: String?,
    readout: Boolean,
    hover: Boolean,
    focusable: Boolean,
    thickness: Float,
    style: String,
    colours: PlotColours?,
    format: (Float) -> String,
    bars: Boolean,
) {
    val box = rememberStyle(style)
    val ink = colours ?: rememberPlotColours(style)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reading = remember(rtl) { Reading(rtl) }
    reading.series = series
    // The pointer arrives in the box's own coordinates and the graph starts inside the box's
    // padding, which is where the readout is written.
    reading.inset = box.padding.left + box.contentOffset.x

    // A cursor belongs to whoever is reading the graph. Worked out rather than cleared, so nothing
    // has to write state while the screen is composing: the pointer leaving or focus going
    // elsewhere puts the cursor away on its own.
    val held = (hover && reading.interaction.isHovered) || (focusable && reading.interaction.isFocused)
    val cursor = if (held) reading.cursor else -1

    // Read every time the values change, so a plot handed the same series twice is handed the same
    // drawing and the node reports nothing changed. A series that does not count its changes says
    // zero for ever, and is redrawn whenever whatever is around it recomposes.
    val revision = series.revision
    val low = plotLow(series, range, guides, fromZero = bars)
    val high = plotHigh(series, range, guides, fromZero = bars)
    val draw = remember(series, revision, low, high, guides, ink, rtl, cursor, thickness, bars) {
        graphDraw(series, low, high, guides, ink, rtl, cursor, thickness, bars)
    }

    // On the box rather than on the graph inside it, so the node a caller tagged and sized is the
    // node that takes the pointer and holds focus — the same way a Slider is put together.
    val reactive = Modifier
        .then(if (hover || focusable) Modifier.interaction(reading.interaction) else Modifier)
        .then(if (hover) Modifier.onPointer(reading.pointer) else Modifier)
        .then(if (focusable) Modifier.focusable(reading.interaction) else Modifier)
        .then(if (focusable) Modifier.onFocusDirection(reading.direction).onKeyEvent(reading.keys) else Modifier)

    Box(Modifier.size(DefaultPlotWidth, DefaultPlotHeight).then(modifier).then(reactive).styled(box)) {
        LeafLayout(
            Modifier.fillMaxSize(),
            name = if (bars) "histogram" else "plot",
            measurePolicy = remember(reading) { GraphPolicy(reading) },
            draw = draw,
        )
        if (label != null) Text(label, Modifier.align(Alignment.TopStart), style = "$style.label")
        val picked = if (cursor >= 0 && cursor < series.size) series[cursor] else null
        if (readout || picked != null) {
            Text(
                format(picked ?: latestOf(series)),
                Modifier.align(Alignment.TopEnd),
                style = if (picked != null) "$style.value" else "$style.label",
            )
        }
        if (readout) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // The samples themselves, not `low` and `high`: those are where the box's edges
                // are, which a fixed range or a guide outside the data moves away from the numbers.
                Text(format(smallestOf(series)), style = "$style.label")
                Text(format(meanOf(series)), style = "$style.label")
                Text(format(largestOf(series)), style = "$style.label")
            }
        }
    }
}

/** The colours the skin names for [style], as one object the drawing can be remembered on. */
@Composable
private fun rememberPlotColours(style: String): PlotColours {
    val skin = LocalSkin.current
    val line = rememberStyle("$style.line")
    val fill = rememberStyle("$style.fill")
    val guide = rememberStyle("$style.guide")
    val cursor = rememberStyle("$style.cursor")
    val bar = rememberStyle("$style.bar")
    // A fill the skin does not name is no fill, rather than a solid block of the line's own colour
    // over everything behind the graph.
    val filled = if (skin.has("$style.fill")) fill.textColour else Colour.Transparent
    return remember(line, filled, guide, cursor, bar) {
        PlotColours(
            line = line.textColour,
            fill = filled,
            guide = guide.textColour,
            cursor = cursor.textColour,
            bar = bar.textColour,
        )
    }
}

/**
 * Which sample is being read, and everything that moves it.
 *
 * One object rather than four remembered lambdas, so the handlers are the same objects from one
 * recomposition to the next and the node they are on is not rebuilt every time a sample arrives.
 */
private class Reading(private val rtl: Boolean) {

    val interaction = InteractionState()

    /** Which sample the pointer or the keys are on, or -1 for none. */
    var cursor by mutableIntStateOf(-1)
        private set

    /** What is being drawn, so the handlers below know how many samples there are. */
    var series: PlotSeries = PlotSeries.of(FloatArray(0))

    /** How wide the graph was last laid out, written down by [GraphPolicy]. */
    var width: Float = 0f

    /** How far inside the box the graph starts: the skin's padding, where the readout is written. */
    var inset: Float = 0f

    val pointer = PointerHandler { event ->
        when (event) {
            is PointerEvent.Move, is PointerEvent.Press -> cursor = indexAt(event.position.x)
            is PointerEvent.Exit, is PointerEvent.Cancel -> cursor = -1
            else -> Unit
        }
        // Never true: a graph reads a value, it does not take the gesture. A press on one still
        // reaches whatever is underneath, and a drag across a window still drags the window.
        false
    }

    val direction = DirectionHandler { direction ->
        val count = series.size
        when {
            count == 0 -> false
            direction == FocusDirection.Left -> step(if (rtl) 1 else -1)
            direction == FocusDirection.Right -> step(if (rtl) -1 else 1)
            else -> false
        }
    }

    val keys = KeyHandler { event ->
        if (event.type != KeyEventType.Down || series.size == 0) {
            false
        } else {
            when (event.key) {
                Key.Home -> true.also { cursor = 0 }
                Key.End -> true.also { cursor = series.size - 1 }
                // Only when there is something to put away, so Escape still closes the dialogue the
                // graph happens to be in.
                Key.Escape -> (cursor >= 0).also { if (it) cursor = -1 }
                else -> false
            }
        }
    }

    /**
     * Moves the cursor [by] samples, answering whether it went anywhere.
     *
     * A first press picks out the newest sample rather than jumping to an end, because that is the
     * one the number beside the graph is already showing. Running off either end says no, which is
     * what lets the pad leave a graph it has finished reading.
     */
    private fun step(by: Int): Boolean {
        val count = series.size
        if (cursor < 0) {
            cursor = count - 1
            return true
        }
        val next = cursor + by
        if (next < 0 || next >= count) return false
        cursor = next
        return true
    }

    /** Which sample the point [x] of the box's own coordinates is over. */
    private fun indexAt(x: Float): Int =
        plotIndexAt(Rect(0f, 0f, width, 0f), series.capacity, series.size, x - inset, rtl)
}

// --- the drawing --------------------------------------------------------------------------------

/**
 * The whole graph, in one batch of quads.
 *
 * In order: the guides under everything, then the fill, then the trace or the bars, then the
 * cursor. Nothing is clipped — a clip would flush the batch — so every coordinate is held inside
 * [bounds] as it is worked out, which is also what draws a spike above the range flat against the
 * top rather than losing it.
 *
 * A sample that is not a number leaves a gap rather than a shape. Games measure things that are
 * sometimes not measurable — a ping before the first reply, a ratio over a zero denominator — and
 * one NaN spreads through every coordinate it touches, so those are left out here and in the range.
 *
 * **The trace costs nothing per sample.** The ring the samples live in allocates nothing, and a
 * graph of frame times that littered the heap every time it was drawn would be making the spikes it
 * is there to show. So the fill and the line share one four-corner scratch array, held on the
 * drawing itself — which is remembered — and hand it to [UiCanvas.fan] a quad at a time; a canvas
 * reads a fan's points there and then, so one array does for all of them. It is also why the
 * segments do not go through [UiCanvas.line], which would allocate two offsets and an array of its
 * own for each one. What is left is a handful of rectangles a frame: the guides and the cursor.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
private fun graphDraw(
    series: PlotSeries,
    low: Float,
    high: Float,
    guides: List<Float>,
    ink: PlotColours,
    rtl: Boolean,
    cursor: Int,
    thickness: Float,
    bars: Boolean,
): UiCanvas.(Rect) -> Unit {
    val quad = FloatArray(8)
    return draw@{ bounds ->
        if (bounds.isEmpty) return@draw
        // Never nothing: a top and a bottom that are the same number would make every coordinate
        // below 0/0, which is NaN, and NaN comes through `coerceIn` untouched and reaches the
        // canvas as a triangle with no corners. [plotHigh] keeps them apart; this is the backstop.
        val span = (high - low).takeIf { it > 0f } ?: 1f
        val count = series.size
        val columns = maxOf(series.capacity, 1)
        val width = bounds.width / columns

        fun y(value: Float): Float =
            bounds.bottom - ((value - low) / span).coerceIn(0f, 1f) * bounds.height

        fun x(index: Int): Float =
            if (rtl) bounds.right - (index + 0.5f) * width else bounds.left + (index + 0.5f) * width

        /** The four corners into the scratch, in the order a fan wants: hub first, then round. */
        fun corners(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            x3: Float,
            y3: Float,
        ) {
            quad[0] = x0
            quad[1] = y0
            quad[2] = x1
            quad[3] = y1
            quad[4] = x2
            quad[5] = y2
            quad[6] = x3
            quad[7] = y3
        }

        for (guide in guides) {
            // A guide that is not a number is skipped rather than drawn: every coordinate worked
            // out from it would be NaN, and a NaN rectangle is neither empty nor anywhere.
            if (!guide.isFinite() || guide < low || guide > high) continue
            val at = y(guide)
            rect(Rect(bounds.left, at - 0.5f, bounds.right, at + 0.5f), ink.guide)
        }

        if (bars) {
            // A bar stands on zero where zero is in the range, and on the floor where it is not:
            // a graph of counts should not float.
            val floor = y(0f.coerceIn(low, high))
            val gap = (width * BarGap).coerceAtMost(MaxBarGap)
            for (index in 0 until count) {
                val value = series[index]
                if (!value.isFinite()) continue
                val at = x(index)
                val top = y(value)
                val left = at - width / 2f + gap / 2f
                val right = at + width / 2f - gap / 2f
                if (right - left <= 0f || top == floor) continue
                rect(Rect(left, minOf(top, floor), right, maxOf(top, floor)), ink.bar)
            }
        } else {
            if (ink.fill.alpha > 0) {
                for (index in 1 until count) {
                    // A segment needs a number at both ends. One sample the game could not measure
                    // leaves a gap in the trace, which is the truth, rather than turning the whole
                    // graph into NaN geometry the batch cannot draw.
                    val from = series[index - 1]
                    val to = series[index]
                    if (!from.isFinite() || !to.isFinite()) continue
                    val leftX = x(index - 1)
                    val rightX = x(index)
                    // Hub first, as a fan wants, and the four corners in order: the trapezoid under
                    // one segment is convex, which is a fan's whole contract.
                    corners(
                        leftX, y(from),
                        rightX, y(to),
                        rightX, bounds.bottom,
                        leftX, bounds.bottom,
                    )
                    fan(quad, ink.fill)
                }
            }
            if (count == 1 && series[0].isFinite()) {
                // One sample is a dot: a line needs two, and a graph that shows nothing until the
                // second frame looks broken.
                val atX = x(0)
                val atY = y(series[0])
                rect(Rect(atX - thickness, atY - thickness, atX + thickness, atY + thickness), ink.line)
            }
            if (thickness > 0f) {
                for (index in 1 until count) {
                    val from = series[index - 1]
                    val to = series[index]
                    if (!from.isFinite() || !to.isFinite()) continue
                    val fromX = x(index - 1)
                    val fromY = y(from)
                    val toX = x(index)
                    val toY = y(to)
                    val dx = toX - fromX
                    val dy = toY - fromY
                    val length = sqrt(dx * dx + dy * dy)
                    if (length <= 0f) continue
                    // The perpendicular, half a width long: the offset from the centreline to each
                    // edge. The same arithmetic UiCanvas.line does, done here so the quad can go
                    // into the scratch instead of into a new array a segment.
                    val halfX = -dy / length * (thickness / 2f)
                    val halfY = dx / length * (thickness / 2f)
                    corners(
                        fromX + halfX, fromY + halfY,
                        toX + halfX, toY + halfY,
                        toX - halfX, toY - halfY,
                        fromX - halfX, fromY - halfY,
                    )
                    fan(quad, ink.line)
                }
            }
        }

        if (cursor in 0 until count) {
            val at = x(cursor)
            rect(Rect(at - 0.5f, bounds.top, at + 0.5f, bounds.bottom), ink.cursor)
            // The upright line marks the column whatever is in it; the dot only marks a crossing
            // there really is one of.
            val value = series[cursor]
            if (value.isFinite()) {
                val on = y(value)
                rect(Rect(at - CursorDot, on - CursorDot, at + CursorDot, on + CursorDot), ink.cursor)
            }
        }
    }
}

/** How much of a column is left empty between two bars, and the most that gap ever is. */
private const val BarGap = 0.25f
private const val MaxBarGap = 3f

/** Half the side of the square drawn where the cursor crosses the trace. */
private const val CursorDot = 2f

// --- the numbers behind the picture ---------------------------------------------------------------

/**
 * The bottom of the graph: what [range] says, or the smallest of the samples and the guides.
 *
 * The guides count because a guide drawn outside the graph is not a guide, and the usual reason to
 * ask for one — "sixteen milliseconds is the budget" — is exactly the case where the samples are all
 * underneath it and an auto range would leave it off the top.
 *
 * A flat series is given room under it as well as over it, so that a steady sixty is drawn across
 * the middle of the box. A histogram is the exception: its bars are measured from zero and zero is
 * the floor they stand on, so the floor is left where it is.
 */
internal fun plotLow(
    series: PlotSeries,
    range: ClosedFloatingPointRange<Float>?,
    guides: List<Float>,
    fromZero: Boolean = false,
): Float {
    if (range != null) return range.start
    val low = smallestFor(series, guides, fromZero)
    val high = largestFor(series, guides, fromZero)
    if (!low.isFinite() || !high.isFinite()) return 0f
    return if (isFlat(low, high) && !fromZero) low - roomFor(low, high) else low
}

/**
 * The top of the graph, the same way round. A flat series is given room either side of its value.
 *
 * A [range] with nothing in it gets that room too, rather than being an error: an overlay works its
 * range out from what it is measuring, and a graph is no place to stop a game.
 *
 * [fromZero] is what a histogram asks for: bars are counts, they are measured from nothing, and a
 * chart whose floor is the smallest count draws the smallest bar as no bar at all.
 */
internal fun plotHigh(
    series: PlotSeries,
    range: ClosedFloatingPointRange<Float>?,
    guides: List<Float>,
    fromZero: Boolean = false,
): Float {
    if (range != null) {
        // A range with nothing in it — `0f..0f`, which an overlay can work out rather than type —
        // is given room rather than rejected: throwing here throws while the screen is composing,
        // and a flat range is the same problem a flat series has, with the same answer.
        val top = range.endInclusive
        return if (top > range.start) top else top + roomFor(range.start, top)
    }
    val low = smallestFor(series, guides, fromZero)
    val high = largestFor(series, guides, fromZero)
    if (!low.isFinite() || !high.isFinite()) return 1f
    // A series that has never changed would otherwise divide by nothing, and drawing it along an
    // edge reads as nothing at all when it is a steady sixty. [plotLow] drops by the same amount,
    // so the value lands halfway up the box rather than at the bottom of it.
    return if (isFlat(low, high)) high + roomFor(low, high) else high
}

/** The smallest of the samples and the guides, ignoring anything that is not a number. */
private fun smallestFor(series: PlotSeries, guides: List<Float>, fromZero: Boolean): Float {
    var low = if (fromZero) 0f else Float.POSITIVE_INFINITY
    // Non-finite samples are skipped rather than folded in: one NaN out of a game's own telemetry —
    // a ping with no samples yet, a ratio over a zero denominator — would otherwise spread through
    // every coordinate on the graph, because minOf answers NaN for anything it is compared with.
    for (index in 0 until series.size) {
        val value = series[index]
        if (value.isFinite() && value < low) low = value
    }
    for (guide in guides) if (guide.isFinite() && guide < low) low = guide
    return low
}

/** The largest of them, the same way round. @see smallestFor */
private fun largestFor(series: PlotSeries, guides: List<Float>, fromZero: Boolean): Float {
    var high = if (fromZero) 0f else Float.NEGATIVE_INFINITY
    for (index in 0 until series.size) {
        val value = series[index]
        if (value.isFinite() && value > high) high = value
    }
    for (guide in guides) if (guide.isFinite() && guide > high) high = guide
    return high
}

/** Whether there is so little between the two that the graph has nothing to scale against. */
private fun isFlat(low: Float, high: Float): Boolean = high - low <= roomFor(low, high)

/**
 * How much room a flat series is given either side of itself.
 *
 * It has to be a fraction of the value and not a fixed amount, because a float only holds about
 * seven digits: `4096f + 0.0001f` *is* `4096f`, so a fixed amount would leave the top and the bottom
 * of the graph the same number, and every coordinate worked out from them would be a division by
 * nothing. Memory in bytes and a frame counter are both steady numbers that big.
 */
private fun roomFor(low: Float, high: Float): Float =
    maxOf(FlatEnough, maxOf(abs(low), abs(high)) * FlatFraction)

/** Closer than this and every sample counts as the same number. */
private const val FlatEnough = 0.0001f

/** And closer than this much of the value itself, for numbers too big for [FlatEnough] to register. */
private const val FlatFraction = 0.001f

/**
 * Which sample the point [x] is over, in a graph [capacity] columns wide holding [count] of them.
 *
 * Clamped to the samples there are, so pointing at the empty part of a half-full graph reads the
 * newest sample rather than nothing.
 */
internal fun plotIndexAt(bounds: Rect, capacity: Int, count: Int, x: Float, rtl: Boolean): Int {
    if (count == 0 || bounds.width <= 0f) return -1
    val width = bounds.width / maxOf(capacity, 1)
    val along = if (rtl) bounds.right - x else x - bounds.left
    return (along / width).toInt().coerceIn(0, count - 1)
}

private fun latestOf(series: PlotSeries): Float =
    if (series.size == 0) 0f else series[series.size - 1]

/**
 * The mean of the samples, over the ones that are numbers.
 *
 * Like [smallestFor], anything that is not a number is left out rather than added in: one NaN in the
 * window would otherwise be the whole readout, and the range and the trace already skip it.
 */
private fun meanOf(series: PlotSeries): Float {
    if (series is PlotBuffer) return series.average
    if (series.size == 0) return 0f
    var sum = 0f
    var counted = 0
    for (index in 0 until series.size) {
        val value = series[index]
        if (!value.isFinite()) continue
        sum += value
        counted++
    }
    return if (counted == 0) Float.NaN else sum / counted
}

/**
 * The smallest sample there is — which is not the bottom of the box.
 *
 * A fixed range, a guide above everything held, and a histogram's floor of zero all move the edges
 * of the box away from the numbers in it, and the readout is about the numbers. A [PlotBuffer]
 * already knows, in one pass kept until the next sample; anything else is walked here.
 *
 * Anything that is not a number is skipped, as it is in [smallestFor]: `minOf` answers NaN for
 * whatever it is compared with, so one bad sample would empty the whole readout.
 */
private fun smallestOf(series: PlotSeries): Float {
    if (series is PlotBuffer) return series.min
    if (series.size == 0) return 0f
    var low = Float.POSITIVE_INFINITY
    for (index in 0 until series.size) {
        val value = series[index]
        if (value.isFinite() && value < low) low = value
    }
    return if (low == Float.POSITIVE_INFINITY) Float.NaN else low
}

/** The largest sample there is, the same way round. @see smallestOf */
private fun largestOf(series: PlotSeries): Float {
    if (series is PlotBuffer) return series.max
    if (series.size == 0) return 0f
    var high = Float.NEGATIVE_INFINITY
    for (index in 0 until series.size) {
        val value = series[index]
        if (value.isFinite() && value > high) high = value
    }
    return if (high == Float.NEGATIVE_INFINITY) Float.NaN else high
}

/**
 * A number with two decimal places, without a formatter: there is no common one, and this is all a
 * readout needs. Anything enormous is written whole, because `18446744073709.55` helps nobody.
 */
fun plotNumber(value: Float): String {
    if (!value.isFinite()) return "-"
    if (abs(value) >= WholeAbove) return value.roundToInt().toString()
    val sign = if (value < 0f) "-" else ""
    val hundredths = (abs(value) * 100f + 0.5f).toInt()
    return "$sign${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

/** Above this a number is written whole: the decimals are noise beside it. */
private const val WholeAbove = 10_000f

// --- how big it is ------------------------------------------------------------------------------

/** The size a plot is when nothing says otherwise: wide enough for a few hundred samples. */
const val DefaultPlotWidth = 240f

/** And tall enough to see a spike in. */
const val DefaultPlotHeight = 60f

/**
 * Whatever room it is given: a graph inside a box that has already been sized.
 *
 * It also writes down how wide the graph came out, which is what turns a pointer's x into a sample.
 * Here rather than in the drawing, because a screen is laid out before it is ever drawn and a
 * pointer must not have to wait for a frame to be read.
 */
private class GraphPolicy(private val reading: Reading) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val width = constraints.constrainWidth(DefaultPlotWidth)
        reading.width = width
        return layout(width, constraints.constrainHeight(DefaultPlotHeight)) {}
    }
}
