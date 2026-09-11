package composegl.ui.graphics

import composegl.ui.draw.DrawPass
import composegl.ui.geometry.Rect
import composegl.ui.layout.Constraints
import composegl.ui.layout.MeasurePass
import composegl.ui.layout.MeasurePolicy
import composegl.ui.layout.Padding
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.fillMaxSize
import composegl.ui.modifier.ninePatch
import composegl.ui.modifier.padding
import composegl.ui.modifier.size
import composegl.ui.node.UiNode
import composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A picture with a size and nothing else, which is all a nine-patch needs to slice one up. */
private class Art(override val width: Int, override val height: Int) : TextureHandle

/**
 * A picture cut into nine, asserted piece by piece.
 *
 * Every one of these would traditionally be checked by rendering a panel and looking at it. They
 * are decisions about rectangles instead, so they run in a millisecond on a machine with no GPU.
 */
class NinePatchTest {

    private val texture = Art(width = 48, height = 48)
    private val slice = Padding.all(16f)
    private val canvas = RecordingCanvas()

    private fun patch(
        leftEdge: EdgeMode = EdgeMode.Stretch,
        topEdge: EdgeMode = EdgeMode.Stretch,
        rightEdge: EdgeMode = EdgeMode.Stretch,
        bottomEdge: EdgeMode = EdgeMode.Stretch,
        centreAcross: EdgeMode = EdgeMode.Stretch,
        centreDown: EdgeMode = EdgeMode.Stretch,
        padding: Padding = slice,
    ) = NinePatch(texture, slice, padding, leftEdge, topEdge, rightEdge, bottomEdge, centreAcross, centreDown)

    private fun drawn(): List<DrawCall.Image> = canvas.only()

    private fun at(x: Float, y: Float) = drawn().single { it.destination.left == x && it.destination.top == y }

    // --- the nine ---

    @Test
    fun `a stretched patch is nine pieces that cover the destination exactly`() {
        patch().drawInto(canvas, Rect.of(100f, 200f, 300f, 100f))

        assertEquals(9, drawn().size)

        val union = drawn().map { it.destination }
        assertEquals(100f, union.minOf { it.left })
        assertEquals(200f, union.minOf { it.top })
        assertEquals(400f, union.maxOf { it.right })
        assertEquals(300f, union.maxOf { it.bottom })
        assertEquals(
            300f * 100f,
            union.sumOf { (it.width * it.height).toDouble() }.toFloat(),
            0.01f,
            "the nine pieces tile the destination: no gap, no overlap",
        )
    }

    @Test
    fun `corners keep the size of the art, however big the panel is`() {
        patch().drawInto(canvas, Rect.of(0f, 0f, 500f, 400f))

        val topLeft = at(0f, 0f)
        assertEquals(Rect.of(0f, 0f, 16f, 16f), topLeft.destination)
        assertEquals(Rect.of(0f, 0f, 16f, 16f), topLeft.source)

        val bottomRight = at(484f, 384f)
        assertEquals(Rect.of(484f, 384f, 16f, 16f), bottomRight.destination)
        assertEquals(Rect.of(32f, 32f, 16f, 16f), bottomRight.source, "the art's own bottom-right")
    }

    @Test
    fun `edges stretch along one axis and the middle along both`() {
        patch().drawInto(canvas, Rect.of(0f, 0f, 200f, 100f))

        val top = at(16f, 0f)
        assertEquals(Rect.of(16f, 0f, 168f, 16f), top.destination, "stretched across, not down")
        assertEquals(Rect.of(16f, 0f, 16f, 16f), top.source)

        val left = at(0f, 16f)
        assertEquals(Rect.of(0f, 16f, 16f, 68f), left.destination, "stretched down, not across")

        val middle = at(16f, 16f)
        assertEquals(Rect.of(16f, 16f, 168f, 68f), middle.destination)
        assertEquals(Rect.of(16f, 16f, 16f, 16f), middle.source)
    }

    @Test
    fun `a patch with nothing to stretch draws only its corners`() {
        // 48 wide is exactly the two corners, so the middle column has no width at all.
        patch().drawInto(canvas, Rect.of(0f, 0f, 32f, 32f))

        assertEquals(4, drawn().size, "the four corners; the edges and the middle are empty")
    }

    @Test
    fun `squeezed below its minimum, both corners give way together`() {
        patch().drawInto(canvas, Rect.of(0f, 0f, 16f, 100f))

        val left = at(0f, 0f)
        assertEquals(8f, left.destination.width, "half of 16, because two 16s must fit in 16")
        assertEquals(16f, left.source!!.width, "the art is unchanged; it is the drawing that shrinks")
        assertTrue(drawn().none { it.destination.right > 16f }, "nothing spills out of the panel")
    }

    @Test
    fun `a zero-area destination draws nothing`() {
        patch().drawInto(canvas, Rect.of(10f, 10f, 0f, 40f))
        assertTrue(drawn().isEmpty())
    }

    // --- tiling ---

    @Test
    fun `a tiled top edge repeats the art instead of pulling it`() {
        patch(topEdge = EdgeMode.Tile).drawInto(canvas, Rect.of(0f, 0f, 80f, 100f))

        // 80 wide, 16-pixel corners: the top edge has 48 to cover with a 16-pixel tile.
        val tiles = drawn().filter { it.destination.top == 0f && it.destination.left >= 16f && it.destination.right <= 64f }
        assertEquals(listOf(16f, 32f, 48f), tiles.map { it.destination.left })
        assertTrue(tiles.all { it.destination.width == 16f })
        assertTrue(tiles.all { it.source == Rect.of(16f, 0f, 16f, 16f) }, "every tile is the whole strip")
    }

    @Test
    fun `the last tile is a fraction of a tile, and takes a matching fraction of the art`() {
        patch(topEdge = EdgeMode.Tile).drawInto(canvas, Rect.of(0f, 0f, 72f, 100f))

        // 40 to cover: two whole 16s and an 8.
        val last = drawn().single { it.destination.top == 0f && it.destination.left == 48f }
        assertEquals(8f, last.destination.width)
        assertEquals(8f, last.source!!.width, "half a tile takes half the art, not a squashed whole one")
        assertEquals(16f, last.source.height, "only the axis that tiles is cut short")
    }

    @Test
    fun `a tiled middle repeats both ways`() {
        patch(centreAcross = EdgeMode.Tile, centreDown = EdgeMode.Tile).drawInto(canvas, Rect.of(0f, 0f, 64f, 64f))

        // 32 by 32 of middle, tiled 16 by 16.
        val middle = drawn().filter { it.destination.left >= 16f && it.destination.top >= 16f && it.destination.right <= 48f && it.destination.bottom <= 48f }
        assertEquals(4, middle.size)
    }

    @Test
    fun `the middle can tile across while it stretches down`() {
        // A header band: the hatch should keep its pitch sideways but fill the height it is given.
        patch(centreAcross = EdgeMode.Tile).drawInto(canvas, Rect.of(0f, 0f, 80f, 100f))

        val middle = drawn().filter {
            it.destination.left >= 16f && it.destination.top >= 16f &&
                it.destination.right <= 64f && it.destination.bottom <= 84f
        }
        assertEquals(3, middle.size, "three tiles across")
        assertTrue(middle.all { it.destination.height == 68f }, "and one of them down, stretched")
    }

    @Test
    fun `tiling and stretching can be chosen per edge`() {
        patch(topEdge = EdgeMode.Tile, bottomEdge = EdgeMode.Stretch)
            .drawInto(canvas, Rect.of(0f, 0f, 80f, 100f))

        assertEquals(3, drawn().count { it.destination.top == 0f && it.destination.left in 16f..63f })
        assertEquals(1, drawn().count { it.destination.bottom == 100f && it.destination.left == 16f })
    }

    @Test
    fun `absurd tile counts give way to stretching, rather than a thousand draw calls`() {
        val hairline = NinePatch.tiled(Art(3, 3), Padding.all(1f))
        hairline.drawInto(canvas, Rect.of(0f, 0f, 4000f, 4000f))

        assertEquals(9, drawn().size)
    }

    // --- the art's own padding ---

    @Test
    fun `the art's content insets become the node's padding`() {
        val node = laidOut(Modifier.size(100f).ninePatch(patch(padding = Padding.all(10f))))

        val child = node.children.single()
        assertEquals(10f, child.x)
        assertEquals(10f, child.y)
        assertEquals(80f, child.width, "100 less 10 either side")
    }

    @Test
    fun `the padding can be declined, for art you want to place things over`() {
        val node = laidOut(
            Modifier.size(100f).ninePatch(patch(padding = Padding.all(10f)), applyPadding = false),
        )

        assertEquals(0f, node.children.single().x)
    }

    @Test
    fun `padding written before the art insets the art, and the art's own padding still applies`() {
        val node = laidOut(Modifier.size(100f).padding(5f).ninePatch(patch(padding = Padding.all(10f))))

        DrawPass(canvas).draw(node)
        val corner = drawn().minByOrNull { it.destination.left + it.destination.top }!!
        assertEquals(5f, corner.destination.left, "the art sits inside the padding that came first")
        assertEquals(15f, node.children.single().x, "and the contents sit inside both")
    }

    @Test
    fun `the patch is drawn across the node the draw pass gave it`() {
        val node = laidOut(Modifier.size(60f, 40f).ninePatch(patch()))
        node.x = 7f
        node.y = 9f

        DrawPass(canvas).draw(node)

        assertEquals(9, drawn().size)
        assertEquals(7f, drawn().minOf { it.destination.left })
        assertEquals(67f, drawn().maxOf { it.destination.right })
        assertEquals(49f, drawn().maxOf { it.destination.bottom })
    }

    // --- what it refuses ---

    @Test
    fun `slices wider than the texture are rejected where they are written, not where they draw`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            NinePatch(Art(20, 20), Padding.all(16f))
        }
        assertTrue(thrown.message!!.contains("32"), thrown.message)
    }

    @Test
    fun `a negative slice is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            NinePatch(texture, Padding(left = -1f))
        }
    }

    /** A node with that modifier and one child, measured. The child is what padding moves. */
    private fun laidOut(modifier: Modifier): UiNode {
        val tree = UiTree()
        // It fills what it is offered, so its size reports the content box the art left it.
        val child = UiNode("child").also {
            it.measurePolicy = MeasurePolicy.Empty
            it.modifier = Modifier.fillMaxSize()
        }
        val node = UiNode("panel").also {
            it.modifier = modifier
            it.measurePolicy = MeasurePolicy.Stack
            it.insertAt(0, child)
        }
        tree.root.insertAt(0, node)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))
        return node
    }
}
