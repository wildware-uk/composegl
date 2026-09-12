package dev.wildware.composegl.ui.text

import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What an outline actually puts on a canvas.
 *
 * The whole feature is a recipe — eight copies of the run, then the run — and a recipe is exactly
 * the kind of thing that can be asserted with no GPU anywhere. So these count the calls, check
 * where each one landed and check the order, which is the only thing that stops one letter's ring
 * being painted over the letter beside it.
 */
class TextOutlineTest {

    private val fonts = MonospaceFontProvider()
    private val layout = fonts.measure("hi")
    private val ink = Colour.White
    private val ring = Colour.Black

    @Test
    fun `an outline cannot be a negative width and the message says which width`() {
        val failure = assertFailsWith<IllegalArgumentException> { TextOutline(ring, width = -3f) }
        assertTrue("-3" in (failure.message ?: ""), "the message should name the width: ${failure.message}")
    }

    @Test
    fun `the ring is eight copies - four straight and then four on the diagonal`() {
        val stamps = mutableListOf<Offset>()
        TextOutline(ring, width = 2f).forEachStamp { dx, dy -> stamps += Offset(dx, dy) }

        assertEquals(8, stamps.size)
        assertContentEquals(
            listOf(Offset(-2f, 0f), Offset(2f, 0f), Offset(0f, -2f), Offset(0f, 2f)),
            stamps.take(4),
            "the straight four are a whole width out, one per side",
        )
        // The corners are the same distance out, taken diagonally, which is the next test. Here
        // they only have to be corners: both axes offset, all four quadrants, none of them zero.
        val corners = stamps.drop(4)
        assertEquals(4, corners.toSet().size, "four different corners")
        assertTrue(corners.all { abs(it.x) == abs(it.y) && it.x != 0f }, "a corner moves on both axes")
    }

    @Test
    fun `every copy sits one width from the letter - corners included`() {
        // The property the whole recipe rests on: eight copies at the same distance make a ring.
        // Corners offset by a width on *both* axes would be 1.41 widths out and the ring would read
        // as a square; corners rounded to whole units — which an earlier version of this did — put
        // the width-2 corners 1.41 out against straight copies at 2, and a ring a third short at
        // the corners is the plus sign the four corner copies exist to prevent.
        for (width in listOf(0.5f, 1f, 2f, 3f, 4f, 6f)) {
            var stamps = 0
            TextOutline(ring, width).forEachStamp { dx, dy ->
                stamps++
                assertEquals(
                    width,
                    sqrt(dx * dx + dy * dy),
                    absoluteTolerance = 0.001f,
                    message = "a copy at ($dx, $dy) is not $width from a letter at the centre",
                )
            }
            assertEquals(8, stamps)
        }
    }

    @Test
    fun `a ring with no paint in it stamps nothing at all`() {
        for (invisible in listOf(TextOutline(ring, width = 0f), TextOutline(Colour.Transparent, width = 4f))) {
            var stamps = 0
            invisible.forEachStamp { _, _ -> stamps++ }
            assertEquals(0, stamps, "$invisible would put no paint down")
            assertTrue(!invisible.isVisible)
        }
    }

    @Test
    fun `the canvas draws nine runs with every ring before every face`() {
        val canvas = RecordingCanvas()

        canvas.text(layout, 100f, 50f, ink, TextOutline(ring, width = 2f))

        val drawn = canvas.only<DrawCall.Text>()
        assertEquals(9, drawn.size, "eight copies and the real one")
        assertTrue(drawn.take(8).all { it.colour == ring }, "the first eight are the ring")
        assertEquals(ink, drawn.last().colour, "and the face is drawn last, over all of them")
        assertEquals(Offset(100f, 50f), drawn.last().at, "the face is where the caller asked for it")
        val corner = 2f * 0.70710678f
        val expected = listOf(
            Offset(98f, 50f), Offset(102f, 50f), Offset(100f, 48f), Offset(100f, 52f),
            Offset(100f - corner, 50f - corner), Offset(100f + corner, 50f - corner),
            Offset(100f - corner, 50f + corner), Offset(100f + corner, 50f + corner),
        )
        drawn.take(8).forEachIndexed { index, call ->
            assertEquals(expected[index].x, call.at.x, absoluteTolerance = 0.001f, message = "copy $index")
            assertEquals(expected[index].y, call.at.y, absoluteTolerance = 0.001f, message = "copy $index")
        }
    }

    @Test
    fun `a fading run fades its ring with it`() {
        val canvas = RecordingCanvas()

        // What a damage number hands over as it goes out: the face at a quarter alpha. The ring has
        // to go with it. Stamped at its own full alpha it would be a solid black silhouette of a
        // number that is supposed to have gone.
        canvas.text(layout, 0f, 0f, ink.scaleAlpha(0.25f), TextOutline(ring, width = 2f))

        val drawn = canvas.only<DrawCall.Text>()
        val faceAlpha = drawn.last().colour.alpha
        assertTrue(faceAlpha in 60..67, "a quarter of 255, give or take rounding: $faceAlpha")
        for (copy in drawn.take(8)) {
            assertEquals(faceAlpha, copy.colour.alpha, "the ring is as faded as the face it is round")
            assertEquals(Colour.Black.argb and 0xFFFFFF, copy.colour.argb and 0xFFFFFF, "still black")
        }
    }

    @Test
    fun `an opaque run leaves the outline colour exactly as it was given`() {
        val canvas = RecordingCanvas()

        // The other half of the same rule: a half-see-through halo under opaque letters is a real
        // thing to ask for, and scaling by an opaque face's alpha leaves it alone.
        val halo = Colour.Black.scaleAlpha(0.5f)
        canvas.text(layout, 0f, 0f, ink, TextOutline(halo, width = 2f))

        assertTrue(canvas.only<DrawCall.Text>().take(8).all { it.colour == halo })
    }

    @Test
    fun `no outline is one run exactly as it was before outlines existed`() {
        val canvas = RecordingCanvas()

        canvas.text(layout, 10f, 10f, ink, outline = null)
        canvas.text(layout, 20f, 20f, ink, TextOutline(ring, width = 0f))
        canvas.text(layout, 30f, 30f, ink, TextOutline(Colour.Transparent, width = 4f))

        assertEquals(3, canvas.only<DrawCall.Text>().size, "one call each, no invisible ring stamped")
    }

    @Test
    fun `the point overload puts the run in the same place as the two-float one`() {
        val canvas = RecordingCanvas()
        val outline = TextOutline(ring, width = 2f)

        canvas.text(layout, 40f, 60f, ink, outline)
        val byFloats = canvas.only<DrawCall.Text>().map { it.at }

        canvas.clear()
        canvas.text(layout, Offset(40f, 60f), ink, outline)

        assertContentEquals(byFloats, canvas.only<DrawCall.Text>().map { it.at })
    }

    @Test
    fun `a canvas that has never heard of outlines still draws one`() {
        // The stand-in for a backend outside this repository, compiled against 0.1.0: it implements
        // the four-argument call and nothing else, and the interface's own body does the rest.
        val canvas = FourArgumentCanvas()

        canvas.text(layout, 0f, 0f, ink, TextOutline(ring, width = 2f))

        assertEquals(9, canvas.runs.size, "the default body did the work")
        assertEquals(8, canvas.runs.count { it.third == ring })
    }

    /** Only [text] with four arguments. Everything else on [UiCanvas] is beside the point here. */
    private class FourArgumentCanvas : UiCanvas {

        val runs = mutableListOf<Triple<Float, Float, Colour>>()

        override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) {
            runs += Triple(x, y, colour)
        }

        override fun rect(rect: Rect, colour: Colour, corner: Float) = Unit
        override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) = Unit
        override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) = Unit
        override fun fan(points: FloatArray, colour: Colour) = Unit
        override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) = Unit
        override fun pushClip(rect: Rect) = Unit
        override fun popClip() = Unit
        override fun pushAlpha(alpha: Float) = Unit
        override fun popAlpha() = Unit
        override fun raw(block: (Any) -> Unit) = Unit
    }
}
