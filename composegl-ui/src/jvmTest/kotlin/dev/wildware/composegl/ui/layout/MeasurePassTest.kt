package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.aspectRatio
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.layoutId
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
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

    // --- aspect ratio ---

    @Test
    fun `aspectRatio derives the height from a filled width`() {
        val box = node("box", Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        run(box, Constraints.atMost(320f, 1000f))

        assertEquals(320f, box.width)
        assertEquals(180f, box.height)
    }

    @Test
    fun `aspectRatio derives the width from a filled height`() {
        val box = node("box", Modifier.fillMaxHeight().aspectRatio(3f / 4f))
        run(box, Constraints.atMost(1000f, 400f))

        assertEquals(300f, box.width)
        assertEquals(400f, box.height)
    }

    @Test
    fun `aspectRatio derives the height from a fixed width`() {
        val box = node("box", Modifier.width(120f).aspectRatio(2f))
        run(box)

        assertEquals(120f, box.width)
        assertEquals(60f, box.height)
    }

    @Test
    fun `aspectRatio on its own takes the widest shape that fits`() {
        val square = node("square", Modifier.aspectRatio(1f))
        run(square, Constraints.atMost(300f, 200f))

        assertEquals(200f, square.width, "300 wide would need 300 tall, and only 200 is on offer")
        assertEquals(200f, square.height)
    }

    @Test
    fun `aspectRatio keeps the bounded axis and clamps the derived one`() {
        // Full width in a short slot: 400 wide at 3:4 would be 533 tall, and the slot is 300.
        val box = node("box", Modifier.fillMaxWidth().aspectRatio(3f / 4f))
        run(box, Constraints.atMost(400f, 300f))

        assertEquals(400f, box.width, "the axis that was asked for is kept")
        assertEquals(300f, box.height, "the derived one is clamped rather than overflowing")
    }

    @Test
    fun `aspectRatio keeps a filled height when the width cannot follow it`() {
        // Full height in a narrow slot: 400 tall at 16:9 would be 711 wide, and the slot is 300.
        val box = node("box", Modifier.fillMaxHeight().aspectRatio(16f / 9f))
        run(box, Constraints.atMost(300f, 400f))

        assertEquals(400f, box.height, "the axis that was asked for is kept")
        assertEquals(300f, box.width, "the derived one is clamped rather than overflowing")
    }

    @Test
    fun `matchHeightConstraintsFirst tries the height before the width`() {
        // No square fits 350..400 wide and at most 100 tall, so the one that is tried first is kept
        // on its own axis and the other axis is clamped. Each node is measured as a root, because a
        // parent would loosen the minimum away before the node ever saw it.
        val offer = Constraints(minWidth = 350f, maxWidth = 400f, maxHeight = 100f)

        val widthFirst = node("wide", Modifier.aspectRatio(1f))
        MeasurePass().run(widthFirst, offer)
        assertEquals(400f, widthFirst.width, "the widest square, cut down to the height on offer")
        assertEquals(100f, widthFirst.height)

        val heightFirst = node("tall", Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true))
        MeasurePass().run(heightFirst, offer)
        assertEquals(350f, heightFirst.width, "the tallest square, pushed out to the narrowest width")
        assertEquals(100f, heightFirst.height)
    }

    @Test
    fun `with nothing bounded aspectRatio leaves the size to the content`() {
        val box = node("box", Modifier.aspectRatio(2f)) { child(node("child", Modifier.size(30f, 70f))) }
        run(box, Constraints.Unbounded)

        assertEquals(30f, box.width)
        assertEquals(70f, box.height)
    }

    @Test
    fun `aspectRatio shapes a weighted child in a row`() {
        val policy = LinearPolicy(true, Arrangement.Start, Alignment(vertical = VerticalAlignment.Top))
        val row = node("row", Modifier.width(300f), policy) {
            child(node("left", Modifier.weight(1f).aspectRatio(1f)))
            child(node("right", Modifier.weight(2f).aspectRatio(2f)))
        }
        run(row)

        val (left, right) = row.children
        assertEquals(100f, left.width)
        assertEquals(100f, left.height)
        assertEquals(200f, right.width)
        assertEquals(100f, right.height)
    }

    @Test
    fun `padding sits inside the shaped box`() {
        val box = node("box", Modifier.width(200f).aspectRatio(2f).padding(10f)) {
            child(node("child", Modifier.fillMaxSize()))
        }
        run(box)

        assertEquals(100f, box.height)
        assertEquals(180f, box.children.single().width)
        assertEquals(80f, box.children.single().height)
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
    fun `a layout can find a child by the name it was given rather than by where it is`() {
        val slots = MeasurePolicy { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.loosen()) }
            val icon = placeables[measurables.indexOfFirst { it.layoutId == "icon" }]
            layout(100f, 10f) {
                placeables.forEach { it.at(0f, 0f) }
                icon.at(60f, 0f)
            }
        }
        val icon = node("icon", Modifier.size(10f).layoutId("icon"))
        val parent = node("parent", policy = slots) {
            child(node("unnamed", Modifier.size(10f)))
            child(icon)
        }
        run(parent)

        assertEquals(60f, icon.x)
    }

    @Test
    fun `a child given no name has none`() {
        val ids = mutableListOf<Any?>()
        val policy = MeasurePolicy { measurables, constraints ->
            measurables.forEach { ids += it.layoutId }
            measurables.forEach { it.measure(constraints) }
            layout(0f, 0f) {}
        }
        val parent = node("parent", policy = policy) {
            child(node("named", Modifier.layoutId("icon")))
            child(node("plain"))
        }
        run(parent)

        assertEquals(listOf("icon", null), ids)
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
