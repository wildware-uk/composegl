package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Orientation
import kotlin.test.Test

/**
 * A bar squeezed to nothing, in both directions.
 *
 * The same guard as the toolkit's `SqueezedWidgetsTest`, for the same reason: a bar is a track with
 * a fill on it, and a fill worked out by subtracting one measured length from another goes below
 * zero when the track is shorter than the fittings. A bar inside a pane dragged shut, or on a HUD
 * laid out before the screen size is known, is measured at nothing and must still come out.
 */
class SqueezedBarTest {

    private val sides = listOf(0f, 1f, 4f)

    private val directions = listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)

    private fun squeezed(content: @Composable (Modifier) -> Unit) {
        for (direction in directions) {
            for (side in sides) {
                show(direction) { content(Modifier.size(side)) }
                show(direction) { content(Modifier.size(side, 200f)) }
                show(direction) { content(Modifier.size(200f, side)) }
                show(direction) { Box(Modifier.size(side)) { content(Modifier) } }
            }
        }
    }

    private fun show(direction: LayoutDirection, content: @Composable () -> Unit) {
        uiTest(Size(200f, 200f)) { ProvideLayoutDirection(direction) { content() } }.use { it.render() }
    }

    @Test
    fun `a bar survives being measured at nothing`() {
        squeezed { Bar(0.5f, modifier = it) }
        squeezed { Bar(0f, modifier = it) }
        squeezed { Bar(1f, modifier = it) }
    }

    @Test
    fun `a vertical bar survives being measured at nothing`() =
        squeezed { Bar(0.5f, modifier = it, orientation = Orientation.Vertical) }

    @Test
    fun `a segmented bar survives being measured at nothing`() =
        squeezed { Bar(0.5f, modifier = it, segments = 6) }

    @Test
    fun `a bar with a fat track survives being measured at nothing`() =
        squeezed { Bar(0.5f, modifier = it, thickness = 64f) }

    /** No pulse: a breathing bar animates for ever by design, so a test can never let it settle. */
    @Test
    fun `a bar past a threshold survives being measured at nothing`() =
        squeezed { Bar(0.1f, modifier = it, thresholds = listOf(BarThreshold(0.25f, "bar.fill"))) }
}
