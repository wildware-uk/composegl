package dev.wildware.composegl.ui.node

import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.padding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [UiNode.changes], on a tree built by hand: what counts, what does not, and that a frame is the unit.
 *
 * The composed half — a click changing a label, a lambda written inline — is in `RedrawOverlayTest`, in `composegl-debug`
 * and `BusiestNodesTest`. This is the bookkeeping underneath, with no runtime in the way.
 */
class ChangeCountTest {

    private fun tree(): UiTree = UiTree().also {
        it.countChanges = true
        it.clocks.advance(0L)
    }

    private fun UiTree.nextFrame() = clocks.advance(clocks.frameNanos + 16_666_667L)

    @Test
    fun `a tree that is not counting counts nothing but still reports the change`() {
        val tree = UiTree()
        val node = UiNode("a")
        tree.root.insertAt(0, node)
        tree.consumeChanges()

        node.modifier = Modifier.padding(4f)

        assertTrue(tree.consumeChanges(), "the change is still a change")
        assertEquals(0, node.changes)
        assertEquals(0, tree.root.changes)
    }

    @Test
    fun `two changes to one node in one frame count once`() {
        val tree = tree()
        val node = UiNode("a")
        tree.root.insertAt(0, node)
        tree.nextFrame()

        node.modifier = Modifier.padding(4f)
        node.content = {}
        node.ink = { null }
        assertEquals(2, node.changes, "inserted, then one frame of three changes")

        tree.nextFrame()
        node.modifier = Modifier.padding(8f)
        assertEquals(3, node.changes)
    }

    @Test
    fun `an equal chain counts nothing`() {
        val tree = tree()
        val node = UiNode("a")
        tree.root.insertAt(0, node)
        node.modifier = Modifier.padding(4f)
        val before = node.changes

        repeat(5) {
            tree.nextFrame()
            node.modifier = Modifier.padding(4f)
        }
        assertEquals(before, node.changes)
    }

    @Test
    fun `an insert counts the child and a removal or a move counts the parent`() {
        val tree = tree()
        val first = UiNode("first")
        val second = UiNode("second")
        tree.root.insertAt(0, first)
        tree.root.insertAt(1, second)
        assertEquals(1, first.changes)
        assertEquals(1, second.changes)
        assertEquals(0, tree.root.changes, "the parent is not what appeared")

        tree.nextFrame()
        tree.root.move(0, 2, 1)
        assertEquals(listOf("second", "first"), tree.root.childNames())
        assertEquals(1, tree.root.changes)

        tree.nextFrame()
        tree.root.removeAt(0, 1)
        assertEquals(2, tree.root.changes)
    }

    @Test
    fun `a rebuild and a redraw are counted apart`() {
        val tree = tree()
        val node = UiNode("a")
        tree.root.insertAt(0, node)
        assertEquals(1, node.composeChanges, "it appeared")
        assertEquals(0, node.redrawChanges)

        tree.nextFrame()
        tree.redraw(node)
        assertEquals(2, node.changes)
        assertEquals(1, node.composeChanges, "nothing about it was different")
        assertEquals(1, node.redrawChanges)
    }

    @Test
    fun `a frame that both rebuilds and redraws counts once in each and once in all`() {
        val tree = tree()
        val node = UiNode("a")
        tree.root.insertAt(0, node)

        tree.nextFrame()
        node.modifier = Modifier.padding(4f)
        node.content = {}
        tree.redraw(node)
        tree.redraw(node)

        assertEquals(2, node.changes, "one frame is one change")
        assertEquals(2, node.composeChanges, "the frame it appeared, and this one")
        assertEquals(1, node.redrawChanges)
    }

    @Test
    fun `turning counting off stops counting and a reset clears what was counted`() {
        val tree = tree()
        val node = UiNode("a")
        tree.root.insertAt(0, node)
        assertEquals(1, node.changes)

        tree.countChanges = false
        assertFalse(tree.counting)
        tree.nextFrame()
        node.modifier = Modifier.padding(2f)
        assertEquals(1, node.changes)

        tree.resetChangeCounts()
        assertEquals(0, node.changes)
        assertEquals(0, node.composeChanges)
        assertEquals(0, node.redrawChanges)
        assertEquals(NeverChanged, node.changedAtNanos)
    }

    @Test
    fun `watchers keep counting on until the last lets go`() {
        val tree = UiTree()
        tree.watchChanges()
        tree.watchChanges()
        tree.stopWatchingChanges()
        assertTrue(tree.counting, "one still watching")
        tree.stopWatchingChanges()
        assertFalse(tree.counting)
        tree.stopWatchingChanges()
        tree.watchChanges()
        assertTrue(tree.counting, "an extra let-go does not leave the next watcher short")
    }
}
