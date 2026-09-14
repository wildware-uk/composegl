package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.text.TextLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a backend written against 0.1.0 does when it meets a blend mode and a turned picture.
 *
 * The stand-in below implements exactly the members [UiCanvas] demanded before either existed, so
 * it is the shape of every canvas outside this repository. If any of these stopped being a default
 * body, this class would stop compiling — which is the point of it.
 */
class UiCanvasDefaultsTest {

    @Test
    fun `pushing a blend mode on a canvas that has never heard of one does nothing`() {
        val canvas = OldBackend()

        canvas.pushBlend(BlendMode.Additive)
        canvas.rect(Rect.of(0f, 0f, 10f, 10f), Colour.White)
        canvas.popBlend()

        assertEquals(1, canvas.rects.size, "the drawing still happened")
        assertFalse(canvas.supports(BlendMode.Additive), "and it says so when asked")
        assertTrue(canvas.supports(BlendMode.SourceOver), "everything can do the ordinary one")
    }

    @Test
    fun `a turned picture is drawn upright in the right place and the right colour`() {
        val canvas = OldBackend()
        val texture = Pretend()
        val box = Rect.of(10f, 20f, 40f, 8f)

        canvas.image(texture, box, degrees = 30f, pivotX = 0f, pivotY = 0.5f, tint = Colour.Black)

        assertFalse(canvas.rotatesImages, "it says it cannot turn one")
        val drawn = canvas.images.single()
        assertEquals(box, drawn.first, "right place, right size")
        assertEquals(Colour.Black, drawn.second, "and the tint went through")
    }

    @Test
    fun `a turned layer is composited upright in the right place`() {
        val canvas = OldBackend()
        val picture = Pretend()
        val box = Rect.of(10f, 20f, 40f, 8f)

        canvas.drawLayer(picture, box, degrees = 30f, pivotX = 0f, pivotY = 0.5f)

        assertFalse(canvas.turnsLayers, "it says it cannot turn one")
        val drawn = canvas.images.single()
        assertEquals(box, drawn.first, "right place, right size, and drawn rather than skipped")
    }

    @Test
    fun `a backend that never heard of the raw questions answers no to all of them`() {
        val canvas = OldBackend()

        assertFalse(canvas.handsOverRaw, "nobody who has not said otherwise has anything to hand over")
        assertFalse(canvas.movesRawOrigin, "and nobody who has not said otherwise moves an origin")
        assertEquals(17f, canvas.rawX(17f), "x is the easy axis - it points the same way everywhere")
        assertEquals(17f, canvas.rawY(17f), "and a backend that shares the toolkit's y leaves that alone too")
    }

    @Test
    fun `a destination an old backend cannot honour still runs the block`() {
        val canvas = OldBackend()

        canvas.raw(Rect.of(10f, 20f, 4f, 4f)) { }

        // Not a lesser picture - the same drawing in the wrong place - which is exactly why the
        // question above exists and why a caller is told to ask it rather than to hope.
        assertEquals(1, canvas.raws, "it fell through to the one call it does have")
    }

    private class Pretend : TextureHandle {
        override val width = 8
        override val height = 8
    }

    /** Only what [UiCanvas] required before blend modes and turned pictures were added to it. */
    private class OldBackend : UiCanvas {

        val rects = mutableListOf<Rect>()
        val images = mutableListOf<Pair<Rect, Colour>>()
        var raws = 0

        override fun rect(rect: Rect, colour: Colour, corner: Float) {
            rects += rect
        }

        override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) {
            images += destination to tint
        }

        override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) = Unit
        override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) = Unit
        override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) = Unit
        override fun fan(points: FloatArray, colour: Colour) = Unit
        override fun pushClip(rect: Rect) = Unit
        override fun popClip() = Unit
        override fun pushAlpha(alpha: Float) = Unit
        override fun popAlpha() = Unit
        override fun raw(block: (Any) -> Unit) {
            raws++
        }
    }
}
