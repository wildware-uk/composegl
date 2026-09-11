package dev.wildware.composegl.ui.debug

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
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
 * @param budget where the numbers come from.
 * @param overMillis the frame time above which the total is drawn in red. The default is a sixty
 *   hertz frame, and a game running at thirty or ninety says so.
 */
@Composable
fun FrameBudgetOverlay(
    budget: FrameBudget,
    modifier: Modifier = Modifier,
    overMillis: Float = 16.6f,
) {
    val reading = budget.reading

    Column(
        modifier
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
    }
}

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
