package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.NinePatch
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.saveable.SaveableStateHolder
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.skin.SkinOverride
import dev.wildware.composegl.ui.skin.StateStyle
import dev.wildware.composegl.ui.skin.Style
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A scene hierarchy in a [TreeView], driven by a mouse, a keyboard and a pad.
 *
 * Every test composes the tree for real and does what a player does — clicks arrows and rows,
 * presses the arrows and the d-pad — and reads the answer off the screen: which rows exist, where
 * they are drawn, and where focus is.
 */
class TreeViewTest {

    private class Node(val id: String, vararg val kids: Node) {
        override fun toString() = id
    }

    private val scene = listOf(
        Node(
            "World",
            Node("Player", Node("Camera"), Node("Weapon")),
            Node("Enemies", Node("Drone"), Node("Turret")),
        ),
        Node("Lights", Node("Sun")),
        Node("Sky"),
    )

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(size: Size = Size(400f, 300f), content: @Composable () -> Unit): UiTest =
        uiTest(size, content = content).also { opened += it }

    private var selection by mutableStateOf<Node?>(null)
    private val asked = mutableListOf<String>()
    private val activated = mutableListOf<String>()

    @Composable
    private fun Scene(
        roots: List<Node> = scene,
        state: TreeState = rememberTreeState(),
        onActivate: ((Node) -> Unit)? = null,
    ) {
        TreeView(
            roots = roots,
            children = { asked += it.id; it.kids.toList() },
            key = { it.id },
            modifier = Modifier.fillMaxSize().testTag("tree"),
            selected = selection,
            onSelect = { selection = it },
            state = state,
            hasChildren = { it.kids.isNotEmpty() },
            onActivate = onActivate,
        ) { node, _ ->
            Text(node.id)
        }
    }

    // --- reading the screen -------------------------------------------------------------------------

    private fun UiTest.rows(): List<String> = named("tree.row").map { wordsOf(it) }

    private fun UiTest.treeRow(label: String): UiNode =
        named("tree.row").firstOrNull { wordsOf(it) == label }
            ?: throw AssertionError("no row \"$label\":\n" + dump())

    private fun UiTest.toggleOf(label: String): UiNode {
        var found: UiNode? = null
        treeRow(label).forEach { if (it.name == "tree.toggle" || it.name == "tree.toggle.open") found = it }
        return found ?: throw AssertionError("the row \"$label\" has no arrow:\n" + dump())
    }

    /** Where a row's words start across the screen. */
    private fun UiTest.textX(label: String): Float =
        drawingOf(treeRow(label)).calls.filterIsInstance<DrawCall.Text>().first().at.x

    private fun UiTest.assertRowFocused(label: String) = assertFocusedOn(treeRow(label), "the $label row")

    // --- the mouse -----------------------------------------------------------------------------------

    @Test
    fun `only the roots show until something is opened`() {
        val ui = open { Scene() }

        assertEquals(listOf("World", "Lights", "Sky"), ui.rows())
        assertEquals(emptyList(), asked, "nothing closed is asked for its children")
    }

    @Test
    fun `a click on the arrow opens the row and its children appear indented under it`() {
        val ui = open { Scene() }

        ui.click(ui.toggleOf("World").boundsInRoot.centre)

        assertEquals(listOf("World", "Player", "Enemies", "Lights", "Sky"), ui.rows())
        assertEquals(listOf("World"), asked, "only the opened row was asked for its children")
        assertTrue(ui.textX("Player") > ui.textX("World"), "a child is indented past its parent")
        assertEquals("tree.toggle.open", ui.toggleOf("World").name)
        assertNull(selection, "the arrow opens and does not choose")
    }

    @Test
    fun `a second click on the arrow closes it again`() {
        val ui = open { Scene() }
        ui.click(ui.toggleOf("World").boundsInRoot.centre)

        ui.click(ui.toggleOf("World").boundsInRoot.centre)

        assertEquals(listOf("World", "Lights", "Sky"), ui.rows())
    }

    @Test
    fun `a click on a row selects it and puts focus there`() {
        val ui = open { Scene() }

        ui.click(ui.treeRow("Lights").boundsInRoot.centre)

        assertEquals("Lights", selection?.id)
        ui.assertRowFocused("Lights")
    }

    @Test
    fun `a double click on a row opens it`() {
        val ui = open { Scene() }

        ui.click(ui.treeRow("Lights").boundsInRoot.centre)
        ui.click(ui.treeRow("Lights").boundsInRoot.centre)

        assertTrue("Sun" in ui.rows(), "the double click opened Lights: ${ui.rows()}")
    }

    @Test
    fun `with onActivate a double click calls it instead of opening`() {
        val ui = open { Scene(onActivate = { activated += it.id }) }

        ui.click(ui.treeRow("Lights").boundsInRoot.centre)
        ui.click(ui.treeRow("Lights").boundsInRoot.centre)

        assertEquals(listOf("Lights"), activated)
        assertFalse("Sun" in ui.rows())
    }

    @Test
    fun `a leaf has no arrow`() {
        val ui = open { Scene() }

        assertTrue(ui.named("tree.leaf").isNotEmpty())
        var arrow = false
        ui.treeRow("Sky").forEach { if (it.name.startsWith("tree.toggle")) arrow = true }
        assertFalse(arrow, "Sky has no children and so nothing to open")
    }

    // --- the keyboard ----------------------------------------------------------------------------------

    @Test
    fun `up and down move from row to row`() {
        val ui = open { Scene() }
        ui.click(ui.treeRow("World").boundsInRoot.centre)

        ui.key(Key.Down)
        ui.assertRowFocused("Lights")
        ui.key(Key.Down)
        ui.assertRowFocused("Sky")
        ui.key(Key.Up)
        ui.assertRowFocused("Lights")
    }

    @Test
    fun `right opens a closed row and then goes into its first child`() {
        val ui = open { Scene() }
        ui.click(ui.treeRow("World").boundsInRoot.centre)

        ui.key(Key.Right)
        assertTrue("Player" in ui.rows(), "the first Right opens World")
        ui.assertRowFocused("World")

        ui.key(Key.Right)
        ui.assertRowFocused("Player")
    }

    @Test
    fun `left closes an open row and on a closed one goes up to the parent`() {
        val ui = open { Scene() }
        ui.click(ui.treeRow("World").boundsInRoot.centre)
        ui.key(Key.Right)
        ui.key(Key.Right)
        ui.key(Key.Right)
        ui.key(Key.Right)
        ui.assertRowFocused("Camera")

        ui.key(Key.Left)
        ui.assertRowFocused("Player")
        assertTrue("Camera" in ui.rows(), "going up to the parent closes nothing")

        ui.key(Key.Left)
        ui.assertRowFocused("Player")
        assertFalse("Camera" in ui.rows(), "Left on an open row closes it")

        ui.key(Key.Left)
        ui.assertRowFocused("World")
    }

    @Test
    fun `down walks into open children in the order they are shown`() {
        val ui = open { Scene() }
        ui.click(ui.treeRow("World").boundsInRoot.centre)
        ui.key(Key.Right)

        ui.key(Key.Down)
        ui.assertRowFocused("Player")
        ui.key(Key.Down)
        ui.assertRowFocused("Enemies")
        ui.key(Key.Down)
        ui.assertRowFocused("Lights")
    }

    @Test
    fun `enter selects the focused row`() {
        val ui = open { Scene() }
        ui.click(ui.treeRow("World").boundsInRoot.centre)
        ui.key(Key.Down)

        ui.key(Key.Enter)

        assertEquals("Lights", selection?.id)
    }

    @Test
    fun `past the last row down leaves the tree`() {
        val ui = open {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(200f)) { Scene() }
                Button("DONE", onClick = {}, modifier = Modifier.testTag("done"))
            }
        }
        ui.click(ui.treeRow("Sky").boundsInRoot.centre)

        ui.key(Key.Down)

        ui.assertFocused("done")
    }

    @Test
    fun `past the first row up leaves the tree`() {
        val ui = open {
            Column(Modifier.fillMaxSize()) {
                Button("DONE", onClick = {}, modifier = Modifier.testTag("done"))
                Box(Modifier.fillMaxWidth().height(200f)) { Scene() }
            }
        }
        ui.click(ui.treeRow("World").boundsInRoot.centre)

        ui.key(Key.Up)

        ui.assertFocused("done")
    }

    /** Presses a key without letting a frame pass after it, as two presses in one frame arrive. */
    private fun UiTest.keyInSameFrame(key: Key) {
        input.onKey(KeyEvent(key, KeyEventType.Down))
        input.onKey(KeyEvent(key, KeyEventType.Up))
    }

    @Test
    fun `two rights in one frame open a row and go into its first child`() {
        val ui = open { Scene() }
        ui.click(ui.treeRow("World").boundsInRoot.centre)

        ui.keyInSameFrame(Key.Right)
        ui.keyInSameFrame(Key.Right)
        ui.settle()

        ui.assertRowFocused("Player")
    }

    @Test
    fun `left then down in one frame skips the rows left closed`() {
        val state = TreeState(listOf("World"))
        val ui = open { Scene(state = state) }
        ui.click(ui.treeRow("World").boundsInRoot.centre)

        ui.keyInSameFrame(Key.Left)
        ui.keyInSameFrame(Key.Down)
        ui.settle()

        assertFalse("Player" in ui.rows())
        ui.assertRowFocused("Lights")
    }

    // --- the pad ----------------------------------------------------------------------------------------

    @Test
    fun `the d-pad walks the tree the same way and south selects`() {
        val ui = open { Scene() }
        ui.click(ui.treeRow("World").boundsInRoot.centre)
        selection = null

        ui.pad(GamepadButton.DpadRight)
        ui.pad(GamepadButton.DpadRight)
        ui.assertRowFocused("Player")
        ui.pad(GamepadButton.DpadDown)
        ui.assertRowFocused("Enemies")
        ui.pad(GamepadButton.DpadLeft)
        ui.assertRowFocused("World")
        ui.pad(GamepadButton.DpadLeft)
        assertFalse("Player" in ui.rows(), "Left on the open World closes it")

        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.South)
        assertEquals("Lights", selection?.id)
    }

    // --- right to left ------------------------------------------------------------------------------------

    @Test
    fun `right to left the indent comes from the right and left opens`() {
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) { Scene() }
        }
        ui.click(ui.treeRow("World").boundsInRoot.centre)

        ui.key(Key.Left)
        assertTrue("Player" in ui.rows(), "Left is inwards in a right-to-left screen")
        ui.key(Key.Left)
        ui.assertRowFocused("Player")

        assertTrue(ui.textX("Player") < ui.textX("World"), "a child is indented in from the right")
        val arrow = ui.toggleOf("Player").boundsInRoot
        assertTrue(arrow.left > ui.textX("Player"), "the arrow is at the start of the line, on the right")

        ui.key(Key.Right)
        ui.assertRowFocused("World")
    }

    @Test
    fun `right to left a closed row's arrow points left`() {
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) { Scene() }
        }
        val fan = ui.drawingOf(ui.toggleOf("Lights")).calls.filterIsInstance<DrawCall.Fan>().single()
        val tip = fan.points.minBy { it.x }

        assertTrue(fan.points.count { it.x == tip.x } == 1, "one point leftmost is a tip pointing left: ${fan.points}")

        val ltr = open { Scene() }
        val forwards = ltr.drawingOf(ltr.toggleOf("Lights")).calls.filterIsInstance<DrawCall.Fan>().single()
        assertTrue(forwards.points.count { p -> p.x == forwards.points.maxOf { it.x } } == 1, "and right in a left-to-right one")
    }

    @Test
    fun `right to left a closed row's patch arrow is flipped`() {
        val art = SkinDrawable.Patch(NinePatch(Art, Padding.None))
        val patched = Skin(mapOf("tree.toggle" to Style(StateStyle(background = art))))
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) { SkinOverride(patched) { Scene(state = rememberTreeState("World")) } }
        }

        assertTrue(ui.toggleOf("Lights").drawnMirrorX, "a closed arrow points left")
        assertFalse(ui.toggleOf("World").drawnMirrorX, "an open one points down and is left alone")

        val ltr = open { SkinOverride(patched) { Scene() } }
        assertFalse(ltr.toggleOf("Lights").drawnMirrorX, "and left to right nothing is flipped")
    }

    private object Art : TextureHandle {
        override val width = 8
        override val height = 8
    }

    // --- only what can be seen -----------------------------------------------------------------------------

    @Test
    fun `with the default hasChildren only built rows are asked for their children`() {
        val many = List(10_000) { Node("row $it", Node("under $it")) }
        val ui = open {
            TreeView(
                roots = many,
                children = { asked += it.id; it.kids.toList() },
                key = { it.id },
                modifier = Modifier.fillMaxSize(),
            ) { node, _ -> Text(node.id) }
        }

        val built = ui.named("tree.row").size
        assertTrue(ui.named("tree.toggle").isNotEmpty(), "the default still finds the arrows")
        assertTrue(asked.size < 100, "asked ${asked.size} nodes for their children with $built rows built")

        asked.clear()
        ui.click(ui.toggleOf("row 0").boundsInRoot.centre)

        assertTrue("under 0" in ui.rows())
        assertTrue(asked.size < 100, "opening one row asked ${asked.size} nodes, not the whole tree again")
    }

    @Test
    fun `ten thousand rows cost a screenful and the pad can walk down them`() {
        val many = List(10_000) { Node("row $it") }
        val ui = open { Scene(roots = many) }

        assertTrue(ui.named("tree.row").size < 40, "built ${ui.named("tree.row").size} rows for a 300 high window")

        ui.click(ui.treeRow("row 0").boundsInRoot.centre)
        repeat(40) { ui.pad(GamepadButton.DpadDown) }

        ui.assertRowFocused("row 40")
        assertTrue(ui.named("tree.row").size < 40, "walking down built more rows rather than moving the window")
    }

    @Test
    fun `left from deep in a long list reaches a parent that had scrolled away`() {
        val big = Node("Big", *Array(200) { Node("child $it") })
        val state = TreeState(listOf("Big"))
        // A root above it, so focus that was merely dropped and picked up again would land there instead.
        val ui = open { Scene(roots = listOf(Node("Before"), big), state = state) }

        state.scrollTo("child 150")
        ui.settle()
        ui.click(ui.treeRow("child 150").boundsInRoot.centre)
        assertTrue(ui.named("tree.row").none { ui.wordsOf(it) == "Big" }, "the parent is not built any more")

        ui.key(Key.Left)

        ui.assertRowFocused("Big")
    }

    // --- open state ----------------------------------------------------------------------------------------

    @Test
    fun `closing a row with focus under it brings focus up to it`() {
        val state = TreeState(listOf("World", "Enemies"))
        val ui = open { Scene(state = state) }
        ui.click(ui.treeRow("Turret").boundsInRoot.centre)

        // Enemies rather than World, which is the first row: focus merely dropped would land there.
        state.collapse("Enemies")
        ui.settle()
        ui.assertRowFocused("Enemies")

        ui.click(ui.treeRow("Enemies").boundsInRoot.centre)
        state.collapse("World")
        ui.settle()
        ui.assertRowFocused("World")
    }

    @Test
    fun `closing a row after focus has left the tree leaves focus where it went`() {
        val state = TreeState(listOf("World", "Enemies"))
        val ui = open {
            Column(Modifier.fillMaxSize()) {
                Button("DONE", onClick = {}, modifier = Modifier.testTag("done"))
                Box(Modifier.fillMaxWidth().height(250f)) { Scene(state = state) }
            }
        }
        ui.click(ui.treeRow("Turret").boundsInRoot.centre)
        ui.click("done")

        state.collapse("Enemies")
        ui.settle()

        ui.assertFocused("done")
    }

    @Test
    fun `closing everything with focus two levels down brings focus to the root it was under`() {
        val state = TreeState(listOf("Lights"))
        val ui = open { Scene(roots = listOf(Node("Top")) + scene, state = state) }
        ui.click(ui.treeRow("Sun").boundsInRoot.centre)

        state.collapseAll()
        ui.settle()

        ui.assertRowFocused("Lights")
    }

    @Test
    fun `open state follows the key when the roots are reordered`() {
        var roots by mutableStateOf(scene)
        val ui = open { Scene(roots = roots) }
        ui.click(ui.toggleOf("Lights").boundsInRoot.centre)

        roots = scene.reversed()
        ui.settle()

        assertEquals(listOf("Sky", "Lights", "Sun", "World"), ui.rows())
    }

    @Test
    fun `open rows are still open when the screen comes back`() {
        var screen by mutableStateOf("tree")
        val ui = open {
            SaveableStateHolder(screen) { key ->
                if (key == "tree") Scene() else Text("elsewhere")
            }
        }
        ui.click(ui.toggleOf("World").boundsInRoot.centre)

        screen = "other"
        ui.settle()
        assertEquals(emptyList(), ui.rows())
        screen = "tree"
        ui.settle()

        assertTrue("Player" in ui.rows(), "World was left open: ${ui.rows()}")
    }

    @Test
    fun `a tree whose screen has gone lets go of the node focus was on`() {
        var screen by mutableStateOf("tree")
        val state = TreeState()
        val ui = open {
            SaveableStateHolder(screen) { key ->
                if (key == "tree") Scene(state = state) else Text("elsewhere")
            }
        }
        ui.click(ui.treeRow("Lights").boundsInRoot.centre)
        assertTrue(state.focusedNode != null)

        screen = "other"
        ui.settle()

        assertNull(state.focusedNode, "a saved state should not keep a dead row alive")
        assertNull(state.focusManager)
    }

    @Test
    fun `a row that has gone after focus left it lets go of its node`() {
        val state = TreeState(listOf("Lights"))
        val ui = open {
            Column(Modifier.fillMaxSize()) {
                Button("DONE", onClick = {}, modifier = Modifier.testTag("done"))
                Box(Modifier.fillMaxWidth().height(250f)) { Scene(state = state) }
            }
        }
        ui.click(ui.treeRow("Sun").boundsInRoot.centre)
        ui.click("done")

        state.collapse("Lights")
        ui.settle()

        assertNull(state.focusedNode)
        ui.assertFocused("done")
    }

    @Test
    fun `a state can open rows from outside`() {
        val state = TreeState()
        val ui = open { Scene(state = state) }

        state.expandAll(listOf("World", "Enemies"))
        ui.settle()
        assertEquals(listOf("World", "Player", "Enemies", "Drone", "Turret", "Lights", "Sky"), ui.rows())
        assertEquals(setOf<Any>("World", "Enemies"), state.expandedKeys)

        state.collapseAll()
        ui.settle()
        assertEquals(listOf("World", "Lights", "Sky"), ui.rows())
    }

    @Test
    fun `a scroll to a row that never showed is forgotten rather than done when it opens later`() {
        val big = Node("Big", *Array(200) { Node("child $it") })
        val state = TreeState()
        val ui = open { Scene(roots = listOf(big), state = state) }

        state.scrollTo("child 150")
        ui.settle()
        ui.settle()
        // Minutes later, the player opens it by hand.
        ui.click(ui.toggleOf("Big").boundsInRoot.centre)
        ui.settle()

        assertTrue("Big" in ui.rows(), "the list stayed at the top: ${ui.rows()}")
        assertFalse("child 150" in ui.rows())
    }

    @Test
    fun `opening a path and scrolling to its end can be done one after the other`() {
        val big = Node("Big", *Array(200) { Node("child $it") })
        val state = TreeState()
        val ui = open { Scene(roots = listOf(big), state = state) }

        state.expandAll(listOf("Big"))
        state.scrollTo("child 150")
        ui.settle()

        assertTrue("child 150" in ui.rows(), "the list scrolled to a row that only showed after it asked: ${ui.rows()}")
    }

    @Test
    fun `scrolling to a row already showing after opening rows above it finds where it has moved to`() {
        val above = Node("A", *Array(100) { Node("a $it") })
        val below = Node("B", *Array(10) { Node("b $it") })
        val state = TreeState(listOf("B"))
        val ui = open { Scene(roots = listOf(above, below), state = state) }
        assertTrue("b 5" in ui.rows(), "b 5 starts on screen: ${ui.rows()}")

        state.expandAll(listOf("A", "B"))
        state.scrollTo("b 5")
        ui.settle()

        assertTrue("b 5" in ui.rows(), "the list followed b 5 down past A's hundred children: ${ui.rows()}")
        assertFalse("a 0" in ui.rows())
    }

    @Test
    fun `scrolling to a row after closing rows above it finds where it has moved to`() {
        val above = Node("A", *Array(100) { Node("a $it") })
        val below = Node("B", *Array(100) { Node("b $it") })
        val state = TreeState(listOf("A", "B"))
        val ui = open { Scene(roots = listOf(above, below), state = state) }
        state.scrollTo("b 60")
        ui.settle()
        assertTrue("b 60" in ui.rows(), "b 60 is on screen: ${ui.rows()}")

        state.collapse("A")
        state.scrollTo("b 60")
        ui.settle()

        assertTrue("b 60" in ui.rows(), "the list followed b 60 up: ${ui.rows()}")
    }

    // --- the skin ------------------------------------------------------------------------------------------

    @Test
    fun `indent guides are drawn beside open children`() {
        val ui = open { Scene(state = rememberTreeState("World")) }

        assertTrue(guideLines(ui, "Player") > 0, "Player should have its guide drawn")
        assertEquals(0, guideLines(ui, "World"), "a root has nothing to hang from")
    }

    @Test
    fun `right to left the guides are drawn from the right`() {
        val rtl = open { ProvideLayoutDirection(LayoutDirection.Rtl) { Scene(state = rememberTreeState("World")) } }
        val ltr = open { Scene(state = rememberTreeState("World")) }

        val rightwards = guideRects(rtl, "Player")
        val leftwards = guideRects(ltr, "Player")
        assertTrue(rightwards.isNotEmpty() && leftwards.isNotEmpty())
        val rtlRow = rtl.treeRow("Player").boundsInRoot
        val ltrRow = ltr.treeRow("Player").boundsInRoot
        val vertical = { rects: List<DrawCall.Rectangle> -> rects.first { it.rect.width == 1f }.rect }
        assertTrue(
            vertical(rightwards).left > rtlRow.left + rtlRow.width / 2f,
            "the guide hangs down the right-hand side: ${vertical(rightwards)} in $rtlRow",
        )
        assertTrue(vertical(leftwards).left < ltrRow.left + ltrRow.width / 2f, "and down the left left to right")
        assertEquals(
            rtlRow.right - vertical(rightwards).right,
            vertical(leftwards).left - ltrRow.left,
            "the same distance in from the start of the line either way",
        )
    }

    @Test
    fun `a skin with no guide background draws no guides`() {
        val none = Skin(mapOf("tree.guide" to Style(StateStyle(background = SkinDrawable.Blank))))
        val ui = open { SkinOverride(none) { Scene(state = rememberTreeState("World")) } }

        assertEquals(0, guideLines(ui, "Player"))
    }

    @Test
    fun `the chosen row is drawn with the selected style`() {
        selection = scene[1]
        val ui = open { Scene() }
        val chosen = Skin.Default.resolve("tree.row.selected").background as SkinDrawable.Fill

        val fills = ui.drawingOf(ui.treeRow("Lights")).calls.filterIsInstance<DrawCall.Rectangle>()

        assertTrue(fills.any { it.colour == chosen.colour }, "Lights is drawn in the selected fill: $fills")
    }

    @Test
    fun `both shipped skins have every part of the tree`() {
        listOf("tree.row", "tree.row.selected", "tree.toggle", "tree.toggle.open", "tree.guide").forEach { part ->
            assertTrue(Skin.Default.has(part), "the default skin has no $part")
            assertTrue(Skin.HighContrast.has(part), "the high contrast skin has no $part")
        }
    }

    /** The one-unit-wide boxes a row's guides are drawn with. */
    private fun guideLines(ui: UiTest, label: String): Int = guideRects(ui, label).size

    private fun guideRects(ui: UiTest, label: String): List<DrawCall.Rectangle> {
        var guides: UiNode? = null
        ui.treeRow(label).forEach { if (it.name == "tree.guides") guides = it }
        val node = guides ?: return emptyList()
        return ui.drawingOf(node).calls.filterIsInstance<DrawCall.Rectangle>()
            .filter { it.rect.width == 1f || it.rect.height == 1f }
    }
}
