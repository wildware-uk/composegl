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
    fun `focus can still leave a zero-size node`() {
        // The other half of the rule. A node with no area stays reachable by tab, by a requester
        // and by focusOrder, so if directions refused to move away from one, focus could get in
        // and never get out.
        val ghost = box("ghost", 50f, 50f, width = 0f, height = 0f)
        box("below", 40f, 100f)

        val focus = manager()
        focus.focusOn(ghost)
        assertEquals("ghost", focus.name())

        assertTrue(focus.moveFocus(FocusDirection.Down))
        assertEquals("below", focus.name(), "a node with no area is not a prison")
    }

    // --- supplying the scoring ----------------------------------------------------------------

    @Test
    fun `a screen can widen what counts as a candidate`() {
        // A wide card with a narrow button beside and slightly below it: the default scoring wants
        // a candidate to clear the source's bottom edge, so pressing down refuses to move.
        box("card", 0f, 0f, width = 100f, height = 100f)
        box("button", 200f, 50f, width = 60f, height = 20f)

        val focus = manager()
        focus.focusOn(tree.root.children.first { it.name == "card" })
        assertFalse(focus.moveFocus(FocusDirection.Down), "the default scoring says there is nothing below")

        focus.focusSearch = object : BeamFocusSearch() {
            override fun accepts(direction: FocusDirection, source: Rect, dest: Rect): Boolean =
                super.accepts(direction, source, dest) ||
                    (direction == FocusDirection.Down && dest.centre.y > source.centre.y)
        }

        assertTrue(focus.moveFocus(FocusDirection.Down))
        assertEquals("button", focus.name(), "the screen's own rule found it")
    }
}
