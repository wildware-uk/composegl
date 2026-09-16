package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.debug.FrameReading
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.widget.Text

/**
 * The frame budget, on the screen.
 *
 * Deliberately ugly and deliberately unskinnable: its own dark box and its own colours, so that it
 * reads the same over a menu, over a bright sky and over a game whose skin has gone wrong. The only
 * thing it takes from the skin is the font, because there is no other way to draw a letter.
 *
 * Compose it behind the game's own switch, which is usually a key:
 *
 * ```kotlin
 * if (budget.isOn) FrameBudgetOverlay(budget, Modifier.align(Alignment.TopEnd).padding(12f))
 * ```
 *
 * It costs what a dozen words cost, and only while it is up. The numbers behind it are refreshed
 * four times a second rather than every frame, so the overlay is not itself the reason the screen
 * is being redrawn — which would make the thing it measures a lie.
 *
 * Under the draw calls, on a canvas that traces them, are the nodes that cut the batch most, and
 * why: `icon#sword  texture 2`. A HUD that costs twelve calls where it should cost two says which
 * two nodes to look at.
 *
 * A budget made with `busiest = 5` also lists the five nodes the most frames changed, with how many,
 * under the numbers: the place to look when "redraws" says the screen never stops.
 *
 * Under all of it is a graph of the last frames' times, with a line across it at [overMillis]. An
 * average says what a frame usually costs; the graph is where the stutter that nobody can average
 * away is, and how often it comes back. It is drawn from [FrameBudget.recentFrameMillis], so it
 * shows the budget's whole window — a hundred and twenty frames by default — however rarely the
 * numbers are published, and it costs the screen no extra redraws.
 *
 * @param budget where the numbers come from.
 * @param overMillis the frame time above which the total is drawn in red. The default is a sixty
 *   hertz frame, and a game running at thirty or ninety says so. Nought and below are allowed —
 *   everything is over budget then, which is a way of watching every frame — and the graph keeps a
 *   sensible height of its own regardless.
 * @param culprits how many of those nodes to list. Zero lists none.
 * @param graph whether the frame time is drawn as a line under the numbers.
 */
@Composable
fun FrameBudgetOverlay(
    budget: FrameBudget,
    modifier: Modifier = Modifier,
    overMillis: Float = 16.6f,
    culprits: Int = 3,
    graph: Boolean = true,
) {
    val reading = budget.reading

    // Read here rather than inside the numbers, so the array is made once for the life of the
    // overlay and refilled where every other number is: the same frame, from the same budget.
    val frames = remember(budget) { FloatArray(budget.window) }
    val count = if (graph) budget.recentFrameMillis(frames) else 0

    // Its own named node round the numbers, so the busiest list and a redraw overlay can leave out a
    // box that changes four times a second by design and would otherwise top every list.
    Layout(
        modifier,
        name = FrameBudget.OverlayName,
        measurePolicy = MeasurePolicy.Stack,
        content = { Numbers(reading, overMillis, culprits, frames, count) },
    )
}

@Composable
private fun Numbers(
    reading: FrameReading,
    overMillis: Float,
    culprits: Int,
    frames: FloatArray,
    count: Int,
) {
    Column(
        Modifier
            .width(230f)
            .background(Ground, corner = 4f)
            .border(Edge, 1f, corner = 4f)
            .padding(horizontal = 10f, vertical = 8f),
        verticalArrangement = Arrangement.spacedBy(2f),
    ) {
        Line("frame", millis(reading.totalMillis), if (reading.totalMillis > overMillis) Over else Bright)
        Line("  recompose", millis(reading.recomposeMillis), Dim)
        Line("  layout", millis(reading.layoutMillis), Dim)
        Line("  draw", millis(reading.drawMillis), Dim)
        Line("worst", millis(reading.worstMillis), if (reading.worstMillis > overMillis) Over else Bright)
        // No spaces round the slash: with them, an overlay on a screen that reads from the right
        // draws "60/12" for twelve redraws in sixty frames. A single slash between two digits is
        // joined onto the number by the bidirectional algorithm, so there is nothing to reorder.
        Line("redraws", "${reading.redraws}/${reading.frames}", Bright)
        if (reading.drawCalls >= 0) Line("draw calls", "${reading.drawCalls}", Bright)
        val blamed = reading.culprits
        for (index in 0 until minOf(culprits, blamed.size)) {
            val culprit = blamed[index]
            Line("  " + shortened(culprit.name), "${culprit.reason.name.lowercase()} ${culprit.calls}", Over)
        }
        // Only when the budget was asked for them, and only while something has changed.
        if (reading.busiest.isNotEmpty()) {
            Line("busiest", "changes", Dim)
            reading.busiest.forEach { Line("  " + it.label.take(LabelLength), "${it.changes}", Bright) }
        }
        // Its own colours rather than the skin's, like everything else here, and nothing to read a
        // value off: the numbers above the graph are the readout, and an overlay lying over a game
        // must not take the pointer the game is using.
        if (count > 0) {
            Plot(
                frames,
                Modifier.fillMaxWidth().height(GraphHeight),
                count = count,
                range = 0f..maxOf(MinGraphMillis, overMillis * 2f, ceilingOf(frames, count)),
                guides = listOf(overMillis),
                readout = false,
                hover = false,
                // A style name the default skin does not have, so the graph keeps the overlay's own
                // blank background rather than the box a skinned plot is drawn in.
                style = "budget.graph",
                colours = GraphInk,
            )
        }
    }
}

/**
 * The shortest the graph's top ever is.
 *
 * The top is twice the budget, or the worst frame when that is worse. Both of those can be nought —
 * an overlay asked for `overMillis = 0f`, a window of frames so fast they all round to nothing — and
 * a graph whose top and bottom are the same number is no graph at all, so it never goes below a
 * millisecond.
 */
private const val MinGraphMillis = 1f

/** The worst frame in [count] of [frames], so a spike is never drawn flat against the top. */
private fun ceilingOf(frames: FloatArray, count: Int): Float {
    var worst = 0f
    for (index in 0 until count) if (frames[index] > worst) worst = frames[index]
    return worst
}

/** A node's name cut to what fits beside its reason, keeping the end, where the tag is. */
private fun shortened(name: String): String =
    // Two plain dots rather than an ellipsis, which a game's own font may not have.
    if (name.length <= NameRoom) name else ".." + name.takeLast(NameRoom - 2)

private const val NameRoom = 18

/** What fits beside the count in the overlay's width. */
private const val LabelLength = 20

@Composable
private fun Line(name: String, value: String, ink: Colour) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = "budget", colour = Dim)
        Text(value, style = "budget", colour = ink)
    }
}

/** Two decimal places, without a formatter: there is no common one, and this is all it needs. */
private fun millis(value: Float): String {
    val hundredths = (value * 100f + 0.5f).toInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} ms"
}

private val Ground = Colour.argb(0xD00A0C10)
private val Edge = Colour.argb(0x40FFFFFF)
private val Bright = Colour.rgb(0xF0F4FF)
private val Dim = Colour.rgb(0x8A94A6)
private val Over = Colour.rgb(0xFF6B5A)

/** How tall the frame time graph is drawn. Two dozen pixels is enough to see a spike in. */
private const val GraphHeight = 34f

/** The graph's own colours, for the same reason the rest of the overlay has its own. */
private val GraphInk = PlotColours(
    line = Bright,
    fill = Colour.argb(0x33F0F4FF),
    guide = Over,
    cursor = Bright,
    bar = Bright,
)
