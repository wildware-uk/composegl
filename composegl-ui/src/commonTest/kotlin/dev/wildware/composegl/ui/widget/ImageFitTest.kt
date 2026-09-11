package dev.wildware.composegl.ui.widget

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.layout.Alignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arithmetic every fit mode comes down to.
 *
 * Kept as a plain function so it can be asserted directly, without a composition, a font or a
 * canvas. The question each test asks is the same one: is the picture still the shape the artist
 * drew it?
 */
class ImageFitTest {

    /** A wide picture: 200 by 100, so its aspect is 2. */
    private val wide = 200f to 100f

    private val square = Rect.of(0f, 0f, 120f, 120f)

    private fun aspectOf(rect: Rect) = rect.width / rect.height

    @Test
    fun `contain fits the whole picture inside and keeps its aspect`() {
        val (destination, source) = fitInto(square, wide.first, wide.second, ImageFit.Contain, Alignment.Centre)

        assertClose(2f, aspectOf(destination), "the picture is still twice as wide as it is tall")
        assertClose(120f, destination.width, "as big as the box allows")
        assertClose(60f, destination.height)
        assertClose(30f, destination.top, "centred in what is left over")
        assertNull(source, "all of the picture is used")
    }

    @Test
    fun `cover fills the box and crops instead of stretching`() {
        val (destination, source) = fitInto(square, wide.first, wide.second, ImageFit.Cover, Alignment.Centre)

        assertEquals(square, destination, "no gaps anywhere")
        val cropped = source ?: error("cover must say which part of the picture it used")
        assertClose(1f, aspectOf(cropped), "the part taken has the box's shape, so nothing stretches")
        assertClose(100f, cropped.height, "the full height of the picture")
        assertClose(50f, cropped.left, "and the middle of its width")
    }

    @Test
    fun `cover crops where the alignment says`() {
        val (_, start) = fitInto(square, wide.first, wide.second, ImageFit.Cover, Alignment.CentreStart)
        val (_, end) = fitInto(square, wide.first, wide.second, ImageFit.Cover, Alignment.CentreEnd)

        assertClose(0f, start?.left ?: -1f, "the left of the picture is kept")
        assertClose(200f, end?.right ?: -1f, "or the right of it")
    }

    @Test
    fun `none draws the picture at its own size`() {
        val (destination, source) = fitInto(square, 40f, 20f, ImageFit.None, Alignment.TopStart)

        assertEquals(Rect.of(0f, 0f, 40f, 20f), destination)
        assertNull(source)
    }

    @Test
    fun `stretch is the only mode that changes the shape`() {
        val (destination, _) = fitInto(square, wide.first, wide.second, ImageFit.Stretch, Alignment.Centre)

        assertEquals(square, destination)
        assertClose(1f, aspectOf(destination), "asked to stretch, it stretches")
    }

    @Test
    fun `every aspect-keeping mode keeps the aspect in any box`() {
        val boxes = listOf(
            Rect.of(0f, 0f, 300f, 40f),
            Rect.of(10f, 10f, 40f, 300f),
            Rect.of(0f, 0f, 7f, 7f),
        )
        val pictures = listOf(200f to 100f, 100f to 200f, 64f to 64f, 3f to 97f)

        boxes.forEach { box ->
            pictures.forEach { (width, height) ->
                val aspect = width / height

                val (contained, _) = fitInto(box, width, height, ImageFit.Contain, Alignment.Centre)
                assertTrue(
                    close(aspect, aspectOf(contained)),
                    "contain stretched ${width}x$height in $box",
                )

                val (destination, source) = fitInto(box, width, height, ImageFit.Cover, Alignment.Centre)
                val crop = source ?: error("cover must crop")
                assertTrue(
                    close(aspectOf(destination), aspectOf(crop)),
                    "cover stretched ${width}x$height in $box",
                )
                assertTrue(crop.width <= width && crop.height <= height, "cover took more than there was")
            }
        }
    }

    @Test
    fun `a picture with no size at all is left alone rather than dividing by zero`() {
        val (destination, source) = fitInto(square, 0f, 10f, ImageFit.Contain, Alignment.Centre)

        assertEquals(square, destination)
        assertNull(source)
    }

    private fun close(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.001f

    /** Floats, so within a thousandth. A pixel is one unit here and nothing is asserted finer. */
    private fun assertClose(expected: Float, actual: Float, message: String? = null) =
        assertTrue(close(expected, actual), "${message ?: ""} expected $expected but was $actual")
}
