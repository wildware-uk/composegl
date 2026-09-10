package composegl.ui.layout

import composegl.ui.modifier.Modifier
import composegl.ui.modifier.fillMaxHeight
import composegl.ui.modifier.fillMaxWidth
import composegl.ui.modifier.height
import composegl.ui.modifier.offset
import composegl.ui.modifier.padding
import composegl.ui.modifier.size
import composegl.ui.modifier.weight
import composegl.ui.modifier.width
import composegl.ui.node.UiNode
import composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The two-pass layout model, driven over trees built by hand. */
class MeasurePassTest {

    private val tree = UiTree()

    private fun node(
        name: String,
        modifier: Modifier = Modifier,
        policy: MeasurePolicy = MeasurePolicy.Stack,
        build: UiNode.() -> Unit = {},
    ): UiNode = UiNode(name).also {
        it.modifier = modifier
        it.measurePolicy = policy
        it.build()
    }

    private fun UiNode.child(child: UiNode) = insertAt(children.size, child)

    private fun run(root: UiNode, constraints: Constraints = Constraints.atMost(1000f, 1000f)) {
        tree.root.insertAt(0, root)
        MeasurePass().run(tree.root, constraints)
    }

    // --- sizing ---

    @Test
    fun `size asks for exactly that`() {
        val box = node("box", Modifier.size(40f, 25f))
        run(box)

        assertEquals(40f, box.width)
        assertEquals(25f, box.height)
    }

    @Test
    fun `width and height can be asked for separately`() {
        val box = node("box", Modifier.width(40f).height(25f))
        run(box)

        assertEquals(40f, box.width)
        assertEquals(25f, box.height)
    }

    @Test
    fun `a child cannot escape its parent by asking to be enormous`() {
        val box = node("box", Modifier.size(5000f, 5000f))
        run(box, Constraints.atMost(100f, 100f))

        assertEquals(100f, box.width)
        assertEquals(100f, box.height)
    }

    @Test
    fun `fillMaxWidth takes everything on offer`() {
        val box = node("box", Modifier.fillMaxWidth().height(10f))
        run(box, Constraints.atMost(300f, 300f))

        assertEquals(300f, box.width)
        assertEquals(10f, box.height)
    }

    @Test
    fun `fillMaxWidth takes a share when asked for one`() {
        val box = node("box", Modifier.fillMaxWidth(0.5f).fillMaxHeight(0.25f))
        run(box, Constraints.atMost(300f, 400f))

        assertEquals(150f, box.width)
        assertEquals(100f, box.height)
    }

    @Test
    fun `there is no share of infinity, so fill does nothing when the offer is unbounded`() {
        val box = node("box", Modifier.fillMaxWidth().height(10f))
        run(box, Constraints.Unbounded)

        assertEquals(0f, box.width)
    }

    @Test
    fun `fill wins over size on the axis it names`() {
        val box = node("box", Modifier.size(50f).fillMaxWidth())
        run(box, Constraints.atMost(200f, 200f))

        assertEquals(200f, box.width)
        assertEquals(50f, box.height)
    }

    @Test
    fun `with nothing to say, a node is as big as its children`() {
        val parent = node("parent") { child(node("child", Modifier.size(30f, 70f))) }
        run(parent)

        assertEquals(30f, parent.width)
        assertEquals(70f, parent.height)
    }

    @Test
    fun `stacking sizes to the largest child`() {
        val parent = node("parent") {
            child(node("small", Modifier.size(10f)))
            child(node("large", Modifier.size(60f, 20f)))
        }
        run(parent)

        assertEquals(60f, parent.width)
        assertEquals(20f, parent.height)
    }

    // --- padding ---

    @Test
    fun `padding makes a node bigger than its content`() {
        val parent = node("parent", Modifier.padding(8f)) {
            child(node("child", Modifier.size(20f)))
        }
        run(parent)

        assertEquals(36f, parent.width)
        assertEquals(36f, parent.height)
    }

    @Test
    fun `padding pushes the content in`() {
        val child = node("child", Modifier.size(20f))
        val parent = node("parent", Modifier.padding(left = 8f, top = 4f)) { child(child) }
        run(parent)

        assertEquals(8f, child.x)
        assertEquals(4f, child.y)
    }

    @Test
    fun `padding takes room off what the content is offered`() {
        val child = node("child", Modifier.fillMaxWidth())
        val parent = node("parent", Modifier.size(100f).padding(10f)) { child(child) }
        run(parent)

        assertEquals(80f, child.width)
    }

    @Test
    fun `a node smaller than its own padding has no room for content, not negative room`() {
        val child = node("child", Modifier.fillMaxWidth())
        val parent = node("parent", Modifier.size(10f).padding(20f)) { child(child) }
        run(parent)

        assertEquals(0f, child.width)
        assertEquals(10f, parent.width)
    }

    // --- placing ---

    @Test
    fun `positions are relative to the parent, not the screen`() {
        val grandchild = node("grandchild", Modifier.size(5f))
        val child = node("child", Modifier.padding(3f)) { child(grandchild) }
        val parent = node("parent", Modifier.padding(10f)) { child(child) }
        run(parent)

        assertEquals(10f, child.x)
        // 3 from its own parent, not 13 — moving `child` does not move this again.
        assertEquals(3f, grandchild.x)
    }

    @Test
    fun `offset nudges a node from where layout put it`() {
        val child = node("child", Modifier.size(10f).offset(4f, -2f))
        val parent = node("parent", Modifier.padding(10f)) { child(child) }
        run(parent)

        assertEquals(14f, child.x)
        assertEquals(8f, child.y)
    }

    @Test
    fun `offset does not change anybody's size`() {
        val child = node("child", Modifier.size(10f).offset(100f, 100f))
        val parent = node("parent") { child(child) }
        run(parent)

        assertEquals(10f, parent.width)
    }

    // --- a layout of your own ---

    @Test
    fun `a game can write its own layout`() {
        val row = MeasurePolicy { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.loosen()) }
            layout(placeables.sumOf { it.width.toDouble() }.toFloat(), placeables.maxOf { it.height }) {
                var x = 0f
                placeables.forEach {
                    it.at(x, 0f)
                    x += it.width
                }
            }
        }
        val a = node("a", Modifier.size(10f, 4f))
        val b = node("b", Modifier.size(20f, 9f))
        val parent = node("parent", policy = row) {
            child(a)
            child(b)
        }
        run(parent)

        assertEquals(30f, parent.width)
        assertEquals(9f, parent.height)
        assertEquals(0f, a.x)
        assertEquals(10f, b.x)
    }

    @Test
    fun `a layout can read what its children asked of it`() {
        val weights = mutableListOf<Float?>()
        val policy = MeasurePolicy { measurables, constraints ->
            measurables.forEach { weights += it.layoutData.weight }
            measurables.forEach { it.measure(constraints) }
            layout(0f, 0f) {}
        }
        val parent = node("parent", policy = policy) {
            child(node("heavy", Modifier.weight(3f)))
            child(node("plain"))
        }
        run(parent)

        assertEquals(listOf(3f, null), weights)
    }

    @Test
    fun `measuring a child twice fails loudly`() {
        val greedy = MeasurePolicy { measurables, constraints ->
            measurables.forEach { it.measure(constraints) }
            measurables.forEach { it.measure(constraints) }
            layout(0f, 0f) {}
        }
        val parent = node("parent", policy = greedy) { child(node("victim")) }

        val error = assertThrows<IllegalStateException> { run(parent) }

        assertEquals(true, error.message!!.startsWith("victim was measured twice in one pass"))
    }

    @Test
    fun `measuring the same node again in the next pass is fine`() {
        val box = node("box", Modifier.size(10f))
        tree.root.insertAt(0, box)

        MeasurePass().run(tree.root, Constraints.atMost(100f, 100f))
        MeasurePass().run(tree.root, Constraints.atMost(100f, 100f))

        assertEquals(10f, box.width)
    }

    @Test
    fun `a leaf with nothing inside takes the smallest size allowed`() {
        val leaf = node("leaf", policy = MeasurePolicy.Empty)

        // Measured directly: a parent would loosen the minimum before offering it on.
        MeasurePass().measure(leaf, Constraints(minWidth = 7f, maxWidth = 100f))

        assertEquals(7f, leaf.width)
        assertEquals(0f, leaf.height)
    }

    @Test
    fun `a parent offers its children room, not an order`() {
        val leaf = node("leaf", policy = MeasurePolicy.Empty)
        run(leaf, Constraints(minWidth = 50f, maxWidth = 100f))

        // The root had to be at least 50 wide; the child did not, so it is as small as it likes.
        assertEquals(0f, leaf.width)
        assertEquals(50f, tree.root.width)
    }
}
