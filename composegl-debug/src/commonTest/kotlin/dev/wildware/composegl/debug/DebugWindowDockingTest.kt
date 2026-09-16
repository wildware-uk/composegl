package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Docking debug windows, driven the way somebody tidying a screen full of them drives it: dragging a
 * title bar onto a square, dropping one window on another to tab them together, pulling a tab back
 * out, hauling a divider, and doing all of it again from the keyboard and the pad.
 *
 * Every answer is read off the screen — where the panes landed, which window is the size of its pane
 * and which is at nothing behind a tab, what went to the store.
 */
class DebugWindowDockingTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private val store = MemoryDebugWindowStore()

    private val screen = Rect.of(0f, 0f, 800f, 600f)

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(800f, 600f), content = content).also { opened += it }

    /** Two ordinary windows over a game, which is all docking needs to be worth having. */
    @Composable
    private fun TwoWindows(state: DebugWindowsState, onClose: (() -> Unit)? = null) {
        DebugWindowHost(state = state) {
            DebugWindow("One", initialPosition = Offset(40f, 40f), onClose = onClose) {
                text("Waves", "1")
            }
            DebugWindow("Two", initialPosition = Offset(360f, 300f), onClose = onClose) {
                text("Waves", "2")
            }
        }
    }

    private fun UiTest.window(id: String): UiNode = node(DebugWindowTags.window(id))

    private fun UiTest.title(id: String): UiNode = node(DebugWindowTags.title(id))

    private fun UiTest.tab(id: String): UiNode = node(DebugWindowTags.tab(id))

    private fun UiTest.drag(from: Offset, to: Offset) {
        press(from)
        dragTo(to)
        release()
    }

    /** The middle of the square that docks a dragged window against [side] of the screen. */
    private fun screenSquare(side: DockSide): Offset =
        screenMarkers(screen).first { it.first == side }.second.centre

    /** The middle of one square of the cross drawn over [window]; null for the middle one. */
    private fun windowSquare(window: Rect, side: DockSide?): Offset =
        windowMarkers(window).first { it.first == side }.second.centre

    // --- dragging one in ---------------------------------------------------------------------------

    @Test
    fun `dragging a window onto the square at the edge of the screen docks it there`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }

        ui.drag(ui.title("One").boundsInRoot.centre, screenSquare(DockSide.Left))

        assertTrue(state.isDocked("One"), "the drop on the square did not dock it")
        val pane = ui.window("One").layoutBoundsInRoot
        assertEquals(0f, pane.left)
        assertEquals(0f, pane.top)
        assertEquals(600f, pane.height, "a pane down the edge is the whole height of the screen")
        assertTrue(pane.width in 150f..250f, "about a quarter of the screen: $pane")
        assertTrue(ui.window("Two").layoutBoundsInRoot.left > pane.right, "the floating window moved")
    }

    @Test
    fun `the squares and the patch are only there while something is being dragged`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }

        ui.assertDoesNotExist(DebugWindowTags.screenDrop(DockSide.Left))

        ui.press(ui.title("One").boundsInRoot.centre)
        ui.dragTo(screenSquare(DockSide.Left))

        ui.assertExists(DebugWindowTags.screenDrop(DockSide.Left))
        ui.assertExists(DebugWindowTags.DropPreview)
        assertEquals(0f, ui.node(DebugWindowTags.DropPreview).layoutBoundsInRoot.left, "the patch is over the space it would take")

        ui.release()

        ui.assertDoesNotExist(DebugWindowTags.screenDrop(DockSide.Left))
        ui.assertDoesNotExist(DebugWindowTags.DropPreview)
    }

    @Test
    fun `a window dragged across the screen and dropped on nothing is only moved`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }

        ui.drag(ui.title("One").boundsInRoot.centre, Offset(200f, 20f))

        assertFalse(state.isDocked("One"), "a window being moved should not dock on its way past an edge")
    }

    @Test
    fun `dropping a window on the middle of another tabs the two together`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        ui.drag(ui.title("One").boundsInRoot.centre, screenSquare(DockSide.Left))
        val pane = ui.window("One").layoutBoundsInRoot

        ui.drag(ui.title("Two").boundsInRoot.centre, windowSquare(pane, null))

        assertEquals(listOf("One", "Two"), state.tabsWith("One"))
        assertEquals(pane, ui.window("Two").layoutBoundsInRoot, "the two share one pane")
        assertEquals(0f, ui.window("One").width, "the window behind the tab takes no room at all")
        assertEquals(listOf("One", "Two"), ui.tabs(), "one strip with both tabs on it")
    }

    @Test
    fun `dropping a window on the side of another splits that window's pane`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        ui.drag(ui.title("One").boundsInRoot.centre, screenSquare(DockSide.Left))
        val pane = ui.window("One").layoutBoundsInRoot

        ui.drag(ui.title("Two").boundsInRoot.centre, windowSquare(pane, DockSide.Bottom))

        val top = ui.window("One").layoutBoundsInRoot
        val bottom = ui.window("Two").layoutBoundsInRoot
        assertEquals(pane.left, bottom.left, "the new pane stayed inside the old one")
        assertTrue(bottom.top >= top.bottom, "Two should be under One: $top and $bottom")
        assertTrue(state.isDocked("Two"))
    }

    @Test
    fun `dropping a floating window on another floating one docks the pair and tabs them`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        val two = ui.window("Two").layoutBoundsInRoot

        ui.drag(ui.title("One").boundsInRoot.centre, windowSquare(two, null))

        assertEquals(listOf("Two", "One"), state.tabsWith("Two"), "neither was docked yet, so nothing happened:\n" + ui.dump())
        val pane = ui.window("One").layoutBoundsInRoot
        assertEquals(600f, pane.bottom, "Two was nearest the bottom, so that is where the pair went")
        assertEquals(800f, pane.width, "a pane against an edge takes the whole of it")
        assertEquals(0f, ui.window("Two").width, "the window behind the tab takes no room at all")
        assertEquals(listOf("Two", "One"), ui.tabs(), "one strip with both tabs on it")
    }

    @Test
    fun `dropping a floating window on the side of another floating one splits the pane they are given`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        val two = ui.window("Two").layoutBoundsInRoot

        ui.drag(ui.title("One").boundsInRoot.centre, windowSquare(two, DockSide.Bottom))

        assertTrue(state.isDocked("Two"), "the window dropped on was floating and should have been docked too")
        val top = ui.window("Two").layoutBoundsInRoot
        val bottom = ui.window("One").layoutBoundsInRoot
        assertEquals(600f, bottom.bottom, "the pair went against the edge Two was nearest")
        assertEquals(top.left, bottom.left, "the split is inside the pane the pair were given")
        assertTrue(bottom.top >= top.bottom, "One should be under Two: $top and $bottom")
    }

    @Test
    fun `the patch shows where a drop on a floating window would really land`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        val two = ui.window("Two").layoutBoundsInRoot

        ui.press(ui.title("One").boundsInRoot.centre)
        ui.dragTo(windowSquare(two, null))
        val patch = ui.node(DebugWindowTags.DropPreview).layoutBoundsInRoot
        ui.release()

        assertEquals(patch, ui.window("One").layoutBoundsInRoot, "the patch promised a place the drop did not use")
    }

    // --- living in a pane --------------------------------------------------------------------------

    @Test
    fun `a docked window has no edges to drag and is not folded`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }

        state.dockToScreen("One", DockSide.Left)
        ui.settle()

        ui.assertDoesNotExist(DebugWindowTags.edge("One", WindowEdge.BottomRight))
        ui.assertDoesNotExist(DebugWindowTags.title("One"))
        ui.assertExists(DebugWindowTags.tabs("One"))
    }

    @Test
    fun `clicking a tab brings that window forward and takes the keyboard with it`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        state.dockToScreen("One", DockSide.Left)
        state.dockWith("Two", "One")
        ui.settle()
        assertEquals(0f, ui.window("One").width, "Two was the one dropped in so Two is showing")

        ui.click(DebugWindowTags.tab("One"))

        assertTrue(ui.window("One").width > 0f, "the tab did not bring its window forward")
        assertEquals(0f, ui.window("Two").width)
        val focused = assertNotNull(ui.focus.focused)
        val one = state.entries.first { it.id == "One" }
        assertTrue(focused.isInside(one.root), "the keyboard was left on a window nobody can see")
    }

    @Test
    fun `dragging a tab out of the strip floats that window again where it was dropped`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        state.dockToScreen("One", DockSide.Left)
        state.dockWith("Two", "One")
        ui.settle()

        ui.drag(ui.tab("Two").boundsInRoot.centre, Offset(520f, 380f))

        assertFalse(state.isDocked("Two"), "the tab was dragged out and should be floating")
        assertEquals(listOf("One"), state.tabsWith("One"))
        val window = ui.window("Two").layoutBoundsInRoot
        assertTrue(window.left in 440f..520f, "it should land under the pointer that pulled it out: $window")
        assertTrue(window.top in 340f..380f, "$window")
    }

    @Test
    fun `dragging a tab onto a square docks it somewhere else instead`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        state.dockToScreen("One", DockSide.Left)
        state.dockWith("Two", "One")
        ui.settle()

        ui.drag(ui.tab("Two").boundsInRoot.centre, screenSquare(DockSide.Bottom))

        assertEquals(listOf("One"), state.tabsWith("One"))
        assertTrue(state.isDocked("Two"))
        val pane = ui.window("Two").layoutBoundsInRoot
        assertEquals(600f, pane.bottom)
        assertEquals(800f, pane.width, "the last one docked to an edge takes the whole of it")
    }

    @Test
    fun `cycling with the hotkey walks past a window that is behind another tab`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        state.dockToScreen("One", DockSide.Left)
        state.dockWith("Two", "One")
        ui.settle()

        assertTrue(ui.key(Key.F6), "F6 should have found the one window there is to go to")

        val focused = assertNotNull(ui.focus.focused)
        val two = state.entries.first { it.id == "Two" }
        assertTrue(focused.isInside(two.root), "focus landed on a window nobody can see:\n" + ui.dump())
    }

    // --- the dividers ------------------------------------------------------------------------------

    @Test
    fun `dragging the divider gives one pane more of the screen`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        state.dockToScreen("One", DockSide.Left)
        ui.settle()
        val was = ui.window("One").width

        val divider = ui.node(DebugWindowTags.divider(0)).boundsInRoot.centre
        ui.drag(divider, divider + Offset(120f, 0f))

        assertEquals(was + 120f, ui.window("One").width, 1f)
        assertTrue(store.values.getValue("dock").isNotEmpty(), "where the divider ended up was not saved")
    }

    @Test
    fun `the divider stops rather than squashing a pane to nothing`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        state.dockToScreen("One", DockSide.Left)
        ui.settle()

        val divider = ui.node(DebugWindowTags.divider(0)).boundsInRoot.centre
        ui.drag(divider, divider - Offset(400f, 0f))

        assertEquals(MinDockPane, ui.window("One").width)
    }

    @Test
    fun `a double click on the divider shares the space out evenly`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        state.dockToScreen("One", DockSide.Left)
        ui.settle()

        val divider = ui.node(DebugWindowTags.divider(0)).boundsInRoot.centre
        ui.click(divider)
        ui.click(divider)

        assertEquals((800f - DockDividerThickness) / 2f, ui.window("One").width, 1f)
    }

    @Test
    fun `the pad moves the divider it is on`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        state.dockToScreen("One", DockSide.Left)
        ui.settle()
        val was = ui.window("One").width

        ui.focus.focusOn(ui.node(DebugWindowTags.divider(0)))
        ui.settle()
        ui.pad(GamepadButton.DpadRight)

        assertTrue(ui.window("One").width > was, "the pad's direction should have moved the divider")
    }

    @Test
    fun `a floating window over a divider keeps the presses meant for its controls`() {
        val state = DebugWindowsState(store)
        var spawned = 0
        val ui = open {
            DebugWindowHost(state = state) {
                DebugWindow("One", initialPosition = Offset(40f, 40f)) { text("Waves", "1") }
                DebugWindow("Two", initialPosition = Offset(360f, 300f)) { button("Spawn") { spawned++ } }
            }
        }
        state.dockToScreen("One", DockSide.Left)
        ui.settle()
        val divider = ui.node(DebugWindowTags.divider(0)).boundsInRoot

        // Two over the divider, which is a strip six pixels wide across the whole height of the screen.
        val button = ui.node(DebugWindowTags.control("Two", "Spawn")).boundsInRoot
        val bar = ui.title("Two").boundsInRoot.centre
        ui.drag(bar, bar + Offset(divider.centre.x - button.centre.x, 0f))
        ui.click(ui.node(DebugWindowTags.control("Two", "Spawn")).boundsInRoot.centre)

        assertEquals(1, spawned, "the divider swallowed the press meant for the window over it:\n" + ui.dump())
    }

    // --- without a mouse ---------------------------------------------------------------------------

    @Test
    fun `the keyboard docks a window and floats it again`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        ui.click(ui.title("One").boundsInRoot.centre)

        ui.key(Key.Right, Modifiers.Primary + Modifiers.Alt)

        assertTrue(state.isDocked("One"))
        assertEquals(800f, ui.window("One").layoutBoundsInRoot.right)

        ui.key(Key.F, Modifiers.Primary + Modifiers.Alt)

        assertFalse(state.isDocked("One"))
        ui.assertExists(DebugWindowTags.title("One"))
    }

    @Test
    fun `docking a window lets go of the stick that was pushing it`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        ui.click(ui.title("One").boundsInRoot.centre)
        // Straight to the window, without letting a frame pass: the stick is still over when it docks.
        ui.input.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.RightX, 1f))

        ui.key(Key.Left, Modifiers.Primary + Modifiers.Alt)
        assertTrue(state.isDocked("One"))
        ui.advanceBy(400)

        val x = assertNotNull(state.position("One")).x
        assertTrue(x < 200f, "the docked window went on drifting to the edge of the screen: at $x")

        ui.key(Key.F, Modifiers.Primary + Modifiers.Alt)

        assertEquals(x, assertNotNull(state.position("One")).x, "it should float where the player left it")
    }

    @Test
    fun `the menu on the title bar docks a window without a drag`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }

        ui.click(ui.title("One").boundsInRoot.centre, PointerButton.Secondary)
        ui.click(ui.row("Dock").boundsInRoot.centre)
        ui.click(ui.row("Left").boundsInRoot.centre)

        assertTrue(state.isDocked("One"), "the Dock menu did nothing:\n" + ui.dump())
        assertEquals(0f, ui.window("One").layoutBoundsInRoot.left)
    }

    @Test
    fun `the menu on a strip of tabs floats the window again`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        state.dockToScreen("One", DockSide.Left)
        ui.settle()

        ui.click(ui.node(DebugWindowTags.tabs("One")).boundsInRoot.centre, PointerButton.Secondary)
        ui.click(ui.row("Float").boundsInRoot.centre)

        assertFalse(state.isDocked("One"))
    }

    // --- right to left -----------------------------------------------------------------------------

    @Test
    fun `a pane docked left is on the left however the screen reads`() {
        val state = DebugWindowsState(store)
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) { TwoWindows(state) }
        }

        state.dockToScreen("One", DockSide.Left)
        ui.settle()

        val pane = ui.window("One").layoutBoundsInRoot
        assertEquals(0f, pane.left, "a mirrored screen still has a left edge:\n" + ui.dump())
        assertEquals(600f, pane.height)
    }

    /**
     * The window behind a tab is measured at nothing at all, and on a mirrored screen a slider
     * squeezed to nothing used to ask layout for a fill of a negative length and bring the whole
     * screen down. Two windows with a slider each, tabbed together, is the smallest thing that did
     * it — and it is what a person tidying a screen full of debug windows does first.
     */
    @Test
    fun `tabbing two windows with sliders together on a mirrored screen does not throw`() {
        val state = DebugWindowsState(store)
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) { TwoTweaks(state) }
        }

        ui.drag(ui.title("One").boundsInRoot.centre, screenSquare(DockSide.Left))
        val pane = ui.window("One").layoutBoundsInRoot
        ui.drag(ui.title("Two").boundsInRoot.centre, windowSquare(pane, null))

        assertEquals(listOf("One", "Two"), state.tabsWith("One"))
        assertEquals(0f, ui.window("One").width, "the window behind the tab takes no room at all")
        assertEquals(listOf("One", "Two"), ui.tabs(), "one strip with both tabs on it")
        ui.render()
    }

    /** The same pair of windows, each with a slider on it. */
    @Composable
    private fun TwoTweaks(state: DebugWindowsState) {
        DebugWindowHost(state = state) {
            DebugWindow("One", initialPosition = Offset(40f, 40f)) {
                tweak("Gravity", 9.8f, {}, 0f..20f)
            }
            DebugWindow("Two", initialPosition = Offset(360f, 300f)) {
                tweak("Drag", 0.5f, {}, 0f..1f)
            }
        }
    }

    @Test
    fun `a tab pulled out on a mirrored screen lands under the pointer`() {
        val state = DebugWindowsState(store)
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) { TwoWindows(state) }
        }
        state.dockToScreen("One", DockSide.Left)
        state.dockWith("Two", "One")
        ui.settle()

        ui.drag(ui.tab("Two").boundsInRoot.centre, Offset(520f, 380f))

        val window = ui.window("Two").layoutBoundsInRoot
        assertFalse(state.isDocked("Two"))
        assertTrue(window.right in 520f..600f, "its start corner is the right one here: $window")
    }

    // --- remembering it ----------------------------------------------------------------------------

    @Test
    fun `a dock layout comes back the next time the game runs`() {
        val first = DebugWindowsState(store)
        val ui = open { TwoWindows(first) }
        first.dockToScreen("One", DockSide.Left)
        first.dockWith("Two", "One")
        ui.settle()
        val pane = ui.window("Two").layoutBoundsInRoot

        val next = DebugWindowsState(store)
        val again = open { TwoWindows(next) }

        assertEquals(listOf("One", "Two"), next.tabsWith("One"))
        assertEquals(pane, again.window("Two").layoutBoundsInRoot)
        assertEquals(listOf("One", "Two"), again.tabs())
    }

    @Test
    fun `a window the layout names but the game does not show leaves no hole`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        state.dockToScreen("One", DockSide.Left)
        state.dockToScreen("Two", DockSide.Right)
        ui.settle()

        val only = DebugWindowsState(store)
        val again = open {
            DebugWindowHost(state = only) {
                DebugWindow("One", initialPosition = Offset(40f, 40f)) { text("Waves", "1") }
            }
        }

        assertEquals(listOf("One", "Two"), only.dockedWindows, "the layout still remembers Two")
        assertEquals(0f, again.window("One").layoutBoundsInRoot.left)
        assertEquals(emptyList(), again.tabs().filter { it == "Two" }, "a window that is not composed draws no tab")
    }

    @Test
    fun `a divider still moves when the layout names a window the game does not show`() {
        val first = DebugWindowsState(store)
        val started = open {
            DebugWindowHost(state = first) {
                DebugWindow("One", initialPosition = Offset(40f, 40f)) { text("Waves", "1") }
                DebugWindow("Two", initialPosition = Offset(360f, 300f)) { text("Waves", "2") }
                DebugWindow("Gone", initialPosition = Offset(600f, 40f)) { text("Waves", "3") }
            }
        }
        first.dockToScreen("One", DockSide.Left)
        first.dockWith("Two", "One", DockSide.Bottom)
        first.dockToScreen("Gone", DockSide.Left)
        started.settle()

        // The next run composes two of the three. Gone keeps its place in the saved layout and its
        // split is not drawn, so the divider between One and Two is one split further in than the
        // layout says — and hauling it has to move that split all the same.
        val next = DebugWindowsState(store)
        val again = open { TwoWindows(next) }
        again.settle()
        val was = again.window("One").height

        val divider = again.node(DebugWindowTags.divider(1)).boundsInRoot.centre
        again.drag(divider, divider + Offset(0f, 90f))

        assertEquals(was + 90f, again.window("One").height, 1f)
        val saved = DockLayoutText.read(store.values.getValue("dock")) as DockSplit
        assertEquals(DockShare, saved.fraction, 0.001f, "the split holding the window nobody composed moved")
        val rest = saved.second as DockSplit
        assertEquals(DockShare, rest.fraction, 0.001f, "the split round the two panes moved")
        val dragged = rest.first as DockSplit
        assertTrue(dragged.fraction > 0.5f, "the fraction did not land on the split that was dragged")
    }

    @Test
    fun `resetting the layout floats everything again`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state) }
        state.dockToScreen("One", DockSide.Left)
        ui.settle()

        state.resetLayout()
        ui.settle()

        assertEquals(emptyList(), state.dockedWindows)
        ui.assertExists(DebugWindowTags.title("One"))
        assertEquals(40f, ui.window("One").layoutBoundsInRoot.left)
    }

    // --- reading the screen ------------------------------------------------------------------------

    /** The windows a strip of tabs has a tab for, in the order they are drawn. */
    private fun UiTest.tabs(): List<String> {
        val found = mutableListOf<String>()
        root.forEach { node ->
            val tag = node.testTag ?: return@forEach
            if (tag.startsWith("debugwindow:") && tag.endsWith(":tab")) {
                found += tag.removePrefix("debugwindow:").removeSuffix(":tab")
            }
        }
        return found
    }

    /** The row of an open menu whose words start with [label]. Menus carry no tags: a player reads them too. */
    private fun UiTest.row(label: String): UiNode {
        val rows = mutableListOf<UiNode>()
        root.forEach { if (it.name == "menu.item") rows += it }
        return rows.lastOrNull { wordsOf(it).startsWith(label) }
            ?: throw AssertionError("no open menu has a row \"$label\":\n" + dump())
    }

    private fun UiTest.wordsOf(node: UiNode): String {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, 800f, 600f))
        val bounds = node.layoutBoundsInRoot
        DrawPass(canvas).draw(node, bounds.left - node.x, bounds.top - node.y)
        return canvas.texts().joinToString("")
    }
}
