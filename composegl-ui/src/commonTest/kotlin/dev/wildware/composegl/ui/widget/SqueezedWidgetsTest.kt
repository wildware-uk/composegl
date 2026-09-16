package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.Test

/**
 * Every widget, squeezed to nothing, in both directions.
 *
 * A widget measured at no size at all is not a silly edge case here: it is what a docked debug
 * window behind another tab is measured at, and what a pane dragged shut is measured at. A widget
 * that throws there takes the whole screen down with it, so this composes each one at nothing, at
 * one pixel and at a few, left to right and right to left, lays it out and draws it. Nothing is
 * asserted about what comes out — the assertion is that it comes out at all.
 *
 * The family that got this written is anything with a track and a fill. The length of the fill is
 * worked out from the length of the track, and one span subtracted from another goes below zero the
 * moment the track is shorter than the fittings sitting on it.
 */
class SqueezedWidgetsTest {

    /** Nothing at all, one pixel, and a few — small enough that the fittings do not fit. */
    private val sides = listOf(0f, 1f, 4f)

    private val directions = listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)

    /** Lays [content] out at every squeezed size in both directions and draws it. */
    private fun squeezed(content: @Composable (Modifier) -> Unit) {
        for (direction in directions) {
            for (side in sides) {
                // Told exactly what size to be, which is what a window behind another tab is told.
                show(direction) { content(Modifier.size(side)) }
                // Crushed on one axis only: the narrow side pane rather than the hidden tab.
                show(direction) { content(Modifier.size(side, 200f)) }
                show(direction) { content(Modifier.size(200f, side)) }
                // And with no room to be in, rather than an order to be nothing.
                show(direction) { Box(Modifier.size(side)) { content(Modifier) } }
            }
        }
    }

    private fun show(direction: LayoutDirection, content: @Composable () -> Unit) {
        uiTest(Size(200f, 200f)) { ProvideLayoutDirection(direction) { content() } }.use { it.render() }
    }

    // --- a track with a fill on it ---------------------------------------------------------------

    @Test
    fun `a slider survives being measured at nothing`() =
        squeezed { Slider(0.5f, onValueChange = {}, modifier = it) }

    @Test
    fun `a slider at either end survives being measured at nothing`() {
        squeezed { Slider(0f, onValueChange = {}, modifier = it) }
        squeezed { Slider(1f, onValueChange = {}, modifier = it) }
    }

    @Test
    fun `a vertical slider survives being measured at nothing`() =
        squeezed { Slider(0.5f, onValueChange = {}, modifier = it, orientation = Orientation.Vertical) }

    @Test
    fun `a slider with a big knob on a short track survives`() =
        squeezed { Slider(0.5f, onValueChange = {}, modifier = it, knob = 64f, thickness = 40f) }

    @Test
    fun `an indeterminate bar survives being measured at nothing`() {
        squeezed { IndeterminateBar(modifier = it) }
        squeezed { IndeterminateBar(modifier = it, orientation = Orientation.Vertical) }
    }

    @Test
    fun `a spinner survives being measured at nothing`() = squeezed { Spinner(modifier = it) }

    @Test
    fun `a scroll area and its bars survive being measured at nothing`() =
        squeezed { modifier ->
            ScrollArea(modifier = modifier, horizontal = true) {
                Column { repeat(6) { Text("row $it") } }
            }
        }

    @Test
    fun `a lazy list and its bars survive being measured at nothing`() {
        squeezed { modifier -> LazyColumn(8, modifier) { Text("row $it") } }
        squeezed { modifier -> LazyRow(8, modifier) { Text("row $it") } }
    }

    @Test
    fun `a splitter survives being measured at nothing`() {
        squeezed { Splitter(0.5f, onFractionChange = {}, modifier = it, first = { Text("one") }, second = { Text("two") }) }
        squeezed {
            Splitter(
                0.5f,
                onFractionChange = {},
                modifier = it,
                orientation = Orientation.Vertical,
                first = { Text("one") },
                second = { Text("two") },
            )
        }
    }

    // --- everything else that draws, for the same reason -----------------------------------------

    @Test
    fun `a scene view survives being measured at nothing`() =
        squeezed { SceneView(rememberSceneViewState(), modifier = it) { clear(dev.wildware.composegl.ui.graphics.Colour.Black) } }

    @Test
    fun `a focused scene view that takes input survives being measured at nothing`() =
        squeezed {
            SceneView(
                rememberSceneViewState(),
                modifier = it,
                onPointer = { true },
                onKey = { true },
                onPad = { true },
                initialFocus = true,
            ) { clear(dev.wildware.composegl.ui.graphics.Colour.Black) }
        }

    @Test
    fun `a button survives being measured at nothing`() = squeezed { Button("GO", onClick = {}, modifier = it) }

    @Test
    fun `a toggle and its friends survive being measured at nothing`() {
        squeezed { Checkbox(true, onCheckedChange = {}, modifier = it) }
        squeezed { RadioButton(true, onSelect = {}, modifier = it) }
        squeezed { Toggle(true, onCheckedChange = {}, modifier = it) }
    }

    @Test
    fun `a stepper survives being measured at nothing`() =
        squeezed { NumberStepper(5, onValueChange = {}, modifier = it, range = 0..10) }

    @Test
    fun `text survives being measured at nothing`() = squeezed { Text("a line of text", modifier = it) }

    @Test
    fun `a text field survives being measured at nothing`() =
        squeezed { TextField("typed", onValueChange = {}, modifier = it) }

    @Test
    fun `a divider survives being measured at nothing`() {
        squeezed { Divider(modifier = it) }
        squeezed { Divider(modifier = it, vertical = true) }
    }

    @Test
    fun `a panel and a row and a column survive being measured at nothing`() {
        squeezed { Panel(modifier = it) { Text("inside") } }
        squeezed { modifier -> Row(modifier) { Text("one"); Text("two") } }
        squeezed { modifier -> Column(modifier) { Text("one"); Text("two") } }
    }

    @Test
    fun `a collapsing header survives being measured at nothing`() =
        squeezed { modifier -> CollapsingHeader("Header", modifier = modifier) { Text("inside") } }
}
