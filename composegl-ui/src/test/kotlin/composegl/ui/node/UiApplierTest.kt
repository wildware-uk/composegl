package composegl.ui.node

import composegl.ui.graphics.Colour
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.background
import composegl.ui.modifier.padding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The tree operations, driven through the real applier.
 *
 * These go through `UiApplier` rather than calling the node directly, because the applier is what
 * the runtime talks to and its `insertBottomUp`/`move` contract is the part that is easy to get
 * subtly wrong. A composition is not needed to exercise it.
 */
class UiApplierTest {

    private val tree = UiTree()
    private val applier = UiApplier(tree.root)

    private fun node(name: String) = UiNode(name)

    private fun insert(vararg names: String) {
        names.forEachIndexed { index, name -> applier.insertBottomUp(index, node(name)) }
    }

    @Test
    fun `inserts land in order`() {
        insert("a", "b", "c")

        assertEquals(listOf("a", "b", "c"), tree.root.childNames())
    }

    @Test
    fun `an insert in the middle pushes the rest along`() {
        insert("a", "b", "c")

        applier.insertBottomUp(1, node("wedge"))

        assertEquals(listOf("a", "wedge", "b", "c"), tree.root.childNames())
    }

    @Test
    fun `inserted children know their parent and their tree`() {
        insert("a")
        val child = tree.root.children.single()

        assertSame(tree.root, child.parent)
        assertSame(tree, child.tree)
    }

    @Test
    fun `a subtree built before insertion is attached whole`() {
        val branch = node("branch")
        branch.insertAt(0, node("leaf"))
        // Bottom-up: the runtime fills a node in before it hands it over.
        assertNull(branch.tree)

        applier.insertBottomUp(0, branch)

        assertSame(tree, branch.children.single().tree)
    }

    @Test
    fun `a node cannot be in two trees at once`() {
        insert("a")
        val child = tree.root.children.single()

        assertThrows<IllegalArgumentException> { tree.root.insertAt(0, child) }
    }

    @Test
    fun `removing from the middle keeps both ends`() {
        insert("a", "b", "c", "d")

        applier.remove(1, 2)

        assertEquals(listOf("a", "d"), tree.root.childNames())
    }

    @Test
    fun `removing the last child leaves an empty parent`() {
        insert("only")

        applier.remove(0, 1)

        assertEquals(emptyList<String>(), tree.root.childNames())
    }

    @Test
    fun `a removed subtree is detached, not just unlisted`() {
        val branch = node("branch").also { it.insertAt(0, node("leaf")) }
        applier.insertBottomUp(0, branch)

        applier.remove(0, 1)

        assertNull(branch.parent)
        assertNull(branch.tree)
        assertNull(branch.children.single().tree)
    }

    @Test
    fun `a removed subtree can be inserted again`() {
        val branch = node("branch")
        applier.insertBottomUp(0, branch)
        applier.remove(0, 1)

        applier.insertBottomUp(0, branch)

        assertEquals(listOf("branch"), tree.root.childNames())
        assertSame(tree, branch.tree)
    }

    @Test
    fun `moving one child forwards`() {
        insert("a", "b", "c", "d")

        applier.move(0, 3, 1)

        assertEquals(listOf("b", "c", "a", "d"), tree.root.childNames())
    }

    @Test
    fun `moving one child backwards`() {
        insert("a", "b", "c", "d")

        applier.move(3, 0, 1)

        assertEquals(listOf("d", "a", "b", "c"), tree.root.childNames())
    }

    @Test
    fun `moving a run to the end`() {
        insert("a", "b", "c", "d", "e")

        // `to` counts positions in the list as it stands now, so 5 means "past the last child".
        applier.move(0, 5, 2)

        assertEquals(listOf("c", "d", "e", "a", "b"), tree.root.childNames())
    }

    @Test
    fun `moving a run to the front`() {
        insert("a", "b", "c", "d", "e")

        applier.move(3, 0, 2)

        assertEquals(listOf("d", "e", "a", "b", "c"), tree.root.childNames())
    }

    @Test
    fun `moving a run into the middle`() {
        insert("a", "b", "c", "d", "e")

        applier.move(0, 4, 2)

        assertEquals(listOf("c", "d", "a", "b", "e"), tree.root.childNames())
    }

    @Test
    fun `moving nowhere changes nothing`() {
        insert("a", "b", "c")

        applier.move(1, 1, 1)

        assertEquals(listOf("a", "b", "c"), tree.root.childNames())
    }

    @Test
    fun `clearing empties the root`() {
        insert("a", "b", "c")

        applier.clear()

        assertEquals(emptyList<String>(), tree.root.childNames())
    }

    @Test
    fun `clearing detaches what it removed`() {
        val branch = node("branch").also { it.insertAt(0, node("leaf")) }
        applier.insertBottomUp(0, branch)

        applier.clear()

        assertNull(branch.parent)
        assertNull(branch.children.single().tree)
    }

    @Test
    fun `the applier walks down and back up`() {
        insert("a")
        val a = tree.root.children.single()

        applier.down(a)
        applier.insertBottomUp(0, node("a-child"))
        applier.up()
        applier.insertBottomUp(1, node("b"))

        assertEquals(listOf("a", "b"), tree.root.childNames())
        assertEquals(listOf("a-child"), a.childNames())
    }

    // --- change tracking ---

    @Test
    fun `a fresh tree starts dirty, because nothing has been drawn yet`() {
        assertTrue(tree.consumeChanges())
        assertFalse(tree.consumeChanges())
    }

    @Test
    fun `every structural change is reported`() {
        tree.consumeChanges()
        insert("a", "b")
        assertTrue(tree.consumeChanges())

        applier.move(0, 2, 1)
        assertTrue(tree.consumeChanges())

        applier.remove(0, 1)
        assertTrue(tree.consumeChanges())

        applier.clear()
        assertTrue(tree.consumeChanges())
    }

    @Test
    fun `a new modifier is reported`() {
        insert("a")
        val a = tree.root.children.single()
        tree.consumeChanges()

        a.modifier = Modifier.background(Colour.rgb(0xFF0000))

        assertTrue(tree.consumeChanges())
    }

    @Test
    fun `the same modifier written again is not a change`() {
        insert("a")
        val a = tree.root.children.single()
        a.modifier = Modifier.padding(8f).background(Colour.rgb(0xFF0000))
        tree.consumeChanges()

        // What recomposition does on every run: hand over a freshly built, identical chain.
        a.modifier = Modifier.padding(8f).background(Colour.rgb(0xFF0000))

        assertFalse(tree.consumeChanges())
    }

    @Test
    fun `a detached node reports nothing`() {
        val orphan = node("orphan")
        tree.consumeChanges()

        orphan.modifier = Modifier.padding(4f)

        assertFalse(tree.consumeChanges())
    }

    @Test
    fun `the resolved chain is cached until the chain changes`() {
        val a = node("a")
        a.modifier = Modifier.padding(8f)
        val first = a.resolved

        assertSame(first, a.resolved)

        a.modifier = Modifier.padding(9f)
        assertEquals(9f, a.resolved.padding.left)
    }

    @Test
    fun `debugTree shows the shape`() {
        val branch = node("branch").also { it.insertAt(0, node("leaf")) }
        applier.insertBottomUp(0, branch)

        assertEquals(
            """
            root
              branch
                leaf
            """.trimIndent(),
            tree.root.debugTree(),
        )
    }

    @Test
    fun `forEach visits parents before children`() {
        val branch = node("branch").also { it.insertAt(0, node("leaf")) }
        applier.insertBottomUp(0, branch)

        val seen = mutableListOf<String>()
        tree.root.forEach { seen += it.name }

        assertEquals(listOf("root", "branch", "leaf"), seen)
    }
}
