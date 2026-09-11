package dev.wildware.composegl.ui.draw

import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.drawInFront
import dev.wildware.composegl.ui.modifier.effect
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.shadow
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
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

    // --- effects ---

    private val invert = ShaderEffect(ShaderSource("invert", "void main() { }"))
    private val glow = ShaderEffect(ShaderSource("glow", "void main() { }"), bleed = 6f)

    /** A backend with no offscreen drawing: everything else recorded, but no layers. */
    private class Plain(canvas: RecordingCanvas) : UiCanvas by canvas {
        override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? = null
    }

    @Test
    fun `an effect draws the subtree into a layer and then draws the layer`() {
        val node = node("panel", Modifier.size(40f).effect(invert).background(red))

        draw(node)

        // The background is recorded first, because it happened inside the layer; the Layer call
        // is the moment the picture came back.
        assertEquals(listOf("Rectangle", "Layer"), kinds())
        val layer = canvas.calls.last() as DrawCall.Layer
        assertEquals(invert, layer.effect)
        assertEquals(Rect.of(0f, 0f, 40f, 40f), layer.bounds)
    }

    @Test
    fun `bleed makes the picture bigger than the node`() {
        // Without it a blur or a glow would be cut off square at the widget's edge.
        val node = node("panel", Modifier.size(40f).effect(glow).background(red))

        draw(node)

        val layer = canvas.calls.last() as DrawCall.Layer
        assertEquals(Rect.of(-6f, -6f, 52f, 52f), layer.bounds)
    }

    @Test
    fun `two effects are the second one working on the first one's answer`() {
        val node = node("panel", Modifier.size(40f).effect(invert).effect(glow).background(red))

        draw(node)

        val layers = canvas.calls.filterIsInstance<DrawCall.Layer>()
        assertEquals(listOf(invert, glow), layers.map { it.effect }, "the chain's order, innermost first")
    }

    @Test
    fun `an effect wraps the children too`() {
        val child = node("child", Modifier.size(10f).background(blue))
        val parent = node("parent", Modifier.size(40f).effect(invert), children = listOf(child))

        draw(parent)

        assertEquals(listOf("Rectangle", "Layer"), kinds(), "the child is inside the picture")
    }

    @Test
    fun `a canvas with no layers draws the subtree straight through`() {
        // The bargain the whole feature rests on: an effect degrades to no effect rather than to a
        // widget that is not there.
        val plain = Plain(canvas)
        val node = node("panel", Modifier.size(40f).effect(invert).background(red))
        tree.root.insertAt(0, node)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))

        DrawPass(plain).draw(tree.root)

        assertEquals(listOf("Rectangle"), kinds(), "drawn once, with no effect and nothing missing")
    }

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
