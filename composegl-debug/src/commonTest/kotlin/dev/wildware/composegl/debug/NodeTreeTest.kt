package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `NodeTree`: browse the whole screen, find what cannot be pointed at, and see what keeps changing.
 *
 * Every test composes a real screen with [uiTest] — an inspector over a little game, and the tree
 * beside it — and drives it with the pointer, the keyboard and the pad, then reads the rows off the
 * screen the way a player would.
 */
class NodeTreeTest {

    /** Every tag on the little screen, top to bottom, so "which rows are open" is one list. */
    private val Tagged = listOf("screen", "stack", "play", "score", "row", "a", "nothing", "b")

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private var inspection = InspectorState()
    private var trees = NodeTreeState()
    private var inspecting by mutableStateOf(true)
    private var showB by mutableStateOf(true)
    private var score by mutableStateOf(0)
    private var rtl by mutableStateOf(false)

    private fun open(): UiTest {
        inspection = InspectorState()
        trees = NodeTreeState()
        return uiTest(Size(800f, 500f)) { Screen() }.also { opened += it }
    }

    /**
     * A little screen in the top left, with the tree in the bottom left so that nothing the tests
     * point at is under it, and the inspector's own panel away in the top right corner.
     */
    @Composable
    private fun Screen() {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            Box(Modifier.fillMaxSize()) {
                Inspector(enabled = inspecting, state = inspection) {
                    Box(Modifier.fillMaxSize().testTag("screen")) {
                        Column(Modifier.offset(20f, 20f).testTag("stack")) {
                            Button("play", onClick = {}, modifier = Modifier.testTag("play"))
                            Text("score $score", modifier = Modifier.testTag("score"))
                            Row(Modifier.testTag("row")) {
                                Box(Modifier.size(60f, 40f).testTag("a"))
                                Box(Modifier.size(0f, 0f).testTag("nothing"))
                                if (showB) Box(Modifier.size(50f, 40f).testTag("b"))
                            }
                        }
                    }
                }
                NodeTree(
                    inspector = inspection,
                    modifier = Modifier.align(Alignment.BottomStart).width(360f).height(300f),
                    state = trees,
                )
            }
        }
    }

    // --- reading the screen -------------------------------------------------------------------------

    /** Every row of the tree, as the words on it. */
    private fun UiTest.rows(): List<String> = named("tree.row").map { wordsOf(it) }

    private fun UiTest.hasRow(tag: String): Boolean = root.findOrNull(NodeTreeTags.row(tag)) != null

    private fun UiTest.row(tag: String): UiNode =
        root.findOrNull(NodeTreeTags.row(tag)) ?: throw AssertionError("no row for #$tag:\n" + dump())

    private fun UiTest.named(name: String): List<UiNode> {
        val found = mutableListOf<UiNode>()
        root.forEach { if (it.name == name) found += it }
        return found
    }

    /** The words [node] and everything inside it draws, in one string. */
    private fun UiTest.wordsOf(node: UiNode): String {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
        val bounds = node.layoutBoundsInRoot
        DrawPass(canvas).draw(node, bounds.left - node.x, bounds.top - node.y)
        return canvas.texts().joinToString(" ")
    }

    /** One more frame, and the colour the counts on [tag]'s row came out in. */
    private fun UiTest.countsDrawn(tag: String): Colour {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear()
        render()
        val box = node(NodeTreeTags.counts(tag)).boundsInRoot
        val call = canvas.calls.filterIsInstance<DrawCall.Text>().lastOrNull { it.at in box }
        return assertNotNull(call, "nothing was drawn in the counts box $box:\n" + dump()).colour
    }

    /** How far a colour is from [RedrawColour], so "redder than" can be asserted whatever the skin. */
    private fun Colour.fromRed(): Int =
        abs(red - RedrawColour.red) + abs(green - RedrawColour.green) + abs(blue - RedrawColour.blue)

    /** The tags whose rows are showing, in the order the screen lists them. */
    private fun UiTest.openTags(): List<String> = Tagged.filter { hasRow(it) }

    /** The tree widget's own row node a tagged row sits inside. */
    private fun rowBox(content: UiNode): UiNode {
        var walk: UiNode? = content.parent
        while (walk != null && walk.name != "tree.row") walk = walk.parent
        return checkNotNull(walk) { "the row is not inside a tree row" }
    }

    // --- the rows -----------------------------------------------------------------------------------

    @Test
    fun `each row says what the node is and how big it is`() {
        val ui = open()
        ui.moveTo("a")

        assertEquals(listOf("box #a", "60x40"), ui.texts(NodeTreeTags.row("a")).take(2))
        assertTrue(ui.hasRow("row"), "the nodes above it are there to get down to it:\n" + ui.dump())
        assertTrue(ui.hasRow("screen"))
    }

    @Test
    fun `the rows are what is under the screen and never the tree itself`() {
        val ui = open()
        ui.moveTo("a")

        val rows = ui.rows()
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.none { "nodetree" in it }, "the tree does not list its own nodes: $rows")
        assertTrue(rows.none { "inspector" in it }, "nor the inspector's: $rows")
    }

    @Test
    fun `with the inspector off there is nothing to show`() {
        inspecting = false
        val ui = open()

        ui.assertExists(NodeTreeTags.Nothing)
        assertEquals("nothing on screen", ui.text(NodeTreeTags.Nothing))
    }

    // --- following the pointer ----------------------------------------------------------------------

    @Test
    fun `the rows open down to whatever the pointer is over`() {
        val ui = open()
        assertFalse(ui.hasRow("a"), "everything starts closed:\n" + ui.dump())

        ui.moveTo("a")
        assertTrue(ui.hasRow("a"), "the path down to the hovered node opened:\n" + ui.dump())

        ui.moveTo("play")
        assertTrue(ui.hasRow("play"))
    }

    // --- choosing a row -----------------------------------------------------------------------------

    @Test
    fun `clicking a row pins that node and fills the inspector panel`() {
        val ui = open()
        ui.moveTo("a")

        ui.click(NodeTreeTags.row("a"))

        assertEquals(ui.node("a"), inspection.pinned)
        assertEquals("pinned", ui.text(InspectorTags.Heading))
        assertTrue(ui.text(InspectorTags.Title).endsWith("#a"), ui.text(InspectorTags.Title))
    }

    @Test
    fun `the pinned node is outlined on the screen`() {
        val ui = open()
        ui.moveTo("a")
        ui.click(NodeTreeTags.row("a"))

        val canvas = ui.backend.canvas as RecordingCanvas
        canvas.clear()
        ui.render()
        val outlines = canvas.calls.filterIsInstance<DrawCall.Border>()
            .filter { it.colour == InspectorColours.Pinned }
            .map { it.rect }
        assertTrue(ui.node("a").boundsInRoot in outlines, "$outlines")
    }

    // --- the filter ---------------------------------------------------------------------------------

    @Test
    fun `the filter keeps what matches by tag and the way down to it`() {
        val ui = open()
        ui.moveTo("a")

        ui.click(NodeTreeTags.Filter)
        ui.type("#b")

        assertTrue(ui.hasRow("b"), "the match is there:\n" + ui.dump())
        assertTrue(ui.hasRow("row"), "and the node above it")
        assertFalse(ui.hasRow("a"), "and nothing else")
    }

    @Test
    fun `the filter matches a name as well as a tag`() {
        val ui = open()
        ui.moveTo("a")

        ui.click(NodeTreeTags.Filter)
        ui.type("column")

        assertTrue(ui.hasRow("stack"), "the column matched by name:\n" + ui.dump())
        assertFalse(ui.hasRow("a"))
    }

    @Test
    fun `clearing the filter puts the rows back the way they were`() {
        val ui = open()
        ui.moveTo("a")
        val before = ui.openTags()

        ui.click(NodeTreeTags.Filter)
        ui.type("#b")
        assertFalse(ui.hasRow("a"))
        ui.key(Key.Backspace)
        ui.key(Key.Backspace)

        assertEquals(before, ui.openTags(), "the same rows are open again:\n" + ui.dump())
    }

    // --- zero-sized nodes ---------------------------------------------------------------------------

    @Test
    fun `the switch leaves out the nodes with no size`() {
        val ui = open()
        ui.moveTo("a")
        assertTrue(ui.hasRow("nothing"), "it is there to start with")

        ui.click(NodeTreeTags.HideEmpty)

        assertFalse(ui.hasRow("nothing"), "gone:\n" + ui.dump())
        assertTrue(ui.hasRow("a"), "and the ones with a size stayed")
    }

    // --- the counts ---------------------------------------------------------------------------------

    @Test
    fun `a row counts the frames that changed its node`() {
        val ui = open()
        ui.moveTo("score")
        assertFalse(
            ui.root.findOrNull(NodeTreeTags.counts("score")) != null,
            "nothing has changed it yet:\n" + ui.dump(),
        )

        score++
        ui.settle()
        assertEquals("c1", ui.text(NodeTreeTags.counts("score")))

        score++
        ui.settle()
        assertEquals("c2", ui.text(NodeTreeTags.counts("score")))
    }

    @Test
    fun `the counts go red on the frame they tick and fade back`() {
        val ui = open()
        ui.moveTo("score")
        score++
        ui.settle()
        val cold = ui.countsDrawn("score")

        score++
        var hot = cold
        repeat(4) {
            val now = ui.countsDrawn("score")
            if (now.fromRed() < hot.fromRed()) hot = now
        }

        assertTrue(hot.fromRed() < cold.fromRed(), "the counts went red when they ticked: $cold then $hot")
        ui.advanceBy(2000)
        assertEquals(cold, ui.countsDrawn("score"), "and faded back")
    }

    @Test
    fun `counting is on while the tree is up and off once it has gone`() {
        val ui = open()
        ui.moveTo("a")
        val tree = assertNotNull(ui.node("a").tree)
        assertTrue(tree.counting, "the tree asked for the counts")

        inspecting = false
        ui.settle()

        assertFalse(tree.counting, "and let go of them")
    }

    // --- the keyboard and the pad -------------------------------------------------------------------

    @Test
    fun `the arrow keys walk the rows and Enter chooses one`() {
        val ui = open()
        ui.moveTo("a")
        ui.click(NodeTreeTags.row("row"))
        inspection.pin(null)

        ui.key(Key.Down)
        ui.key(Key.Enter)

        assertEquals(ui.node("a"), inspection.pinned, "Down then Enter pinned the first child:\n" + ui.dump())
    }

    @Test
    fun `the d-pad walks the rows and South chooses one`() {
        val ui = open()
        ui.moveTo("a")
        ui.click(NodeTreeTags.row("row"))
        inspection.pin(null)

        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.South)

        assertEquals(ui.node("a"), inspection.pinned)
    }

    @Test
    fun `Left closes a row and Right opens it again`() {
        val ui = open()
        ui.moveTo("a")
        ui.click(NodeTreeTags.row("row"))

        ui.key(Key.Left)
        assertFalse(ui.hasRow("a"), "Left closed it:\n" + ui.dump())

        ui.key(Key.Right)
        assertTrue(ui.hasRow("a"), "Right opened it again:\n" + ui.dump())
    }

    // --- right to left ------------------------------------------------------------------------------

    @Test
    fun `the rows indent from the other side in a right-to-left screen`() {
        val ui = open()
        ui.moveTo("a")
        val ltr = ui.row("a").boundsInRoot
        val ltrBox = rowBox(ui.row("a")).boundsInRoot
        assertTrue(ltr.left - ltrBox.left > 1f, "indented from the left: $ltrBox then $ltr")

        rtl = true
        ui.settle()

        val row = ui.row("a").boundsInRoot
        val box = rowBox(ui.row("a")).boundsInRoot
        assertTrue(box.right - row.right > 1f, "indented from the right: $box then $row")
    }

    // --- on its own ---------------------------------------------------------------------------------

    @Test
    fun `it can be pointed at a node of its own with no inspector anywhere`() {
        var chosen: UiNode? = null
        val ui = uiTest(Size(600f, 400f)) {
            var root by remember { mutableStateOf<UiNode?>(null) }
            val placed = remember { PlacedHandler { root = it } }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.size(80f, 40f).onPlaced(placed).testTag("home")) {
                    Box(Modifier.size(20f, 20f).testTag("inner"))
                }
                NodeTree(root, Modifier.fillMaxWidth().height(300f), onSelect = { chosen = it })
            }
        }.also { opened += it }
        ui.settle()

        ui.click(NodeTreeTags.row("inner"))

        assertEquals(ui.node("inner"), chosen, "the row handed its node over:\n" + ui.dump())
    }

    @Test
    fun `it drops into a debug window and lists the game under it`() {
        var chosen: UiNode? = null
        val windows = DebugWindowsState(MemoryDebugWindowStore())
        val ui = uiTest(Size(800f, 600f)) {
            var root by remember { mutableStateOf<UiNode?>(null) }
            val placed = remember { PlacedHandler { root = it } }
            DebugWindowHost(state = windows) {
                Box(Modifier.fillMaxSize().onPlaced(placed)) {
                    Button("play", onClick = {}, modifier = Modifier.testTag("play"))
                }
                // A height of its own: a window's body scrolls, so it hands what is in it all the
                // room it asks for, and a tree given that would have nothing to scroll inside.
                DebugWindow("UI tree", initialPosition = Offset(360f, 40f)) {
                    NodeTree(root, Modifier.width(280f).height(320f), onSelect = { chosen = it })
                }
            }
        }.also { opened += it }
        ui.settle()

        assertTrue(ui.hasRow("play"), "the game under the window is what it lists:\n" + ui.dump())

        ui.click(NodeTreeTags.row("play"))

        assertEquals(ui.node("play"), chosen, "and a row in a window still chooses:\n" + ui.dump())
    }

    // --- nodes that go away -------------------------------------------------------------------------

    @Test
    fun `a node taken off the screen loses its row`() {
        val ui = open()
        ui.moveTo("a")
        assertTrue(ui.hasRow("b"))

        showB = false
        ui.settle()

        assertFalse(ui.hasRow("b"), "the row went with it:\n" + ui.dump())
        assertTrue(ui.hasRow("a"), "and the rest stayed")
    }
}
