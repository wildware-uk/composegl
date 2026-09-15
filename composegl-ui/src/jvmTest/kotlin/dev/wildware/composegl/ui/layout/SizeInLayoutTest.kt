package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.defaultMinSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.heightIn
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.sizeIn
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.modifier.widthIn
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `widthIn`, `heightIn`, `sizeIn` and `defaultMinSize`: a range a node's size stays inside, and a
 * minimum that holds only until something more definite says otherwise.
 */
class SizeInLayoutTest {

    private val tree = UiTree()

    private fun node(name: String, modifier: Modifier = Modifier, build: UiNode.() -> Unit = {}): UiNode =
        UiNode(name).also {
            it.modifier = modifier
            it.measurePolicy = MeasurePolicy.Stack
            it.build()
        }

    /** A node whose contents want exactly this much room. */
    private fun holding(modifier: Modifier, width: Float, height: Float = 10f) =
        node("holder", modifier) { insertAt(0, node("contents", Modifier.size(width, height))) }

    private fun run(root: UiNode, constraints: Constraints = Constraints.atMost(1000f, 1000f)): UiNode {
        tree.root.insertAt(0, root)
        // The node itself rather than the tree's root, which loosens what it hands down and so
        // would never let a parent's minimum through.
        MeasurePass().run(root, constraints)
        return root
    }

    // --- a range ---

    @Test
    fun `a minimum lifts contents that are smaller than it`() {
        val box = run(holding(Modifier.widthIn(min = 100f), width = 30f))

        assertEquals(100f, box.width)
    }

    @Test
    fun `a maximum stops contents that are bigger than it`() {
        val box = run(holding(Modifier.widthIn(max = 200f), width = 500f))

        assertEquals(200f, box.width)
        assertEquals(200f, box.children.single().width, "the contents were told, not just cut off")
    }

    @Test
    fun `between the two the contents decide`() {
        val box = run(holding(Modifier.widthIn(min = 100f, max = 200f), width = 150f))

        assertEquals(150f, box.width)
    }

    @Test
    fun `a range cannot escape its parent at either end`() {
        assertEquals(300f, run(holding(Modifier.widthIn(min = 400f), 10f), Constraints.atMost(300f, 300f)).width)
    }

    @Test
    fun `a maximum below the parent's minimum loses to the parent`() {
        val box = run(holding(Modifier.widthIn(max = 50f), 10f), Constraints(minWidth = 80f, maxWidth = 1000f))

        assertEquals(80f, box.width)
    }

    @Test
    fun `a maximum bounds a parent that offered everything`() {
        val box = run(holding(Modifier.widthIn(max = 400f), width = 900f), Constraints.Unbounded)

        assertEquals(400f, box.width)
    }

    @Test
    fun `the two axes are separate statements`() {
        val box = run(holding(Modifier.heightIn(min = 40f), width = 25f, height = 10f))

        assertEquals(25f, box.width, "the width was left to the contents")
        assertEquals(40f, box.height)
    }

    @Test
    fun `sizeIn is all four bounds at once`() {
        val small = run(holding(Modifier.sizeIn(minWidth = 64f, minHeight = 48f, maxWidth = 100f, maxHeight = 80f), 5f, 5f))
        assertEquals(64f, small.width)
        assertEquals(48f, small.height)
    }

    @Test
    fun `sizeIn stops at its maxima`() {
        val big = run(holding(Modifier.sizeIn(minWidth = 64f, minHeight = 48f, maxWidth = 100f, maxHeight = 80f), 500f, 500f))
        assertEquals(100f, big.width)
        assertEquals(80f, big.height)
    }

    @Test
    fun `padding is inside the range`() {
        val box = run(holding(Modifier.widthIn(max = 100f).padding(10f), width = 500f))

        assertEquals(100f, box.width)
        assertEquals(80f, box.children.single().width)
    }

    // --- a range with a size or a fill in the same chain ---

    @Test
    fun `a width is kept inside the range written after it`() {
        assertEquals(400f, run(holding(Modifier.width(500f).widthIn(max = 400f), 10f)).width)
    }

    @Test
    fun `a width is kept inside the range written before it`() {
        assertEquals(400f, run(holding(Modifier.widthIn(max = 400f).width(500f), 10f)).width)
    }

    @Test
    fun `a width below the range is lifted to its minimum`() {
        assertEquals(100f, run(holding(Modifier.width(50f).widthIn(min = 100f), 10f)).width)
    }

    @Test
    fun `fillMaxWidth takes everything up to the maximum`() {
        assertEquals(300f, run(holding(Modifier.fillMaxWidth().widthIn(max = 300f), 10f)).width)
    }

    @Test
    fun `fillMaxWidth under a narrower parent takes the parent`() {
        val box = run(holding(Modifier.widthIn(max = 300f).fillMaxWidth(), 10f), Constraints.atMost(200f, 200f))

        assertEquals(200f, box.width)
    }

    @Test
    fun `a share is a share of the maximum`() {
        assertEquals(150f, run(holding(Modifier.widthIn(max = 300f).fillMaxWidth(0.5f), 10f)).width)
    }

    @Test
    fun `a share that falls short of the minimum is lifted to it`() {
        assertEquals(250f, run(holding(Modifier.widthIn(min = 250f).fillMaxWidth(0.1f), 10f)).width)
    }

    @Test
    fun `a fill still wins over a size on its axis inside a range`() {
        val box = run(holding(Modifier.size(50f).widthIn(max = 300f).fillMaxWidth(), 10f))

        assertEquals(300f, box.width, "the share is of the range, not of the 50 the size said")
        assertEquals(50f, box.height)
    }

    @Test
    fun `fill makes an unbounded range a bounded one`() {
        val box = run(holding(Modifier.widthIn(max = 300f).fillMaxWidth(), 10f), Constraints.Unbounded)

        assertEquals(300f, box.width, "there is a share of 300 even when there is no share of infinity")
    }

    // --- a default minimum ---

    @Test
    fun `a default minimum holds when nobody said anything`() {
        val box = run(holding(Modifier.defaultMinSize(minWidth = 48f, minHeight = 48f), width = 10f, height = 10f))

        assertEquals(48f, box.width)
        assertEquals(48f, box.height)
    }

    @Test
    fun `contents bigger than a default minimum are left alone`() {
        assertEquals(90f, run(holding(Modifier.defaultMinSize(minWidth = 48f), width = 90f)).width)
    }

    @Test
    fun `a default minimum gives way to a minimum from the parent`() {
        val box = run(holding(Modifier.defaultMinSize(minWidth = 48f), 10f), Constraints(minWidth = 20f, maxWidth = 1000f))

        assertEquals(20f, box.width)
    }

    @Test
    fun `a default minimum gives way to a width written either side of it`() {
        assertEquals(30f, run(holding(Modifier.width(30f).defaultMinSize(minWidth = 48f), 10f)).width)
    }

    @Test
    fun `a default minimum gives way to a width written after it`() {
        assertEquals(30f, run(holding(Modifier.defaultMinSize(minWidth = 48f).width(30f), 10f)).width)
    }

    @Test
    fun `a default minimum gives way to a fill`() {
        val box = run(holding(Modifier.defaultMinSize(minWidth = 48f).fillMaxWidth(0.02f), 10f))

        assertEquals(20f, box.width)
    }

    @Test
    fun `a default minimum gives way to a range's minimum`() {
        assertEquals(20f, run(holding(Modifier.defaultMinSize(minWidth = 48f).widthIn(min = 20f), 10f)).width)
    }

    @Test
    fun `a default minimum stays under a range's maximum`() {
        assertEquals(40f, run(holding(Modifier.defaultMinSize(minWidth = 48f).widthIn(max = 40f), 10f)).width)
    }

    @Test
    fun `a default minimum stays under the parent's maximum`() {
        val box = run(holding(Modifier.defaultMinSize(minWidth = 48f), 10f), Constraints.atMost(30f, 30f))

        assertEquals(30f, box.width)
    }

    @Test
    fun `a default minimum on one axis says nothing about the other`() {
        val box = run(holding(Modifier.defaultMinSize(minWidth = 48f).height(12f), width = 10f, height = 4f))

        assertEquals(48f, box.width)
        assertEquals(12f, box.height, "a height on the other axis does not cancel the width's default")
    }
}
