package dev.wildware.composegl.ui.draw

import dev.wildware.composegl.ui.debug.measureOverdraw
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.blend
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.effect
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A scaled node on a canvas that transforms: drawn straight onto the screen through one transform,
 * frame after frame, with no offscreen picture — and the cases that still take one.
 */
class ScaleInPlaceTest {

    private val canvas = RecordingCanvas()
    private val tree = UiTree()
    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val green = Colour.rgb(0x00FF00)

    private fun node(
        name: String,
        modifier: Modifier = Modifier,
        children: List<UiNode> = emptyList(),
        policy: MeasurePolicy = MeasurePolicy.Stack,
    ) = UiNode(name).also { node ->
        node.modifier = modifier
        node.measurePolicy = policy
        children.forEach { node.insertAt(node.children.size, it) }
    }

    private var nanos = 0L

    /** One frame: the tree's clocks moved on, [root] laid out and drawn through [pass] from a clean recording. */
    private fun frame(root: UiNode, pass: DrawPass = DrawPass(canvas)) {
        nanos += 16_666_667L
        tree.clocks.advance(nanos)
        again(root, pass)
    }

    /** Another pass over the same frame: the clocks stay where they are. */
    private fun again(root: UiNode, pass: DrawPass = DrawPass(canvas)) {
        if (root.parent == null) tree.root.insertAt(tree.root.children.size, root)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))
        canvas.clear()
        pass.draw(tree.root)
        canvas.assertBalanced()
    }

    private fun assertRect(expected: Rect, actual: Rect, message: String) {
        assertEquals(expected.left, actual.left, 0.001f, "$message: left")
        assertEquals(expected.top, actual.top, 0.001f, "$message: top")
        assertEquals(expected.right, actual.right, 0.001f, "$message: right")
        assertEquals(expected.bottom, actual.bottom, 0.001f, "$message: bottom")
    }

    private fun layers() = canvas.only<DrawCall.Layer>()

    @Test
    fun `a still scaled node draws on its second frame without a picture`() {
        val panel = node("panel", Modifier.size(40f).scale(2f).background(red))

        repeat(2) { at ->
            frame(panel)
            assertEquals(emptyList(), layers(), "frame ${at + 1} took no picture")
            val box = canvas.only<DrawCall.Rectangle>().single()
            assertRect(Rect(-20f, -20f, 60f, 60f), box.rect, "drawn where the picture was put down, about the middle")
            assertEquals(2f, canvas.scaleOf(box), "and drawn that much bigger")
        }
    }

    @Test
    fun `the origin says which point stays where it is`() {
        frame(node("panel", Modifier.size(40f).scale(0.5f, Alignment.TopStart).background(red)))

        assertRect(Rect(0f, 0f, 20f, 20f), canvas.only<DrawCall.Rectangle>().single().rect, "shrunk into its corner")
    }

    @Test
    fun `a scaled node whose child changes draws the change in the next frame`() {
        val dot = node("dot", Modifier.size(10f).background(blue))
        val panel = node("panel", Modifier.size(40f).scale(0.5f, Alignment.TopStart), listOf(dot))
        frame(panel)

        dot.modifier = Modifier.size(10f).background(green)
        frame(panel)

        val box = canvas.only<DrawCall.Rectangle>().single()
        assertEquals(green, box.colour)
        assertRect(Rect(0f, 0f, 5f, 5f), box.rect, "at half its size")
        assertEquals(emptyList(), layers())
    }

    @Test
    fun `a child is cut off at the scaled rectangle as the picture's edge cut it`() {
        val spill = node("spill", Modifier.size(10f).offset(35f, 0f).background(blue))
        frame(node("panel", Modifier.size(40f).scale(0.5f, Alignment.TopStart), listOf(spill)))

        val box = canvas.only<DrawCall.Rectangle>().single()
        assertRect(Rect(17.5f, 0f, 22.5f, 5f), box.rect, "the child, half size")
        assertRect(Rect(0f, 0f, 20f, 20f), box.clip, "and clipped to where the panel is drawn")
    }

    @Test
    fun `a scale inside a scale composes`() {
        val inner = node("inner", Modifier.size(20f).scale(2f, Alignment.TopStart).background(blue))
        frame(node("outer", Modifier.size(60f).scale(0.5f, Alignment.TopStart), listOf(inner)))

        val box = canvas.only<DrawCall.Rectangle>().single()
        assertRect(Rect(0f, 0f, 20f, 20f), box.rect, "twice the size, at half the size")
        assertEquals(1f, canvas.scaleOf(box))
        assertEquals(emptyList(), layers())
    }

    @Test
    fun `a faded scaled node is still one picture`() {
        frame(node("panel", Modifier.size(40f).alpha(0.5f).scale(2f).background(red)))

        assertEquals(1, layers().size, "so it fades as one thing, not part through part")
    }

    @Test
    fun `a scaled node under a faded parent is still one picture`() {
        val panel = node("panel", Modifier.size(40f).scale(2f).background(red))
        frame(node("parent", Modifier.size(100f).alpha(0.5f), listOf(panel)))

        assertEquals(1, layers().size)
    }

    @Test
    fun `a scaled node blending in a mode of its own is still one picture`() {
        frame(node("panel", Modifier.size(40f).blend(BlendMode.Additive).scale(2f).background(red)))

        assertEquals(1, layers().size)
    }

    @Test
    fun `a fade or a blend beside a scaled node does not reach it`() {
        val faded = node("faded", Modifier.size(10f).alpha(0.5f).background(blue))
        val glowing = node("glowing", Modifier.size(10f).blend(BlendMode.Additive).background(blue))
        val panel = node("panel", Modifier.size(40f).scale(2f).background(red))
        tree.root.insertAt(0, faded)
        tree.root.insertAt(1, glowing)
        frame(panel)

        assertEquals(emptyList(), layers(), "drawn and popped before the panel's turn")
    }

    @Test
    fun `a scale that is moving is drawn through a picture until it holds still`() {
        val panel = node("panel", Modifier.size(40f).scale(0.5f).background(red))
        frame(panel)
        assertEquals(emptyList(), layers(), "the first frame has nothing to have moved from")

        panel.modifier = Modifier.size(40f).scale(0.6f).background(red)
        frame(panel)
        assertEquals(1, layers().size, "a new factor: the letters slide together in a picture")

        repeat(9) { held ->
            frame(panel)
            assertEquals(1, layers().size, "held for ${held + 1} frames: not settled yet")
        }
        frame(panel)
        assertEquals(emptyList(), layers(), "held for ten: still, so a transform")
    }

    @Test
    fun `a factor that changes every other frame stays a picture throughout`() {
        // A camera moving on a 60 Hz update drawn at 120 Hz: a new distance, then the same one.
        val panel = node("panel", Modifier.size(40f).scale(0.5f).background(red))
        frame(panel)
        repeat(12) { at ->
            if (at % 2 == 0) panel.modifier = Modifier.size(40f).scale(0.51f + at * 0.01f).background(red)
            frame(panel)
            assertEquals(1, layers().size, "frame ${at + 1} keeps the picture, so the letters never switch road")
        }
    }

    @Test
    fun `every pass in one frame agrees that a scale is moving`() {
        val panel = node("panel", Modifier.size(40f).scale(0.5f).background(red))
        frame(panel)

        panel.modifier = Modifier.size(40f).scale(0.6f).background(red)
        frame(panel)
        assertEquals(1, layers().size)
        again(panel)
        assertEquals(1, layers().size, "a second pass in the same frame, an overdraw count say, takes the same road")

        repeat(10) { frame(panel) }
        assertEquals(emptyList(), layers(), "and once it has settled, a transform")
    }

    @Test
    fun `an overdraw count early in a frame does not change how the frame draws a moving scale`() {
        val panel = node("panel", Modifier.size(40f).scale(0.5f).background(red))
        frame(panel)

        panel.modifier = Modifier.size(40f).scale(0.6f).background(red)
        nanos += 16_666_667L
        tree.clocks.advance(nanos)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))
        assertEquals(2, measureOverdraw(tree.root, like = canvas).at(30f, 30f), "counted as the picture it is")
        again(panel)

        assertEquals(1, layers().size, "and the frame still takes it, as if nobody had counted")
    }

    @Test
    fun `a tree whose clocks never move draws every scale in place`() {
        val panel = node("panel", Modifier.size(40f).scale(0.5f).background(red))
        again(panel)
        panel.modifier = Modifier.size(40f).scale(0.6f).background(red)
        again(panel)

        assertEquals(emptyList(), layers(), "no frames, so nothing can be seen to move")
    }

    @Test
    fun `a glow under a still scale draws on its second frame without a picture`() {
        val glow = node("glow", Modifier.size(10f).blend(BlendMode.Additive).background(blue))
        val panel = node("panel", Modifier.size(40f).scale(0.5f, Alignment.TopStart), listOf(node("card", Modifier.size(20f).background(red), listOf(glow))))

        repeat(2) { at ->
            frame(panel)
            assertEquals(emptyList(), layers(), "frame ${at + 1} took no picture")
            val added = canvas.only<DrawCall.Rectangle>().single { it.colour == blue }
            assertEquals(BlendMode.Additive, canvas.blendOf(added), "still added, onto the card under it")
            assertEquals(0.5f, canvas.scaleOf(added), "at the panel's size")
            assertRect(Rect(0f, 0f, 5f, 5f), added.rect, "where the picture put it")
        }
    }

    @Test
    fun `an effect under a still scale takes only its own picture`() {
        val soft = ShaderEffect(ShaderSource("soft", "void main() { }"), bleed = 4f)
        val blurred = node("blurred", Modifier.size(10f).effect(soft).background(blue))
        frame(node("panel", Modifier.size(40f).scale(0.5f, Alignment.TopStart), listOf(blurred)))

        val picture = layers().single()
        assertEquals(soft, picture.effect, "the effect's picture, and none for the scale round it")
        assertEquals(0.5f, canvas.scaleOf(picture), "put down at the panel's size")
        assertRect(Rect(-2f, -2f, 7f, 7f), picture.bounds, "the bled area, shrunk with the panel")
        // Taken as the scale's picture took it: at the node's own size, so a shader stepping in the
        // picture's own pixels sees what it always saw.
        assertEquals(1f, canvas.scaleOf(canvas.only<DrawCall.Rectangle>().single()), "the box inside it, at its own size")
    }

    @Test
    fun `an effect under a still scale that grows is taken at its own size and grown on the way down`() {
        val soft = ShaderEffect(ShaderSource("soft", "void main() { }"), bleed = 4f)
        val blurred = node("blurred", Modifier.size(10f).effect(soft).background(blue))
        frame(node("panel", Modifier.size(40f).scale(2f, Alignment.TopStart), listOf(node("card", Modifier.size(20f), listOf(blurred)))))

        val picture = layers().single()
        assertEquals(2f, canvas.scaleOf(picture), "put down twice the size")
        assertRect(Rect(-8f, -8f, 28f, 28f), picture.bounds, "the bled area, grown with the panel")
        assertEquals(1f, canvas.scaleOf(canvas.only<DrawCall.Rectangle>().single()), "taken at the node's own size")
    }

    @Test
    fun `an effect under two still scales is taken with both taken off`() {
        val soft = ShaderEffect(ShaderSource("soft", "void main() { }"))
        val blurred = node("blurred", Modifier.size(10f).effect(soft).background(blue))
        val inner = node("inner", Modifier.size(20f).scale(0.5f, Alignment.TopStart), listOf(blurred))
        frame(node("outer", Modifier.size(40f).scale(0.5f, Alignment.TopStart), listOf(inner)))

        assertEquals(0.25f, canvas.scaleOf(layers().single()))
        assertEquals(1f, canvas.scaleOf(canvas.only<DrawCall.Rectangle>().single()))
    }

    @Test
    fun `a frame that failed inside a still scale leaves the next frame's effects at their own size`() {
        val pass = DrawPass(canvas)
        val broken = node("broken", Modifier.size(10f).drawBehind { error("a widget that failed to draw") })
        assertTrue(runCatching { frame(node("panel", Modifier.size(40f).scale(0.5f), listOf(broken)), pass) }.isFailure)
        tree.root.removeAt(0, tree.root.children.size)

        val soft = ShaderEffect(ShaderSource("soft", "void main() { }"))
        frame(node("plain", Modifier.size(10f).effect(soft).background(blue)), pass)

        assertEquals(1f, canvas.scaleOf(layers().single()), "no scale is around it now")
        assertEquals(1f, canvas.scaleOf(canvas.only<DrawCall.Rectangle>().single()), "and none is taken off")
    }

    @Test
    fun `a glow under a still scale that grows draws without a picture`() {
        val glow = node("glow", Modifier.size(10f).blend(BlendMode.Additive).background(blue))
        frame(node("panel", Modifier.size(40f).scale(2f, Alignment.TopStart), listOf(glow)))

        assertEquals(emptyList(), layers())
        assertEquals(2f, canvas.scaleOf(canvas.only<DrawCall.Rectangle>().single()))
    }

    @Test
    fun `a glow a widget pushes itself under a still scale is drawn as it would be unscaled`() {
        // The limit, pinned: only the modifier is seen. What a draw block blends is its own business,
        // and while the factor holds still it adds onto what is behind the panel, as it would unscaled.
        val glow = node("glow", Modifier.size(10f).drawBehind { bounds ->
            pushBlend(BlendMode.Additive)
            rect(bounds, blue)
            popBlend()
        })
        frame(node("panel", Modifier.size(40f).scale(0.5f), listOf(glow)))

        assertEquals(emptyList(), layers())
        assertEquals(BlendMode.Additive, canvas.blendOf(canvas.only<DrawCall.Rectangle>().single()))
    }

    @Test
    fun `a scale arriving from nothing is a picture on its way in`() {
        val panel = node("panel", Modifier.size(40f).scale(0f).background(red))
        frame(panel)
        assertEquals(emptyList(), canvas.calls, "nothing to draw at zero")

        panel.modifier = Modifier.size(40f).scale(0.2f).background(red)
        frame(panel)
        assertEquals(1, layers().size)
    }

    @Test
    fun `a fade far above a scaled node still makes it one picture`() {
        val panel = node("panel", Modifier.size(40f).scale(2f).background(red))
        val middle = node("middle", Modifier.size(60f), listOf(panel))
        frame(node("faded", Modifier.size(100f).alpha(0.5f), listOf(middle)))

        assertEquals(1, layers().size)
    }

    @Test
    fun `a frame that failed inside a fade leaves the next frame free to draw in place`() {
        val pass = DrawPass(canvas)
        val broken = node("broken", Modifier.size(10f).drawBehind { error("a widget that failed to draw") })
        val faded = node("faded", Modifier.size(20f).alpha(0.5f), listOf(broken))
        assertTrue(runCatching { frame(faded, pass) }.isFailure)
        tree.root.removeAt(0, tree.root.children.size)

        frame(node("panel", Modifier.size(40f).scale(2f).background(red)), pass)

        assertEquals(emptyList(), layers(), "nothing about the failed frame is carried into this one")
    }

    @Test
    fun `a scaled node with no area keeps the picture's road and its children are drawn plainly`() {
        // A real canvas refuses a picture of nothing, and the subtree is drawn at its own size.
        val refusing = object : UiCanvas by canvas {
            override fun layer(bounds: Rect, block: () -> Unit) = if (bounds.isEmpty) null else canvas.layer(bounds, block)
        }
        // A panel that takes no room at all and lets its child hang out of it.
        val nothing = MeasurePolicy { measurables, _ ->
            val placed = measurables.map { it.measure(Constraints.atMost(100f, 100f)) }
            layout(0f, 0f) { placed.forEach { it.at(0f, 0f) } }
        }
        val child = node("child", Modifier.size(10f).background(blue))
        val panel = node("panel", Modifier.scale(0.5f, Alignment.TopStart), listOf(child), nothing)
        frame(panel, DrawPass(refusing))

        val box = canvas.only<DrawCall.Rectangle>().single()
        assertRect(Rect(0f, 0f, 10f, 10f), box.rect, "the child, at its own size")
        assertEquals(1f, canvas.scaleOf(box))
        assertEquals(Rect(0f, 0f, 10f, 10f), child.boundsInRoot, "and clicked where it is drawn")
    }

    @Test
    fun `a moving scale whose picture is refused is drawn through the transform and keeps its size`() {
        // Too big for one picture, as anything over 4096 screen pixels a side is on a real backend.
        val refusing = object : UiCanvas by canvas {
            override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? = null
        }
        val pass = DrawPass(refusing)
        val panel = node("panel", Modifier.size(40f).scale(0.5f).background(red))
        frame(panel, pass)
        assertEquals(0.5f, canvas.scaleOf(canvas.only<DrawCall.Rectangle>().single()), "still: a transform")

        repeat(11) { at ->
            panel.modifier = Modifier.size(40f).scale(0.6f + at * 0.01f).background(red)
            frame(panel, pass)
            val box = canvas.only<DrawCall.Rectangle>().single()
            assertEquals(0.6f + at * 0.01f, canvas.scaleOf(box), 0.0001f, "frame ${at + 1} of the move is drawn at its factor, not full size")
            assertTrue(panel.scaleApplied, "and clicked where it is drawn")
        }
    }

    @Test
    fun `a canvas that cannot transform still takes a picture`() {
        val flat = object : UiCanvas by canvas {
            override val transforms: Boolean get() = false
        }
        frame(node("panel", Modifier.size(40f).scale(2f).background(red)), DrawPass(flat))

        assertEquals(1, layers().size)
        assertTrue(canvas.only<DrawCall.Rectangle>().single().rect == Rect.of(0f, 0f, 40f, 40f))
    }
}
