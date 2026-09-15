package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `LayoutOverlay`: the layout of the whole screen drawn over it, which must change nothing about
 * that layout and must not be the reason the screen redraws.
 *
 * Every test composes a real screen with [uiTest], turns the overlay on the way a player would — a
 * click, a pad button — and reads the rectangles it drew back off a recording canvas, against the
 * nodes' own rectangles.
 */
class LayoutOverlayTest {

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

    private fun List<DrawCall>.outlines(colour: Colour) =
        filterIsInstance<DrawCall.Border>().filter { it.colour == colour }.map { it.rect }

    private fun List<DrawCall>.fills(colour: Colour) =
        filterIsInstance<DrawCall.Rectangle>().filter { it.colour == colour }.map { it.rect }

    private fun Iterable<Rect>.sorted() = sortedWith(compareBy({ it.left }, { it.top }, { it.right }, { it.bottom }))

    private fun isOverlay(call: DrawCall) = when (call) {
        is DrawCall.Border -> call.colour in OverlayInks
        is DrawCall.Rectangle -> call.colour in OverlayInks
        else -> false
    }

    /** Every node the overlay should outline: laid out, visible, and not the overlay. */
    private fun visibleNodes(node: UiNode, into: MutableList<UiNode> = mutableListOf()): List<UiNode> {
        if (node.name == OverlayName || node.resolved.alpha <= 0f) return into
        into += node
        node.children.forEach { visibleNodes(it, into) }
        return into
    }

    // --- the screen ------------------------------------------------------------------------------

    /**
     * A column of a toggle button and a padded row of three boxes, with the overlay behind the
     * toggle. The first box widens when clicked, so a test can move layout under the overlay.
     */
    @Composable
    private fun Screen(show: Set<Show> = Show.All, overlayFirst: Boolean = false) {
        var debug by remember { mutableStateOf(false) }
        var wide by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            if (overlayFirst) LayoutOverlay(debug, show)
            Column(
                Modifier.offset(20f, 30f).padding(10f).testTag("column"),
                verticalArrangement = Arrangement.spacedBy(8f),
            ) {
                Button("debug", onClick = { debug = !debug }, modifier = Modifier.testTag("toggle"), initialFocus = true)
                Row(
                    Modifier.padding(horizontal = 4f, vertical = 6f).testTag("row"),
                    horizontalArrangement = Arrangement.spacedBy(12f),
                ) {
                    Box(Modifier.size(if (wide) 90f else 60f, 40f).background(Colour.Blue).clickable { wide = !wide }.testTag("a"))
                    Box(Modifier.size(50f, 40f).background(Colour.Green).testTag("b"))
                    Box(Modifier.size(40f, 40f).testTag("empty"))
                }
            }
            if (!overlayFirst) LayoutOverlay(debug, show)
        }
    }

    // --- tests -----------------------------------------------------------------------------------

    @Test
    fun `off it composes nothing and draws nothing`() {
        val ui = open { Screen() }

        assertNull(ui.root.firstOrNull { it.name == OverlayName }, "no overlay node while it is off")
        assertTrue(drawn(ui).none(::isOverlay), "nothing in the overlay's colours")
    }

    @Test
    fun `a click turns it on and every node is outlined where layout put it`() {
        val ui = open { Screen() }
        ui.click("toggle")

        val expected = visibleNodes(ui.root).map { it.layoutBoundsInRoot }
        assertTrue(expected.size >= 7, "the screen has its nodes: ${expected.size}")
        assertEquals(expected.sorted(), drawn(ui).outlines(LayoutOverlayColours.Bounds).sorted())

        ui.click("toggle")
        assertTrue(drawn(ui).none(::isOverlay), "a second click takes it off")
    }

    @Test
    fun `the pad turns it on as well`() {
        val ui = open { Screen() }
        ui.assertFocused("toggle")

        ui.pad(GamepadButton.South)

        assertTrue(ui.node("a").layoutBoundsInRoot in drawn(ui).outlines(LayoutOverlayColours.Bounds))
    }

    @Test
    fun `turning it on moves nothing and a click still reaches what is under it`() {
        val tags = listOf("column", "toggle", "row", "a", "b", "empty")
        val ui = open { Screen() }
        val before = tags.associateWith { ui.node(it).boundsInRoot }

        ui.click("toggle")
        tags.forEach { assertEquals(before[it], ui.node(it).boundsInRoot, "#$it moved") }
        val overlay = ui.root.firstOrNull { it.name == OverlayName }!!
        assertEquals(0f, overlay.width)
        assertEquals(0f, overlay.height)

        // The overlay is over the box and the click lands on the box anyway, and what is drawn next
        // frame follows the box to its new width without anybody telling the overlay.
        assertTrue(ui.click("a"), "the box took the click")
        assertEquals(90f, ui.node("a").width)
        val outlines = drawn(ui).outlines(LayoutOverlayColours.Bounds)
        assertTrue(ui.node("a").layoutBoundsInRoot in outlines, "outlined at its new width")
        assertTrue(ui.node("b").layoutBoundsInRoot in outlines, "and its neighbour where it moved to")
        assertFalse(before.getValue("b") in outlines, "not where the neighbour used to be")
    }

    @Test
    fun `padding is shaded inside the box and nowhere else`() {
        val ui = open { Screen(show = setOf(Show.Padding)) }
        ui.click("toggle")

        val row = ui.node("row").layoutBoundsInRoot
        val column = ui.node("column").layoutBoundsInRoot
        val shaded = drawn(ui).fills(LayoutOverlayColours.Padding)
        val rowBands = listOf(
            Rect(row.left, row.top, row.right, row.top + 6f),
            Rect(row.left, row.bottom - 6f, row.right, row.bottom),
            Rect(row.left, row.top + 6f, row.left + 4f, row.bottom - 6f),
            Rect(row.right - 4f, row.top + 6f, row.right, row.bottom - 6f),
        )
        val columnBands = listOf(
            Rect(column.left, column.top, column.right, column.top + 10f),
            Rect(column.left, column.bottom - 10f, column.right, column.bottom),
            Rect(column.left, column.top + 10f, column.left + 10f, column.bottom - 10f),
            Rect(column.right - 10f, column.top + 10f, column.right, column.bottom - 10f),
        )
        rowBands.forEach { assertTrue(it in shaded, "row band $it in $shaded") }
        columnBands.forEach { assertTrue(it in shaded, "column band $it in $shaded") }
        // The boxes in the row have no padding of their own, so nothing is shaded over them.
        listOf("a", "b", "empty").forEach { tag ->
            val box = ui.node(tag).layoutBoundsInRoot
            assertTrue(shaded.none { it.overlaps(box) }, "#$tag is not shaded")
        }
    }

    @Test
    fun `the gaps a row and a column leave are shaded between the children`() {
        val ui = open { Screen(show = setOf(Show.Gaps)) }
        ui.click("toggle")

        val row = ui.node("row").layoutBoundsInRoot
        val a = ui.node("a").layoutBoundsInRoot
        val b = ui.node("b").layoutBoundsInRoot
        val empty = ui.node("empty").layoutBoundsInRoot
        val toggle = ui.node("toggle").layoutBoundsInRoot
        val column = ui.node("column").layoutBoundsInRoot

        val expected = listOf(
            Rect(a.right, row.top + 6f, b.left, row.bottom - 6f),
            Rect(b.right, row.top + 6f, empty.left, row.bottom - 6f),
            Rect(column.left + 10f, toggle.bottom, column.right - 10f, row.top),
        )
        val gaps = drawn(ui).fills(LayoutOverlayColours.Gaps)
        assertEquals(expected.sorted(), gaps.sorted())
        assertEquals(12f, gaps.sorted().first { it.top > toggle.bottom }.width, "the row's spacedBy(12)")
        assertEquals(8f, expected[2].height, "the column's spacedBy(8)")
    }

    @Test
    fun `a scaled node shows where it is drawn as well as where it was laid out`() {
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Button("debug", onClick = { debug = true }, modifier = Modifier.testTag("toggle"))
                Box(Modifier.offset(100f, 100f).size(80f, 40f).scale(0.5f).background(Colour.Red).testTag("scaled"))
                LayoutOverlay(debug, setOf(Show.Bounds, Show.Drawn))
            }
        }
        ui.click("toggle")
        val calls = drawn(ui)

        val scaled = ui.node("scaled")
        assertEquals(Rect(100f, 100f, 180f, 140f), scaled.layoutBoundsInRoot)
        assertEquals(Rect(120f, 110f, 160f, 130f), scaled.boundsInRoot)
        assertTrue(scaled.layoutBoundsInRoot in calls.outlines(LayoutOverlayColours.Bounds))
        assertEquals(listOf(scaled.boundsInRoot), calls.outlines(LayoutOverlayColours.Drawn), "only the scaled node differs")
    }

    @Test
    fun `painted ink is outlined where it differs from the box`() {
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Column {
                Button("debug", onClick = { debug = true }, modifier = Modifier.testTag("toggle"))
                // Painted inside its padding, so its ink is smaller than its box.
                Box(Modifier.size(100f, 60f).padding(10f).background(Colour.Red).testTag("inset"))
                // Paints nothing at all.
                Box(Modifier.size(50f, 50f).testTag("bare"))
                LayoutOverlay(debug, setOf(Show.Painted))
            }
        }
        ui.click("toggle")
        val painted = drawn(ui).outlines(LayoutOverlayColours.Painted)

        val inset = ui.node("inset").boundsInRoot
        assertTrue(Rect(inset.left + 10f, inset.top + 10f, inset.right - 10f, inset.bottom - 10f) in painted, "$painted")
        assertFalse(inset in painted, "not the box itself")
        val bare = ui.node("bare").boundsInRoot
        assertTrue(painted.none { it.overlaps(bare) }, "a box that painted nothing has no ink to outline")
    }

    @Test
    fun `show picks what is drawn`() {
        val ui = open { Screen(show = setOf(Show.Gaps)) }
        ui.click("toggle")
        val calls = drawn(ui)

        assertTrue(calls.fills(LayoutOverlayColours.Gaps).isNotEmpty())
        assertTrue(calls.outlines(LayoutOverlayColours.Bounds).isEmpty())
        assertTrue(calls.outlines(LayoutOverlayColours.Painted).isEmpty())
        assertTrue(calls.fills(LayoutOverlayColours.Padding).isEmpty())
    }

    @Test
    fun `it is drawn over everything even when it is composed first`() {
        val ui = open { Screen(overlayFirst = true) }
        ui.click("toggle")
        val calls = drawn(ui)

        val lastOfScreen = calls.indexOfLast { !isOverlay(it) }
        val firstOfOverlay = calls.indexOfFirst(::isOverlay)
        assertTrue(firstOfOverlay >= 0)
        assertTrue(firstOfOverlay > lastOfScreen, "overlay from $firstOfOverlay, screen until $lastOfScreen")
    }

    @Test
    fun `a still screen with it on is not redrawn and recomposing it changes nothing`() {
        var tick by mutableStateOf(0)
        var recomposed = 0
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Button("debug", onClick = { debug = true }, modifier = Modifier.testTag("toggle"))
                // Built from the tick, so every tick hands the overlay a new set object equal to the last,
                // the way a screen writing `setOf(...)` inline does. Whether the call is skipped by value
                // or runs and finds its remembered painter, the node must hear nothing.
                val show = Show.entries.filter { tick >= 0 && (it == Show.Bounds || it == Show.Gaps) }.toSet()
                SideEffect { recomposed++ }
                LayoutOverlay(debug, show)
            }
        }
        ui.click("toggle")
        ui.render()

        assertFalse(ui.render(), "a still frame")
        val changed = ui.host.changedFrames
        repeat(3) {
            val before = recomposed
            tick++
            assertFalse(ui.render(), "recomposed with the same arguments on tick $tick")
            assertTrue(recomposed > before, "tick $tick really recomposed the screen")
        }
        assertEquals(changed, ui.host.changedFrames)
        assertTrue(drawn(ui).outlines(LayoutOverlayColours.Bounds).isNotEmpty(), "and it is still drawing")
    }

    @Test
    fun `a subtree faded out is left out`() {
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Button("debug", onClick = { debug = true }, modifier = Modifier.testTag("toggle"))
                Box(Modifier.offset(200f, 150f).size(60f, 60f).alpha(0f).testTag("gone")) {
                    Box(Modifier.size(20f, 20f).testTag("inside"))
                }
                LayoutOverlay(debug)
            }
        }
        ui.click("toggle")
        val outlines = drawn(ui).outlines(LayoutOverlayColours.Bounds)

        assertFalse(ui.node("gone").layoutBoundsInRoot in outlines)
        assertFalse(ui.node("inside").layoutBoundsInRoot in outlines)
        assertTrue(ui.node("toggle").layoutBoundsInRoot in outlines)
    }

    @Test
    fun `composed deep inside a padded box it still draws in screen coordinates`() {
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Button("debug", onClick = { debug = true }, modifier = Modifier.testTag("toggle"))
                Box(Modifier.offset(120f, 90f).size(100f, 80f).testTag("target"))
                Box(Modifier.offset(50f, 40f).padding(15f)) {
                    LayoutOverlay(debug, setOf(Show.Bounds))
                }
            }
        }
        ui.click("toggle")

        assertTrue(Rect(120f, 90f, 220f, 170f) in drawn(ui).outlines(LayoutOverlayColours.Bounds))
    }

    @Test
    fun `a parent's painted rectangle is not stretched to reach the overlay`() {
        val ui = open {
            Box(Modifier.fillMaxSize().testTag("screen")) {
                Box(Modifier.offset(200f, 200f).size(40f, 40f).background(Colour.Red))
                LayoutOverlay(true)
            }
        }

        assertEquals(Rect(200f, 200f, 240f, 240f), ui.node("screen").paintedInRoot)
    }

    @Test
    fun `painted or drawn on their own still mark a node whose rectangle matches its box`() {
        val ui = open {
            var debug by remember { mutableStateOf(setOf<Show>()) }
            Column {
                Button("painted", onClick = { debug = setOf(Show.Painted) }, modifier = Modifier.testTag("painted"))
                Button("drawn", onClick = { debug = setOf(Show.Drawn) }, modifier = Modifier.testTag("drawn"))
                // Painted right to its edges and not scaled: ink, drawn box and laid-out box are one rectangle.
                Box(Modifier.size(70f, 30f).background(Colour.Red).testTag("solid"))
                LayoutOverlay(debug.isNotEmpty(), debug)
            }
        }
        val solid = ui.node("solid").layoutBoundsInRoot

        ui.click("painted")
        val painted = drawn(ui)
        assertTrue(solid in painted.outlines(LayoutOverlayColours.Painted), "pink with no blue edge to stand in for it")
        assertTrue(painted.outlines(LayoutOverlayColours.Bounds).isEmpty())

        ui.click("drawn")
        val placed = drawn(ui)
        assertTrue(solid in placed.outlines(LayoutOverlayColours.Drawn), "yellow with no blue edge to stand in for it")
        assertTrue(placed.outlines(LayoutOverlayColours.Painted).isEmpty())
    }

    @Test
    fun `a node with no width is drawn as a line`() {
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Button("debug", onClick = { debug = true }, modifier = Modifier.testTag("toggle"))
                Box(Modifier.offset(150f, 60f).size(0f, 30f).testTag("thin"))
                LayoutOverlay(debug, setOf(Show.Bounds))
            }
        }
        ui.click("toggle")

        assertEquals(Rect(150f, 60f, 150f, 90f), ui.node("thin").layoutBoundsInRoot)
        assertTrue(Rect(150f, 60f, 151f, 90f) in drawn(ui).fills(LayoutOverlayColours.Bounds), "a one-unit line")
    }

    @Test
    fun `alone on an empty screen it outlines only the screen`() {
        val ui = open { LayoutOverlay(true) }

        val calls = drawn(ui)
        assertEquals(listOf(ui.root.layoutBoundsInRoot), calls.outlines(LayoutOverlayColours.Bounds))
        assertTrue(calls.fills(LayoutOverlayColours.Bounds).isEmpty(), "no dot where the overlay itself sits")
    }

    @Test
    fun `two overlays on at once do not mark each other`() {
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Button("debug", onClick = { debug = !debug }, modifier = Modifier.testTag("toggle"))
                Box(Modifier.offset(80f, 120f).padding(10f)) {
                    LayoutOverlay(debug, setOf(Show.Bounds))
                }
                LayoutOverlay(debug, setOf(Show.Bounds))
            }
        }
        ui.click("toggle")
        val calls = drawn(ui)

        assertTrue(calls.fills(LayoutOverlayColours.Bounds).isEmpty(), "no one-unit dot for either overlay: ${calls.fills(LayoutOverlayColours.Bounds)}")
        val toggle = ui.node("toggle").layoutBoundsInRoot
        assertEquals(2, calls.outlines(LayoutOverlayColours.Bounds).count { it == toggle }, "each draws the screen once")

        ui.click("toggle")
        assertTrue(drawn(ui).none(::isOverlay), "and both go when turned off")
    }

    private companion object {
        val OverlayInks = setOf(
            LayoutOverlayColours.Bounds,
            LayoutOverlayColours.Drawn,
            LayoutOverlayColours.Painted,
            LayoutOverlayColours.Padding,
            LayoutOverlayColours.Gaps,
        )
    }
}
