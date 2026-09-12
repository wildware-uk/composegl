package dev.wildware.composegl.ui.testing

import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.node.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The fixture, tested the way the tests that use it will use it.
 *
 * It lives in the common source set so this runs on a JVM and on Linux native — the fixture is
 * shipped library code, and shipped library code that only compiles for a JVM is a promise nobody
 * checked.
 */
class TestTreeTest {

    @Test
    fun `a box is where you put it with no pass and no composition`() {
        val screen = TestTree()

        val node = screen.box("panel", x = 100f, y = 200f, width = 60f, height = 30f)

        assertEquals(100f, node.x)
        assertEquals(200f, node.y)
        assertEquals(60f, node.width)
        assertEquals(30f, node.height)
        assertEquals(node, screen["panel"], "and the name is how a test finds it again")
    }

    @Test
    fun `a row is evenly spaced and a column is too`() {
        val screen = TestTree()

        screen.row("a", "b", "c")
        screen.column("x", "y", parent = screen["a"])

        assertEquals(listOf(0f, 50f, 100f), listOf("a", "b", "c").map { screen[it].x })
        assertEquals(listOf(0f, 30f), listOf("x", "y").map { screen[it].y })
        assertEquals(listOf("x", "y"), screen["a"].childNames(), "the parent was honoured")
    }

    @Test
    fun `a child's rectangle is inside its parent the way the tree means it`() {
        val screen = TestTree()

        val panel = screen.box("panel", x = 100f, y = 100f, width = 200f, height = 100f)
        screen.box("label", x = 10f, y = 20f, parent = panel)

        assertEquals(10f, screen["label"].x, "stored relative to the parent")
        assertEquals(110f, screen["label"].boundsInRoot.left, "and 110 on the screen")
    }

    @Test
    fun `a real measure pass leaves every rectangle where it was put`() {
        val screen = TestTree()
        screen.row("a", "b", "c")

        screen.layOut()

        assertEquals(listOf(0f, 50f, 100f), listOf("a", "b", "c").map { screen[it].x })
        assertEquals(40f, screen["b"].width, "the size survived the pass too")
    }

    @Test
    fun `a modifier of the caller's own does not move the rectangle either`() {
        // The guarantee above, with the thing tests actually pass. Everything a caller is allowed
        // to say here is about behaviour rather than geometry, so a real pass under real
        // constraints has to hand back the same numbers it does for a bare Modifier.
        val screen = TestTree()
        screen.box(
            "a",
            x = 10f,
            y = 5f,
            modifier = Modifier.focusable().clickable { }.then(Modifier.focusable()),
        )

        screen.layOut(Constraints.atMost(500f, 500f))

        assertEquals(10f, screen["a"].x)
        assertEquals(5f, screen["a"].y)
        assertEquals(40f, screen["a"].width)
        assertEquals(20f, screen["a"].height)
    }

    @Test
    fun `a modifier that says where or how big is refused rather than silently added`() {
        // Each of these used to go through and come out wrong at layOut: the fixture's own offset
        // is appended to the caller's chain and offsets add up (10 + 5 = 15), fill beats size on
        // the axis it names (40 becomes 500), and padding on a box shifts every child in it.
        val screen = TestTree()

        val refused = listOf(
            Modifier.offset(5f, 0f),
            Modifier.size(60f),
            Modifier.fillMaxWidth(),
            Modifier.padding(8f),
        ).map { modifier ->
            assertFailsWith<IllegalStateException> {
                screen.box("a", x = 10f, modifier = modifier)
            }.message.orEmpty()
        }

        refused.forEach { assertTrue(it.contains("says where it is or how big"), it) }
        assertEquals(emptyList(), screen.root.childNames(), "and no half-made node was left behind")
    }

    @Test
    fun `the constraints a fresh root would have flatten the tree and that is caught`() {
        // Why layOut does not default to the root's own size: a root nobody has measured is 0 by
        // 0, and a size modifier is clamped into the constraints it is offered, so every box in
        // the tree would come back as nothing at all. The default cannot do this; asking for it
        // by hand still can, and then the whole-tree check says so rather than letting a test
        // assert about a screen where nothing is anywhere.
        val screen = TestTree()
        screen.row("a", "b")

        val failure = assertFailsWith<IllegalStateException> {
            screen.layOut(Constraints.atMost(screen.root.width, screen.root.height))
        }

        assertTrue(failure.message.orEmpty().contains("any area left"), failure.message.orEmpty())
    }

    @Test
    fun `one node measuring to nothing is not a failure`() {
        // A stacking leaf with no size of its own honestly measures to nothing. The check is about
        // the whole tree being empty, not about any one node in it.
        val screen = TestTree()
        screen.row("a", "b")
        screen.root.insertAt(screen.root.children.size, UiNode("ghost"))

        screen.layOut()

        // The ghost has no area and the pass was happy anyway, which is only news because the
        // other two do: that is the difference between a whole-tree check and a per-node one.
        assertEquals(0f, screen["ghost"].width)
        assertEquals(listOf(40f, 40f), listOf("a", "b").map { screen[it].width })
    }

    @Test
    fun `a tree flattened on one axis is caught too because a sliver cannot be hit`() {
        val screen = TestTree()
        screen.row("a", "b")

        val failure = assertFailsWith<IllegalStateException> {
            screen.layOut(Constraints.atMost(0f, 200f))
        }

        assertTrue(failure.message.orEmpty().contains("any area left"), failure.message.orEmpty())
    }

    @Test
    fun `a name is claimed once because a name is how a test finds a node`() {
        val screen = TestTree()
        screen.box("a")

        val failure = assertFailsWith<IllegalStateException> { screen.box("a") }

        assertTrue(
            failure.message.orEmpty().contains("already a node called"),
            failure.message.orEmpty(),
        )
    }

    @Test
    fun `nodes can be taken away again one at a time or all at once`() {
        val screen = TestTree()
        screen.row("a", "b", "c")

        screen.remove("b")
        assertEquals(listOf("a", "c"), screen.root.childNames())

        screen.clear()
        assertEquals(emptyList(), screen.root.childNames())
    }

    @Test
    fun `the focus tests' own question asked over the fixture`() {
        val screen = TestTree()
        screen.row("cut", "copy", "paste", modifier = Modifier.focusable())

        val focus = FocusManager(screen.root)
        focus.focusOn(screen["cut"])

        assertTrue(focus.moveFocus(FocusDirection.Right))
        assertEquals("copy", focus.focused?.name)
    }
}
