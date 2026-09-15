package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.Grid
import dev.wildware.composegl.ui.layout.GridCells
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.describePolicy
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Inspector`: point at a node, read what it is, pin it, walk the tree from it.
 *
 * Every test composes a real screen with [uiTest] and drives it with the pointer, the keyboard and
 * the pad, then reads the panel's text and the outlines drawn on a recording canvas.
 */
class InspectorTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private var inspecting by mutableStateOf(true)
    private var wide by mutableStateOf(false)
    private var showC by mutableStateOf(true)
    private var plays = 0

    private fun open(content: @Composable () -> Unit = { Screen() }): UiTest =
        uiTest(Size(800f, 500f), content = content).also { opened += it }

    /**
     * A padded column of a button and a padded row of three boxes, at 20, 30, with the panel well
     * off to the right of it.
     */
    @Composable
    private fun Screen() {
        Inspector(enabled = inspecting) {
            Box(Modifier.fillMaxSize().testTag("screen")) {
                Column(
                    Modifier.offset(20f, 30f).padding(10f).testTag("column"),
                    verticalArrangement = Arrangement.spacedBy(8f),
                ) {
                    var count by remember { mutableStateOf(0) }
                    Button("play $count", onClick = { count++; plays++ }, modifier = Modifier.testTag("play"))
                    Row(
                        Modifier.padding(horizontal = 4f, vertical = 6f).testTag("row"),
                        horizontalArrangement = Arrangement.spacedBy(12f),
                    ) {
                        Box(Modifier.size(if (wide) 90f else 60f, 40f).background(Colour.Blue).testTag("a"))
                        Box(Modifier.size(50f, 40f).testTag("b"))
                        if (showC) Box(Modifier.size(40f, 40f).testTag("c"))
                    }
                }
            }
        }
    }

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

    private fun UiTest.title() = text(InspectorTags.Title)
    private fun UiTest.heading() = text(InspectorTags.Heading)
    private fun UiTest.facts() = texts(InspectorTags.Facts)

    // --- off ---------------------------------------------------------------------------------------

    @Test
    fun `off it adds nothing and the screen takes the pointer as usual`() {
        inspecting = false
        val ui = open()

        ui.assertDoesNotExist(InspectorTags.Layer)
        ui.assertDoesNotExist(InspectorTags.Panel)
        assertTrue(ui.click("play"))
        assertEquals(1, plays)
        assertTrue(drawn(ui).outlines(InspectorColours.Hover).isEmpty())
    }

    // --- hover -------------------------------------------------------------------------------------

    @Test
    fun `hovering outlines the deepest node under the pointer and names it`() {
        val ui = open()
        ui.assertText(InspectorTags.Heading, "point at something")

        ui.moveTo("a")

        assertEquals("hovered", ui.heading())
        assertTrue(ui.title().endsWith("#a"), ui.title())
        val calls = drawn(ui)
        assertEquals(listOf(ui.node("a").boundsInRoot), calls.outlines(InspectorColours.Hover))
        assertTrue(ui.node("a").boundsInRoot in calls.fills(InspectorColours.HoverWash))

        // In the row's padding, beside its children, the row itself is what is under the pointer.
        val row = ui.node("row").boundsInRoot
        ui.moveTo(Offset(row.left + 2f, row.top + 2f))
        assertTrue(ui.title().endsWith("#row"), ui.title())
        assertEquals(listOf(row), drawn(ui).outlines(InspectorColours.Hover))
    }

    @Test
    fun `the panel shows position size constraints padding policy and the modifier chain in order`() {
        val ui = open()
        val row = ui.node("row")
        val box = row.layoutBoundsInRoot
        ui.moveTo(Offset(box.left + 1f, box.top + 1f))

        val facts = ui.facts()
        assertEquals("at ${box.left.toInt()},${box.top.toInt()}", facts[0])
        assertEquals("size ${row.width.toInt()}x${row.height.toInt()}", facts[1])
        assertTrue(facts.any { it.startsWith("given ") && it.contains("x") }, "$facts")
        assertTrue("padding 4,6,4,6" in facts, "$facts")
        assertTrue("policy Row spaced 12" in facts, "$facts")
        assertTrue("children 3" in facts, "$facts")
        assertEquals(listOf("1. padding(4,6,4,6)", "2. testTag(\"row\")"), ui.texts(InspectorTags.Modifiers))

        ui.moveTo("a")
        assertEquals(
            listOf("1. size(60x40)", "2. background(#FF0000FF)", "3. testTag(\"a\")"),
            ui.texts(InspectorTags.Modifiers),
        )
        assertTrue("padding none" in ui.facts(), "${ui.facts()}")
        assertTrue("policy Stack" in ui.facts() || ui.facts().any { it.startsWith("policy Box") }, "${ui.facts()}")
        assertTrue("children 0" in ui.facts(), "${ui.facts()}")
    }

    @Test
    fun `the padding of the hovered node is shaded inside its outline`() {
        val ui = open()
        val column = ui.node("column").boundsInRoot
        ui.moveTo(Offset(column.left + 3f, column.top + 3f))

        val shaded = drawn(ui).fills(InspectorColours.Padding)
        assertTrue(Rect(column.left, column.top, column.right, column.top + 10f) in shaded, "$shaded")
        assertTrue(Rect(column.left, column.bottom - 10f, column.right, column.bottom) in shaded, "$shaded")
        assertTrue(shaded.none { it.overlaps(ui.node("a").boundsInRoot) }, "nothing over a child")
    }

    @Test
    fun `while it is on the screen hears nothing from the pointer`() {
        val ui = open()

        ui.click("play")

        assertEquals(0, plays, "the button was not pressed")
        assertEquals("pinned", ui.heading())
        // The deepest node under the middle of the button is its label.
        assertEquals("text", ui.title())
        ui.key(Key.Up)
        assertTrue(ui.title().endsWith("#play"), ui.title())
        assertEquals(0, plays, "and the key did not press it either")
    }

    @Test
    fun `moving onto the panel keeps the last node in it so the tree holds still`() {
        val ui = open()
        ui.moveTo("a")
        assertEquals("hovered", ui.heading())
        val line = ui.node(InspectorTags.row("c")).boundsInRoot

        val panel = ui.node(InspectorTags.Panel).boundsInRoot
        assertTrue(panel.left > ui.node("column").boundsInRoot.right, "the panel is clear of the screen")
        ui.moveTo(Offset(panel.left + 4f, panel.top + 4f))
        assertTrue(ui.title().endsWith("#a"), ui.title())
        assertEquals(line, ui.node(InspectorTags.row("c")).boundsInRoot)

        // Onto a line: its node is outlined, and the panel still says a, so nothing moves.
        ui.moveTo(InspectorTags.row("c"))
        assertTrue(ui.title().endsWith("#a"), ui.title())
        assertEquals(line, ui.node(InspectorTags.row("c")).boundsInRoot)
        assertEquals(listOf(ui.node("c").boundsInRoot), drawn(ui).outlines(InspectorColours.Hover))

        // And back onto the screen, the screen's node again.
        ui.moveTo("b")
        assertTrue(ui.title().endsWith("#b"), ui.title())
        assertEquals(listOf(ui.node("b").boundsInRoot), drawn(ui).outlines(InspectorColours.Hover))
    }

    @Test
    fun `a faded subtree is not what the pointer finds`() {
        val ui = open {
            Inspector(true) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.offset(50f, 50f).size(100f, 100f).testTag("under"))
                    Box(Modifier.offset(50f, 50f).size(100f, 100f).alpha(0f).testTag("ghost"))
                }
            }
        }
        ui.moveTo(Offset(100f, 100f))

        assertTrue(ui.title().endsWith("#under"), ui.title())
    }

    @Test
    fun `a scaled node reports where it is drawn`() {
        val ui = open {
            Inspector(true) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.offset(100f, 100f).size(80f, 40f).scale(0.5f).background(Colour.Red).testTag("scaled"))
                }
            }
        }
        ui.moveTo(Offset(140f, 120f))

        assertTrue(ui.title().endsWith("#scaled"))
        assertTrue("drawn 120,110 40x20" in ui.facts(), "${ui.facts()}")
        assertEquals(listOf(Rect(120f, 110f, 160f, 130f)), drawn(ui).outlines(InspectorColours.Hover))
    }

    // --- pinning -----------------------------------------------------------------------------------

    @Test
    fun `a click pins so the pointer can leave and a second click lets go`() {
        val ui = open()
        ui.click("a")
        ui.moveTo("b")

        assertEquals("pinned", ui.heading())
        assertTrue(ui.title().endsWith("#a"))
        val calls = drawn(ui)
        assertEquals(listOf(ui.node("a").boundsInRoot), calls.outlines(InspectorColours.Pinned))
        assertEquals(listOf(ui.node("b").boundsInRoot), calls.outlines(InspectorColours.Hover), "b is still hovered")

        ui.click("a")
        assertEquals("hovered", ui.heading())
        assertTrue(drawn(ui).outlines(InspectorColours.Pinned).isEmpty())
    }

    @Test
    fun `a pinned node that changes size shows its new size without being picked again`() {
        val ui = open()
        ui.click("a")
        assertTrue("size 60x40" in ui.facts())

        wide = true
        ui.settle()

        assertEquals(90f, ui.node("a").width)
        assertTrue("size 90x40" in ui.facts(), "${ui.facts()}")
        assertTrue(ui.node("a").boundsInRoot in drawn(ui).outlines(InspectorColours.Pinned))
    }

    @Test
    fun `a pinned node taken off the screen is let go`() {
        val ui = open()
        ui.click("c")
        assertTrue(ui.title().endsWith("#c"))

        showC = false
        ui.settle()

        assertEquals("point at something", ui.heading())
        ui.assertDoesNotExist(InspectorTags.Title)
    }

    @Test
    fun `a still screen with it on and a node pinned is not redrawn`() {
        val ui = open()
        ui.click("a")
        ui.render()

        val changed = ui.host.changedFrames
        repeat(5) { assertFalse(ui.render(), "frame $it was still") }
        ui.advanceBy(500)
        assertEquals(changed, ui.host.changedFrames)
    }

    // --- walking the tree --------------------------------------------------------------------------

    @Test
    fun `arrow keys walk from the pinned node to its parent child and siblings`() {
        val ui = open()
        ui.click("b")

        ui.key(Key.Left)
        assertTrue(ui.title().endsWith("#a"), ui.title())
        ui.key(Key.Right)
        ui.key(Key.Right)
        assertTrue(ui.title().endsWith("#c"), ui.title())
        ui.key(Key.Right)
        assertTrue(ui.title().endsWith("#c"), "no sibling after the last")

        ui.key(Key.Up)
        assertTrue(ui.title().endsWith("#row"), ui.title())
        ui.key(Key.Up)
        assertTrue(ui.title().endsWith("#column"), ui.title())
        ui.key(Key.Up)
        ui.key(Key.Up)
        assertTrue(ui.title().endsWith("#screen"), "not up out of the screen: ${ui.title()}")

        ui.key(Key.Down)
        assertTrue(ui.title().endsWith("#column"), ui.title())
        ui.key(Key.Down)
        assertTrue(ui.title().endsWith("#play"), ui.title())
        assertEquals(listOf(ui.node("play").boundsInRoot), drawn(ui).outlines(InspectorColours.Pinned))

        assertTrue(ui.key(Key.Escape))
        // The pointer is still over b, where the click was, so b is hovered again.
        assertEquals("hovered", ui.heading())
        assertTrue(ui.title().endsWith("#b"), ui.title())
        // With nothing pinned, Escape is the screen's again: the inspector leaves it alone.
        ui.key(Key.Escape)
        assertEquals("hovered", ui.heading(), "nothing left to let go of")
    }

    @Test
    fun `the d-pad walks the tree too and East lets go`() {
        val ui = open()
        ui.click("a")

        ui.pad(GamepadButton.DpadRight)
        assertTrue(ui.title().endsWith("#b"), ui.title())
        ui.pad(GamepadButton.DpadUp)
        assertTrue(ui.title().endsWith("#row"), ui.title())
        ui.pad(GamepadButton.DpadDown)
        assertTrue(ui.title().endsWith("#a"), ui.title())

        ui.pad(GamepadButton.East)
        assertEquals("hovered", ui.heading(), "let go, with the pointer still over a")
    }

    @Test
    fun `arrow keys with nothing shown go to the screen as usual`() {
        val ui = open()
        ui.key(Key.Up)
        ui.pad(GamepadButton.DpadDown)
        assertEquals("point at something", ui.heading(), "nothing was pinned by a key")
        ui.assertDoesNotExist(InspectorTags.Title)
    }

    @Test
    fun `a node only hovered leaves the arrow keys and the pad to the screen`() {
        val keys = mutableListOf<Key>()
        val ui = open {
            Inspector(true) {
                Box(
                    Modifier.fillMaxSize().testTag("world").onKeyEvent {
                        if (it.type == KeyEventType.Down) keys += it.key
                        true
                    }.focusable(initial = true),
                )
            }
        }
        ui.moveTo("world")
        assertEquals("hovered", ui.heading())

        ui.key(Key.Up)
        ui.key(Key.Escape)
        ui.pad(GamepadButton.DpadDown)

        assertEquals(listOf(Key.Up, Key.Escape), keys, "the game heard its keys")
        assertEquals("hovered", ui.heading(), "and nothing was pinned by them")
    }

    @Test
    fun `the tree lists the screen and a line pins its node`() {
        val ui = open()
        listOf("screen", "column", "play", "row", "a", "b", "c").forEach { ui.assertExists(InspectorTags.row(it)) }
        val a = ui.node(InspectorTags.row("a")).boundsInRoot
        val row = ui.node(InspectorTags.row("row")).boundsInRoot
        assertTrue(a.left >= row.left && a.top > row.top, "a is under its row")
        assertTrue(ui.text(InspectorTags.row("a")).contains("#a  60x40"), ui.text(InspectorTags.row("a")))

        ui.moveTo(InspectorTags.row("b"))
        assertEquals(listOf(ui.node("b").boundsInRoot), drawn(ui).outlines(InspectorColours.Hover), "pointing at a line outlines its node")

        ui.click(InspectorTags.row("b"))
        assertEquals("pinned", ui.heading())
        assertTrue(ui.title().endsWith("#b"))
        assertEquals(0, plays)
    }

    @Test
    fun `a branch folds away and back and the whole tree hides`() {
        val ui = open()

        ui.click(InspectorTags.fold("row"))
        ui.assertExists(InspectorTags.row("row"))
        listOf("a", "b", "c").forEach { ui.assertDoesNotExist(InspectorTags.row(it)) }
        assertEquals("+", ui.text(InspectorTags.fold("row")))

        ui.click(InspectorTags.fold("row"))
        ui.assertExists(InspectorTags.row("a"))

        ui.click(InspectorTags.TreeToggle)
        ui.assertDoesNotExist(InspectorTags.Tree)
        ui.click(InspectorTags.TreeToggle)
        ui.assertExists(InspectorTags.row("a"))
    }

    @Test
    fun `the panel moves to the other side`() {
        val ui = open()
        assertEquals(800f - 8f, ui.node(InspectorTags.Panel).boundsInRoot.right)

        ui.click(InspectorTags.Side)

        assertEquals(8f, ui.node(InspectorTags.Panel).boundsInRoot.left)
    }

    // --- on and off --------------------------------------------------------------------------------

    @Test
    fun `turning it on and off keeps the screen's state and moves nothing`() {
        inspecting = false
        val ui = open()
        ui.click("play")
        ui.click("play")
        assertEquals("play 2", ui.text("play"))
        val before = listOf("column", "row", "a", "b", "c").associateWith { ui.node(it).boundsInRoot }

        inspecting = true
        ui.settle()
        ui.click("a")
        before.forEach { (tag, rect) -> assertEquals(rect, ui.node(tag).boundsInRoot, "#$tag moved") }
        assertEquals("pinned", ui.heading())

        inspecting = false
        ui.settle()
        ui.assertDoesNotExist(InspectorTags.Panel)
        assertEquals("play 2", ui.text("play"))
        ui.click("play")
        assertEquals("play 3", ui.text("play"))

        inspecting = true
        ui.settle()
        assertEquals("point at something", ui.heading(), "nothing is still pinned from last time")
    }

    // --- the words -----------------------------------------------------------------------------------

    @Test
    fun `each kind of layout is named as it is written`() {
        val ui = open {
            Inspector(true) {
                Column(Modifier.testTag("column")) {
                    Row(Modifier.testTag("row")) { Text("x") }
                    Box(Modifier.testTag("box")) { Text("x") }
                    FlowRow(Modifier.testTag("flow")) { Text("x") }
                    Grid(GridCells.Fixed(2), Modifier.testTag("grid")) { Text("x") }
                    Layout(Modifier.testTag("custom"), measurePolicy = MeasurePolicy { _, c -> layout(c.minWidth, 10f) {} })
                }
            }
        }
        assertEquals("Column", describePolicy(ui.node("column").measurePolicy))
        assertEquals("Row", describePolicy(ui.node("row").measurePolicy))
        assertEquals("Box", describePolicy(ui.node("box").measurePolicy))
        assertEquals("FlowRow", describePolicy(ui.node("flow").measurePolicy))
        assertEquals("Grid", describePolicy(ui.node("grid").measurePolicy))
        assertEquals("Stack", describePolicy(MeasurePolicy.Stack))
        assertEquals("Empty", describePolicy(MeasurePolicy.Empty))
        assertEquals("custom", describePolicy(ui.node("custom").measurePolicy))
    }

    @Test
    fun `scrolling goes through to the screen`() {
        var scrolled = 0
        val ui = open {
            Inspector(true) {
                Box(
                    Modifier.fillMaxSize().testTag("world").then(
                        Modifier.onPointer {
                            if (it is PointerEvent.Scroll) scrolled++
                            it is PointerEvent.Scroll
                        },
                    ),
                )
            }
        }
        ui.scroll("world", Offset(0f, 10f))

        assertEquals(1, scrolled)
    }
}
