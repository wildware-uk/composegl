package dev.wildware.composegl.ui.focus

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.focusOrder
import dev.wildware.composegl.ui.modifier.focusRequester
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Directional focus, judged the way a player judges it: press right, and the thing to the right
 * lights up.
 *
 * The layouts here are the ones that break naive implementations — a grid, a row that wrapped, and
 * an L. Every one of them is built and placed by hand, because focus is about rectangles.
 */
class FocusManagerTest {

    private val tree = UiTree()

    private fun box(
        name: String,
        x: Float,
        y: Float,
        width: Float = 40f,
        height: Float = 20f,
        modifier: Modifier = Modifier.focusable(),
    ): UiNode = UiNode(name).also {
        it.modifier = modifier
        tree.root.insertAt(tree.root.children.size, it)
        it.x = x
        it.y = y
        it.width = width
        it.height = height
    }

    private fun manager(autoFocus: Boolean = true) = FocusManager(tree.root, autoFocus)

    private fun FocusManager.name(): String? = focused?.name

    // --- a grid -------------------------------------------------------------------------------

    /** Three by three, fifty apart. The layout every inventory screen in the world is. */
    private fun grid() {
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                box("r${row}c$column", column * 50f, row * 30f)
            }
        }
    }

    @Test
    fun `a grid moves one cell at a time, in the direction pressed`() {
        grid()
        val focus = manager()
        focus.focusOn(tree.root.children.first { it.name == "r1c1" })

        assertTrue(focus.moveFocus(FocusDirection.Right))
        assertEquals("r1c2", focus.name())

        assertTrue(focus.moveFocus(FocusDirection.Down))
        assertEquals("r2c2", focus.name())

        assertTrue(focus.moveFocus(FocusDirection.Left))
        assertEquals("r2c1", focus.name())

        assertTrue(focus.moveFocus(FocusDirection.Up))
        assertEquals("r1c1", focus.name())
    }

    @Test
    fun `a grid never moves diagonally, however close the diagonal is`() {
        grid()
        val focus = manager()
        focus.focusOn(tree.root.children.first { it.name == "r0c0" })

        // r1c1 is nearer by straight-line distance than r0c1 is wide. It still must not win.
        focus.moveFocus(FocusDirection.Right)
        assertEquals("r0c1", focus.name())
    }

    @Test
    fun `the edge of a grid is the end of the road`() {
        grid()
        val focus = manager()
        focus.focusOn(tree.root.children.first { it.name == "r0c2" })

        assertFalse(focus.moveFocus(FocusDirection.Right), "nothing to the right of the last column")
        assertEquals("r0c2", focus.name(), "and focus stayed where it was")
    }

    // --- a wrapped row ------------------------------------------------------------------------

    @Test
    fun `a wrapped row needs to be told where the wrap goes`() {
        // Five items in a row three wide: 0 1 2 on the first line, 3 4 on the second. Pressing
        // right at the end of the first line should reach 3, and geometry alone says there is
        // nothing to the right of 2 at all.
        val endOfLine = FocusRequester()
        val startOfNext = FocusRequester()
        box("0", 0f, 0f)
        box("1", 50f, 0f)
        box("2", 100f, 0f, modifier = Modifier.focusable().focusRequester(endOfLine).focusOrder(right = startOfNext))
        box("3", 0f, 30f, modifier = Modifier.focusable().focusRequester(startOfNext).focusOrder(left = endOfLine))
        box("4", 50f, 30f)

        val focus = manager()
        focus.focusOn(tree.root.children.first { it.name == "2" })

        assertTrue(focus.moveFocus(FocusDirection.Right))
        assertEquals("3", focus.name(), "the wrap was declared, so it happened")

        assertTrue(focus.moveFocus(FocusDirection.Left))
        assertEquals("2", focus.name(), "and back again")
    }

    @Test
    fun `a short second line still finds something above it`() {
        box("0", 0f, 0f)
        box("1", 50f, 0f)
        box("2", 100f, 0f)
        box("3", 0f, 30f)
        box("4", 50f, 30f)

        val focus = manager()
        focus.focusOn(tree.root.children.first { it.name == "4" })

        focus.moveFocus(FocusDirection.Up)
        assertEquals("1", focus.name(), "straight up, not diagonally to 0 or 2")
    }

    // --- an L-shaped menu ---------------------------------------------------------------------

    @Test
    fun `an L-shaped menu turns the corner`() {
        // A column down the left, then a row along the bottom.
        box("top", 0f, 0f)
        box("middle", 0f, 30f)
        box("corner", 0f, 60f)
        box("right1", 50f, 60f)
        box("right2", 100f, 60f)

        val focus = manager()
        focus.focusOn(tree.root.children.first { it.name == "top" })

        focus.moveFocus(FocusDirection.Down)
        assertEquals("middle", focus.name())
        focus.moveFocus(FocusDirection.Down)
        assertEquals("corner", focus.name())
        focus.moveFocus(FocusDirection.Right)
        assertEquals("right1", focus.name())
        focus.moveFocus(FocusDirection.Right)
        assertEquals("right2", focus.name())

        // Pressing up from the far end of the bottom leg: nothing is directly above it, so focus
        // goes to the nearest thing that is above at all rather than refusing to move. Getting
        // stuck in the corner of an L is the failure players actually complain about.
        assertTrue(focus.moveFocus(FocusDirection.Up))
        assertEquals("middle", focus.name())
    }

    // --- tab ----------------------------------------------------------------------------------

    @Test
    fun `tab and shift-tab walk the same set in tree order, and wrap`() {
        box("a", 0f, 0f)
        box("b", 50f, 0f)
        box("c", 100f, 0f)

        val focus = manager()
        focus.focusOn(tree.root.children.first { it.name == "a" })

        focus.moveFocus(FocusDirection.Next)
        assertEquals("b", focus.name())
        focus.moveFocus(FocusDirection.Next)
        assertEquals("c", focus.name())
        focus.moveFocus(FocusDirection.Next)
        assertEquals("a", focus.name(), "tab wraps; a menu you can tab off the end of traps you")

        focus.moveFocus(FocusDirection.Previous)
        assertEquals("c", focus.name())
    }

    // --- never nothing ------------------------------------------------------------------------

    @Test
    fun `a menu opens with something selected`() {
        box("a", 0f, 0f)
        box("b", 50f, 0f, modifier = Modifier.focusable(initial = true))

        val focus = manager()
        focus.refresh()

        assertEquals("b", focus.name(), "the screen said where focus starts")
    }

    @Test
    fun `without a declared start, focus goes to the first focusable`() {
        box("a", 0f, 0f)
        box("b", 50f, 0f)

        val focus = manager()
        focus.refresh()

        assertEquals("a", focus.name())
    }

    @Test
    fun `the first direction press starts focus rather than moving it`() {
        box("a", 0f, 0f)
        box("b", 50f, 0f)

        val focus = manager(autoFocus = false)
        assertNull(focus.focused)

        assertTrue(focus.moveFocus(FocusDirection.Right))
        assertEquals("a", focus.name(), "pressing a direction in an untouched menu selects something")
    }

    @Test
    fun `focus survives the node it was on being taken away`() {
        box("a", 0f, 0f)
        val b = box("b", 50f, 0f)

        val focus = manager()
        focus.focusOn(b)
        assertEquals("b", focus.name())

        tree.root.removeAt(tree.root.children.indexOf(b), 1)
        focus.refresh()

        assertEquals("a", focus.name(), "the screen changed; focus landed somewhere real")
    }

    @Test
    fun `a disabled node is not in the running`() {
        box("a", 0f, 0f)
        box("off", 50f, 0f, modifier = Modifier.focusable(enabled = false))
        box("c", 100f, 0f)

        val focus = manager()
        focus.refresh()
        assertEquals("a", focus.name())

        focus.moveFocus(FocusDirection.Right)
        assertEquals("c", focus.name(), "the disabled one is stepped over, not stopped at")
    }

    @Test
    fun `the widget hears about focus through the state it already reads`() {
        val state = InteractionState()
        val other = InteractionState()
        box("a", 0f, 0f, modifier = Modifier.focusable(state))
        box("b", 50f, 0f, modifier = Modifier.focusable(other))

        val focus = manager()
        focus.refresh()
        assertTrue(state.isFocused)
        assertFalse(other.isFocused)

        focus.moveFocus(FocusDirection.Right)
        assertFalse(state.isFocused, "focus is exactly one node, so the old one lets go")
        assertTrue(other.isFocused)

        focus.clearFocus()
        assertFalse(other.isFocused)
        assertNull(focus.focused)
    }

    @Test
    fun `a requester sends focus straight to its node`() {
        val wanted = FocusRequester()
        box("a", 0f, 0f)
        box("b", 50f, 0f, modifier = Modifier.focusable().focusRequester(wanted))

        val focus = manager()
        focus.refresh()
        assertEquals("a", focus.name())

        assertTrue(focus.focusOn(wanted))
        assertEquals("b", focus.name())

        assertFalse(focus.focusOn(FocusRequester()), "a handle attached to nothing focuses nothing")
    }

    // --- nodes with no area -------------------------------------------------------------------

    @Test
    fun `a zero-size node between two real ones does not steal the press`() {
        // A collapsed panel, or a node part way through being inserted: it has a position but no
        // area, so there is nothing to draw a focus ring round and nothing to press. It sits
        // nearer than the real button, and by weighted distance alone it would win.
        box("left", 0f, 0f)
        box("ghost", 50f, 5f, width = 0f, height = 0f)
        box("right", 100f, 0f)

        val focus = manager()
        focus.focusOn(tree.root.children.first { it.name == "left" })

        assertTrue(focus.moveFocus(FocusDirection.Right))
        assertEquals("right", focus.name(), "the collapsed node is skipped, not landed on")
    }

    @Test
    fun `a zero-size node below does not steal a downward press`() {
        // The same rule, pressed the other way. accepts() asks a separate question per direction,
        // so each one is its own chance to let a node with no area through — and the bug was
        // reported against Down.
        box("top", 0f, 0f)
        box("ghost", 5f, 50f, width = 0f, height = 0f)
        box("bottom", 0f, 100f)

        val focus = manager()
        focus.focusOn(tree.root.children.first { it.name == "top" })

        assertTrue(focus.moveFocus(FocusDirection.Down))
        assertEquals("bottom", focus.name(), "the collapsed node is skipped, not landed on")
    }

    @Test
    fun `a zero-size node alone in a direction is not somewhere to go`() {
        // With nothing real that way, the press does nothing at all and focus stays put. That is
        // the honest answer — the caller reads the false and decides whether to scroll or wrap —
        // and it is better than parking the player on a node they cannot see or press.
        box("left", 0f, 0f)
        box("ghost", 100f, 0f, width = 0f, height = 0f)

        val focus = manager()
        focus.focusOn(tree.root.children.first { it.name == "left" })

        assertFalse(focus.moveFocus(FocusDirection.Right), "nothing real to the right")
        assertEquals("left", focus.name(), "so focus stays where it was")
    }

    @Test
    fun `focus can still leave a zero-size node`() {
        // Not a fixed bug: this guards a design decision. Refusing to move away from a node with
        // no area, as well as refusing to move onto one, was considered and rejected. A node with
        // no area stays reachable by tab, by a requester and by focusOrder, so a rule that looked
        // at the source too would let focus in and never let it out.
        val ghost = box("ghost", 50f, 50f, width = 0f, height = 0f)
        box("below", 40f, 100f)

        val focus = manager()
        focus.focusOn(ghost)
        assertEquals("ghost", focus.name())

        assertTrue(focus.moveFocus(FocusDirection.Down))
        assertEquals("below", focus.name(), "a node with no area is not a prison")
    }

    // --- supplying the scoring ----------------------------------------------------------------

    /**
     * The example in [BeamFocusSearch]'s own documentation, written out here so the snippet is
     * compiled rather than merely read.
     */
    private class RoomierDown : BeamFocusSearch() {
        override fun accepts(direction: FocusDirection, source: Rect, dest: Rect): Boolean =
            super.accepts(direction, source, dest) ||
                (direction == FocusDirection.Down && dest.centre.y > source.centre.y)
    }

    @Test
    fun `a screen can widen what counts as a candidate`() {
        // The reported layout: a wide card over two narrow buttons. The default asks a candidate's
        // bottom edge to fall below the source's bottom edge, and a short button beside a tall card
        // never gets there, so pressing down refuses to move.
        //
        // The far button is inserted first on purpose. The first candidate a scan accepts is taken
        // on accepts() alone; every one after it has to win through beats(). Putting the winner
        // second is what makes this test notice if beats() stops asking the supplied search.
        box("card", 0f, 0f, width = 100f, height = 100f)
        box("far", 400f, 50f, width = 60f, height = 20f)
        box("near", 200f, 50f, width = 60f, height = 20f)

        val focus = manager()
        focus.focusOn(tree.root.children.first { it.name == "card" })
        assertFalse(focus.moveFocus(FocusDirection.Down), "the default scoring says there is nothing below")

        focus.focusSearch = RoomierDown()

        assertTrue(focus.moveFocus(FocusDirection.Down))
        assertEquals("near", focus.name(), "both buttons were candidates, and the closer one won")
    }

    @Test
    fun `a screen can change which candidate wins`() {
        // Widening accepts() is half the seam. This is the other half: both buttons are ordinary
        // candidates the default would pick between, and the screen's own rule picks the other one.
        box("card", 0f, 0f, width = 100f, height = 40f)
        box("near", 0f, 100f)
        box("far", 0f, 200f)

        val focus = manager()
        focus.focusOn(tree.root.children.first { it.name == "card" })

        assertTrue(focus.moveFocus(FocusDirection.Down))
        assertEquals("near", focus.name(), "the default takes the first thing below")

        focus.focusOn(tree.root.children.first { it.name == "card" })
        focus.focusSearch = object : BeamFocusSearch() {
            // Down means the end of the list, the way Page Down does.
            override fun beats(direction: FocusDirection, source: Rect, rect: Rect, against: Rect) =
                accepts(direction, source, rect) &&
                    (!accepts(direction, source, against) ||
                        score(direction, source, rect) > score(direction, source, against))
        }

        assertTrue(focus.moveFocus(FocusDirection.Down))
        assertEquals("far", focus.name(), "the screen's own comparison chose the other one")
    }

    /**
     * The default search, taken apart and put back together out of the pieces a subclass is given.
     *
     * Its answers do not matter much; what matters is that it compiles. Every protected member
     * [BeamFocusSearch] advertises is named here, so narrowing one would break this build instead
     * of somebody else's.
     */
    private class Rebuilt : BeamFocusSearch() {
        override fun beats(
            direction: FocusDirection,
            source: Rect,
            rect: Rect,
            against: Rect,
        ): Boolean {
            if (!accepts(direction, source, rect)) return false
            if (!accepts(direction, source, against)) return true
            if (beamBeats(direction, source, rect, against)) return true
            if (inBeam(direction, source, rect) && !inBeam(direction, source, against)) return true
            val reach = majorDistanceToFarEdge(direction, source, against)
            if (majorDistance(direction, source, rect) >= reach) return false
            if (minorDistance(direction, source, rect) > minorDistance(direction, source, against)) return false
            return score(direction, source, rect) < score(direction, source, against)
        }
    }

    @Test
    fun `a subclass can rebuild the search out of the measurements it is given`() {
        box("a", 0f, 0f)
        box("b", 50f, 0f)
        box("c", 100f, 0f)

        val focus = manager()
        focus.focusSearch = Rebuilt()
        focus.focusOn(tree.root.children.first { it.name == "a" })

        assertTrue(focus.moveFocus(FocusDirection.Right))
        assertEquals("b", focus.name(), "the next one along, same as the default")
    }
}
