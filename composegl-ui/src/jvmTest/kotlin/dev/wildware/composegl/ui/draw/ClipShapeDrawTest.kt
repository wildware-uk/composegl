package dev.wildware.composegl.ui.draw

import dev.wildware.composegl.ui.debug.measureOverdraw
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Shapes
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
import dev.wildware.composegl.ui.modifier.clipShape
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.drawInFront
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot

/** A clip that is not a rectangle, turned into drawing and asserted call by call. */
class ClipShapeDrawTest {

    private val canvas = RecordingCanvas()
    private val tree = UiTree()

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    private fun node(name: String, modifier: Modifier = Modifier, children: List<UiNode> = emptyList()) =
        UiNode(name).also { node ->
            node.modifier = modifier
            node.measurePolicy = MeasurePolicy.Stack
            children.forEach { node.insertAt(node.children.size, it) }
        }

    private fun draw(root: UiNode, on: UiCanvas = canvas) {
        tree.root.insertAt(0, root)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))
        DrawPass(on).draw(tree.root)
        canvas.assertBalanced()
    }

    private fun portrait(modifier: Modifier) =
        node("portrait", modifier, children = listOf(node("art", Modifier.size(40f).background(red))))

    @Test
    fun `a shaped clip draws its node into a picture and cuts the picture to the outline`() {
        draw(portrait(Modifier.size(40f).clipShape(Shapes.Circle)))

        val art = canvas.only<DrawCall.Rectangle>().single()
        assertEquals(Rect.of(0f, 0f, 40f, 40f), art.clip, "the art was drawn into a picture the size of the node")

        val layer = canvas.only<DrawCall.Layer>().single()
        assertEquals(Rect.of(0f, 0f, 40f, 40f), layer.bounds)
        assertNotNull(layer.outline, "and the picture was put down cut, not whole")
        val outline = layer.outline!!
        for (at in outline.indices step 2) {
            val r = hypot(outline[at] - 20f, outline[at + 1] - 20f)
            assertTrue(abs(r - 20f) < 0.01f, "every point of the cut is on the circle, got $r")
        }
    }

    @Test
    fun `the outline is placed where the node is drawn`() {
        draw(node("frame", Modifier.padding(30f), children = listOf(portrait(Modifier.size(40f).clipShape(Shapes.Diamond)))))

        val outline = canvas.only<DrawCall.Layer>().single().outline!!
        assertEquals(listOf(50f, 30f, 70f, 50f, 50f, 70f, 30f, 50f), outline)
    }

    @Test
    fun `what the chain paints before the clip stays whole and what comes after is cut`() {
        draw(node("badge", Modifier.size(40f).border(blue, 2f).clipShape(Shapes.Circle).background(red)))

        val fill = canvas.only<DrawCall.Rectangle>().single()
        val ring = canvas.only<DrawCall.Border>().single()
        assertEquals(Rect.of(0f, 0f, 40f, 40f), fill.clip, "the background came after the clip, so it is in the picture")
        assertNotEquals(Rect.of(0f, 0f, 40f, 40f), ring.clip, "the border came before it, so it is drawn plainly")
        assertEquals(
            listOf("Rectangle", "Border", "Layer"),
            canvas.calls.map { it::class.simpleName },
            "the whole ring goes down under the cut picture",
        )
    }

    @Test
    fun `something drawn in front before the clip lands on top of the cut picture`() {
        draw(portrait(Modifier.size(40f).drawInFront { rect(it, blue) }.clipShape(Shapes.Circle)))

        assertEquals(listOf("Rectangle", "Layer", "Rectangle"), canvas.calls.map { it::class.simpleName })
        assertEquals(blue, canvas.only<DrawCall.Rectangle>().last().colour)
    }

    @Test
    fun `a clip with a corner is really rounded now`() {
        draw(portrait(Modifier.size(40f).clip(corner = 8f)))

        val outline = canvas.only<DrawCall.Layer>().single().outline!!
        assertTrue(outline.size > 8, "more than the four corners of the box")
        assertTrue((outline.indices step 2).none { outline[it] == 0f && outline[it + 1] == 0f }, "the corner is cut")
    }

    @Test
    fun `a plain clip is still a scissor and takes no picture`() {
        draw(portrait(Modifier.size(20f).clip()))

        assertTrue(canvas.only<DrawCall.Layer>().isEmpty())
        assertEquals(Rect.of(0f, 0f, 20f, 20f), canvas.only<DrawCall.Rectangle>().single().clip)
    }

    @Test
    fun `a rounded rectangle with no rounding is a scissor and takes no picture`() {
        draw(portrait(Modifier.size(20f).clipShape(Shapes.roundedRect(0f))))

        assertTrue(canvas.only<DrawCall.Layer>().isEmpty())
        assertEquals(Rect.of(0f, 0f, 20f, 20f), canvas.only<DrawCall.Rectangle>().single().clip)
    }

    @Test
    fun `a canvas that cannot cut clips to the rectangle instead`() {
        val uncut = object : UiCanvas by canvas {
            override val cutsLayers: Boolean get() = false
        }
        draw(portrait(Modifier.size(20f).clipShape(Shapes.Circle)), on = uncut)

        assertTrue(canvas.only<DrawCall.Layer>().isEmpty(), "no picture taken for nothing")
        assertEquals(Rect.of(0f, 0f, 20f, 20f), canvas.only<DrawCall.Rectangle>().single().clip, "and still clipped")
    }

    @Test
    fun `a canvas that refuses the picture clips to the rectangle instead`() {
        val refusing = object : UiCanvas by canvas {
            override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? = null
        }
        draw(portrait(Modifier.size(20f).clipShape(Shapes.Circle)), on = refusing)

        assertTrue(canvas.only<DrawCall.Layer>().isEmpty())
        assertEquals(Rect.of(0f, 0f, 20f, 20f), canvas.only<DrawCall.Rectangle>().single().clip)
    }

    @Test
    fun `a faded portrait fades the cut picture rather than its parts`() {
        draw(portrait(Modifier.size(40f).alpha(0.5f).clipShape(Shapes.Circle)))

        assertEquals(1f, canvas.only<DrawCall.Rectangle>().single().alpha, "full strength inside the picture")
        assertEquals(0.5f, canvas.only<DrawCall.Layer>().single().alpha, "faded as it is put down")
    }

    /**
     * A canvas that rounds clips in place, the way the GL canvas does, writing down each one: the
     * rectangle pushed last, and the corners it was rounded by.
     */
    private class Rounding(private val inner: RecordingCanvas) : UiCanvas by inner {
        val rounded = ArrayList<Pair<Rect, Corners>>()
        var rounds = true
        private var pushed: Rect? = null
        override val roundsClips: Boolean get() = rounds

        override fun pushClip(rect: Rect) {
            pushed = rect
            inner.pushClip(rect)
        }

        override fun roundClip(corners: Corners) {
            rounded += checkNotNull(pushed) { "rounded with nothing pushed" } to corners
        }
    }

    private val rounding = Rounding(canvas)

    @Test
    fun `a rounded clip on a canvas that rounds clips is drawn in place with no picture`() {
        draw(portrait(Modifier.size(40f).clip(corner = 8f)), on = rounding)

        assertTrue(canvas.only<DrawCall.Layer>().isEmpty(), "no picture: ${canvas.calls}")
        assertEquals(listOf(Rect.of(0f, 0f, 40f, 40f) to Corners.all(8f)), rounding.rounded)
        assertEquals(Rect.of(0f, 0f, 40f, 40f), canvas.only<DrawCall.Rectangle>().single().clip, "the art is inside it")
    }

    @Test
    fun `a still rounded clip takes no picture on its second frame either`() {
        val root = portrait(Modifier.size(40f).clip(Corners.top(10f)))
        draw(root, on = rounding)
        DrawPass(rounding).draw(tree.root)
        canvas.assertBalanced()

        assertTrue(canvas.only<DrawCall.Layer>().isEmpty())
        assertEquals(2, rounding.rounded.size)
        assertEquals(Corners.top(10f), rounding.rounded.last().second)
    }

    @Test
    fun `a rounded clip that holds a game's drawing is cut as a picture from its next frame until it is gone`() {
        var drawsRaw = true
        val root = node(
            "card",
            Modifier.size(40f).clip(corner = 8f).drawBehind { if (drawsRaw) raw {} },
            children = listOf(node("art", Modifier.size(40f).background(red))),
        )
        tree.root.insertAt(0, root)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))
        val pass = DrawPass(rounding)
        fun frame(): Boolean {
            canvas.clear()
            pass.draw(tree.root)
            canvas.assertBalanced()
            return canvas.only<DrawCall.Layer>().isNotEmpty()
        }

        assertEquals(false, frame(), "the first frame rounds in place: the canvas does not know yet")
        assertEquals(true, frame(), "the next one cuts a picture, as it did before rounding in place")
        drawsRaw = false
        assertEquals(true, frame(), "the frame the drawing goes still cuts, and finds it gone")
        assertEquals(false, frame(), "and the one after is back in place")
        assertEquals(2, rounding.rounded.size)
    }

    @Test
    fun `counting overdraw between frames leaves a rounded clip that holds a game's drawing on the cut`() {
        val root = node("card", Modifier.size(40f).clip(corner = 8f).drawBehind { raw {} })
        tree.root.insertAt(0, root)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))
        val pass = DrawPass(rounding)
        fun cuts(): Boolean {
            canvas.clear()
            pass.draw(tree.root)
            canvas.assertBalanced()
            return canvas.only<DrawCall.Layer>().isNotEmpty()
        }

        assertEquals(false, cuts(), "in place on the first frame")
        assertEquals(true, cuts(), "cut on the second")
        measureOverdraw(tree.root, like = rounding)
        assertEquals(true, cuts(), "and still cut after the overdraw was counted, which runs no game's drawing")
    }

    @Test
    fun `a rounded clip sent to the cut for a game's drawing rounds in place when the picture is refused`() {
        val refusing = object : UiCanvas by rounding {
            override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? = null
        }
        val root = node("card", Modifier.size(40f).clip(corner = 8f).drawBehind { raw {} })
        tree.root.insertAt(0, root)
        MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))
        val pass = DrawPass(refusing)
        repeat(3) {
            canvas.clear()
            pass.draw(tree.root)
            canvas.assertBalanced()
        }

        assertEquals(3, rounding.rounded.size, "rounded in place every frame, never left square")
        assertTrue(canvas.only<DrawCall.Layer>().isEmpty())
    }

    @Test
    fun `a canvas that does not count games' drawings keeps rounding in place`() {
        val quiet = object : UiCanvas by rounding {
            override val rawDrawings: Int get() = 0
        }
        val root = portrait(Modifier.size(40f).clip(corner = 8f).drawBehind { raw {} })
        draw(root, on = quiet)
        DrawPass(quiet).draw(tree.root)
        canvas.assertBalanced()

        assertTrue(canvas.only<DrawCall.Layer>().isEmpty(), "no picture: ${canvas.calls}")
        assertEquals(2, rounding.rounded.size)
    }

    @Test
    fun `a circle is rounded in place as the biggest square it fits`() {
        draw(node("wide", Modifier.size(60f, 40f).clipShape(Shapes.Circle)), on = rounding)

        assertEquals(listOf(Rect.of(10f, 0f, 40f, 40f) to Corners.all(20f)), rounding.rounded)
        assertTrue(canvas.only<DrawCall.Layer>().isEmpty())
    }

    @Test
    fun `what the chain paints before a rounded clip stays whole and what comes after is trimmed`() {
        draw(node("badge", Modifier.size(40f).border(blue, 2f).clip(corner = 8f).background(red)), on = rounding)

        val fill = canvas.only<DrawCall.Rectangle>().single()
        val ring = canvas.only<DrawCall.Border>().single()
        assertEquals(Rect.of(0f, 0f, 40f, 40f), fill.clip, "the background came after the clip, so it is inside it")
        assertNotEquals(Rect.of(0f, 0f, 40f, 40f), ring.clip, "the border came before it, so it is drawn plainly")
        assertEquals(listOf("Border", "Rectangle"), canvas.calls.map { it::class.simpleName }, "the ring goes down first")
    }

    @Test
    fun `something drawn in front before a rounded clip lands on top of it and outside it`() {
        draw(portrait(Modifier.size(40f).drawInFront { rect(it, blue) }.clip(corner = 8f)), on = rounding)

        val (art, front) = canvas.only<DrawCall.Rectangle>()
        assertEquals(red, art.colour)
        assertEquals(Rect.of(0f, 0f, 40f, 40f), art.clip)
        assertEquals(blue, front.colour)
        assertNotEquals(Rect.of(0f, 0f, 40f, 40f), front.clip)
    }

    @Test
    fun `a canvas that will not round this clip gets the cut picture as before`() {
        rounding.rounds = false
        draw(portrait(Modifier.size(40f).clip(corner = 8f)), on = rounding)

        assertTrue(rounding.rounded.isEmpty())
        assertNotNull(canvas.only<DrawCall.Layer>().single().outline)
    }

    @Test
    fun `a shape that is not round still takes the cut picture`() {
        val diamond = portrait(Modifier.size(40f).clipShape(Shapes.Diamond))
        val ellipse = portrait(Modifier.size(40f, 20f).clipShape(Shapes.Ellipse))
        draw(node("pair", children = listOf(diamond, ellipse)), on = rounding)

        assertTrue(rounding.rounded.isEmpty())
        assertEquals(2, canvas.only<DrawCall.Layer>().size)
    }

    @Test
    fun `a rounded clip with no area takes neither road`() {
        draw(portrait(Modifier.size(0f).clip(corner = 8f)), on = rounding)

        assertTrue(rounding.rounded.isEmpty())
        assertNull(canvas.only<DrawCall.Layer>().firstOrNull())
    }
}
