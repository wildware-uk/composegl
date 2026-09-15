package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Flow rows and flow columns, measured over trees built by hand. */
class FlowLayoutTest {

    private val tree = UiTree()

    private fun leaf(name: String, modifier: Modifier = Modifier) =
        UiNode(name).also {
            it.modifier = modifier
            it.measurePolicy = MeasurePolicy.Empty
        }

    private fun flowRow(
        modifier: Modifier = Modifier,
        horizontalSpacing: Float = 0f,
        verticalSpacing: Float = 0f,
        arrangement: Arrangement = Arrangement.Start,
        alignment: VerticalAlignment = VerticalAlignment.Top,
        maxItems: Int = Int.MAX_VALUE,
        children: List<UiNode>,
    ) = group(
        "flowRow",
        modifier,
        FlowPolicy(true, horizontalSpacing, verticalSpacing, arrangement, Alignment(vertical = alignment), maxItems),
        children,
    )

    private fun flowColumn(
        modifier: Modifier = Modifier,
        horizontalSpacing: Float = 0f,
        verticalSpacing: Float = 0f,
        arrangement: Arrangement = Arrangement.Top,
        alignment: HorizontalAlignment = HorizontalAlignment.Start,
        maxItems: Int = Int.MAX_VALUE,
        children: List<UiNode>,
    ) = group(
        "flowColumn",
        modifier,
        FlowPolicy(false, verticalSpacing, horizontalSpacing, arrangement, Alignment(horizontal = alignment), maxItems),
        children,
    )

    private fun group(name: String, modifier: Modifier, policy: MeasurePolicy, children: List<UiNode>) =
        UiNode(name).also { node ->
            node.modifier = modifier
            node.measurePolicy = policy
            children.forEach { node.insertAt(node.children.size, it) }
        }

    private fun run(root: UiNode, constraints: Constraints = Constraints.atMost(1000f, 1000f)) {
        tree.root.insertAt(0, root)
        MeasurePass().run(tree.root, constraints)
    }

    private fun squares(count: Int, side: Float = 30f) = List(count) { leaf("item$it", Modifier.size(side)) }

    private fun corners(nodes: List<UiNode>) = nodes.map { it.x to it.y }

    // --- wrapping ---

    @Test
    fun `children that fit stay on one line like a row`() {
        val items = squares(3)
        val flow = flowRow(modifier = Modifier.width(100f), children = items)
        run(flow)

        assertEquals(listOf(0f to 0f, 30f to 0f, 60f to 0f), corners(items))
        assertEquals(30f, flow.height)
    }

    @Test
    fun `a child that does not fit goes to the start of the next line`() {
        val items = squares(5)
        val flow = flowRow(modifier = Modifier.width(100f), children = items)
        run(flow)

        assertEquals(
            listOf(0f to 0f, 30f to 0f, 60f to 0f, 0f to 30f, 30f to 30f),
            corners(items),
        )
        assertEquals(100f, flow.width)
        assertEquals(60f, flow.height, "two lines deep")
    }

    @Test
    fun `the spacing is kept between neighbours and between lines`() {
        val items = squares(4)
        // 30 + 4 + 30 + 4 + 30 = 98 fits in 100; a fourth would need 132.
        val flow = flowRow(modifier = Modifier.width(100f), horizontalSpacing = 4f, verticalSpacing = 6f, children = items)
        run(flow)

        assertEquals(listOf(0f to 0f, 34f to 0f, 68f to 0f, 0f to 36f), corners(items))
        assertEquals(66f, flow.height)
    }

    @Test
    fun `spacing counts when deciding whether the next child fits`() {
        val items = squares(4, side = 25f)
        // Four at 25 fill 100 exactly with no gap, but one gap of 1 pushes the fourth over.
        val tight = flowRow(modifier = Modifier.width(100f), children = items)
        run(tight)
        assertEquals(75f, items[3].x)

        val spacedItems = squares(4, side = 25f)
        val spaced = flowRow(modifier = Modifier.width(100f), horizontalSpacing = 1f, children = spacedItems)
        UiTree().root.insertAt(0, spaced)
        MeasurePass().run(spaced.parent!!, Constraints.atMost(1000f, 1000f))
        assertEquals(0f to 25f, spacedItems[3].x to spacedItems[3].y)
    }

    @Test
    fun `a flow with no limit wraps as a row as wide as its content`() {
        val items = squares(4)
        val flow = flowRow(children = items)
        run(flow, Constraints.atMost(95f, 1000f))

        assertEquals(90f, flow.width, "three fit in 95 so the flow is as wide as three")
        assertEquals(0f to 30f, items[3].x to items[3].y)
    }

    @Test
    fun `on an unbounded axis there is nothing to wrap against`() {
        val items = squares(10)
        val flow = flowRow(children = items)
        run(flow, Constraints(0f, Float.POSITIVE_INFINITY, 0f, 1000f))

        assertEquals(300f, flow.width)
        assertEquals(30f, flow.height)
        assertEquals(270f, items[9].x)
    }

    @Test
    fun `thirds of a width stay on one line despite the floats`() {
        val third = 100f / 3f
        val items = List(3) { leaf("third$it", Modifier.size(third, 10f)) }
        val flow = flowRow(modifier = Modifier.width(100f), children = items)
        run(flow)

        assertEquals(0f, items[2].y)
        assertEquals(10f, flow.height)
    }

    @Test
    fun `a child wider than the flow gets a line to itself and is held to the width`() {
        val before = leaf("before", Modifier.size(20f))
        val wide = leaf("wide", Modifier.size(500f, 20f))
        val after = leaf("after", Modifier.size(20f))
        val flow = flowRow(modifier = Modifier.width(100f), children = listOf(before, wide, after))
        run(flow)

        assertEquals(0f to 0f, before.x to before.y)
        assertEquals(0f to 20f, wide.x to wide.y)
        assertEquals(100f, wide.width, "offered no more than the flow is wide")
        assertEquals(0f to 40f, after.x to after.y)
    }

    @Test
    fun `a line is as deep as its deepest child`() {
        val short = leaf("short", Modifier.size(40f, 10f))
        val tall = leaf("tall", Modifier.size(40f, 50f))
        val next = leaf("next", Modifier.size(40f, 10f))
        val flow = flowRow(modifier = Modifier.width(100f), children = listOf(short, tall, next))
        run(flow)

        assertEquals(50f, next.y)
        assertEquals(60f, flow.height)
    }

    @Test
    fun `an empty flow is nothing at all`() {
        val flow = flowRow(children = emptyList())
        run(flow)

        assertEquals(0f, flow.width)
        assertEquals(0f, flow.height)
    }

    @Test
    fun `a fill width flow keeps the width it was given`() {
        val items = squares(2)
        val flow = flowRow(modifier = Modifier.fillMaxWidth(), children = items)
        run(flow, Constraints.atMost(400f, 400f))

        assertEquals(400f, flow.width)
    }

    // --- items per line ---

    @Test
    fun `a limit on items wraps even when there is room`() {
        val items = squares(6, side = 10f)
        val flow = flowRow(maxItems = 4, children = items)
        run(flow)

        assertEquals(listOf(0f, 10f, 20f, 30f, 0f, 10f), items.map { it.x })
        assertEquals(listOf(0f, 0f, 0f, 0f, 10f, 10f), items.map { it.y })
        assertEquals(40f, flow.width)
    }

    @Test
    fun `a flow needs room for at least one item a line`() {
        assertThrows<IllegalArgumentException> {
            FlowPolicy(true, 0f, 0f, Arrangement.Start, Alignment.TopStart, 0)
        }
    }

    // --- arrangement and alignment ---

    @Test
    fun `each line is arranged on its own`() {
        val items = squares(4)
        val flow = flowRow(
            modifier = Modifier.width(100f),
            horizontalSpacing = 5f,
            arrangement = Arrangement.Centre,
            children = items,
        )
        run(flow)

        // Line one: 30 + 5 + 30 + 5 + 30 = 100, so no room to centre in. Line two: 30 alone.
        assertEquals(listOf(0f, 35f, 70f), items.take(3).map { it.x })
        assertEquals(35f, items[3].x)
    }

    @Test
    fun `an end arranged line keeps its gaps`() {
        val items = squares(2)
        val flow = flowRow(
            modifier = Modifier.width(100f),
            horizontalSpacing = 10f,
            arrangement = Arrangement.End,
            children = items,
        )
        run(flow)

        assertEquals(listOf(30f, 70f), items.map { it.x })
    }

    @Test
    fun `space between spreads each line across the width`() {
        val items = squares(5)
        val flow = flowRow(modifier = Modifier.width(100f), arrangement = Arrangement.SpaceBetween, children = items)
        run(flow)

        assertEquals(listOf(0f, 35f, 70f), items.take(3).map { it.x })
        assertEquals(listOf(0f, 70f), items.drop(3).map { it.x })
    }

    @Test
    fun `a spaced by arrangement adds its gap to the flow spacing`() {
        val items = squares(3)
        val flow = flowRow(
            modifier = Modifier.width(100f),
            horizontalSpacing = 2f,
            arrangement = Arrangement.spacedBy(3f),
            children = items,
        )
        run(flow)

        // 30 + 5 + 30 = 65, a third would need 100 — it fits exactly.
        assertEquals(listOf(0f, 35f, 70f), items.map { it.x })
    }

    @Test
    fun `children are aligned inside their own line`() {
        val short = leaf("short", Modifier.size(40f, 10f))
        val tall = leaf("tall", Modifier.size(40f, 50f))
        val wrapped = leaf("wrapped", Modifier.size(40f, 10f))
        val wrappedTall = leaf("wrappedTall", Modifier.size(40f, 30f))
        val flow = flowRow(
            modifier = Modifier.width(100f),
            alignment = VerticalAlignment.Bottom,
            verticalSpacing = 5f,
            children = listOf(short, tall, wrapped, wrappedTall),
        )
        run(flow)

        assertEquals(40f, short.y, "at the bottom of a 50 deep line")
        assertEquals(0f, tall.y)
        assertEquals(55f + 20f, wrapped.y, "at the bottom of the second line, not of the flow")
        assertEquals(55f, wrappedTall.y)
    }

    @Test
    fun `a child can align itself and wins`() {
        val short = leaf("short", Modifier.size(40f, 10f).then(Modifier.align(Alignment.BottomStart)))
        val tall = leaf("tall", Modifier.size(40f, 50f))
        val flow = flowRow(modifier = Modifier.width(100f), children = listOf(short, tall))
        run(flow)

        assertEquals(40f, short.y)
    }

    // --- flow column ---

    @Test
    fun `a flow column wraps into a new column to the right`() {
        val items = squares(5)
        val flow = flowColumn(
            modifier = Modifier.height(100f),
            horizontalSpacing = 8f,
            verticalSpacing = 2f,
            children = items,
        )
        run(flow)

        // 30 + 2 + 30 + 2 + 30 = 94 fits, a fourth does not.
        assertEquals(listOf(0f to 0f, 0f to 32f, 0f to 64f, 38f to 0f, 38f to 32f), corners(items))
        assertEquals(68f, flow.width)
        assertEquals(100f, flow.height)
    }

    @Test
    fun `a flow column aligns children across its own column`() {
        val narrow = leaf("narrow", Modifier.size(10f, 40f))
        val wide = leaf("wide", Modifier.size(50f, 40f))
        val next = leaf("next", Modifier.size(10f, 40f))
        val flow = flowColumn(
            modifier = Modifier.height(100f),
            alignment = HorizontalAlignment.Centre,
            children = listOf(narrow, wide, next),
        )
        run(flow)

        assertEquals(20f, narrow.x)
        assertEquals(0f, wide.x)
        assertEquals(50f to 0f, next.x to next.y)
    }

    @Test
    fun `a flow column can be limited to items per column`() {
        val items = squares(3, side = 10f)
        val flow = flowColumn(maxItems = 2, children = items)
        run(flow)

        assertEquals(listOf(0f to 0f, 0f to 10f, 10f to 0f), corners(items))
    }

    // --- nesting ---

    @Test
    fun `a flow inside a flow is measured once`() {
        val inner = flowRow(modifier = Modifier.width(40f), children = squares(4, side = 20f))
        val outer = flowRow(modifier = Modifier.width(100f), children = listOf(leaf("a", Modifier.size(70f, 10f)), inner))

        // Measuring twice throws, so completing is half the assertion.
        run(outer)

        assertEquals(40f to 40f, inner.width to inner.height)
        assertEquals(0f to 10f, inner.x to inner.y)
    }

    @Test
    fun `the same settings make an equal policy`() {
        assertEquals(
            FlowPolicy(true, 4f, 4f, Arrangement.Start, Alignment.TopStart, 3),
            FlowPolicy(true, 4f, 4f, Arrangement.Start, Alignment.TopStart, 3),
        )
    }
}
