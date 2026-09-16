package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Plot` and `Histogram` on a real screen, read off a recording canvas and driven with a real
 * pointer, real keys and a real pad.
 *
 * The numbers behind them — the ring, the range, which column a point is in — are in
 * `PlotBufferTest`. This is about what ends up on the screen and what a player can do to it.
 */
class PlotTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    private fun drawn(ui: UiTest): List<DrawCall> {
        val canvas = ui.backend.canvas as RecordingCanvas
        canvas.clear()
        ui.render()
        return canvas.calls.toList()
    }

    /** The graph itself: the leaf inside the plot's box, which is what the trace is drawn on. */
    private fun graphOf(ui: UiTest, tag: String): UiNode =
        ui.node(tag).children.first { it.name == "plot" || it.name == "histogram" }

    private fun buffer(vararg values: Float, capacity: Int = values.size) =
        PlotBuffer(capacity).also { values.forEach { value -> it.add(value) } }

    private val line = Skin.Default.resolve("plot.line").textColour
    private val bar = Skin.Default.resolve("plot.bar").textColour
    private val guide = Skin.Default.resolve("plot.guide").textColour
    private val cursor = Skin.Default.resolve("plot.cursor").textColour

    // --- what it draws ----------------------------------------------------------------------------

    @Test
    fun `a plot draws a segment between each pair of samples`() {
        val samples = buffer(1f, 5f, 2f, 9f)
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph"), readout = false) }

        val fans = drawn(ui).filterIsInstance<DrawCall.Fan>().filter { it.colour == line }

        assertEquals(3, fans.size, "three segments join four samples")
    }

    @Test
    fun `a single sample is drawn as a dot rather than nothing`() {
        val samples = buffer(4f, capacity = 8)
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph"), readout = false) }

        val dots = drawn(ui).filterIsInstance<DrawCall.Rectangle>().filter { it.colour == line }

        assertEquals(1, dots.size, "one sample is a dot: a line needs two")
    }

    @Test
    fun `the trace is one run of quads with nothing between them`() {
        val samples = PlotBuffer(capacity = 240)
        repeat(240) { samples.add(it.toFloat() % 17f) }
        val ui = open { Plot(samples, Modifier.size(240f, 60f).testTag("graph"), guides = listOf(8f), readout = false) }

        val canvas = RecordingCanvas()
        val graph = graphOf(ui, "graph")
        DrawPass(canvas).draw(graph, 0f, 0f)

        val kinds = canvas.calls.map { it::class.simpleName }.toSet()
        assertEquals(
            setOf("Rectangle", "Fan"),
            kinds,
            "a graph is quads and nothing else: anything textured or clipped would break the batch",
        )
        assertTrue(canvas.calls.size > 400, "240 samples is a segment and a fill each: ${canvas.calls.size}")
    }

    @Test
    fun `the trace hands over one array however many segments it has`() {
        // A plot of frame times is drawn every time a frame is measured, so a segment that left an
        // array behind would be making the rubbish it is there to help find.
        val samples = PlotBuffer(capacity = 16)
        repeat(16) { samples.add(it.toFloat() % 5f) }
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph"), readout = false) }

        val counting = CountingFans()
        DrawPass(counting).draw(graphOf(ui, "graph"), 0f, 0f)

        assertTrue(counting.fans > 20, "a fill and a segment for each pair: ${counting.fans}")
        assertEquals(1, counting.arrays.size, "and one scratch array serving all of them")
    }

    /** A canvas that watches which arrays a fan is handed, rather than what is in them. */
    private class CountingFans(private val inner: RecordingCanvas = RecordingCanvas()) : UiCanvas by inner {
        val arrays = mutableSetOf<FloatArray>()
        var fans = 0
        override fun fan(points: FloatArray, colour: Colour) {
            arrays += points
            fans++
            inner.fan(points, colour)
        }
    }

    @Test
    fun `a flat series is drawn across the middle rather than along an edge`() {
        // The whole point of giving a flat series room: a steady sixty drawn on the bottom edge
        // reads as nothing at all, and half the line is outside the box.
        val samples = PlotBuffer(capacity = 10)
        repeat(10) { samples.add(60f) }
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph"), readout = false) }

        val graph = graphOf(ui, "graph").boundsInRoot
        val trace = drawn(ui).filterIsInstance<DrawCall.Fan>().filter { it.colour == line }

        assertTrue(trace.isNotEmpty(), "a line through ten samples")
        val middle = graph.top + graph.height / 2f
        trace.flatMap { it.points }.forEach {
            assertTrue(
                kotlin.math.abs(it.y - middle) < graph.height / 4f,
                "across the middle of $graph, not its edge: $it",
            )
        }
    }

    @Test
    fun `a sample that is not a number leaves a gap rather than a graph of NaN`() {
        // One unmeasurable number out of a game's own telemetry used to spread through every
        // coordinate on the graph, and a NaN rectangle is neither empty nor anywhere.
        val samples = buffer(4f, Float.NaN, 8f, 6f)
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph"), readout = false) }

        val calls = drawn(ui)
        val trace = calls.filterIsInstance<DrawCall.Fan>().filter { it.colour == line }

        assertEquals(1, trace.size, "only the pair either side of the gap joins up")
        calls.filterIsInstance<DrawCall.Fan>().flatMap { it.points }.forEach {
            assertTrue(it.x.isFinite() && it.y.isFinite(), "nothing NaN reaches the batch: $it")
        }
        calls.filterIsInstance<DrawCall.Rectangle>().forEach {
            assertTrue(it.rect.left.isFinite() && it.rect.top.isFinite(), "nor in a rectangle: ${it.rect}")
        }
    }

    @Test
    fun `one sample that is not a number does not empty the readout`() {
        // The array overload, which is what the frame budget overlay and the showcase both hand
        // over. Min, mean and max all used to come out as dashes for as long as the NaN was in the
        // window, which is the opposite of leaving it out of the range and out of the trace.
        val ui = open {
            Plot(floatArrayOf(4f, Float.NaN, 8f, 6f), Modifier.size(200f, 60f).testTag("graph"))
        }

        val shown = ui.texts("graph")

        assertTrue("4.00" in shown && "6.00" in shown && "8.00" in shown, "min mean max: $shown")
        assertTrue("-" !in shown, "and no dash while there are still numbers in it: $shown")
    }

    @Test
    fun `a buffer with a gap in it reads as the numbers either side of the gap`() {
        val samples = buffer(4f, Float.NaN, 8f, 6f)
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph")) }

        val shown = ui.texts("graph")

        assertTrue("4.00" in shown && "6.00" in shown && "8.00" in shown, "min mean max: $shown")
    }

    @Test
    fun `a flat series of a large number is still drawn as a line`() {
        // The room a flat series is given has to be a fraction of the value: a fixed ten-thousandth
        // added to four thousand changes nothing in a float, and the whole trace came out NaN.
        val samples = PlotBuffer(capacity = 5)
        repeat(5) { samples.add(4096f) }
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph"), readout = false) }

        val graph = graphOf(ui, "graph").boundsInRoot
        val trace = drawn(ui).filterIsInstance<DrawCall.Fan>().filter { it.colour == line }

        assertTrue(trace.isNotEmpty(), "a line through five samples")
        val middle = graph.top + graph.height / 2f
        trace.flatMap { it.points }.forEach {
            assertTrue(it.x.isFinite() && it.y.isFinite(), "nothing NaN reaches the batch: $it")
            assertTrue(
                kotlin.math.abs(it.y - middle) < graph.height / 4f,
                "across the middle of $graph, not its edge: $it",
            )
        }
    }

    @Test
    fun `a histogram skips a bucket that is not a number`() {
        val ui = open {
            Histogram(floatArrayOf(3f, Float.NaN, 4f), Modifier.size(200f, 60f).testTag("graph"), readout = false)
        }

        val bars = drawn(ui).filterIsInstance<DrawCall.Rectangle>().filter { it.colour == bar }

        assertEquals(2, bars.size, "the two buckets there are numbers for")
        bars.forEach { assertTrue(it.rect.top.isFinite(), "and none of them NaN: ${it.rect}") }
    }

    @Test
    fun `a guide is drawn across the graph at the value it names`() {
        val samples = buffer(0f, 30f)
        val ui = open {
            Plot(samples, Modifier.size(200f, 60f).testTag("graph"), range = 0f..30f, guides = listOf(15f), readout = false)
        }

        val graph = graphOf(ui, "graph").boundsInRoot
        val rules = drawn(ui).filterIsInstance<DrawCall.Rectangle>().filter { it.colour == guide }

        assertEquals(1, rules.size, "one guide asked for, one drawn")
        val middle = graph.top + graph.height / 2f
        assertTrue(
            kotlin.math.abs(rules[0].rect.top - middle) < 1f,
            "halfway up a range of nought to thirty: ${rules[0].rect} in $graph",
        )
        assertEquals(graph.left, rules[0].rect.left, "and all the way across")
    }

    @Test
    fun `a histogram draws a bar for every value`() {
        val ui = open {
            Histogram(floatArrayOf(3f, 1f, 4f, 1f), Modifier.size(200f, 60f).testTag("graph"), readout = false)
        }

        val bars = drawn(ui).filterIsInstance<DrawCall.Rectangle>().filter { it.colour == bar }

        assertEquals(4, bars.size, "one bar a bucket")
        assertTrue(bars[0].rect.height > bars[1].rect.height, "three stands taller than one")
    }

    @Test
    fun `a right to left screen draws the newest sample on the left`() {
        val samples = buffer(0f, 10f)
        val ui = open {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Histogram(samples, Modifier.size(200f, 60f).testTag("graph"), range = 0f..10f, readout = false)
            }
        }

        val graph = graphOf(ui, "graph").boundsInRoot
        val bars = drawn(ui).filterIsInstance<DrawCall.Rectangle>().filter { it.colour == bar }

        // The only bar with any height is the newest sample, and on a right-to-left screen it is on
        // the left, the way everything else in the toolkit is mirrored.
        assertEquals(1, bars.size, "the zero has no bar")
        assertTrue(bars[0].rect.left < graph.centre.x, "newest on the left: ${bars[0].rect} in $graph")
    }

    // --- reading a value off it -------------------------------------------------------------------

    @Test
    fun `the readout says the smallest and the mean and the largest`() {
        val samples = buffer(2f, 4f, 6f)
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph")) }

        val shown = ui.texts("graph")

        assertTrue("2.00" in shown && "4.00" in shown && "6.00" in shown, "min mean max: $shown")
    }

    @Test
    fun `the readout says what is in the graph rather than where its edges are`() {
        // The example the docs give: a frame budget drawn against a fixed range with a guide on it.
        // The box runs from nought to thirty-three and the samples do not, and it is the samples the
        // readout is about.
        val samples = buffer(5f, 7f, 6f)
        val ui = open {
            Plot(samples, Modifier.size(200f, 60f).testTag("graph"), range = 0f..33f, guides = listOf(16.6f))
        }

        val shown = ui.texts("graph")

        assertTrue("5.00" in shown && "6.00" in shown && "7.00" in shown, "min mean max: $shown")
        assertTrue("0.00" !in shown && "33.00" !in shown, "the range's ends are not the readout: $shown")
    }

    @Test
    fun `a guide above everything held does not become the largest in the readout`() {
        // No range, so the graph scales to the guide as well — but nothing in it is 16.6.
        val samples = buffer(5f, 7f, 6f)
        val ui = open {
            Plot(samples, Modifier.size(200f, 60f).testTag("graph"), guides = listOf(16.6f))
        }

        val shown = ui.texts("graph")

        assertTrue("7.00" in shown, "the largest sample: $shown")
        assertTrue("16.60" !in shown, "the guide is a line on the graph, not a value in it: $shown")
    }

    @Test
    fun `a histogram says its smallest bucket rather than its floor`() {
        // Bars stand on zero, so the bottom of the box is nought and the smallest bucket is three.
        val ui = open { Histogram(floatArrayOf(3f, 5f, 4f), Modifier.size(200f, 60f).testTag("graph")) }

        val shown = ui.texts("graph")

        assertTrue("3.00" in shown && "4.00" in shown && "5.00" in shown, "min mean max: $shown")
        assertTrue("0.00" !in shown, "the floor the bars stand on is not a bucket: $shown")
    }

    @Test
    fun `a label is written in the corner`() {
        val samples = buffer(1f, 2f)
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph"), label = "frame") }

        assertTrue("frame" in ui.texts("graph"), "the label is on it: ${ui.texts("graph")}")
    }

    @Test
    fun `hovering reads the sample under the pointer`() {
        val samples = buffer(11f, 22f, 33f, 44f)
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph"), readout = false) }

        val graph = graphOf(ui, "graph").boundsInRoot
        val column = graph.width / 4f
        ui.moveTo(Offset(graph.left + column * 1.5f, graph.centre.y))

        assertTrue("22.00" in ui.texts("graph"), "the second sample: ${ui.texts("graph")}")

        ui.moveTo(Offset(graph.left + column * 2.5f, graph.centre.y))
        assertTrue("33.00" in ui.texts("graph"), "and the third: ${ui.texts("graph")}")
    }

    @Test
    fun `a right to left screen reads the sample under the pointer mirrored`() {
        val samples = buffer(11f, 22f, 33f, 44f)
        val ui = open {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Plot(samples, Modifier.size(200f, 60f).testTag("graph"), readout = false)
            }
        }

        val graph = graphOf(ui, "graph").boundsInRoot
        val column = graph.width / 4f
        ui.moveTo(Offset(graph.left + column * 0.5f, graph.centre.y))

        assertTrue("44.00" in ui.texts("graph"), "the newest is the leftmost: ${ui.texts("graph")}")

        ui.moveTo(Offset(graph.right - column * 0.5f, graph.centre.y))
        assertTrue("11.00" in ui.texts("graph"), "and the oldest the rightmost: ${ui.texts("graph")}")
    }

    @Test
    fun `a hovered sample is marked on the graph`() {
        val samples = buffer(11f, 22f, 33f, 44f)
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph"), readout = false) }

        val graph = graphOf(ui, "graph").boundsInRoot
        assertTrue(drawn(ui).none { it is DrawCall.Rectangle && it.colour == cursor }, "nothing before the pointer")

        ui.moveTo(Offset(graph.left + graph.width / 8f, graph.centre.y))

        val marks = drawn(ui).filterIsInstance<DrawCall.Rectangle>().filter { it.colour == cursor }
        assertEquals(2, marks.size, "an upright line and a dot where it crosses the trace")
    }

    @Test
    fun `the pointer leaving puts the value it was reading away`() {
        val samples = buffer(11f, 22f, 33f, 44f)
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph"), readout = false) }

        val graph = graphOf(ui, "graph").boundsInRoot
        ui.moveTo(Offset(graph.left + graph.width / 8f, graph.centre.y))
        assertTrue("11.00" in ui.texts("graph"), "reading the oldest: ${ui.texts("graph")}")

        // Off the graph altogether, which is what ends the hover.
        ui.moveTo(Offset(350f, 250f))

        assertEquals(emptyList(), ui.texts("graph"), "nothing is being read any more")
        assertTrue(drawn(ui).none { it is DrawCall.Rectangle && it.colour == cursor }, "and nothing marked")
    }

    @Test
    fun `a plot with a readout shows the newest sample until one is pointed at`() {
        val samples = buffer(11f, 22f, 33f, 44f)
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph")) }

        assertTrue("44.00" in ui.texts("graph"), "the newest, to begin with: ${ui.texts("graph")}")

        val graph = graphOf(ui, "graph").boundsInRoot
        ui.moveTo(Offset(graph.left + graph.width * 3f / 8f, graph.centre.y))

        assertTrue("22.00" in ui.texts("graph"), "then the one under the pointer: ${ui.texts("graph")}")
    }

    @Test
    fun `a plot that is not asked to be read is scenery the pointer passes over`() {
        val samples = buffer(11f, 22f, 33f, 44f)
        val ui = open { Plot(samples, Modifier.size(200f, 60f).testTag("graph"), readout = false, hover = false) }

        val graph = graphOf(ui, "graph").boundsInRoot
        ui.moveTo(Offset(graph.left + graph.width / 8f, graph.centre.y))

        assertTrue("11.00" !in ui.texts("graph"), "nothing was picked out: ${ui.texts("graph")}")
        assertTrue(drawn(ui).none { it is DrawCall.Rectangle && it.colour == cursor }, "and nothing marked")
    }

    // --- the keyboard and the pad -----------------------------------------------------------------

    @Composable
    private fun Focusable(samples: PlotBuffer) {
        Box(Modifier.fillMaxSize()) {
            Plot(samples, Modifier.size(200f, 60f).testTag("graph"), readout = false, focusable = true)
        }
    }

    @Test
    fun `the arrows walk the cursor along the samples`() {
        val samples = buffer(11f, 22f, 33f, 44f)
        val ui = open { Focusable(samples) }
        ui.assertFocused("graph")

        // The first press picks out the newest, which is the one the number beside it already shows.
        ui.key(Key.Left)
        assertTrue("44.00" in ui.texts("graph"), "starts on the newest: ${ui.texts("graph")}")

        ui.key(Key.Left)
        assertTrue("33.00" in ui.texts("graph"), "then back one: ${ui.texts("graph")}")

        ui.key(Key.Right)
        assertTrue("44.00" in ui.texts("graph"), "and forwards again: ${ui.texts("graph")}")
    }

    @Test
    fun `home and end jump to the ends and escape puts the cursor away`() {
        val samples = buffer(11f, 22f, 33f, 44f)
        val ui = open { Focusable(samples) }

        ui.key(Key.Home)
        assertTrue("11.00" in ui.texts("graph"), "the oldest: ${ui.texts("graph")}")

        ui.key(Key.End)
        assertTrue("44.00" in ui.texts("graph"), "the newest: ${ui.texts("graph")}")

        ui.key(Key.Home)
        ui.key(Key.Escape)
        assertTrue(drawn(ui).none { it is DrawCall.Rectangle && it.colour == cursor }, "nothing marked any more")
    }

    @Test
    fun `the arrows are mirrored on a right to left screen`() {
        val samples = buffer(11f, 22f, 33f, 44f)
        val ui = open {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Focusable(samples)
            }
        }
        ui.assertFocused("graph")

        // The newest sample is on the left here, so Right is the way back through the older ones and
        // Left is the way towards the newer — the opposite of the test above, on the same graph.
        ui.key(Key.Right)
        assertTrue("44.00" in ui.texts("graph"), "starts on the newest: ${ui.texts("graph")}")

        ui.key(Key.Right)
        assertTrue("33.00" in ui.texts("graph"), "then back one: ${ui.texts("graph")}")

        ui.key(Key.Left)
        assertTrue("44.00" in ui.texts("graph"), "and Left goes towards the newer: ${ui.texts("graph")}")
    }

    @Test
    fun `the pad walks the cursor too`() {
        val samples = buffer(11f, 22f, 33f, 44f)
        val ui = open { Focusable(samples) }
        ui.assertFocused("graph")

        ui.pad(GamepadButton.DpadLeft)
        ui.pad(GamepadButton.DpadLeft)

        assertTrue("33.00" in ui.texts("graph"), "two presses back from the newest: ${ui.texts("graph")}")
    }

    @Test
    fun `running off the end of a graph lets focus leave it`() {
        val samples = buffer(11f, 22f)
        val ui = open {
            Row(Modifier.fillMaxSize()) {
                Plot(samples, Modifier.size(120f, 40f).testTag("graph"), readout = false, focusable = true)
                Button("next", onClick = {}, modifier = Modifier.testTag("next"))
            }
        }
        ui.assertFocused("graph")

        // Onto the newest sample, and then there is nowhere left to go: the press has to reach focus
        // rather than be swallowed, or a pad could never leave a graph it has finished reading.
        ui.key(Key.Right)
        ui.assertFocused("graph")

        ui.key(Key.Right)
        ui.assertFocused("next")
    }

    // --- the frame budget's own graph ---------------------------------------------------------------

    /** The colour the overlay draws its own graph in, which is its own rather than the skin's. */
    private val overlayLine = Colour.rgb(0xF0F4FF)

    /** A screen whose frames really are measured by [budget], as a game's renderer measures them. */
    private fun withBudget(budget: FrameBudget, content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), budget = budget, content = content).also { opened += it }

    @Test
    fun `the frame budget overlay draws the frames it measured`() {
        val budget = FrameBudget(window = 8, publishEveryMillis = 0L)
        val ui = withBudget(budget) {
            Box(Modifier.fillMaxSize()) { FrameBudgetOverlay(budget, Modifier.testTag("budget")) }
        }
        repeat(6) { ui.render() }

        val fans = drawn(ui).filterIsInstance<DrawCall.Fan>().filter { it.colour == overlayLine }

        assertTrue(fans.isNotEmpty(), "a line through the frames it has measured")
    }

    @Test
    fun `the frame budget overlay can be asked for no graph at all`() {
        val budget = FrameBudget(window = 8, publishEveryMillis = 0L)
        val ui = withBudget(budget) {
            Box(Modifier.fillMaxSize()) { FrameBudgetOverlay(budget, Modifier.testTag("budget"), graph = false) }
        }
        repeat(6) { ui.render() }

        assertTrue(
            drawn(ui).filterIsInstance<DrawCall.Fan>().none { it.colour == overlayLine },
            "the numbers on their own",
        )
    }

    @Test
    fun `an overlay with no budget at all still draws its graph`() {
        // Everything is over budget at nought, which is a way of watching every frame — and the
        // graph's range used to come out empty, which threw while the screen was composing.
        val budget = FrameBudget(window = 8, publishEveryMillis = 0L)
        val ui = withBudget(budget) {
            Box(Modifier.fillMaxSize()) { FrameBudgetOverlay(budget, Modifier.testTag("budget"), overMillis = 0f) }
        }
        repeat(6) { ui.render() }

        val fans = drawn(ui).filterIsInstance<DrawCall.Fan>().filter { it.colour == overlayLine }

        assertTrue(fans.isNotEmpty(), "a line through the frames it has measured")
    }

}
