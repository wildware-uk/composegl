package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Rows, columns and boxes, measured over trees built by hand. */
class LinearLayoutTest {

    private val tree = UiTree()

    private fun leaf(name: String, modifier: Modifier = Modifier) =
        UiNode(name).also {
            it.modifier = modifier
            it.measurePolicy = MeasurePolicy.Empty
        }

    private fun row(
        modifier: Modifier = Modifier,
        arrangement: Arrangement = Arrangement.Start,
        alignment: VerticalAlignment = VerticalAlignment.Top,
        children: List<UiNode>,
    ) = group("row", modifier, LinearPolicy(true, arrangement, Alignment(vertical = alignment)), children)

    private fun column(
        modifier: Modifier = Modifier,
        arrangement: Arrangement = Arrangement.Top,
        alignment: HorizontalAlignment = HorizontalAlignment.Start,
        children: List<UiNode>,
    ) = group("column", modifier, LinearPolicy(false, arrangement, Alignment(horizontal = alignment)), children)

    private fun box(
        modifier: Modifier = Modifier,
        contentAlignment: Alignment = Alignment.TopStart,
        children: List<UiNode>,
    ) = group("box", modifier, BoxPolicy(contentAlignment), children)

    private fun group(
        name: String,
        modifier: Modifier,
        policy: MeasurePolicy,
        children: List<UiNode>,
    ) = UiNode(name).also { node ->
        node.modifier = modifier
        node.measurePolicy = policy
        children.forEach { node.insertAt(node.children.size, it) }
    }

    private fun run(root: UiNode, constraints: Constraints = Constraints.atMost(1000f, 1000f)) {
        tree.root.insertAt(0, root)
        MeasurePass().run(tree.root, constraints)
    }

    // --- a row of fixed children ---

    @Test
    fun `a row lays its children out left to right`() {
        val a = leaf("a", Modifier.size(10f, 4f))
        val b = leaf("b", Modifier.size(20f, 9f))
        val row = row(children = listOf(a, b))
        run(row)

        assertEquals(30f, row.width)
        assertEquals(9f, row.height)
        assertEquals(0f, a.x)
        assertEquals(10f, b.x)
    }

    @Test
    fun `a column lays its children out top to bottom`() {
        val a = leaf("a", Modifier.size(10f, 4f))
        val b = leaf("b", Modifier.size(20f, 9f))
        val column = column(children = listOf(a, b))
        run(column)

        assertEquals(20f, column.width)
        assertEquals(13f, column.height)
        assertEquals(0f, a.y)
        assertEquals(4f, b.y)
    }

    @Test
    fun `an empty row is nothing at all`() {
        val row = row(children = emptyList())
        run(row)

        assertEquals(0f, row.width)
        assertEquals(0f, row.height)
    }

    // --- weight ---

    @Test
    fun `weights share out what is left`() {
        val fixed = leaf("fixed", Modifier.width(40f).height(5f))
        val one = leaf("one", Modifier.weight(1f).then(Modifier.height(5f)))
        val three = leaf("three", Modifier.weight(3f).then(Modifier.height(5f)))
        val row = row(modifier = Modifier.width(200f), children = listOf(fixed, one, three))
        run(row)

        assertEquals(40f, fixed.width)
        assertEquals(40f, one.width)
        assertEquals(120f, three.width)
    }

    @Test
    fun `three equal weights leave no seam`() {
        // A third of 100 is not representable, so the naive sum lands short and a sliver of
        // background shows between the last two children.
        val children = List(3) { leaf("child$it", Modifier.weight(1f)) }
        val row = row(modifier = Modifier.width(100f), children = children)
        run(row)

        assertEquals(100f, children.sumOf { it.width.toDouble() }.toFloat())
        assertEquals(100f, children[2].x + children[2].width)
    }

    @Test
    fun `a weighted child fills its share even when it would rather be small`() {
        val small = leaf("small", Modifier.size(5f).then(Modifier.weight(1f)))
        val row = row(modifier = Modifier.width(80f), children = listOf(small))
        run(row)

        assertEquals(80f, small.width)
    }

    @Test
    fun `a weighted spacer pushes everything after it to the far end`() {
        val left = leaf("left", Modifier.size(10f))
        val spacer = leaf("spacer", Modifier.weight(1f))
        val right = leaf("right", Modifier.size(10f))
        val row = row(modifier = Modifier.width(100f), children = listOf(left, spacer, right))
        run(row)

        assertEquals(0f, left.x)
        assertEquals(90f, right.x)
    }

    // --- arrangement ---

    @Test
    fun `arrangements share out the space along the axis`() {
        fun positions(arrangement: Arrangement): List<Float> {
            val tree = UiTree()
            val children = List(2) { leaf("child$it", Modifier.size(20f)) }
            val row = row(modifier = Modifier.width(100f), arrangement = arrangement, children = children)
            tree.root.insertAt(0, row)
            MeasurePass().run(tree.root, Constraints.atMost(1000f, 1000f))
            return children.map { it.x }
        }

        assertEquals(listOf(0f, 20f), positions(Arrangement.Start))
        assertEquals(listOf(60f, 80f), positions(Arrangement.End))
        assertEquals(listOf(30f, 50f), positions(Arrangement.Centre))
        assertEquals(listOf(0f, 80f), positions(Arrangement.SpaceBetween))
        assertEquals(listOf(15f, 65f), positions(Arrangement.SpaceAround))
        assertEquals(listOf(20f, 60f), positions(Arrangement.SpaceEvenly))
        assertEquals(listOf(0f, 28f), positions(Arrangement.spacedBy(8f)))
        assertEquals(listOf(26f, 54f), positions(Arrangement.spacedBy(8f, Arrangement.Centre)))
    }

    @Test
    fun `a fixed gap is taken out of what the children are offered`() {
        val one = leaf("one", Modifier.weight(1f))
        val two = leaf("two", Modifier.weight(1f))
        val row = row(
            modifier = Modifier.width(100f),
            arrangement = Arrangement.spacedBy(20f),
            children = listOf(one, two),
        )
        run(row)

        assertEquals(40f, one.width)
        assertEquals(40f, two.width)
        assertEquals(60f, two.x)
    }

    // --- alignment across the axis ---

    @Test
    fun `a row aligns its children down the cross axis`() {
        val short = leaf("short", Modifier.size(10f, 10f))
        val tall = leaf("tall", Modifier.size(10f, 50f))
        val row = row(alignment = VerticalAlignment.Centre, children = listOf(short, tall))
        run(row)

        assertEquals(20f, short.y)
        assertEquals(0f, tall.y)
    }

    @Test
    fun `a child can align itself, and wins`() {
        val short = leaf("short", Modifier.size(10f).then(Modifier.align(Alignment.BottomStart)))
        val tall = leaf("tall", Modifier.size(10f, 50f))
        val row = row(alignment = VerticalAlignment.Top, children = listOf(short, tall))
        run(row)

        assertEquals(40f, short.y)
    }

    @Test
    fun `a column aligns its children across the cross axis`() {
        val narrow = leaf("narrow", Modifier.size(10f))
        val wide = leaf("wide", Modifier.size(50f, 10f))
        val column = column(alignment = HorizontalAlignment.End, children = listOf(narrow, wide))
        run(column)

        assertEquals(40f, narrow.x)
    }

    @Test
    fun `a child can fill the cross axis`() {
        val stretched = leaf("stretched", Modifier.width(10f).then(Modifier.fillMaxHeight()))
        val tall = leaf("tall", Modifier.size(10f, 50f))
        val row = row(modifier = Modifier.height(50f), children = listOf(stretched, tall))
        run(row)

        assertEquals(50f, stretched.height)
    }

    // --- box ---

    @Test
    fun `a box is as big as its largest child`() {
        val small = leaf("small", Modifier.size(10f))
        val large = leaf("large", Modifier.size(40f, 20f))
        val box = box(children = listOf(small, large))
        run(box)

        assertEquals(40f, box.width)
        assertEquals(20f, box.height)
    }

    @Test
    fun `a box places its children where it was told`() {
        val child = leaf("child", Modifier.size(10f))
        val backdrop = leaf("backdrop", Modifier.size(50f))
        val box = box(contentAlignment = Alignment.Centre, children = listOf(backdrop, child))
        run(box)

        assertEquals(20f, child.x)
        assertEquals(20f, child.y)
    }

    @Test
    fun `a box child can place itself`() {
        val child = leaf("child", Modifier.size(10f).then(Modifier.align(Alignment.BottomEnd)))
        val backdrop = leaf("backdrop", Modifier.size(50f))
        val box = box(children = listOf(backdrop, child))
        run(box)

        assertEquals(40f, child.x)
        assertEquals(40f, child.y)
    }

    // --- nesting ---

    @Test
    fun `a nested row inside a weighted column is measured once`() {
        val inner = row(children = listOf(leaf("a", Modifier.size(10f)), leaf("b", Modifier.size(10f))))
        val column = column(
            modifier = Modifier.size(100f),
            children = listOf(group("weighted", Modifier.weight(1f), BoxPolicy(Alignment.TopStart), listOf(inner))),
        )

        // Measuring twice throws, so simply completing is the assertion. The sizes confirm the
        // weighted child really did fill the column.
        run(column)

        assertEquals(20f, inner.width)
        assertEquals(100f, column.children.single().height)
    }
}
