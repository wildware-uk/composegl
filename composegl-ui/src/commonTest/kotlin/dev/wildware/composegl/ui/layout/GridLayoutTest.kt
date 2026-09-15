package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Grids, measured over trees built by hand. */
class GridLayoutTest {

    private val tree = UiTree()

    private fun leaf(name: String, modifier: Modifier = Modifier) =
        UiNode(name).also {
            it.modifier = modifier
            it.measurePolicy = MeasurePolicy.Empty
        }

    private fun leaves(count: Int, modifier: Modifier) = List(count) { leaf("$it", modifier) }

    private fun grid(
        columns: GridCells,
        children: List<UiNode>,
        horizontalSpacing: Float = 0f,
        verticalSpacing: Float = 0f,
        contentAlignment: Alignment = Alignment.TopStart,
        modifier: Modifier = Modifier,
    ) = UiNode("grid").also { node ->
        node.modifier = modifier
        node.measurePolicy = GridPolicy(columns, horizontalSpacing, verticalSpacing, contentAlignment)
        children.forEach { node.insertAt(node.children.size, it) }
    }

    private fun run(root: UiNode, constraints: Constraints = Constraints.atMost(1000f, 1000f)) {
        tree.root.insertAt(0, root)
        MeasurePass().run(tree.root, constraints)
    }

    // --- fixed columns ---

    @Test
    fun `a fixed grid fills across and then down`() {
        val slots = leaves(5, Modifier.size(10f))
        val grid = grid(GridCells.Fixed(3), slots)
        run(grid, Constraints.atMost(90f, 1000f))

        assertEquals(listOf(0f, 30f, 60f, 0f, 30f), slots.map { it.x })
        assertEquals(listOf(0f, 0f, 0f, 10f, 10f), slots.map { it.y })
        assertEquals(90f, grid.width, "the columns share the whole width")
        assertEquals(20f, grid.height, "two rows of ten")
    }

    @Test
    fun `the gaps are taken out before the columns share the width`() {
        val slots = leaves(4, Modifier.size(10f))
        val grid = grid(GridCells.Fixed(2), slots, horizontalSpacing = 10f, verticalSpacing = 6f)
        run(grid, Constraints.atMost(110f, 1000f))

        // (110 - 10) / 2 = 50 a column, so the second starts at 50 + 10.
        assertEquals(listOf(0f, 60f, 0f, 60f), slots.map { it.x })
        assertEquals(listOf(0f, 0f, 16f, 16f), slots.map { it.y })
        assertEquals(26f, grid.height)
    }

    @Test
    fun `a child is offered its cell and no more`() {
        val wide = leaf("wide", Modifier.width(500f))
        val grid = grid(GridCells.Fixed(4), listOf(wide), horizontalSpacing = 4f)
        run(grid, Constraints.atMost(208f, 1000f))

        assertEquals(49f, wide.width, "(208 - 3 * 4) / 4")
    }

    @Test
    fun `with no width to share every column is as wide as the widest child`() {
        val slots = listOf(
            leaf("a", Modifier.size(10f)),
            leaf("b", Modifier.size(30f, 10f)),
            leaf("c", Modifier.size(20f, 10f)),
        )
        val grid = grid(GridCells.Fixed(2), slots, horizontalSpacing = 5f)
        run(grid, Constraints.Unbounded)

        assertEquals(listOf(0f, 35f, 0f), slots.map { it.x })
        assertEquals(65f, grid.width)
    }

    @Test
    fun `each row is as tall as the tallest child in it`() {
        val slots = listOf(
            leaf("a", Modifier.size(10f, 10f)),
            leaf("b", Modifier.size(10f, 40f)),
            leaf("c", Modifier.size(10f, 15f)),
            leaf("d", Modifier.size(10f, 5f)),
        )
        val grid = grid(GridCells.Fixed(2), slots, verticalSpacing = 2f)
        run(grid)

        assertEquals(42f, slots[2].y, "the second row starts under the taller of the first two")
        assertEquals(57f, grid.height)
    }

    @Test
    fun `rows hand out height in order until it runs out`() {
        val slots = leaves(6, Modifier.size(10f, 40f))
        val grid = grid(GridCells.Fixed(2), slots, verticalSpacing = 10f)
        run(grid, Constraints.atMost(100f, 70f))

        assertEquals(40f, slots[0].height)
        assertEquals(20f, slots[2].height, "the second row gets what is left after the first and a gap")
        assertEquals(0f, slots[4].height, "and the third gets none, like a column that ran out")
        assertEquals(70f, grid.height)
    }

    // --- adaptive columns ---

    @Test
    fun `an adaptive grid fits as many columns as the width allows`() {
        val policy = GridPolicy(GridCells.Adaptive(64f), 4f, 4f, Alignment.TopStart)

        assertEquals(3, policy.columnCount(200f), "three at 64 and two gaps is exactly 200")
        assertEquals(2, policy.columnCount(199f), "one short of that is two")
        assertEquals(1, policy.columnCount(20f), "never fewer than one, even when one does not fit")
        assertEquals(15, policy.columnCount(1016f))
    }

    @Test
    fun `an adaptive grid shares the leftover between its columns`() {
        val slots = leaves(3, Modifier.size(10f))
        val grid = grid(GridCells.Adaptive(60f), slots, horizontalSpacing = 4f)
        run(grid, Constraints.atMost(200f, 1000f))

        // 3 columns fit (3 * 60 + 8 = 188); the other 12 goes to the columns, 64 each.
        assertEquals(listOf(0f, 68f, 136f), slots.map { it.x })
        assertEquals(200f, grid.width)
    }

    @Test
    fun `an adaptive grid with no width to fit into says so`() {
        val grid = grid(GridCells.Adaptive(64f), leaves(2, Modifier.size(10f)))

        val failure = assertFailsWith<IllegalStateException> { run(grid, Constraints.Unbounded) }
        assertTrue(failure.message.orEmpty().contains("unbounded width"), failure.message)
    }

    // --- where a child sits in its cell ---

    @Test
    fun `children sit in their cells by the grid's alignment unless they say otherwise`() {
        val centred = leaf("centred", Modifier.size(10f))
        val cornered = leaf("cornered", Modifier.size(10f).align(Alignment.BottomEnd))
        val tall = leaf("tall", Modifier.size(10f, 30f))
        val grid = grid(GridCells.Fixed(3), listOf(centred, cornered, tall), contentAlignment = Alignment.Centre)
        run(grid, Constraints.atMost(90f, 1000f))

        assertEquals(10f, centred.x, "the middle of a 30-wide cell")
        assertEquals(10f, centred.y, "the middle of a 30-tall row")
        assertEquals(50f, cornered.x, "the right of the second cell")
        assertEquals(20f, cornered.y, "the bottom of the row")
    }

    // --- edges ---

    @Test
    fun `an empty grid takes the smallest size it is allowed`() {
        val grid = grid(GridCells.Adaptive(64f), emptyList())
        run(grid, Constraints.atMost(500f, 500f))

        assertEquals(0f, grid.width, "no columns to share the width between")
        assertEquals(0f, grid.height)
    }

    @Test
    fun `a grid with far more columns than children still measures every child`() {
        val slots = leaves(3, Modifier.size(10f))
        val grid = grid(GridCells.Fixed(Int.MAX_VALUE), slots)
        run(grid, Constraints.Unbounded)

        assertEquals(listOf(10f, 10f, 10f), slots.map { it.width })
        assertEquals(listOf(0f, 10f, 20f), slots.map { it.x }, "all in the one row")
        assertEquals(10f, grid.height)
    }

    @Test
    fun `nonsense columns and spacing are refused where they are written`() {
        assertFailsWith<IllegalArgumentException> { GridCells.Fixed(0) }
        assertFailsWith<IllegalArgumentException> { GridCells.Adaptive(0f) }
        assertFailsWith<IllegalArgumentException> { GridCells.Adaptive(Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> {
            GridPolicy(GridCells.Fixed(2), -1f, 0f, Alignment.TopStart)
        }
        assertFailsWith<IllegalArgumentException> {
            GridPolicy(GridCells.Fixed(2), 0f, Float.POSITIVE_INFINITY, Alignment.TopStart)
        }
        assertFailsWith<IllegalArgumentException> {
            GridPolicy(GridCells.Fixed(2), Float.NaN, 0f, Alignment.TopStart)
        }
    }

    @Test
    fun `the same arguments make an equal policy`() {
        assertEquals(
            GridPolicy(GridCells.Adaptive(64f), 4f, 4f, Alignment.Centre),
            GridPolicy(GridCells.Adaptive(64f), 4f, 4f, Alignment.Centre),
        )
    }
}
