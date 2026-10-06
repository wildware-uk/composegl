package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.animateContentSize
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.marquee
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onSizeChanged
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Image
import dev.wildware.composegl.ui.widget.LazyColumn
import dev.wildware.composegl.ui.widget.LazyGridState
import dev.wildware.composegl.ui.widget.LazyListState
import dev.wildware.composegl.ui.widget.LazyVerticalGrid
import dev.wildware.composegl.ui.widget.Popup
import dev.wildware.composegl.ui.widget.PopupAnchor
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.ScrollState
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Tooltip
import dev.wildware.composegl.ui.widget.TooltipHost
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred

/**
 * A layout pass measures what changed and the path from it to the root, and nothing else.
 *
 * What is counted is calls to a measure policy, because running the policy is what a measure costs.
 * Every test also looks at the rectangles that came out, so a node that was skipped is shown to be
 * exactly where a full pass would have put it — moved down by a neighbour that grew, still told
 * where it is on screen, still following a resize.
 */
class RemeasureChangedUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(640f, 480f), content = content).also { opened += it }

    private val measured = mutableMapOf<String, Int>()

    private fun count(name: String) = measured[name] ?: 0

    /** Stacks its children at its corner, as big as the biggest of them, and counts every run. */
    private fun counting(name: String) = MeasurePolicy { measurables, constraints ->
        measured[name] = count(name) + 1
        val loose = constraints.loosen()
        val placed = measurables.map { it.measure(loose) }
        val width = constraints.constrainWidth(placed.maxOfOrNull { it.width } ?: 0f)
        val height = constraints.constrainHeight(placed.maxOfOrNull { it.height } ?: 0f)
        layout(width, height) { placed.forEach { it.at(0f, 0f) } }
    }

    @Composable
    private fun Counted(name: String, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
        val policy = remember(name) { counting(name) }
        Layout(modifier.testTag(name), name = name, content = content, measurePolicy = policy)
    }

    @Test
    fun `a label whose text changes measures the label and its ancestors and not the panel beside it`() {
        var score by mutableStateOf("1")
        val ui = open {
            Column {
                Counted("hud") { Text(score, Modifier.testTag("score")) }
                Counted("panel") {
                    Counted("inner") { Text("Inventory") }
                }
            }
        }
        val hud = count("hud")
        val panel = count("panel")
        val inner = count("inner")
        val narrow = ui.node("score").width

        score = "1000000"
        ui.settle()

        assertTrue(ui.node("score").width > narrow, "the label was not measured with its new text")
        assertEquals(ui.node("score").width, ui.node("hud").width, "the label's parent kept its old size")
        assertTrue(count("hud") > hud, "the label's parent was not measured again")
        assertEquals(panel, count("panel"), "the panel beside the label was measured again")
        assertEquals(inner, count("inner"), "the panel's insides were measured again")
    }

    @Test
    fun `a node that changed is measured again in the same room`() {
        var colour by mutableStateOf(Colour.rgb(0xAA3333))
        val ui = open {
            Column {
                Counted("changed", Modifier.background(colour).size(40f, 20f))
                Counted("still", Modifier.size(40f, 20f))
            }
        }
        val changed = count("changed")
        val still = count("still")

        colour = Colour.rgb(0x3333AA)
        ui.settle()

        assertTrue(count("changed") > changed, "a node handed a new chain was skipped")
        assertEquals(still, count("still"), "a node nothing changed was measured again")
    }

    @Test
    fun `a parent whose room changes measures its children again`() {
        var wide by mutableStateOf(100f)
        val ui = open {
            Box(Modifier.width(wide)) {
                Counted("child", Modifier.fillMaxWidth()) {
                    Counted("grandchild", Modifier.fillMaxWidth().height(10f))
                }
            }
        }
        val child = count("child")
        val grandchild = count("grandchild")

        wide = 200f
        ui.settle()

        assertTrue(count("child") > child, "a child offered more room was skipped")
        assertTrue(count("grandchild") > grandchild, "a grandchild offered more room was skipped")
        assertEquals(200f, ui.node("child").width)
        assertEquals(200f, ui.node("grandchild").width)
    }

    @Test
    fun `a neighbour that grows moves the nodes after it without measuring them`() {
        var tall by mutableStateOf(20f)
        // A row centring its children: the one beside a node that grows taller moves down, and is
        // offered exactly the room it was before. (A column would offer it less height.)
        val ui = open {
            Row(verticalAlignment = VerticalAlignment.Centre) {
                Counted("top", Modifier.size(40f, tall))
                Counted("below") {
                    Counted("deep", Modifier.size(30f, 10f))
                }
            }
        }
        assertEquals(Rect.of(40f, 5f, 30f, 10f), ui.node("deep").layoutBoundsInRoot)
        val below = count("below")
        val deep = count("deep")

        tall = 50f
        ui.settle()

        assertEquals(Rect.of(40f, 20f, 30f, 10f), ui.node("below").layoutBoundsInRoot)
        assertEquals(Rect.of(40f, 20f, 30f, 10f), ui.node("deep").layoutBoundsInRoot)
        assertEquals(below, count("below"), "a node only moved was measured again")
        assertEquals(deep, count("deep"))
    }

    // Both say no layout reads what they keep, so hearing them does not make the next pass measure
    // everything; what is being shown is that a skipped part still tells them.
    private var placedAt: Rect? = null
    private val whereIsIt = object : PlacedHandler {
        override val readByLayout get() = false
        override fun onPlaced(node: UiNode) {
            placedAt = node.layoutBoundsInRoot
        }
    }
    private var sizes = 0
    private val howBig = object : SizeChangedHandler {
        override val readByLayout get() = false
        override fun onSizeChanged(size: Size) {
            sizes++
        }
    }

    @Test
    fun `a watcher inside a skipped subtree still hears its ancestor move`() {
        var tall by mutableStateOf(20f)
        val ui = open {
            Row(verticalAlignment = VerticalAlignment.Centre) {
                Counted("top", Modifier.size(40f, tall))
                Counted("below") {
                    Counted("inside") {
                        Box(Modifier.size(10f).onPlaced(whereIsIt).onSizeChanged(howBig))
                    }
                }
            }
        }
        assertEquals(Rect.of(40f, 5f, 10f, 10f), placedAt)
        val below = count("below")
        val inside = count("inside")
        val told = sizes

        tall = 50f
        ui.settle()

        assertEquals(Rect.of(40f, 20f, 10f, 10f), placedAt, "onPlaced was not told the subtree moved")
        assertEquals(told, sizes, "a size that did not change was reported")
        assertEquals(below, count("below"), "the subtree was measured again")
        assertEquals(inside, count("inside"))
    }

    @Test
    fun `a resize keeps stepping while the nodes around it are skipped`() {
        var wide by mutableStateOf(20f)
        val resize = Clock("resize")
        val linear = Tween(durationMillis = 160, easing = Easings.Linear)
        val ui = open {
            Column {
                Box(Modifier.animateContentSize(linear, clock = resize).testTag("growing")) {
                    Box(Modifier.size(wide, 10f))
                }
                Counted("below", Modifier.size(30f, 10f))
            }
        }
        val below = count("below")

        // Held at its first frame while the test takes the frames over, as a game's loop would.
        ui.host.clocks.stop(resize)
        wide = 180f
        ui.settle()
        var now = 1_000_000_000_000L
        ui.host.settle(ui.viewport, nanos = now)
        ui.host.clocks.start(resize)
        val widths = mutableListOf<Float>()
        repeat(20) {
            now += 16_666_667L
            ui.host.settle(ui.viewport, nanos = now)
            widths += ui.node("growing").width
        }

        assertTrue(widths[2] > widths[0] && widths[4] > widths[2], "the resize did not step: $widths")
        assertEquals(180f, widths.last(), "the resize stopped short: $widths")
        assertEquals(below, count("below"), "a neighbour the resize never touched was measured again")
    }

    @Test
    fun `a size asked of the contents is asked again when they change`() {
        var label by mutableStateOf("OK")
        val ui = open {
            Column(Modifier.width(IntrinsicSize.Max).testTag("menu")) {
                Text(label, Modifier.testTag("label"))
                Counted("button", Modifier.fillMaxWidth().height(10f))
            }
        }
        val short = ui.node("menu").width

        label = "A much longer entry"
        ui.settle()

        val long = ui.node("label").width
        assertTrue(long > short)
        assertEquals(long, ui.node("menu").width, "the column kept the width its old contents asked for")
        assertEquals(long, ui.node("button").width, "the button kept its old room")
    }

    private var offered = Float.NaN

    /** Writes down how wide it was told to be, and answers intrinsic questions the default way. */
    private val writesDown = MeasurePolicy { _, constraints ->
        offered = constraints.maxWidth
        layout(constraints.constrainWidth(50f), constraints.constrainHeight(10f)) {}
    }

    @Test
    fun `a policy whose measure writes things down is measured for real after an intrinsic question`() {
        var colour by mutableStateOf(Colour.rgb(0xAA3333))
        val ui = open {
            Column(Modifier.width(IntrinsicSize.Max)) {
                Counted("label", Modifier.background(colour).size(80f, 10f))
                Layout(Modifier.fillMaxWidth(), name = "writer", measurePolicy = writesDown)
            }
        }
        assertEquals(80f, offered)

        // The column is measured again and asks the writer how wide it would like to be, which runs
        // its measure in unbounded room; the writer is then offered exactly what it had before.
        colour = Colour.rgb(0x3333AA)
        ui.settle()

        assertEquals(80f, offered, "the writer kept what the intrinsic question had it write down")
    }

    /** Hands its one child the room it was given, and answers intrinsic questions by asking it. */
    private val passesThrough = object : MeasurePolicy {
        override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
            val child = measurables.single().measure(constraints)
            return layout(child.width, child.height) { child.at(0f, 0f) }
        }

        override fun MeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
            measurables.single().minIntrinsicWidth(height)

        override fun MeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
            measurables.single().maxIntrinsicWidth(height)

        override fun MeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
            measurables.single().minIntrinsicHeight(width)

        override fun MeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
            measurables.single().maxIntrinsicHeight(width)
    }

    @Test
    fun `a policy that writes things down deeper down is measured for real after an intrinsic question`() {
        var colour by mutableStateOf(Colour.rgb(0xAA3333))
        val ui = open {
            Column(Modifier.width(IntrinsicSize.Max)) {
                Counted("label", Modifier.background(colour).size(80f, 10f))
                // A wrapper that answers without running its own measure, so nothing marks it.
                Layout(Modifier.fillMaxWidth(), name = "wrapper", measurePolicy = passesThrough, content = {
                    Layout(Modifier.fillMaxWidth(), name = "writer", measurePolicy = writesDown)
                })
            }
        }
        assertEquals(80f, offered)

        colour = Colour.rgb(0x3333AA)
        ui.settle()

        assertEquals(80f, offered, "the writer under the wrapper kept what the intrinsic question had it write down")
    }

    private fun UiTest.baselineOf(tag: String) = node(tag).let { it.layoutBoundsInRoot.top + it.firstBaseline }

    @Test
    fun `a neighbour lined up by its text follows a baseline that moved and is not measured`() {
        var pad by mutableStateOf(0f)
        val ui = open {
            Row(verticalAlignment = VerticalAlignment.Baseline) {
                Box(Modifier.padding(top = pad)) { Text("Score", Modifier.testTag("left")) }
                Counted("right") { Text("Gold", Modifier.testTag("rightText")) }
            }
        }
        assertEquals(ui.baselineOf("left"), ui.baselineOf("rightText"), 0.01f)
        val right = count("right")
        val before = ui.node("rightText").layoutBoundsInRoot.top

        pad = 20f
        ui.settle()

        assertEquals(ui.baselineOf("left"), ui.baselineOf("rightText"), 0.01f, "the two lines of text came apart")
        assertEquals(before + 20f, ui.node("rightText").layoutBoundsInRoot.top, 0.01f)
        assertEquals(right, count("right"), "the neighbour was measured again to be moved")
    }

    @Test
    fun `a marquee in a part that only moved keeps going round`() {
        var tall by mutableStateOf(20f)
        val ui = open {
            Row(verticalAlignment = VerticalAlignment.Centre) {
                Counted("top", Modifier.size(40f, tall))
                Counted("strip") {
                    Text(
                        "A title far too long for the strip it sits in",
                        Modifier.width(60f).marquee(delayMillis = 0).testTag("title"),
                    )
                }
            }
        }
        val strip = count("strip")

        tall = 50f
        ui.settle()
        ui.advanceBy(200)
        val run = checkNotNull(ui.node("title").marquee) { "the title has no marquee running" }
        val early = run.offset
        ui.advanceBy(200)

        assertTrue(run.offset > early && early > 0f, "the marquee stopped once its part moved: $early then ${run.offset}")
        assertEquals(strip, count("strip"), "the strip was measured again to be moved")
    }

    @Test
    fun `a lazy list scrolls while the panel beside it is skipped`() {
        val state = LazyListState()
        val ui = open {
            Row {
                LazyColumn(40, Modifier.size(100f, 100f).testTag("list"), state = state, bars = false) { index ->
                    Box(Modifier.size(100f, 20f).testTag("item$index"))
                }
                Counted("panel") { Counted("inner", Modifier.size(30f)) }
            }
        }
        val panel = count("panel")
        val inner = count("inner")

        ui.scroll("list", Offset(0f, 1f))

        val scrolled = state.position
        assertTrue(scrolled > 0f, "the wheel did not scroll the list")
        val first = state.firstVisibleItem
        for (index in first..first + 4) {
            assertEquals(index * 20f - scrolled, ui.node("item$index").layoutBoundsInRoot.top, 0.01f, "item $index")
        }
        assertEquals(panel, count("panel"), "the panel beside the list was measured again")
        assertEquals(inner, count("inner"))
    }

    @Test
    fun `focus moves by where nodes are now after a part was moved without being measured`() {
        var drop by mutableStateOf(0f)
        val ui = open {
            Row {
                Box(Modifier.offset(0f, drop)) {
                    Counted("holder") { Box(Modifier.size(40f, 20f).focusable().testTag("a")) }
                }
                Box(Modifier.size(40f, 20f).focusable(initial = true).testTag("b"))
            }
        }
        ui.assertFocused("b")
        val holder = count("holder")

        // "a" goes from beside "b" to below it, inside a part nothing measures again.
        drop = 100f
        ui.settle()
        ui.key(Key.Down)

        ui.assertFocused("a")
        assertEquals(holder, count("holder"), "the part was measured again to be moved")
        assertEquals(Rect.of(0f, 100f, 40f, 20f), ui.node("a").boundsInRoot)
    }

    /** A picture that has not loaded yet: no size until the test gives it one. */
    private class Loading : TextureHandle {
        override var width = 0
        override var height = 0
    }

    @Test
    fun `a picture that loads while hidden takes its size on the next pass`() {
        val picture = Loading()
        var colour by mutableStateOf(Colour.rgb(0xAA3333))
        val ui = open {
            Column {
                Box(Modifier.alpha(0f)) { Image(picture, Modifier.testTag("hidden")) }
                Counted("other", Modifier.background(colour).size(10f))
            }
        }
        assertEquals(0f, ui.node("hidden").width)

        // Nothing here draws, so only layout can notice; something else changing runs a pass.
        picture.width = 40
        picture.height = 30
        colour = Colour.rgb(0x3333AA)
        ui.settle()

        assertEquals(Size(40f, 30f), Size(ui.node("hidden").width, ui.node("hidden").height))
    }

    @Test
    fun `a popup whose anchor is placed after it still opens next to it`() {
        val anchor = PopupAnchor()
        val learn = PlacedHandler { anchor.node = it }
        val ui = open {
            PopupHost {
                Column {
                    Box(Modifier.size(10f, 100f))
                    Box(Modifier.size(80f, 20f).onPlaced(learn).testTag("field"))
                }
                Popup(anchor, onDismiss = {}) { Box(Modifier.size(50f, 30f).testTag("list")) }
            }
        }

        val field = ui.node("field").boundsInRoot
        assertEquals(field.bottom + 2f, ui.node("list").boundsInRoot.top, "the popup stayed where it opened with no anchor")
    }

    @Test
    fun `a layout lined up with a node in another tree catches up on its next pass`() {
        val elsewhere = UiTree()
        val marker = UiNode("marker").also { it.modifier = Modifier.size(10f) }
        elsewhere.root.insertAt(0, marker)
        MeasurePass().run(elsewhere.root, Constraints.atMost(500f, 500f))
        val follows = MeasurePolicy { _, constraints ->
            layout(constraints.constrainWidth(marker.x), 4f) {}
        }
        var colour by mutableStateOf(Colour.rgb(0xAA3333))
        val ui = open {
            Column {
                Layout(Modifier.testTag("follower"), measurePolicy = follows)
                Counted("other", Modifier.background(colour).size(10f))
            }
        }
        assertEquals(0f, ui.node("follower").width)

        marker.modifier = Modifier.offset(30f, 0f).size(10f)
        MeasurePass().run(elsewhere.root, Constraints.atMost(500f, 500f))
        colour = Colour.rgb(0x3333AA)
        ui.settle()

        assertEquals(30f, ui.node("follower").width, "the follower kept where the other tree's node was")
    }

    private var target: UiNode? = null
    private val learn = PlacedHandler { target = it }

    /** An arrow under whatever [target] is: the node kept from `onPlaced`, read while measuring. */
    private val underTarget = MeasurePolicy { measurables, constraints ->
        val arrow = measurables.single().measure(constraints.loosen())
        val x = target?.boundsInRoot?.left ?: 0f
        layout(constraints.maxWidth, arrow.height) { arrow.at(x, 0f) }
    }

    @Test
    fun `a layout lined up with a node it learned through onPlaced is right at once and follows it`() {
        var gap by mutableStateOf(120f)
        val ui = open {
            Column(Modifier.fillMaxWidth()) {
                Row {
                    Box(Modifier.size(gap, 10f))
                    Box(Modifier.size(30f, 10f).onPlaced(learn).testTag("button"))
                }
                Layout(Modifier.fillMaxWidth(), name = "marker", measurePolicy = underTarget, content = {
                    Box(Modifier.size(8f).testTag("arrow"))
                })
            }
        }
        assertEquals(120f, ui.node("arrow").boundsInRoot.left, "the arrow is not under the button on the first frame")

        gap = 200f
        ui.settle()

        assertEquals(200f, ui.node("arrow").boundsInRoot.left, "the arrow stayed where the button was")
    }

    private var anchorRect = Rect.Zero
    private val copyRect = PlacedHandler { anchorRect = it.boundsInRoot }

    /** An arrow under [anchorRect]: a copy of the button's rectangle in a plain field. */
    private val underCopy = MeasurePolicy { measurables, constraints ->
        val arrow = measurables.single().measure(constraints.loosen())
        layout(constraints.maxWidth, arrow.height) { arrow.at(anchorRect.left, 0f) }
    }

    @Test
    fun `a layout lined up with a rectangle a handler copied follows it`() {
        var gap by mutableStateOf(120f)
        val ui = open {
            Column(Modifier.fillMaxWidth()) {
                Row {
                    Box(Modifier.size(gap, 10f))
                    Box(Modifier.size(30f, 10f).onPlaced(copyRect).testTag("button"))
                }
                Layout(Modifier.fillMaxWidth(), name = "marker", measurePolicy = underCopy, content = {
                    Box(Modifier.size(8f).testTag("arrow"))
                })
            }
        }
        assertEquals(120f, ui.node("arrow").boundsInRoot.left, "the arrow is not under the button on the first frame")

        gap = 200f
        ui.settle()

        assertEquals(200f, ui.node("arrow").boundsInRoot.left, "the arrow stayed where the button was")
    }

    @Test
    fun `a list of rows with tooltips scrolls without measuring the panel beside it`() {
        val state = LazyListState()
        val ui = open {
            TooltipHost {
                Row {
                    LazyColumn(400, Modifier.size(100f, 100f).testTag("list"), state = state, bars = false) { index ->
                        Tooltip("Row $index") { Box(Modifier.size(100f, 20f).testTag("row$index")) }
                    }
                    Counted("panel") { Counted("inner", Modifier.size(30f)) }
                }
            }
        }
        val panel = count("panel")

        // Far enough that rows the list has never shown come into view, each with a handler new to it.
        repeat(10) { ui.scroll("list", Offset(0f, 1f)) }

        assertTrue(state.firstVisibleItem > 5, "the list did not scroll: ${state.firstVisibleItem}")
        assertEquals(panel, count("panel"), "the panel beside the list was measured again")
    }

    private val quietWatch = object : PlacedHandler {
        override val readByLayout get() = false
        override fun onPlaced(node: UiNode) = Unit
    }
    private val plainWatch = PlacedHandler { }

    @Test
    fun `a handler that says no layout reads it leaves the rest of the screen alone and one that does not measures it all`() {
        var tall by mutableStateOf(20f)
        var quiet by mutableStateOf(true)
        val ui = open {
            Row(verticalAlignment = VerticalAlignment.Centre) {
                Counted("top", Modifier.size(40f, tall))
                Box(Modifier.size(10f).onPlaced(if (quiet) quietWatch else plainWatch))
                Counted("still", Modifier.size(30f))
            }
        }
        val before = count("still")

        tall = 50f
        ui.settle()
        assertEquals(before, count("still"), "a quiet handler made the pass after it measure everything")

        quiet = false
        ui.settle()
        val plain = count("still")
        tall = 80f
        ui.settle()
        assertTrue(count("still") > plain, "a handler that may feed a layout did not have the screen measured after it")
    }

    @Test
    fun `a scroll area moved from a frame callback is placed on the frame it moves in`() {
        val scroll = ScrollState()
        var lines by mutableStateOf(5)
        val ui = open {
            ScrollArea(Modifier.size(200f, 100f), scroll) {
                Column { repeat(lines) { Box(Modifier.size(200f, 20f).testTag("line$it")) } }
            }
            // A log that follows its newest line, the way the dev console does: after the frame
            // that laid the new lines out, so the bottom it scrolls to is the new one.
            LaunchedEffect(lines) {
                withFrameNanos { }
                scroll.scrollTo(y = scroll.maxY)
            }
        }

        lines = 30
        var now = 1_000_000_000_000L
        var moved = false
        for (frame in 0 until 10) {
            now += 16_666_667L
            ui.host.settle(ui.viewport, nanos = now)
            if (scroll.y > 0f) {
                assertEquals(-scroll.y, ui.node("line0").layoutBoundsInRoot.top, "frame $frame: scrolled but not moved")
                moved = true
                break
            }
        }
        assertTrue(moved, "the log never followed its newest line")
    }

    /** A colour in state, so a test can change something unrelated on every frame. */
    private class Paint {
        var colour by mutableStateOf(Colour.rgb(0xAA3333))
    }

    @Composable
    private fun Swatch(paint: Paint) {
        Box(Modifier.background(paint.colour).size(10f))
    }

    /**
     * Steps frames one at a time, with something unrelated changing on each so every frame is laid
     * out, until [moved] says the scroll happened; then [check] runs on that same frame.
     */
    private fun UiTest.stepUntil(paint: Paint, moved: () -> Boolean, check: (Int) -> Unit) {
        var now = 1_000_000_000_000L
        for (frame in 0 until 6) {
            paint.colour = if (frame % 2 == 0) Colour.rgb(0x3333AA) else Colour.rgb(0xAA3333)
            now += 16_666_667L
            host.settle(viewport, nanos = now)
            if (moved()) {
                check(frame)
                return
            }
        }
        throw AssertionError("it never scrolled")
    }

    @Test
    fun `a lazy list scrolled from a frame callback is placed on the frame it moves in`() {
        val state = LazyListState()
        val gate = CompletableDeferred<Unit>()
        val paint = Paint()
        val ui = open {
            Column {
                LazyColumn(40, Modifier.size(100f, 100f), state = state, bars = false) { index ->
                    Box(Modifier.size(100f, 20f).testTag("item$index"))
                }
                Swatch(paint)
            }
            LaunchedEffect(Unit) {
                gate.await()
                withFrameNanos { }
                state.scrollBy(30f)
            }
        }
        assertEquals(0f, ui.node("item0").layoutBoundsInRoot.top)

        gate.complete(Unit)

        ui.stepUntil(paint, { state.position > 0f }) { frame ->
            assertEquals(-state.position, ui.node("item0").layoutBoundsInRoot.top, "frame $frame: scrolled but not moved")
        }
    }

    @Test
    fun `a lazy grid scrolled from a frame callback is placed on the frame it moves in`() {
        val state = LazyGridState()
        val gate = CompletableDeferred<Unit>()
        val paint = Paint()
        val ui = open {
            Column {
                LazyVerticalGrid(80, GridCells.Fixed(2), Modifier.size(100f, 100f), state = state, bars = false) { index ->
                    Box(Modifier.size(50f, 20f).testTag("cell$index"))
                }
                Swatch(paint)
            }
            LaunchedEffect(Unit) {
                gate.await()
                withFrameNanos { }
                state.scrollBy(30f)
            }
        }
        assertEquals(0f, ui.node("cell0").layoutBoundsInRoot.top)

        gate.complete(Unit)

        ui.stepUntil(paint, { state.position > 0f }) { frame ->
            assertEquals(-state.position, ui.node("cell0").layoutBoundsInRoot.top, "frame $frame: scrolled but not moved")
        }
    }

    @Test
    fun `a change nobody names measures every node`() {
        val ui = open {
            Column {
                Counted("one", Modifier.size(10f))
                Counted("two") { Counted("three", Modifier.size(10f)) }
            }
        }
        val counts = listOf("one", "two", "three").map(::count)

        ui.host.tree.invalidate()
        ui.settle()

        assertEquals(counts.map { it + 1 }, listOf("one", "two", "three").map(::count))
    }

    @Test
    fun `a node built by hand and changed is measured again`() {
        val root = UiNode("root")
        val child = UiNode("child").also { it.modifier = Modifier.size(10f, 10f) }
        root.insertAt(0, child)
        MeasurePass().run(root, Constraints.atMost(100f, 100f))
        assertEquals(10f, child.width)

        child.modifier = Modifier.size(30f, 10f)
        MeasurePass().run(root, Constraints.atMost(100f, 100f))

        assertEquals(30f, child.width, "a node with no tree kept its old size")
        assertEquals(30f, root.width)
    }

    @Test
    fun `a pass that threw part of the way is followed by one that measures everything`() {
        var explode = false
        val root = UiNode("root").also { it.measurePolicy = MeasurePolicy.Stack }
        val first = UiNode("first").also { it.measurePolicy = counting("first") }
        val thrower = UiNode("thrower").also {
            it.measurePolicy = MeasurePolicy { _, constraints ->
                check(!explode) { "boom" }
                layout(constraints.constrainWidth(10f), constraints.constrainHeight(10f)) {}
            }
        }
        root.insertAt(0, first)
        root.insertAt(1, thrower)
        MeasurePass().run(root, Constraints.atMost(100f, 100f))
        val before = count("first")

        explode = true
        thrower.invalidate()
        assertFailsWith<IllegalStateException> { MeasurePass().run(root, Constraints.atMost(100f, 100f)) }
        explode = false
        MeasurePass().run(root, Constraints.atMost(100f, 100f))

        assertEquals(before + 1, count("first"), "the pass after one that threw skipped a node")
    }
}
