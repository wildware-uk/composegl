package composegl.ui.draw

import composegl.ui.geometry.Rect
import composegl.ui.graphics.Colour
import composegl.ui.graphics.DrawCall
import composegl.ui.graphics.RecordingCanvas
import composegl.ui.layout.Constraints
import composegl.ui.layout.MeasurePass
import composegl.ui.layout.MeasurePolicy
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.alpha
import composegl.ui.modifier.background
import composegl.ui.modifier.border
import composegl.ui.modifier.clip
import composegl.ui.modifier.drawInFront
import composegl.ui.modifier.padding
import composegl.ui.modifier.shadow
import composegl.ui.modifier.size
import composegl.ui.node.UiNode
import composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A tree turned into drawing, asserted call by call. No GPU anywhere near it. */
class DrawPassTest {

    private val canvas = RecordingCanvas()
    private val tree = UiTree()

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val black = Colour.rgb(0x000000)

    private fun node(
        name: String,
        modifier: Modifier = Modifier,
        policy: MeasurePolicy = MeasurePolicy.Stack,
        children: List<UiNode> = emptyList(),
    ) = UiNode(name).also { node ->
        node.modifier = modifier
        node.measurePolicy = policy
        children.forEach { node.insertAt(node.children.size, it) }
    }

    private fun draw(root: UiNode, constraints: Constraints = Constraints.atMost(500f, 500f)) {
        tree.root.insertAt(0, root)
        MeasurePass().run(tree.root, constraints)
        DrawPass(canvas).draw(tree.root)
        canvas.assertBalanced()
    }

    private fun kinds() = canvas.calls.map { it::class.simpleName }

    // --- order ---

    @Test
    fun `a node paints behind, then itself, then its children, then in front`() {
        val child = node("child", Modifier.size(10f).background(blue))
        child.content = { rect -> rect(rect, black) }
        val parent = node(
            "parent",
            Modifier.size(50f).shadow(black, 4f).background(red).drawInFront { rect(it, blue) },
            children = listOf(child),
        )
        draw(parent)

        assertEquals(
            listOf("Shadow", "Rectangle", "Rectangle", "Rectangle", "Rectangle"),
            kinds(),
        )
        val rectangles = canvas.calls.filterIsInstance<DrawCall.Rectangle>()
        assertEquals(listOf(red, blue, black, blue), rectangles.map { it.colour })
    }

    @Test
    fun `children are drawn in the order they were added`() {
        val children = listOf(
            node("first", Modifier.size(10f).background(red)),
            node("second", Modifier.size(10f).background(blue)),
        )
        draw(node("parent", Modifier.size(50f), children = children))

        assertEquals(
            listOf(red, blue),
            canvas.calls.filterIsInstance<DrawCall.Rectangle>().map { it.colour },
        )
    }

    // --- chain order ---

    @Test
    fun `padding before a background paints inside the padding`() {
        draw(node("box", Modifier.size(50f).padding(8f).background(red)))

        val rect = canvas.calls.filterIsInstance<DrawCall.Rectangle>().single()
        assertEquals(Rect.of(8f, 8f, 34f, 34f), rect.rect)
    }

    @Test
    fun `padding after a background paints across the whole node`() {
        draw(node("box", Modifier.size(50f).background(red).padding(8f)))

        val rect = canvas.calls.filterIsInstance<DrawCall.Rectangle>().single()
        assertEquals(Rect.of(0f, 0f, 50f, 50f), rect.rect)
    }

    @Test
    fun `two backgrounds either side of a padding are both drawn, in order`() {
        draw(node("box", Modifier.size(50f).background(red).padding(8f).background(blue)))

        val rects = canvas.calls.filterIsInstance<DrawCall.Rectangle>()
        assertEquals(listOf(red, blue), rects.map { it.colour })
        assertEquals(50f, rects[0].rect.width)
        assertEquals(34f, rects[1].rect.width)
    }

    @Test
    fun `a border is drawn where the chain put it`() {
        draw(node("box", Modifier.size(50f).padding(4f).border(black, 2f, corner = 3f)))

        val border = canvas.calls.filterIsInstance<DrawCall.Border>().single()
        assertEquals(Rect.of(4f, 4f, 42f, 42f), border.rect)
        assertEquals(2f, border.width)
        assertEquals(3f, border.corner)
    }

    // --- where things end up ---

    @Test
    fun `positions add up down the tree`() {
        val grandchild = node("grandchild", Modifier.size(5f).background(red))
        val child = node("child", Modifier.padding(3f), children = listOf(grandchild))
        val parent = node("parent", Modifier.padding(10f), children = listOf(child))
        draw(parent)

        val rect = canvas.calls.filterIsInstance<DrawCall.Rectangle>().single()
        assertEquals(Rect.of(13f, 13f, 5f, 5f), rect.rect)
    }

    @Test
    fun `content is drawn in the content box, inside the padding`() {
        val box = node("box", Modifier.size(50f).padding(6f))
        box.content = { rect -> rect(rect, red) }
        draw(box)

        val rect = canvas.calls.filterIsInstance<DrawCall.Rectangle>().single()
        assertEquals(Rect.of(6f, 6f, 38f, 38f), rect.rect)
    }

    // --- clipping ---

    @Test
    fun `a clip applies to children and is popped afterwards`() {
        val child = node("child", Modifier.size(100f).background(red))
        val after = node("after", Modifier.size(100f).background(blue))
        val clipper = node("clipper", Modifier.size(20f).clip(), children = listOf(child))
        draw(node("root", children = listOf(clipper, after)))

        val rects = canvas.calls.filterIsInstance<DrawCall.Rectangle>()
        assertEquals(Rect.of(0f, 0f, 20f, 20f), rects[0].clip)
        assertEquals(Rect.of(0f, 0f, 1000f, 1000f), rects[1].clip, "the clip was not popped")
    }

    @Test
    fun `nested clips intersect`() {
        val inner = node(
            "inner",
            Modifier.size(100f).padding(30f).clip(),
            children = listOf(node("leaf", Modifier.size(100f).background(red))),
        )
        val outer = node("outer", Modifier.size(50f).clip(), children = listOf(inner))
        draw(outer)

        val rect = canvas.calls.filterIsInstance<DrawCall.Rectangle>().single()
        assertEquals(Rect.of(0f, 0f, 50f, 50f), rect.clip)
    }

    // --- opacity ---

    @Test
    fun `opacity multiplies down the tree`() {
        val child = node("child", Modifier.size(10f).alpha(0.5f).background(red))
        val parent = node("parent", Modifier.size(50f).alpha(0.5f), children = listOf(child))
        draw(parent)

        assertEquals(0.25f, canvas.calls.single().alpha)
    }

    @Test
    fun `nothing under a fully transparent node is drawn at all`() {
        val child = node("child", Modifier.size(10f).background(red))
        val parent = node("parent", Modifier.size(50f).alpha(0f), children = listOf(child))
        draw(node("root", children = listOf(parent, node("sibling", Modifier.size(5f).background(blue)))))

        assertEquals(listOf(blue), canvas.calls.filterIsInstance<DrawCall.Rectangle>().map { it.colour })
    }

    @Test
    fun `drawing the same tree twice draws the same thing`() {
        val root = node("box", Modifier.size(30f).background(red).clip().alpha(0.5f))
        draw(root)
        val first = canvas.calls.toList()

        canvas.clear()
        DrawPass(canvas).draw(tree.root)
        canvas.assertBalanced()

        assertEquals(first, canvas.calls)
    }
}
