package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.modifier.IntrinsicSizeElement
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.aspectRatio
import dev.wildware.composegl.ui.modifier.defaultMinSize
import dev.wildware.composegl.ui.modifier.widthIn
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.resolve
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Asking a child how big it would like to be before measuring it, over trees built by hand. */
class IntrinsicsTest {

    private val tree = UiTree()

    /**
     * A leaf that can be squeezed from [max] down to [min] wide, and is [area] divided by its width
     * tall — a paragraph, near enough, without a font.
     */
    private class Stretchy(val min: Float, val max: Float, val area: Float = 0f) : MeasurePolicy {

        private fun heightAt(width: Float) = if (area == 0f || width == 0f) 0f else area / width

        override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
            val width = constraints.constrainWidth(max)
            return layout(width, constraints.constrainHeight(heightAt(width))) {}
        }

        override fun MeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) = min
        override fun MeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) = max
        override fun MeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
            heightAt(width.coerceAtMost(max))
        override fun MeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
            heightAt(width.coerceAtMost(max))
    }

    private fun leaf(name: String, modifier: Modifier = Modifier, policy: MeasurePolicy = MeasurePolicy.Empty) =
        UiNode(name).also {
            it.modifier = modifier
            it.measurePolicy = policy
        }

    private fun group(name: String, modifier: Modifier, policy: MeasurePolicy, vararg children: UiNode) =
        UiNode(name).also { node ->
            node.modifier = modifier
            node.measurePolicy = policy
            children.forEach { node.insertAt(node.children.size, it) }
        }

    private fun column(modifier: Modifier = Modifier, spacing: Float = 0f, vararg children: UiNode) =
        group("column", modifier, LinearPolicy(false, Arrangement.spacedBy(spacing), Alignment.TopStart), *children)

    private fun row(modifier: Modifier = Modifier, spacing: Float = 0f, vararg children: UiNode) =
        group("row", modifier, LinearPolicy(true, Arrangement.spacedBy(spacing), Alignment.TopStart), *children)

    private fun run(root: UiNode, constraints: Constraints = Constraints.atMost(1000f, 1000f)) {
        tree.root.insertAt(0, root)
        MeasurePass().run(tree.root, constraints)
    }

    // --- a column as wide as its widest child ---

    @Test
    fun `a column sized to its widest child is exactly that wide`() {
        val short = leaf("short", Modifier.fillMaxWidth().height(10f))
        val long = leaf("long", Modifier.size(90f, 10f))
        val column = column(Modifier.width(IntrinsicSize.Max), 0f, short, long)
        run(column)

        assertEquals(90f, column.width)
        assertEquals(90f, short.width, "so a child filling it fills to the widest, not to the screen")
    }

    @Test
    fun `without asking the same column fills all the room it is offered`() {
        val short = leaf("short", Modifier.fillMaxWidth().height(10f))
        val long = leaf("long", Modifier.size(90f, 10f))
        val column = column(Modifier, 0f, short, long)
        run(column)

        assertEquals(1000f, short.width, "the control for the test above")
    }

    @Test
    fun `asking for the minimum takes the narrowest a child can be squeezed to`() {
        val squeezable = leaf("text", Modifier.fillMaxWidth(), Stretchy(min = 20f, max = 80f))
        val column = column(Modifier.width(IntrinsicSize.Min), 0f, squeezable)
        run(column)

        assertEquals(20f, column.width)
        assertEquals(20f, squeezable.width)
    }

    @Test
    fun `padding goes around what the contents want`() {
        val child = leaf("child", Modifier.size(50f, 10f))
        val column = column(Modifier.width(IntrinsicSize.Max).padding(8f), 0f, child)
        run(column)

        assertEquals(66f, column.width)
        assertEquals(8f, child.x)
    }

    @Test
    fun `padding on a child is part of what it wants`() {
        val filler = leaf("filler", Modifier.fillMaxWidth().height(4f))
        val padded = group("padded", Modifier.padding(horizontal = 12f), MeasurePolicy.Stack, leaf("in", Modifier.size(30f)))
        val column = column(Modifier.width(IntrinsicSize.Max), 0f, filler, padded)
        run(column)

        assertEquals(54f, filler.width)
    }

    @Test
    fun `a fixed width on the same axis wins over an intrinsic one`() {
        val child = leaf("child", Modifier.size(50f, 10f))
        val column = column(Modifier.width(IntrinsicSize.Max).width(200f), 0f, child)
        run(column)

        assertEquals(200f, column.width)
    }

    @Test
    fun `the answer is clamped to what the parent allows`() {
        val child = leaf("child", Modifier.size(90f, 10f))
        val column = column(Modifier.width(IntrinsicSize.Max), 0f, child)
        run(column, Constraints.atMost(60f, 1000f))

        assertEquals(60f, column.width)
    }

    // --- a row as tall as its tallest child ---

    @Test
    fun `a divider in a row sized to its tallest cell is as tall as that cell`() {
        val left = leaf("left", Modifier.size(20f, 30f))
        val divider = leaf("divider", Modifier.width(2f).fillMaxHeight())
        val right = leaf("right", Modifier.size(20f, 70f))
        val row = row(Modifier.height(IntrinsicSize.Min), 0f, left, divider, right)
        run(row)

        assertEquals(70f, row.height)
        assertEquals(70f, divider.height)
    }

    @Test
    fun `a column's intrinsic height adds up its children and its gaps`() {
        val a = leaf("a", Modifier.size(10f, 20f))
        val b = leaf("b", Modifier.size(10f, 30f))
        val stretch = leaf("stretch", Modifier.width(10f).weight(1f))
        val column = column(Modifier.height(IntrinsicSize.Max), 5f, a, b, stretch)
        run(column)

        assertEquals(60f, column.height, "20 and 30, two gaps of 5, and a weighted child that wants nothing")
        assertEquals(0f, stretch.height)
    }

    @Test
    fun `a height is asked about at the width the node settled on`() {
        // 1000 square units at the 50 it would like to be wide is 20 tall.
        val paragraph = leaf("paragraph", Modifier, Stretchy(min = 10f, max = 50f, area = 1000f))
        val box = group("box", Modifier.width(IntrinsicSize.Max).height(IntrinsicSize.Max), BoxPolicy(Alignment.TopStart), paragraph)
        run(box)

        assertEquals(50f, box.width)
        assertEquals(20f, box.height)
    }

    // --- weights ---

    @Test
    fun `a line of weights is long enough that every share fits what it wants`() {
        val fixed = leaf("fixed", Modifier.size(20f, 10f))
        val small = leaf("small", Modifier.weight(1f), Stretchy(0f, 30f))
        val big = leaf("big", Modifier.weight(1f), Stretchy(0f, 90f))
        val row = row(Modifier.width(IntrinsicSize.Max), 10f, fixed, small, big)
        run(row)

        assertEquals(220f, row.width, "20 fixed, two gaps of 10, and two equal shares of 90")
        assertEquals(90f, small.width)
        assertEquals(90f, big.width)
    }

    @Test
    fun `a weighted child's share decides how tall it is asked to be`() {
        // A 200 wide row gives each of two equal weights 100: 1000 square units at 100 is 10 tall.
        val a = leaf("a", Modifier.weight(1f), Stretchy(0f, 400f, area = 1000f))
        val b = leaf("b", Modifier.weight(1f), Stretchy(0f, 400f, area = 1000f))
        val row = row(Modifier.width(200f).height(IntrinsicSize.Max), 0f, a, b)
        run(row)

        assertEquals(10f, row.height)
    }

    // --- layouts nobody wrote intrinsics for ---

    @Test
    fun `a layout of its own answers without writing any intrinsics`() {
        // Children side by side, and nothing about intrinsics: the default runs this over stand-ins.
        val strip = MeasurePolicy { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.loosen()) }
            layout(placeables.sumOf { it.width.toDouble() }.toFloat(), placeables.maxOfOrNull { it.height } ?: 0f) {
                var x = 0f
                placeables.forEach { it.at(x, 0f); x += it.width }
            }
        }
        val icons = group("strip", Modifier, strip, leaf("a", Modifier.size(30f)), leaf("b", Modifier.size(45f)))
        val filler = leaf("filler", Modifier.fillMaxWidth().height(2f))
        val column = column(Modifier.width(IntrinsicSize.Max), 0f, icons, filler)
        run(column)

        assertEquals(75f, column.width)
        assertEquals(75f, filler.width)
    }

    @Test
    fun `a nested column sized to its narrowest reports that to a parent asking for its widest`() {
        val squeezable = leaf("text", Modifier, Stretchy(min = 20f, max = 80f))
        val inner = column(Modifier.width(IntrinsicSize.Min), 0f, squeezable)
        val filler = leaf("filler", Modifier.fillMaxWidth().height(2f))
        val outer = column(Modifier.width(IntrinsicSize.Max), 0f, inner, filler)
        run(outer)

        assertEquals(20f, outer.width)
        assertEquals(20f, filler.width)
    }

    @Test
    fun `asking about a child is not measuring it`() {
        // Ask, then measure exactly once at the answer. The once-only check must not count the ask.
        val child = leaf("child", Modifier, Stretchy(min = 10f, max = 64f))
        val asking = MeasurePolicy { measurables, _ ->
            val wanted = measurables[0].maxIntrinsicWidth(Float.POSITIVE_INFINITY)
            val placeable = measurables[0].measure(Constraints.fixed(wanted, 12f))
            layout(placeable.width, placeable.height) { placeable.at(0f, 0f) }
        }
        val parent = group("parent", Modifier, asking, child)
        run(parent)

        assertEquals(64f, child.width)
    }

    @Test
    fun `the answer follows the children from one pass to the next`() {
        val long = leaf("long", Modifier.size(90f, 10f))
        val filler = leaf("filler", Modifier.fillMaxWidth().height(2f))
        val column = column(Modifier.width(IntrinsicSize.Max), 0f, long, filler)
        run(column)
        assertEquals(90f, filler.width)

        long.modifier = Modifier.size(140f, 10f)
        MeasurePass().run(tree.root, Constraints.atMost(1000f, 1000f))
        assertEquals(140f, filler.width)

        column.removeAt(0, 1)
        MeasurePass().run(tree.root, Constraints.atMost(1000f, 1000f))
        assertEquals(0f, column.width, "a column with only a filler in it wants nothing")
    }

    // --- the other size rules ---

    @Test
    fun `a child's widthIn range bounds what it says it wants`() {
        val short = leaf("short", Modifier.widthIn(min = 120f), Stretchy(10f, 40f))
        val long = leaf("long", Modifier.widthIn(max = 60f), Stretchy(10f, 300f))
        val filler = leaf("filler", Modifier.fillMaxWidth().height(2f))
        run(column(Modifier.width(IntrinsicSize.Max), 0f, short, filler))
        assertEquals(120f, filler.width, "40 of text lifted to the range's 120")

        tree.root.removeAt(0, 1)
        val narrow = leaf("filler", Modifier.fillMaxWidth().height(2f))
        run(column(Modifier.width(IntrinsicSize.Max), 0f, long, narrow))
        assertEquals(60f, narrow.width, "300 of text held to the range's 60")
    }

    @Test
    fun `a default minimum lifts what a child wants unless a range says otherwise`() {
        val small = leaf("small", Modifier.defaultMinSize(minWidth = 48f), Stretchy(10f, 12f))
        val filler = leaf("filler", Modifier.fillMaxWidth().height(2f))
        run(column(Modifier.width(IntrinsicSize.Max), 0f, small, filler))
        assertEquals(48f, filler.width)

        tree.root.removeAt(0, 1)
        val ranged = leaf("ranged", Modifier.defaultMinSize(minWidth = 48f).widthIn(min = 20f), Stretchy(10f, 12f))
        val other = leaf("filler", Modifier.fillMaxWidth().height(2f))
        run(column(Modifier.width(IntrinsicSize.Max), 0f, ranged, other))
        assertEquals(20f, other.width, "the range's minimum wins over the default, as it does when measuring")
    }

    @Test
    fun `a child keeping its shape says how tall it is from the width it is asked at`() {
        val fixed = leaf("fixed", Modifier.size(80f, 10f))
        val picture = leaf("picture", Modifier.fillMaxWidth().aspectRatio(2f))
        val column = column(Modifier.height(IntrinsicSize.Max).width(80f), 0f, fixed, picture)
        run(column)

        assertEquals(40f, picture.height)
        assertEquals(50f, column.height, "10 above, and 80 wide at 2:1 is 40 tall")
    }

    // --- the modifier ---

    @Test
    fun `intrinsic width and height settle per axis`() {
        val resolved = Modifier.width(IntrinsicSize.Max).height(IntrinsicSize.Min).resolve()
        assertEquals(IntrinsicSizeElement(IntrinsicSize.Max, IntrinsicSize.Min), resolved.intrinsicSize)

        val later = Modifier.width(IntrinsicSize.Max).width(IntrinsicSize.Min).resolve()
        assertEquals(IntrinsicSizeElement(width = IntrinsicSize.Min), later.intrinsicSize, "a later choice wins")
    }
}
