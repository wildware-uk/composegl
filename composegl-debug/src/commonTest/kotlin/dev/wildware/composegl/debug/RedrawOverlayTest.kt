package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.animateContentSize
import dev.wildware.composegl.ui.modifier.animatePlacement
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.marquee
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `RedrawOverlay`: a border flashed on every node that changed, fading out, which must not itself be
 * a reason the screen changes.
 *
 * Every test composes a real screen with [uiTest], changes it the way a player would — a click, a pad
 * button — and reads the flashes back off a recording canvas, against the nodes that changed and the
 * ones that did not.
 */
class RedrawOverlayTest {

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

    /** The flashes, by the rectangle they were drawn round, with how strong each one still is. */
    private fun List<DrawCall>.flashes(): Map<Rect, Int> =
        filterIsInstance<DrawCall.Border>()
            .filter { it.colour.withAlpha(255) == RedrawColour && it.width == RedrawWidth }
            .associate { it.rect to it.colour.alpha }

    // --- the screen ------------------------------------------------------------------------------

    /** A toggle, a button that counts up, the count, and a box nothing ever changes. */
    @Composable
    private fun Screen(hold: Int = 500) {
        var debug by remember { mutableStateOf(false) }
        var count by remember { mutableStateOf(0) }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.offset(20f, 20f), verticalArrangement = Arrangement.spacedBy(8f)) {
                Button("debug", onClick = { debug = !debug }, modifier = Modifier.testTag("toggle"), initialFocus = true)
                Button("more", onClick = { count++ }, modifier = Modifier.testTag("more"))
                Text("count $count", modifier = Modifier.testTag("count"))
                Box(Modifier.size(60f, 40f).background(Colour.Blue).testTag("still"))
            }
            RedrawOverlay(debug, hold)
        }
    }

    // --- tests -----------------------------------------------------------------------------------

    @Test
    fun `off it composes nothing counts nothing and draws nothing`() {
        val ui = open { Screen() }
        ui.click("more")

        assertNull(ui.root.firstOrNull { it.name == RedrawOverlayName }, "no overlay node while it is off")
        assertFalse(ui.host.tree.counting, "nobody asked for counts")
        assertEquals(0, ui.node("count").changes, "the count changed but nothing was counting")
        assertTrue(drawn(ui).flashes().isEmpty())
    }

    @Test
    fun `turned on it flashes the node that changed and nothing else`() {
        val ui = open { Screen() }
        ui.click("toggle")
        ui.advanceBy(1000)
        assertTrue(drawn(ui).flashes().isEmpty(), "switching it on flashes nothing once the switch has faded")

        ui.click("more")
        ui.assertText("count", "count 1")
        val flashes = drawn(ui).flashes()

        val count = ui.node("count").boundsInRoot
        assertTrue(count in flashes, "the count flashed: $flashes")
        assertEquals(1, ui.node("count").changes)
        assertFalse(ui.node("still").boundsInRoot in flashes, "the box that never changed did not")
        assertEquals(0, ui.node("still").changes)
    }

    @Test
    fun `the pad turns it on as well`() {
        val ui = open { Screen() }
        ui.assertFocused("toggle")
        ui.pad(GamepadButton.South)
        assertNotNull(ui.root.firstOrNull { it.name == RedrawOverlayName })

        ui.click("more")
        assertTrue(ui.node("count").boundsInRoot in drawn(ui).flashes())
    }

    @Test
    fun `a flash fades as frames pass and is gone after the hold`() {
        val ui = open { Screen(hold = 500) }
        ui.click("toggle")
        ui.click("more")
        val count = ui.node("count").boundsInRoot

        val fresh = drawn(ui).flashes().getValue(count)
        ui.advanceBy(250)
        val later = drawn(ui).flashes().getValue(count)
        assertTrue(later < fresh, "fading: $fresh then $later")
        assertTrue(later in 60..160, "about half way after 250 of 500 ms: $later")

        ui.advanceBy(300)
        assertFalse(count in drawn(ui).flashes(), "gone once the hold has passed")
    }

    @Test
    fun `every change starts the flash again`() {
        val ui = open { Screen(hold = 500) }
        ui.click("toggle")
        ui.click("more")
        val count = ui.node("count").boundsInRoot
        // Short of the hold, with room for the frames each action settles for.
        ui.advanceBy(300)
        val faded = drawn(ui).flashes().getValue(count)

        ui.click("more")
        val again = drawn(ui).flashes().getValue(count)
        assertTrue(again > faded, "bright again: $faded then $again")
        assertEquals(2, ui.node("count").changes)
    }

    @Test
    fun `a box handed a new lambda each tick flashes each tick and its still neighbour never does`() {
        val remembered: UiCanvas.(Rect) -> Unit = { rect(it, Colour.Green) }
        val ui = open {
            var tick by remember { mutableStateOf(0) }
            Box(Modifier.fillMaxSize()) {
                Column {
                    Button("tick", onClick = { tick++ }, modifier = Modifier.testTag("tick"))
                    // Read here, in composition, and captured: every tick hands the box a new lambda,
                    // which is the drawing equivalent of a lambda written inline.
                    val seen = tick
                    Box(Modifier.size(40f, 40f).drawBehind { if (seen >= 0) rect(it, Colour.Red) }.testTag("inline"))
                    Box(Modifier.size(40f, 40f).drawBehind(remembered).testTag("remembered"))
                }
                RedrawOverlay(true)
            }
        }

        repeat(4) {
            ui.click("tick")
            val flashes = drawn(ui).flashes()
            assertTrue(ui.node("inline").boundsInRoot in flashes, "tick $it flashed the box with the new lambda")
            assertFalse(ui.node("remembered").boundsInRoot in flashes, "and not the one handed the same lambda")
            ui.advanceBy(600)
        }
        assertEquals(4, ui.node("inline").changes)
        assertEquals(0, ui.node("remembered").changes)
    }

    @Test
    fun `a still screen with it on is not redrawn and the overlay never flashes itself`() {
        val ui = open { Screen(hold = 200) }
        ui.click("toggle")
        ui.advanceBy(500)
        ui.render()

        val changed = ui.host.changedFrames
        repeat(20) { assertFalse(ui.render(), "frame $it is still") }
        assertEquals(changed, ui.host.changedFrames, "no frame changed")
        assertTrue(drawn(ui).flashes().isEmpty(), "and nothing flashes, the overlay included")
        val counted = mutableListOf<Int>()
        ui.root.forEach { counted += it.changes }
        ui.render()
        val after = mutableListOf<Int>()
        ui.root.forEach { after += it.changes }
        assertEquals(counted, after, "no count moved")
    }

    @Test
    fun `turning it off stops the counting it started`() {
        val ui = open { Screen() }
        ui.click("toggle")
        assertTrue(ui.host.tree.counting)

        ui.click("toggle")
        assertFalse(ui.host.tree.counting, "the overlay let go of the counts")
        val before = ui.node("count").changes
        ui.click("more")
        assertEquals(before, ui.node("count").changes)
    }

    @Test
    fun `a new hold picked while it is on keeps one hold on the counts`() {
        val ui = open {
            var debug by remember { mutableStateOf(true) }
            var hold by remember { mutableStateOf(500) }
            var count by remember { mutableStateOf(0) }
            Box(Modifier.fillMaxSize()) {
                Column {
                    Button("debug", onClick = { debug = !debug }, modifier = Modifier.testTag("toggle"))
                    Button("longer", onClick = { hold += 500 }, modifier = Modifier.testTag("longer"))
                    Button("more", onClick = { count++ }, modifier = Modifier.testTag("more"))
                    Text("count $count", modifier = Modifier.testTag("count"))
                }
                RedrawOverlay(debug, hold)
            }
        }
        repeat(3) { ui.click("longer") }
        assertTrue(ui.host.tree.counting, "still counting with the new hold")

        ui.click("more")
        ui.advanceBy(1200)
        assertTrue(ui.node("count").boundsInRoot in drawn(ui).flashes(), "the new hold is the one it fades by")

        ui.click("toggle")
        assertFalse(ui.host.tree.counting, "off lets go of the counts however many holds it had")
    }

    @Test
    fun `two overlays on one screen and one taken away leaves the other counting`() {
        val ui = open {
            var second by remember { mutableStateOf(true) }
            var count by remember { mutableStateOf(0) }
            Box(Modifier.fillMaxSize()) {
                Column {
                    Button("second", onClick = { second = !second }, modifier = Modifier.testTag("second"))
                    Button("more", onClick = { count++ }, modifier = Modifier.testTag("more"))
                    Text("count $count", modifier = Modifier.testTag("count"))
                }
                RedrawOverlay(true)
                RedrawOverlay(second)
            }
        }
        ui.click("second")
        assertTrue(ui.host.tree.counting, "the first is still there")

        ui.click("more")
        assertEquals(1, ui.node("count").changes)
        assertTrue(ui.node("count").boundsInRoot in drawn(ui).flashes())
    }

    @Test
    fun `a node added is what flashes and a removal flashes its parent`() {
        val ui = open {
            var shown by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Button("show", onClick = { shown = !shown }, modifier = Modifier.testTag("show"))
                Column(Modifier.offset(100f, 100f).size(120f, 120f).testTag("list")) {
                    Box(Modifier.size(50f, 20f).background(Colour.Blue))
                    if (shown) Box(Modifier.size(50f, 20f).background(Colour.Red).testTag("added"))
                }
                RedrawOverlay(true)
            }
        }

        ui.click("show")
        val flashes = drawn(ui).flashes()
        assertTrue(ui.node("added").boundsInRoot in flashes, "the new box: $flashes")
        assertFalse(ui.node("list").boundsInRoot in flashes, "not the column it went into")

        ui.advanceBy(600)
        ui.click("show")
        assertTrue(ui.node("list").boundsInRoot in drawn(ui).flashes(), "the column it left")
    }

    @Test
    fun `a subtree faded out is not flashed`() {
        val ui = open {
            var count by remember { mutableStateOf(0) }
            Box(Modifier.fillMaxSize()) {
                Button("more", onClick = { count++ }, modifier = Modifier.testTag("more"))
                Box(Modifier.offset(200f, 150f).alpha(0f)) {
                    Text("hidden $count", modifier = Modifier.testTag("hidden"))
                }
                RedrawOverlay(true)
            }
        }
        ui.click("more")

        assertEquals(1, ui.node("hidden").changes, "it changed")
        assertFalse(ui.node("hidden").boundsInRoot in drawn(ui).flashes(), "but nothing of it is drawn")
    }

    @Test
    fun `composed deep inside a padded box it still flashes in screen coordinates`() {
        val ui = open {
            var count by remember { mutableStateOf(0) }
            Box(Modifier.fillMaxSize()) {
                Button("more", onClick = { count++ }, modifier = Modifier.testTag("more"))
                Box(Modifier.offset(120f, 90f).size(100f, 80f).background(if (count > 0) Colour.Red else Colour.Blue).testTag("target"))
                Box(Modifier.offset(50f, 40f).padding(15f)) {
                    RedrawOverlay(true)
                }
            }
        }
        ui.click("more")

        assertTrue(Rect(120f, 90f, 220f, 170f) in drawn(ui).flashes(), "round the box where it is on screen")
    }

    @Test
    fun `a row sliding to its new place flashes while it moves and stops when it lands`() {
        var order by mutableStateOf(listOf("a", "b"))
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Column {
                    Button("reverse", onClick = { order = order.reversed() }, modifier = Modifier.testTag("reverse"))
                    order.forEach { name ->
                        key(name) {
                            Box(
                                Modifier.size(100f, 40f)
                                    .animatePlacement(Tween(durationMillis = 400, easing = Easings.Linear), Clock.World)
                                    .background(Colour.Grey)
                                    .testTag(name),
                            )
                        }
                    }
                }
                RedrawOverlay(true)
            }
        }
        ui.host.clocks.stop(Clock.World)
        ui.click("reverse")
        val before = ui.node("a").changes

        ui.host.clocks.start(Clock.World)
        ui.advanceBy(100)
        assertTrue(ui.node("a").changes >= before + 4, "a frame of sliding is a change to the row: ${ui.node("a").changes}")
        assertTrue(ui.node("a").boundsInRoot in drawn(ui).flashes(), "and it flashes where it has got to")

        ui.advanceBy(1000)
        val landed = ui.node("a").changes
        ui.advanceBy(500)
        assertEquals(landed, ui.node("a").changes, "a row at rest counts nothing more")
        assertFalse(ui.node("a").boundsInRoot in drawn(ui).flashes(), "and stops flashing")
    }

    @Test
    fun `a panel growing to new contents flashes while it grows and stops when it arrives`() {
        val ui = open {
            var long by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Column {
                    Button("grow", onClick = { long = true }, modifier = Modifier.testTag("grow"))
                    Column(Modifier.animateContentSize(Tween(durationMillis = 400, easing = Easings.Linear)).testTag("panel")) {
                        Box(Modifier.size(100f, if (long) 200f else 40f).background(Colour.Grey))
                    }
                }
                RedrawOverlay(true)
            }
        }
        ui.host.clocks.stop(Clock.Ui)
        ui.click("grow")
        ui.host.clocks.start(Clock.Ui)
        val before = ui.node("panel").changes

        ui.advanceBy(100)
        assertTrue(ui.node("panel").changes >= before + 4, "a frame of growing is a change to the panel: ${ui.node("panel").changes}")
        assertTrue(ui.node("panel").boundsInRoot in drawn(ui).flashes(), "and it flashes at the size it has got to")

        ui.advanceBy(1000)
        val arrived = ui.node("panel").changes
        ui.advanceBy(500)
        assertEquals(arrived, ui.node("panel").changes, "a panel at its size counts nothing more")
        assertFalse(ui.node("panel").boundsInRoot in drawn(ui).flashes(), "and stops flashing")
    }

    @Test
    fun `a label scrolling round inside its slot flashes as it scrolls`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Text(
                    "a title far too long for the little slot it has been given here",
                    modifier = Modifier.width(80f).marquee(delayMillis = 100).testTag("title"),
                )
                RedrawOverlay(true)
            }
        }
        ui.advanceBy(1000)
        val first = ui.node("title").changes
        ui.advanceBy(500)

        assertTrue(ui.node("title").changes >= first + 20, "each frame it moves is a change to the label: $first then ${ui.node("title").changes}")
        assertTrue(ui.node("title").boundsInRoot in drawn(ui).flashes())
    }

    @Test
    fun `the frame budget's numbers changing never flash`() {
        val budget = FrameBudget(window = 4, publishEveryMillis = 0L)
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                FrameBudgetOverlay(budget, Modifier.testTag("budget"))
                RedrawOverlay(true)
            }
        }
        val canvas = RecordingCanvas()
        val renderer = UiRenderer(ui.host, canvas, budget)
        repeat(10) {
            renderer.render(ui.viewport, ui.nanos)
            ui.settle()
        }

        val numbers = mutableListOf<UiNode>()
        ui.node("budget").forEach { if (it.changes > 0) numbers += it }
        assertTrue(numbers.isNotEmpty(), "the numbers did change and were counted")
        canvas.clear()
        renderer.render(ui.viewport, ui.nanos)
        assertTrue(canvas.calls.toList().flashes().isEmpty(), "but they are the overlay's own and do not flash")
    }

    @Test
    fun `with a layout overlay on as well neither marks the other`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(50f, 50f).size(30f, 30f))
                LayoutOverlay(true, setOf(Show.Bounds))
                RedrawOverlay(true)
            }
        }
        val calls = drawn(ui)

        val dots = calls.filterIsInstance<DrawCall.Rectangle>().filter { it.colour == LayoutOverlayColours.Bounds }
        assertTrue(dots.isEmpty(), "the layout overlay draws no dot for the redraw overlay: $dots")
        assertTrue(calls.flashes().isEmpty(), "and nothing has changed to flash")
    }
}
