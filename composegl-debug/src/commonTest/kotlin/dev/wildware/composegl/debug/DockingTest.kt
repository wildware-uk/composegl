package dev.wildware.composegl.debug

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.widget.Orientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dock layout on its own: what docking a window does to the tree, where the panes land, which
 * square a drop is over, and what survives being written to a store and read back.
 *
 * No screen and no composition here — this is the arithmetic the windows are laid out by, and it is
 * cheaper to be sure of it here than through a drag.
 */
class DockingTest {

    private val screen = Rect.of(0f, 0f, 800f, 600f)

    // --- docking and undocking ------------------------------------------------------------------

    @Test
    fun `docking to an edge splits the screen and leaves the game the rest`() {
        val layout = DockEmpty.dockedToScreen("Physics", DockSide.Left)

        val split = layout as DockSplit
        assertEquals(Orientation.Horizontal, split.orientation)
        assertEquals(DockTabs(listOf("Physics"), "Physics"), split.first)
        assertEquals(DockEmpty, split.second, "the game must keep somewhere to show through")

        val pane = dockRect(assertNotNull(layout.stepsTo("Physics")), screen)
        assertEquals(0f, pane.left)
        assertEquals(600f, pane.height)
        assertTrue(pane.width in 190f..200f, "a quarter of the screen less the divider: $pane")
    }

    @Test
    fun `docking to the right puts the pane on the right and the game on the left`() {
        val layout = DockEmpty.dockedToScreen("Physics", DockSide.Right)

        val pane = dockRect(assertNotNull(layout.stepsTo("Physics")), screen)
        assertEquals(800f, pane.right)
        assertTrue(pane.left > 590f, "a quarter of the screen from the right: $pane")
    }

    @Test
    fun `a second window docked to another edge shares what is left`() {
        val layout = DockEmpty
            .dockedToScreen("One", DockSide.Left)
            .dockedToScreen("Two", DockSide.Bottom)

        val left = dockRect(assertNotNull(layout.stepsTo("One")), screen)
        val bottom = dockRect(assertNotNull(layout.stepsTo("Two")), screen)

        assertEquals(800f, bottom.width, "the last one docked takes the whole width")
        assertTrue(left.bottom <= bottom.top, "the panes overlap: $left and $bottom")
        assertEquals(listOf("One", "Two"), layout.windows())
    }

    @Test
    fun `docking onto the middle of another window tabs the two together`() {
        val layout = DockEmpty
            .dockedToScreen("One", DockSide.Left)
            .dockedWith("Two", "One", side = null)

        val pane = assertNotNull(layout.tabsOf("Two"))
        assertEquals(listOf("One", "Two"), pane.windows)
        assertEquals("Two", pane.selected, "the window just dropped in is the one showing")
        assertEquals(dockRect(assertNotNull(layout.stepsTo("One")), screen), dockRect(assertNotNull(layout.stepsTo("Two")), screen))
    }

    @Test
    fun `docking onto the side of another window splits that window's own pane`() {
        val layout = DockEmpty
            .dockedToScreen("One", DockSide.Left)
            .dockedWith("Two", "One", DockSide.Bottom)

        val one = dockRect(assertNotNull(layout.stepsTo("One")), screen)
        val two = dockRect(assertNotNull(layout.stepsTo("Two")), screen)

        assertEquals(one.left, two.left, "the new pane is inside the old one")
        assertEquals(one.right, two.right)
        assertTrue(two.top >= one.bottom, "Two should be under One: $one and $two")
    }

    @Test
    fun `docking a window that is already docked moves it rather than showing it twice`() {
        val layout = DockEmpty
            .dockedToScreen("One", DockSide.Left)
            .dockedToScreen("Two", DockSide.Right)
            .dockedWith("Two", "One", side = null)

        assertEquals(listOf("One", "Two"), layout.windows())
        assertEquals(listOf("One", "Two"), assertNotNull(layout.tabsOf("One")).windows)
    }

    @Test
    fun `docking against a floating window docks that one first so the two land together`() {
        val layout = DockEmpty.dockedWith("Two", "One", side = null, anchor = DockSide.Right)

        val pane = assertNotNull(layout.tabsOf("One"))
        assertEquals(listOf("One", "Two"), pane.windows, "the floating one should have been given a pane to share")
        assertEquals("Two", pane.selected, "the window just dropped in is the one showing")
        val rect = dockRect(assertNotNull(layout.stepsTo("Two")), screen)
        assertEquals(800f, rect.right, "the pane went against the edge it was anchored to")
        assertEquals(DockEmpty, (layout as DockSplit).first, "the game must keep somewhere to show through")
    }

    @Test
    fun `docking onto the side of a floating window splits the pane it is given`() {
        val layout = DockEmpty.dockedWith("Two", "One", DockSide.Bottom, anchor = DockSide.Left)

        val one = dockRect(assertNotNull(layout.stepsTo("One")), screen)
        val two = dockRect(assertNotNull(layout.stepsTo("Two")), screen)
        assertEquals(0f, one.left, "the pair went against the edge they were anchored to")
        assertEquals(one.left, two.left, "the new pane is inside the one the floating window was given")
        assertTrue(two.top >= one.bottom, "Two should be under One: $one and $two")
    }

    @Test
    fun `a floating window docks against the edge of the screen it is nearest`() {
        assertEquals(DockSide.Left, nearestSide(Rect.of(20f, 240f, 200f, 120f), screen))
        assertEquals(DockSide.Right, nearestSide(Rect.of(560f, 240f, 200f, 120f), screen))
        assertEquals(DockSide.Top, nearestSide(Rect.of(300f, 10f, 200f, 120f), screen))
        assertEquals(DockSide.Bottom, nearestSide(Rect.of(300f, 460f, 200f, 120f), screen))
    }

    @Test
    fun `undocking the last window in a pane takes the pane away and leaves the hole`() {
        val layout = DockEmpty
            .dockedToScreen("One", DockSide.Left)
            .dockedToScreen("Two", DockSide.Right)

        assertEquals(listOf("Two"), layout.without("One").windows())
        assertEquals(DockEmpty, layout.without("One").without("Two"), "nothing docked is nothing at all")
    }

    @Test
    fun `undocking one of two tabs leaves the other showing`() {
        val layout = DockEmpty
            .dockedToScreen("One", DockSide.Left)
            .dockedWith("Two", "One", side = null)

        val left = layout.without("Two")

        val pane = assertNotNull(left.tabsOf("One"))
        assertEquals(listOf("One"), pane.windows)
        assertEquals("One", pane.selected)
    }

    @Test
    fun `a window only composed later keeps its place while it is away`() {
        val layout = DockEmpty
            .dockedToScreen("One", DockSide.Left)
            .dockedWith("Two", "One", side = null)

        val showing = layout.retaining { it == "One" }

        assertEquals(listOf("One"), showing.windows())
        assertEquals("One", assertNotNull(showing.tabsOf("One")).selected)
        assertEquals(listOf("One", "Two"), layout.windows(), "what is kept is not what is drawn")
    }

    @Test
    fun `choosing a tab changes which one is showing and nothing else`() {
        val layout = DockEmpty
            .dockedToScreen("One", DockSide.Left)
            .dockedWith("Two", "One", side = null)

        val chosen = layout.selecting("One")

        assertEquals("One", assertNotNull(chosen.tabsOf("One")).selected)
        assertEquals(listOf("One", "Two"), assertNotNull(chosen.tabsOf("One")).windows)
        assertEquals(layout, layout.selecting("Nobody"), "a window that is not docked has no tab to choose")
    }

    // --- dividers -----------------------------------------------------------------------------

    @Test
    fun `a divider sits in the gap between the two panes it divides`() {
        val layout = DockEmpty.dockedToScreen("One", DockSide.Left)

        val divider = layout.dividers(screen).single()

        val pane = dockRect(assertNotNull(layout.stepsTo("One")), screen)
        assertEquals(pane.right, divider.rect.left)
        assertEquals(DockDividerThickness, divider.rect.width)
        assertEquals(600f, divider.rect.height)
        assertEquals(emptyList(), divider.path, "the only split there is, is the outermost one")
    }

    @Test
    fun `moving a divider gives one pane more of the space`() {
        val layout = DockEmpty.dockedToScreen("One", DockSide.Left)

        val wider = layout.withFraction(emptyList(), 0.5f)

        val pane = dockRect(assertNotNull(wider.stepsTo("One")), screen)
        assertEquals(397f, pane.width, 0.5f)
    }

    @Test
    fun `a divider cannot be pushed past the smallest a pane is allowed to be`() {
        val layout = DockEmpty.dockedToScreen("One", DockSide.Left)

        val squashed = layout.withFraction(emptyList(), 0f)
        val stretched = layout.withFraction(emptyList(), 1f)

        assertEquals(MinDockPane, dockRect(assertNotNull(squashed.stepsTo("One")), screen).width)
        assertEquals(MinDockPane, 800f - DockDividerThickness - dockRect(assertNotNull(stretched.stepsTo("One")), screen).width)
    }

    @Test
    fun `a divider drawn without a window the layout keeps still names its own split`() {
        val layout = DockEmpty
            .dockedToScreen("One", DockSide.Left)
            .dockedWith("Two", "One", DockSide.Bottom)
            .dockedToScreen("Gone", DockSide.Left)
        val live = { id: String -> id != "Gone" }

        val drawn = layout.retaining(live).dividers(screen).map { it.path }

        // Gone's split is not drawn at all, so every path through what is drawn is one split short.
        assertEquals(listOf(emptyList(), listOf(true)), drawn)
        assertEquals(listOf(false), layout.pathRetaining(drawn[0], live))
        assertEquals(listOf(false, true), layout.pathRetaining(drawn[1], live))
    }

    @Test
    fun `moving a drawn divider moves the split under it and no other`() {
        val layout = DockEmpty
            .dockedToScreen("One", DockSide.Left)
            .dockedWith("Two", "One", DockSide.Bottom)
            .dockedToScreen("Gone", DockSide.Left)
        val live = { id: String -> id != "Gone" }
        val drawn = layout.retaining(live).dividers(screen)

        val moved = layout.withFraction(assertNotNull(layout.pathRetaining(drawn[1].path, live)), 0.75f)

        val after = moved.retaining(live).dividers(screen)
        assertEquals(0.75f, after[1].fraction, "the fraction should land on the split that was dragged")
        assertEquals(drawn[0].fraction, after[0].fraction, "the split nobody dragged should not have moved")
        assertEquals(layout.stepsTo("Gone"), moved.stepsTo("Gone"), "nor should the one holding the window nobody composed")
        assertTrue(
            dockRect(assertNotNull(moved.retaining(live).stepsTo("One")), screen).height >
                dockRect(assertNotNull(layout.retaining(live).stepsTo("One")), screen).height,
        )
    }

    @Test
    fun `a path through a layout with nothing left in it names no split`() {
        val layout = DockEmpty.dockedToScreen("Gone", DockSide.Left)

        assertNull(layout.pathRetaining(emptyList()) { false })
    }

    @Test
    fun `two panes in a space too small for both share it evenly`() {
        val tiny = Rect.of(0f, 0f, 80f, 40f)
        val layout = DockEmpty.dockedToScreen("One", DockSide.Left)

        val pane = dockRect(assertNotNull(layout.stepsTo("One")), tiny)

        assertEquals((80f - DockDividerThickness) / 2f, pane.width)
    }

    // --- where a drag would land -----------------------------------------------------------------

    @Test
    fun `a drop is only a dock when it is let go on a square`() {
        val windows = listOf("One" to Rect.of(300f, 200f, 200f, 160f))

        assertNull(dropTargetAt(Offset(200f, 20f), screen, windows), "across the top is a window being moved")
        assertEquals(
            DockDrop(null, DockSide.Top),
            dropTargetAt(screenMarkers(screen).first { it.first == DockSide.Top }.second.centre, screen, windows),
        )
    }

    @Test
    fun `the middle square of a window tabs them together and the others split its pane`() {
        val rect = Rect.of(300f, 200f, 200f, 160f)
        val windows = listOf("One" to rect)

        val markers = windowMarkers(rect).associate { (side, square) -> side to square.centre }

        assertEquals(DockDrop("One", null), dropTargetAt(markers.getValue(null), screen, windows))
        assertEquals(DockDrop("One", DockSide.Left), dropTargetAt(markers.getValue(DockSide.Left), screen, windows))
        assertEquals(DockDrop("One", DockSide.Bottom), dropTargetAt(markers.getValue(DockSide.Bottom), screen, windows))
    }

    @Test
    fun `a square that sticks out past a small window still docks into it`() {
        val small = Rect.of(300f, 200f, 60f, 40f)
        val windows = listOf("One" to small)
        val below = windowMarkers(small).first { it.first == DockSide.Bottom }.second.centre

        assertTrue(below !in small, "this square is meant to be outside the window: $small")
        assertEquals("One", assertNotNull(hoveredWindow(below, windows)).first, "the cross is still that window's")
        assertEquals(DockDrop("One", DockSide.Bottom), dropTargetAt(below, screen, windows))
    }

    @Test
    fun `the squares belong to the window in front when two of them overlap`() {
        val front = Rect.of(300f, 200f, 200f, 160f)
        val behind = Rect.of(280f, 180f, 240f, 200f)
        val windows = listOf("Front" to front, "Behind" to behind)

        assertEquals("Front", assertNotNull(hoveredWindow(front.centre, windows)).first)
        assertEquals(DockDrop("Front", null), dropTargetAt(front.centre, screen, windows))
    }

    // --- keeping a layout -------------------------------------------------------------------------

    @Test
    fun `a layout written out reads back as the same layout`() {
        val layout = DockEmpty
            .dockedToScreen("One", DockSide.Left)
            .dockedWith("Two", "One", side = null)
            .dockedToScreen("Three", DockSide.Bottom)
            .selecting("One")

        assertEquals(layout, DockLayoutText.read(DockLayoutText.write(layout)))
    }

    @Test
    fun `a window named with the separator in it survives being written out`() {
        val layout = DockEmpty.dockedToScreen("Fight | round 2", DockSide.Left)

        assertEquals(layout, DockLayoutText.read(DockLayoutText.write(layout)))
        assertEquals(listOf("Fight | round 2"), DockLayoutText.read(DockLayoutText.write(layout)).windows())
    }

    @Test
    fun `anything that is not a layout is read as nothing docked`() {
        listOf("", "x", "s|h", "s|h|0.25|t", "t|0|2|One", "t|0|2|One|One", null).forEach { text ->
            assertEquals(DockEmpty, DockLayoutText.read(text), "read <$text>")
        }
    }
}
