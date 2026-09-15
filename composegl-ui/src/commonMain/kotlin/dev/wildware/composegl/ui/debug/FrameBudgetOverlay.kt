package dev.wildware.composegl.ui.debug

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.fillMaxWidth
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
 * @param budget where the numbers come from.
 * @param overMillis the frame time above which the total is drawn in red. The default is a sixty
 *   hertz frame, and a game running at thirty or ninety says so.
 * @param culprits how many of those nodes to list. Zero lists none.
 */
@Composable
fun FrameBudgetOverlay(
    budget: FrameBudget,
    modifier: Modifier = Modifier,
    overMillis: Float = 16.6f,
    culprits: Int = 3,
) {
    val reading = budget.reading

    // Its own named node round the numbers, so the busiest list and a redraw overlay can leave out a
    // box that changes four times a second by design and would otherwise top every list.
    Layout(modifier, name = FrameBudgetName, measurePolicy = MeasurePolicy.Stack, content = { Numbers(reading, overMillis, culprits) })
}

@Composable
private fun Numbers(reading: FrameReading, overMillis: Float, culprits: Int) {
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
        Line("redraws", "${reading.redraws} / ${reading.frames}", Bright)
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
    }
}

/** A node's name cut to what fits beside its reason, keeping the end, where the tag is. */
private fun shortened(name: String): String =
    // Two plain dots rather than an ellipsis, which a game's own font may not have.
    if (name.length <= NameRoom) name else ".." + name.takeLast(NameRoom - 2)

private const val NameRoom = 18

/** What fits beside the count in the overlay's width. */
private const val LabelLength = 20

/** What the overlay's outer node is called, and how the change counting knows to leave it out. */
internal const val FrameBudgetName = "frame budget"

/** Whether [node] is a debug overlay of this package, whose own changes are not the screen's. */
internal fun isDebugOverlay(node: UiNode): Boolean =
    node.name == FrameBudgetName || node.content is DebugOverlayPainter

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
