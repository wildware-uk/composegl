package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.ninePatch
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
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

    // --- nine separately-cut regions ---

    /**
     * The same 48x48 frame as [texture], cut up beforehand instead of sliced arithmetically.
     *
     * @param middle how big the two middle bands are. One is the whole point of the exercise: a
     *   band that is a single texel cannot fetch a neighbour out of a coarser mip, because a texel
     *   stretched wide is magnified and no mip is fetched at all.
     */
    private fun nine(middle: Int = 16) = NineRegions(
        topLeft = Art(16, 16), top = Art(middle, 16), topRight = Art(16, 16),
        left = Art(16, middle), centre = Art(middle, middle), right = Art(16, middle),
        bottomLeft = Art(16, 16), bottom = Art(middle, 16), bottomRight = Art(16, 16),
    )

    @Test
    fun `nine regions draw exactly the geometry one texture draws`() {
        val box = Rect.of(11f, 13f, 317f, 149f)

        patch().drawInto(canvas, box)
        val sliced = drawn().map { it.destination }

        canvas.clear()
        NinePatch.of(nine()).drawInto(canvas, box)

        assertEquals(sliced, drawn().map { it.destination }, "the two paths cannot be allowed to drift")
        assertEquals(
            Rect.of(0f, 0f, 16f, 16f),
            at(27f, 29f).source,
            "and each piece is drawn whole, rather than out of a rectangle inside a bigger picture",
        )
    }

    @Test
    fun `and the same geometry when the edges tile`() {
        val box = Rect.of(0f, 0f, 200f, 120f)

        patch(topEdge = EdgeMode.Tile, leftEdge = EdgeMode.Tile).drawInto(canvas, box)
        val sliced = drawn().map { it.destination }

        canvas.clear()
        NinePatch.of(nine(), leftEdge = EdgeMode.Tile, topEdge = EdgeMode.Tile).drawInto(canvas, box)

        assertEquals(sliced, drawn().map { it.destination })
    }

    @Test
    fun `a middle cut down to one texel is legal, because that is what it is for`() {
        val thin = NinePatch.of(nine(middle = 1))

        assertEquals(Padding.all(16f), thin.slice, "the corners still say how thick the border is")
        thin.drawInto(canvas, Rect.of(0f, 0f, 200f, 120f))

        val middle = at(16f, 16f)
        assertEquals(Rect.of(16f, 16f, 168f, 88f), middle.destination, "stretched across the whole middle")
        assertEquals(Rect.of(0f, 0f, 1f, 1f), middle.source, "out of a single texel")
    }

    @Test
    fun `a piece left out means that row or column has no slice`() {
        // A scrollbar track: two end caps and a middle, and nothing above or below them at all.
        val bar = NinePatch.of(NineRegions(left = Art(6, 12), centre = Art(1, 12), right = Art(6, 12)))
        assertEquals(Padding(left = 6f, right = 6f), bar.slice)

        bar.drawInto(canvas, Rect.of(0f, 0f, 100f, 12f))
        val pieces = drawn().map { it.destination }

        canvas.clear()
        NinePatch(Art(13, 12), Padding(left = 6f, right = 6f)).drawInto(canvas, Rect.of(0f, 0f, 100f, 12f))

        assertEquals(3, pieces.size, "the row it has, and neither of the rows it has not")
        assertEquals(drawn().map { it.destination }, pieces, "the same bar either way")
    }

    @Test
    fun `the size a nine-region handle reports is a bound rather than any picture's size`() {
        val thin = nine(middle = 1)

        assertEquals(33, thin.width, "two 16-pixel corners and a one-texel band")
        assertEquals(33, thin.height)
    }

    @Test
    fun `two tiled sides that would repeat at different pitches are refused, by name`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            NinePatch.of(
                NineRegions(left = Art(6, 8), centre = Art(1, 8), right = Art(6, 12)),
                leftEdge = EdgeMode.Tile,
                rightEdge = EdgeMode.Tile,
            )
        }

        val message = thrown.message!!
        assertTrue("left" in message && "right" in message, "it names both pieces: $message")
        assertTrue("8" in message && "12" in message, "and both sizes: $message")
        assertTrue("Tile" in message, "and the mode that made it matter: $message")
    }

    @Test
    fun `sides of different sizes are fine while they stretch`() {
        // Stretching has to keep the freedom: cutting a band to one texel depends on it.
        val uneven = NinePatch.of(NineRegions(left = Art(6, 1), centre = Art(1, 1), right = Art(6, 40)))

        uneven.drawInto(canvas, Rect.of(0f, 0f, 80f, 40f))
        assertEquals(3, drawn().size)
    }

    @Test
    fun `pieces down one side that disagree about their width are refused`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            NineRegions(topLeft = Art(6, 6), left = Art(8, 20), bottomLeft = Art(6, 6))
        }

        val message = thrown.message!!
        assertTrue("topLeft" in message && "left" in message, message)
    }

    @Test
    fun `a nine-region patch cannot be handed a slice of its own`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            NinePatch(nine(), Padding.all(8f))
        }

        assertTrue("NinePatch.of" in thrown.message!!, thrown.message)
    }

    @Test
    fun `nine regions offered to something that draws one picture say what is really wrong`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) { refuseNineRegions(nine()) }

        val message = thrown.message!!
        assertTrue("nine separately-cut" in message, message)
        assertTrue("NinePatch" in message, "and what to do about it: $message")
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
