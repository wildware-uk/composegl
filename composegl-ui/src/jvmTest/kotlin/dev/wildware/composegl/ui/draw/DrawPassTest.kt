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
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.drawInFront
import dev.wildware.composegl.ui.modifier.effect
import dev.wildware.composegl.ui.modifier.mirror
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.rotate
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.shadow
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.skew
import dev.wildware.composegl.ui.geometry.Offset
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
        override val drawsLayers: Boolean get() = false
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

    // --- scale ---

    /**
     * Where a picture of [capture] ends up once it has been stretched to fill [destination].
     *
     * Worked out from the two rectangles the canvas was actually given, rather than from the
     * formula the toolkit uses, so that these tests check the arithmetic instead of repeating it.
     * This is what the GPU does with the quad: the whole picture, mapped corner to corner.
     */
    private fun composited(capture: Rect, destination: Rect, rect: Rect): Rect {
        val factorX = destination.width / capture.width
        val factorY = destination.height / capture.height
        return Rect(
            destination.left + (rect.left - capture.left) * factorX,
            destination.top + (rect.top - capture.top) * factorY,
            destination.left + (rect.right - capture.left) * factorX,
            destination.top + (rect.bottom - capture.top) * factorY,
        )
    }

    private fun assertRect(expected: Rect, actual: Rect, message: String) {
        assertEquals(expected.left, actual.left, 0.001f, "$message: left")
        assertEquals(expected.top, actual.top, 0.001f, "$message: top")
        assertEquals(expected.right, actual.right, 0.001f, "$message: right")
        assertEquals(expected.bottom, actual.bottom, 0.001f, "$message: bottom")
    }

    @Test
    fun `a scaled node is captured at its own size and drawn into a bigger rectangle`() {
        val node = node("panel", Modifier.size(40f).scale(2f).background(red))

        draw(node)

        // The background is recorded first, at the size the node was laid out: nothing inside a
        // capture knows the scale is happening. Then the picture is put down twice as big, about
        // the node's centre, so the node grows in every direction rather than off to one side.
        assertEquals(listOf("Rectangle", "Layer"), kinds())
        assertEquals(Rect.of(0f, 0f, 40f, 40f), canvas.only<DrawCall.Rectangle>().single().rect)
        assertEquals(Rect(-20f, -20f, 60f, 60f), canvas.only<DrawCall.Layer>().single().bounds)
    }

    @Test
    fun `the origin says which point stays where it is`() {
        val node = node("panel", Modifier.size(40f).scale(2f, Alignment.TopStart).background(red))

        draw(node)

        assertEquals(Rect(0f, 0f, 80f, 80f), canvas.only<DrawCall.Layer>().single().bounds)
    }

    @Test
    fun `a scale of one takes no picture at all`() {
        // The fast path, asserted rather than assumed: a fit correction is exactly one whenever it
        // does not bind, and most of them do not.
        val node = node("panel", Modifier.size(40f).scale(1f).background(red))

        draw(node)

        assertEquals(listOf("Rectangle"), kinds())
    }

    @Test
    fun `a clip on a scaled node does not hold the picture inside the node`() {
        // Documented in `Modifier.scale`, and pinned here because it is the one thing about scale
        // that reads backwards: the clip is a clip on the capture, and the capture is then put
        // down filling the scaled rectangle. So a clipped 40-pixel box draws 80 pixels, and a
        // viewport that must not spill puts the clip on the parent instead.
        val node = node("panel", Modifier.size(40f).clip().scale(2f).background(red))

        draw(node)

        assertEquals(Rect(-20f, -20f, 60f, 60f), canvas.only<DrawCall.Layer>().single().bounds)
    }

    @Test
    fun `a scale of nothing draws nothing`() {
        val child = node("child", Modifier.size(10f).background(blue))
        val node = node("panel", Modifier.size(40f).scale(0f).background(red), children = listOf(child))

        draw(node)

        assertEquals(emptyList<String>(), kinds())
    }

    // --- mirroring ---

    /** A backend that makes pictures but cannot flip one. */
    private class NoMirror(canvas: RecordingCanvas) : UiCanvas by canvas {
        override val mirrorsLayers: Boolean get() = false
    }

    @Test
    fun `a mirror is one picture put down read from the other side`() {
        val node = node("panel", Modifier.size(40f).mirror().background(red))

        draw(node)

        assertEquals(listOf("Rectangle", "Layer"), kinds())
        val layer = canvas.only<DrawCall.Layer>().single()
        assertEquals(true, layer.mirrorX)
        assertEquals(false, layer.mirrorY)
        assertEquals(Rect.of(0f, 0f, 40f, 40f), layer.bounds, "in the node's own rectangle")
        assertEquals(null, layer.effect)
    }

    @Test
    fun `a mirror that says no takes no picture`() {
        draw(node("panel", Modifier.size(40f).mirror(horizontal = false).background(red)))

        assertEquals(listOf("Rectangle"), kinds())
    }

    @Test
    fun `two mirrors on one node take no picture`() {
        draw(node("panel", Modifier.size(40f).mirror().mirror().background(red)))

        assertEquals(listOf("Rectangle"), kinds())
    }

    @Test
    fun `a mirror and a scale share one picture`() {
        draw(node("panel", Modifier.size(40f).mirror(vertical = true).scale(2f).background(red)))

        val layer = canvas.only<DrawCall.Layer>().single()
        assertEquals(Rect(-20f, -20f, 60f, 60f), layer.bounds)
        assertEquals(true, layer.mirrorX)
        assertEquals(true, layer.mirrorY)
    }

    @Test
    fun `a mirror under an effect is the picture the shader works on`() {
        draw(node("panel", Modifier.size(40f).mirror().effect(glow).background(red)))

        val layers = canvas.only<DrawCall.Layer>()
        assertEquals(2, layers.size)
        assertEquals(true, layers[0].mirrorX, "the inner picture is the flipped one")
        assertEquals(null, layers[0].effect)
        assertEquals(glow, layers[1].effect, "and the glow is put round what it made")
        assertEquals(false, layers[1].mirrorX)
    }

    @Test
    fun `a turned mirror is flipped and then turned`() {
        draw(node("panel", Modifier.size(40f).mirror().rotate(90f).background(red)))

        val layers = canvas.only<DrawCall.Layer>()
        assertEquals(listOf(true, false), layers.map { it.mirrorX })
        assertEquals(listOf(0f, 90f), layers.map { it.degrees })
    }

    @Test
    fun `a canvas that cannot mirror draws the right way round and is clicked that way`() {
        val label = node("label", Modifier.size(10f).background(blue))
        val panel = node("panel", Modifier.size(40f).mirror().background(red), children = listOf(label))
        tree.root.insertAt(0, panel)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))
        assertEquals(Rect.of(30f, 0f, 10f, 10f), label.boundsInRoot, "before a draw it believes its chain")

        DrawPass(NoMirror(canvas)).draw(tree.root)

        assertEquals(listOf("Rectangle", "Rectangle"), kinds(), "no picture taken for a flip nobody can do")
        assertEquals(Rect.of(0f, 0f, 10f, 10f), label.boundsInRoot, "and hit testing goes back with the drawing")
    }

    @Test
    fun `where a mirrored node is drawn is where it says it is`() {
        val label = node("label", Modifier.size(20f).background(blue))
        val inner = node("inner", Modifier.size(30f), children = listOf(label))
        val panel = node("panel", Modifier.size(60f).mirror().background(red), children = listOf(inner))

        draw(panel)

        // The label is drawn at 0..20 inside a picture of 0..60; read from the other side, its
        // right edge is 60 - 20 = 40 from the left.
        val layer = canvas.only<DrawCall.Layer>().single()
        val drawn = canvas.only<DrawCall.Rectangle>().last { it.colour == blue }.rect
        val flipped = Rect(
            layer.bounds.right - (drawn.right - layer.bounds.left),
            drawn.top,
            layer.bounds.right - (drawn.left - layer.bounds.left),
            drawn.bottom,
        )
        assertEquals(flipped, label.boundsInRoot)
        assertEquals(Rect.of(40f, 0f, 20f, 20f), flipped)
    }

    @Test
    fun `where a scaled node is drawn is where it says it is`() {
        // The one assertion holding the two halves of this feature together. Drawing composites a
        // picture; hit testing and focus walk up the tree with arithmetic. They agree here or a
        // screen takes its clicks in the wrong place, and no screenshot can tell.
        val label = node("label", Modifier.size(20f).background(blue))
        val inner = node("inner", Modifier.size(30f), children = listOf(label))
        val panel = node("panel", Modifier.size(60f).scale(1.5f).background(red), children = listOf(inner))

        draw(panel)

        val layer = canvas.only<DrawCall.Layer>().single()
        val drawn = canvas.only<DrawCall.Rectangle>().last { it.colour == blue }.rect
        assertRect(
            composited(panel.layoutBoundsInRoot, layer.bounds, drawn),
            tree.root.firstOrNull { it.name == "label" }!!.boundsInRoot,
            "the label's own idea of where it is",
        )
    }

    @Test
    fun `a scale inside a scale composes`() {
        val inner = node("inner", Modifier.size(20f).scale(2f).background(blue))
        val outer = node("outer", Modifier.size(60f).scale(0.5f), children = listOf(inner))

        draw(outer)

        val layers = canvas.only<DrawCall.Layer>()
        assertEquals(2, layers.size, "one picture each, not one picture per factor")

        // The inner picture is composited inside the outer one, so where it ends up on screen is
        // its destination put through the outer composite — and that is what `inner` reports.
        val innerDrawn = composited(outer.layoutBoundsInRoot, layers.last().bounds, layers.first().bounds)
        assertRect(innerDrawn, inner.boundsInRoot, "the inner node")
        assertEquals(1f, inner.scaleInRoot, "half of twice the size is the size it was laid out")
    }

    @Test
    fun `a scale and an effect share one picture`() {
        val node = node("panel", Modifier.size(40f).scale(2f).effect(invert).background(red))

        draw(node)

        val layer = canvas.only<DrawCall.Layer>().single()
        assertEquals(invert, layer.effect, "the effect is still applied")
        assertEquals(Rect(-20f, -20f, 60f, 60f), layer.bounds, "and the composite is the scaled one")
    }

    @Test
    fun `a scaled node with a bleeding effect scales the bleed with it`() {
        val node = node("panel", Modifier.size(40f).scale(2f).effect(glow).background(red))

        draw(node)

        // The glow grows with the thing that is glowing rather than staying its own size round it.
        assertEquals(Rect(-32f, -32f, 72f, 72f), canvas.only<DrawCall.Layer>().single().bounds)
    }

    @Test
    fun `a canvas with no pictures draws the subtree once, unscaled, and says so`() {
        val node = node("panel", Modifier.size(40f).scale(2f).background(red))
        tree.root.insertAt(0, node)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))

        DrawPass(Plain(canvas)).draw(tree.root)

        assertEquals(listOf("Rectangle"), kinds(), "drawn straight, exactly once")
        assertEquals(Rect.of(0f, 0f, 40f, 40f), canvas.only<DrawCall.Rectangle>().single().rect)
        // And the important half: the node now agrees it is the size it was drawn at, so a click
        // on what you can see reaches it. A widget present and unscaled beats one you cannot hit.
        assertEquals(Rect.of(0f, 0f, 40f, 40f), node.boundsInRoot)
        assertEquals(1f, node.scaleInRoot)
    }

    @Test
    fun `a refusal is reconsidered the next time the tree is drawn`() {
        val node = node("panel", Modifier.size(40f).scale(2f).background(red))
        tree.root.insertAt(0, node)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))

        DrawPass(Plain(canvas)).draw(tree.root)
        assertEquals(Rect.of(0f, 0f, 40f, 40f), node.boundsInRoot, "refused, so unscaled")

        canvas.clear()
        DrawPass(canvas).draw(tree.root)

        assertEquals(Rect(-20f, -20f, 60f, 60f), node.boundsInRoot, "and a canvas that can, does")
    }

    // --- rotation ------------------------------------------------------------------------------

    @Test
    fun `a turned node is captured at its own size and put down at an angle`() {
        val node = node("card", Modifier.size(40f).rotate(15f).background(red))

        draw(node)

        val layer = canvas.only<DrawCall.Layer>().single()
        assertEquals(Rect(0f, 0f, 40f, 40f), layer.bounds, "captured at the size it was laid out")
        assertEquals(15f, layer.degrees, 0.001f, "and composited turned")
        assertEquals(0.5f, layer.pivotX, 0.001f, "about its middle by default")
        assertEquals(0.5f, layer.pivotY, 0.001f, "about its middle by default")
    }

    @Test
    fun `an angle of zero takes no picture at all`() {
        val node = node("card", Modifier.size(40f).rotate(0f).background(red))

        draw(node)

        assertEquals(emptyList<String>(), canvas.only<DrawCall.Layer>().map { "layer" })
    }

    @Test
    fun `angles add and the last origin wins`() {
        val node = node(
            "card",
            Modifier.size(40f).rotate(10f, Alignment.Centre).rotate(5f, Alignment.TopStart)
                .background(red),
        )

        draw(node)

        val layer = canvas.only<DrawCall.Layer>().single()
        assertEquals(15f, layer.degrees, 0.001f, "two rotations on one node are one turn")
        assertEquals(0f, layer.pivotX, 0.001f, "and the origin the later one named")
        assertEquals(0f, layer.pivotY, 0.001f, "and the origin the later one named")
    }

    @Test
    fun `a turn is about where the origin says, not the middle of a bled capture`() {
        // The glow grows the captured area past the node, so the node's own middle is no longer the
        // middle of the picture — which is the whole reason the pivot is worked out rather than
        // taken straight off the alignment.
        val node = node("card", Modifier.size(40f).effect(glow).rotate(30f).background(red))

        draw(node)

        val layer = canvas.only<DrawCall.Layer>().last()
        assertEquals(Rect(-6f, -6f, 46f, 46f), layer.bounds, "the bled area turns with the node")
        assertEquals(0.5f, layer.pivotX, 0.001f, "the node's middle, which the bleed left centred")
        assertEquals(20f, layer.bounds.left + layer.bounds.width * layer.pivotX, 0.001f,
            "and that really is the node's middle in the picture's own coordinates")
    }

    @Test
    fun `a scale and a turn are two pictures, the turn outermost`() {
        val node = node("card", Modifier.size(40f).scale(2f).rotate(15f).background(red))

        draw(node)

        val layers = canvas.only<DrawCall.Layer>()
        assertEquals(2, layers.size, "a turn cannot be folded into a rectangle, so it takes its own")
        assertEquals(0f, layers.first().degrees, 0.001f, "the scale's composite is upright")
        assertEquals(Rect(-20f, -20f, 60f, 60f), layers.first().bounds, "and is the scaled one")
        assertEquals(15f, layers.last().degrees, 0.001f, "and the turn is put down over it")
        assertEquals(Rect(-20f, -20f, 60f, 60f), layers.last().bounds, "turning what the scale drew")
    }

    @Test
    fun `a canvas that cannot make pictures draws a turned node upright rather than not at all`() {
        val node = node("card", Modifier.size(40f).rotate(30f).background(red))
        tree.root.insertAt(0, node)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))

        DrawPass(Plain(canvas)).draw(tree.root)

        assertEquals(emptyList<String>(), canvas.only<DrawCall.Layer>().map { "layer" })
        assertEquals(
            Rect(0f, 0f, 40f, 40f),
            canvas.only<DrawCall.Rectangle>().single { it.colour == red }.rect,
            "present, the right size and the right way up",
        )
    }

    @Test
    fun `a turned node is still hit where its upright box is`() {
        // Stated as a test rather than left to the docs: clicks deliberately do not follow a turn,
        // so a change that quietly made them follow it should fail here and be thought about.
        val node = node("card", Modifier.size(40f).rotate(45f).background(red))

        draw(node)

        assertRect(
            Rect(0f, 0f, 40f, 40f),
            tree.root.firstOrNull { it.name == "card" }!!.boundsInRoot,
            "the box it was laid out in",
        )
    }

    // --- skew ----------------------------------------------------------------------------------

    /** A backend that makes and turns pictures but has never been taught to put one on corners. */
    private class Unslanting(canvas: RecordingCanvas) : UiCanvas by canvas {
        override val drawsLayersOnto: Boolean get() = false
    }

    private fun assertCorners(expected: List<Offset>, actual: List<Offset>, message: String) {
        assertEquals(4, actual.size, "$message: four corners")
        val names = listOf("top-left", "top-right", "bottom-right", "bottom-left")
        for (index in 0 until 4) {
            assertEquals(expected[index].x, actual[index].x, 0.01f, "$message: ${names[index]} x")
            assertEquals(expected[index].y, actual[index].y, 0.01f, "$message: ${names[index]} y")
        }
    }

    @Test
    fun `a slanted node is captured upright and put down on a parallelogram`() {
        // Forty square with a slope of one about its middle: the top slides twenty left, the
        // bottom twenty right, and the middle row stays exactly where it was.
        val node = node("banner", Modifier.size(40f).skew(x = 45f).background(red))

        draw(node)

        assertEquals(listOf("Rectangle", "LayerOnto"), kinds(), "one picture, no upright composite")
        val onto = canvas.only<DrawCall.LayerOnto>().single()
        assertEquals(Rect(0f, 0f, 40f, 40f), onto.bounds, "captured at the size it was laid out")
        assertCorners(
            listOf(Offset(-20f, 0f), Offset(20f, 0f), Offset(60f, 40f), Offset(20f, 40f)),
            onto.corners,
            "top and bottom stay level and slide past each other",
        )
    }

    @Test
    fun `a negative horizontal skew leans the top forward like italic type`() {
        val node = node("banner", Modifier.size(40f).skew(x = -45f).background(red))

        draw(node)

        assertCorners(
            listOf(Offset(20f, 0f), Offset(60f, 0f), Offset(20f, 40f), Offset(-20f, 40f)),
            canvas.only<DrawCall.LayerOnto>().single().corners,
            "the sign CSS uses: negative puts the top to the right",
        )
    }

    @Test
    fun `a vertical skew keeps the sides upright and slides them instead`() {
        val node = node("banner", Modifier.size(40f).skew(y = 45f).background(red))

        draw(node)

        assertCorners(
            listOf(Offset(0f, -20f), Offset(40f, 20f), Offset(40f, 60f), Offset(0f, 20f)),
            canvas.only<DrawCall.LayerOnto>().single().corners,
            "the right side is lower than the left",
        )
    }

    @Test
    fun `the skew origin is the point that stays where it was laid out`() {
        val node = node(
            "bar",
            Modifier.size(40f).skew(x = 45f, origin = Alignment.BottomStart).background(red),
        )

        draw(node)

        assertCorners(
            listOf(Offset(-40f, 0f), Offset(0f, 0f), Offset(40f, 40f), Offset(0f, 40f)),
            canvas.only<DrawCall.LayerOnto>().single().corners,
            "the bottom edge does not move",
        )
    }

    @Test
    fun `a skew of zero takes no picture at all`() {
        val node = node("banner", Modifier.size(40f).skew(x = 0f, y = 0f).background(red))

        draw(node)

        assertEquals(listOf("Rectangle"), kinds(), "drawn exactly as if the modifier were not there")
    }

    @Test
    fun `a slant and a turn on one node are one picture`() {
        // Slanted first, about the middle, then a quarter turn clockwise about the same middle.
        val node = node("banner", Modifier.size(40f).skew(x = 45f).rotate(90f).background(red))

        draw(node)

        assertEquals(emptyList<String>(), canvas.only<DrawCall.Layer>().map { "layer" },
            "no second picture for the turn")
        assertCorners(
            listOf(Offset(40f, -20f), Offset(40f, 20f), Offset(0f, 60f), Offset(0f, 20f)),
            canvas.only<DrawCall.LayerOnto>().single().corners,
            "the slanted shape, turned",
        )
    }

    @Test
    fun `slopes add along an axis and the last origin wins`() {
        val node = node(
            "banner",
            Modifier.size(40f).skew(x = 10f).skew(x = 5f, origin = Alignment.TopStart).background(red),
        )

        draw(node)

        val slope = kotlin.math.tan(Math.toRadians(10.0)) + kotlin.math.tan(Math.toRadians(5.0))
        assertEquals(Math.toDegrees(kotlin.math.atan(slope)).toFloat(), node.resolved.skewX, 0.001f,
            "one shear whose slope is both slopes")
        assertEquals(Alignment.TopStart, node.resolved.skewOrigin)
        val corners = canvas.only<DrawCall.LayerOnto>().single().corners
        assertEquals(0f, corners[0].x, 0.01f, "the top-left is the origin, so it stays put")
        assertEquals((40.0 * slope).toFloat(), corners[3].x, 0.01f, "and the bottom slides by both")
    }

    @Test
    fun `a bleed widens the capture but not the point the slant is about`() {
        val node = node("banner", Modifier.size(40f).effect(glow).skew(x = 45f).background(red))

        draw(node)

        val onto = canvas.only<DrawCall.LayerOnto>().single()
        assertEquals(Rect(-6f, -6f, 46f, 46f), onto.bounds, "the glow slants with the node")
        assertCorners(
            listOf(Offset(-32f, -6f), Offset(20f, -6f), Offset(72f, 46f), Offset(20f, 46f)),
            onto.corners,
            "still about the node's own middle",
        )
    }

    @Test
    fun `a scale under a slant is the picture that gets slanted`() {
        val node = node("banner", Modifier.size(40f).scale(2f).skew(x = 45f).background(red))

        draw(node)

        assertEquals(Rect(-20f, -20f, 60f, 60f), canvas.only<DrawCall.Layer>().single().bounds,
            "the scale's upright composite")
        val onto = canvas.only<DrawCall.LayerOnto>().single()
        assertEquals(Rect(-20f, -20f, 60f, 60f), onto.bounds)
        assertCorners(
            listOf(Offset(-60f, -20f), Offset(20f, -20f), Offset(100f, 60f), Offset(20f, 60f)),
            onto.corners,
            "slanted about the middle of the scaled rectangle",
        )
    }

    @Test
    fun `a canvas that cannot use four corners keeps the turn and drops the slant`() {
        val node = node("banner", Modifier.size(40f).skew(x = 30f).rotate(15f).background(red))
        tree.root.insertAt(0, node)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))

        DrawPass(Unslanting(canvas)).draw(tree.root)

        assertEquals(emptyList<String>(), canvas.only<DrawCall.LayerOnto>().map { "onto" })
        assertEquals(15f, canvas.only<DrawCall.Layer>().single().degrees, 0.001f, "still turned")
    }

    @Test
    fun `a canvas that cannot use four corners draws a slant alone with no picture`() {
        val node = node("banner", Modifier.size(40f).skew(x = 30f).background(red))
        tree.root.insertAt(0, node)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))

        DrawPass(Unslanting(canvas)).draw(tree.root)

        assertEquals(listOf("Rectangle"), kinds(), "a picture put down upright would be a waste of one")
    }

    @Test
    fun `a canvas that cannot make pictures draws a slanted node plainly rather than not at all`() {
        val node = node("banner", Modifier.size(40f).skew(x = -12f).background(red))
        tree.root.insertAt(0, node)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))

        DrawPass(Plain(canvas)).draw(tree.root)

        assertEquals(
            Rect(0f, 0f, 40f, 40f),
            canvas.only<DrawCall.Rectangle>().single { it.colour == red }.rect,
            "present, the right size and upright",
        )
    }

    @Test
    fun `a slanted node is still hit where its upright box is`() {
        val node = node("banner", Modifier.size(40f).skew(x = -30f).background(red))

        draw(node)

        assertRect(
            Rect(0f, 0f, 40f, 40f),
            tree.root.firstOrNull { it.name == "banner" }!!.boundsInRoot,
            "the box it was laid out in",
        )
    }
}
