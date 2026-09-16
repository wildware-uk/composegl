package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The camera a pan-and-zoom canvas is built on, at the level every canvas shares: what a pushed
 * transform does to a box, a thickness, a clip, a picture and a run of text.
 */
class CanvasTransformTest {

    private val canvas = RecordingCanvas(Rect.of(0f, 0f, 400f, 300f))

    private fun frame(block: RecordingCanvas.() -> Unit): RecordingCanvas {
        canvas.begin(Viewport.oneToOne(Size(400f, 300f)))
        canvas.block()
        canvas.end()
        return canvas
    }

    @Test
    fun `a box is drawn where the transform puts it and as big as it makes it`() {
        frame {
            pushTransform(2f, 10f, 20f)
            rect(Rect.of(5f, 5f, 30f, 10f), Colour.Red, corner = 4f)
            popTransform()
        }

        val box = canvas.only<DrawCall.Rectangle>().single()
        assertEquals(Rect(20f, 30f, 80f, 50f), box.rect)
        assertEquals(8f, box.corner, "a corner is part of the thing being zoomed")
        assertEquals(2f, canvas.scaleOf(box))
    }

    @Test
    fun `an outline and a shadow grow with it`() {
        frame {
            pushTransform(3f, 0f, 0f)
            border(Rect.of(0f, 0f, 10f, 10f), Colour.White, width = 2f, corner = 1f)
            shadow(Rect.of(0f, 0f, 10f, 10f), Colour.Black, spread = 4f)
            popTransform()
        }

        assertEquals(6f, canvas.only<DrawCall.Border>().single().width)
        assertEquals(12f, canvas.only<DrawCall.Shadow>().single().spread)
    }

    @Test
    fun `corners that differ are each grown`() {
        frame {
            pushTransform(2f, 0f, 0f)
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red, Corners(1f, 2f, 3f, 4f))
            popTransform()
        }

        assertEquals(Corners(2f, 4f, 6f, 8f), canvas.only<DrawCall.CorneredRectangle>().single().corners)
    }

    @Test
    fun `transforms nest inner first`() {
        frame {
            pushTransform(2f, 100f, 0f)
            pushTransform(3f, 1f, 0f)
            rect(Rect(1f, 0f, 2f, 1f), Colour.Red)
            popTransform()
            popTransform()
        }

        // Inside: 1 × 3 + 1 = 4. Outside: 4 × 2 + 100 = 108, and one unit wide becomes six.
        val box = canvas.only<DrawCall.Rectangle>().single()
        assertEquals(108f, box.rect.left)
        assertEquals(6f, box.rect.width)
        assertEquals(6f, canvas.scaleOf(box))
    }

    @Test
    fun `a clip pushed inside is the box where it lands`() {
        frame {
            pushTransform(2f, 40f, 0f)
            pushClip(Rect.of(0f, 0f, 50f, 50f))
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            popClip()
            popTransform()
        }

        assertEquals(Rect(40f, 0f, 140f, 100f), canvas.only<DrawCall.Rectangle>().single().clip)
    }

    @Test
    fun `text lands where the transform puts it and says how big it was drawn`() {
        frame {
            pushTransform(2f, 10f, 10f, textScale = 1.5f)
            text(Words, 5f, 5f, Colour.White)
            popTransform()
        }

        val run = canvas.only<DrawCall.Text>().single()
        assertEquals(Offset(20f, 20f), run.at)
        assertEquals(2f, canvas.scaleOf(run))
    }

    @Test
    fun `a canvas says what glyphs would be made for`() {
        canvas.begin(Viewport.oneToOne(Size(400f, 300f)))
        canvas.pushTransform(2f, 0f, 0f, textScale = 1.4f)
        assertEquals(1.4f, canvas.textScale, "the text scale is its own, not the zoom")
        canvas.popTransform()
        canvas.end()
    }

    @Test
    fun `a picture and a fan are moved and grown too`() {
        frame {
            pushTransform(2f, 0f, 0f)
            fan(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f), Colour.White)
            popTransform()
        }

        val fan = canvas.only<DrawCall.Fan>().single()
        assertEquals(listOf(Offset(0f, 0f), Offset(20f, 0f), Offset(20f, 20f)), fan.points)
    }

    @Test
    fun `a layer is taken where it lands so a zoomed effect is as sharp as the node`() {
        frame {
            pushTransform(2f, 30f, 0f)
            val picture = layer(Rect.of(10f, 10f, 20f, 20f)) { rect(Rect.of(10f, 10f, 20f, 20f), Colour.Red) }
            drawLayer(checkNotNull(picture), Rect.of(10f, 10f, 20f, 20f))
            popTransform()
        }

        val inside = canvas.only<DrawCall.Rectangle>().single()
        assertEquals(Rect(50f, 20f, 90f, 60f), inside.rect)
        assertEquals(Rect(50f, 20f, 90f, 60f), inside.clip, "the layer's clip is where the layer lands")
        assertEquals(Rect(50f, 20f, 90f, 60f), canvas.only<DrawCall.Layer>().single().bounds)
    }

    @Test
    fun `a transform left pushed is caught at the end of the frame`() {
        canvas.begin(Viewport.oneToOne(Size(400f, 300f)))
        canvas.pushTransform(2f, 0f, 0f)
        assertFailsWith<IllegalStateException> { canvas.end() }
        canvas.clear(Rect.of(0f, 0f, 400f, 300f))
    }

    @Test
    fun `popping one that was never pushed is caught`() {
        assertFailsWith<IllegalStateException> { canvas.popTransform() }
    }

    @Test
    fun `a canvas that cannot transform draws where it was asked`() {
        val flat = Plain()
        assertTrue(!flat.transforms, "the default is no")

        flat.pushTransform(2f, 50f, 50f)
        flat.rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red, 0f)
        flat.popTransform()

        assertEquals(listOf(Rect(0f, 0f, 10f, 10f)), flat.boxes, "unmoved and its own size, rather than nothing")
    }

    /** A backend written before transforms existed: it implements what it has to and no more. */
    private class Plain : UiCanvas {
        val boxes = mutableListOf<Rect>()
        override fun rect(rect: Rect, colour: Colour, corner: Float) {
            boxes += rect
        }
        override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) = Unit
        override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) = Unit
        override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) = Unit
        override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) = Unit
        override fun fan(points: FloatArray, colour: Colour) = Unit
        override fun pushClip(rect: Rect) = Unit
        override fun popClip() = Unit
        override fun pushAlpha(alpha: Float) = Unit
        override fun popAlpha() = Unit
        override fun raw(block: (Any) -> Unit) = Unit
    }

    private companion object {
        val Words = object : TextLayout {
            override val text: String = "words"
            override val size: Size = Size(30f, 10f)
            override val lineCount: Int = 1
            override val firstBaseline: Float = 8f
        }
    }
}
